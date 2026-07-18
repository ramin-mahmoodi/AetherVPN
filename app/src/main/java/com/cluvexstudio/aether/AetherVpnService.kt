package com.cluvexstudio.aether

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

class AetherVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var aetherProcess: Process? = null
    private var tun2socksPid: Int = -1

    companion object {
        var isRunning = false
        var currentStatus = "disconnected"
        private const val NOTIFICATION_CHANNEL_ID = "aether_vpn_channel"
        private const val NOTIFICATION_ID = 1

        const val ACTION_STATUS = "AETHER_STATUS"
        const val STATUS_CONNECTING = "connecting"
        const val STATUS_CONNECTED = "connected"
        const val STATUS_DISCONNECTED = "disconnected"

        private val READY_SIGNALS = listOf(
            "accepted by gateway",
            "CONNECT-IP accepted",
            "listening on",
            "bound to",
            "socks5 proxy started",
            "proxy ready"
        )

        init {
            System.loadLibrary("exec_vpn")
        }
    }

    private external fun startTun2SocksNative(path: String, fd: Int, configPath: String): IntArray?

    private fun broadcastStatus(status: String) {
        currentStatus = status
        val intent = Intent(ACTION_STATUS).putExtra("status", status)
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        
        // Update notification
        if (isRunning) {
            val sharedPrefs = getSharedPreferences("aether_prefs", Context.MODE_PRIVATE)
            if (sharedPrefs.getBoolean("show_notification", true)) {
                val title = if (status == STATUS_CONNECTED) "Connected" else "Connecting..."
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, createNotification(title))
            }
        }
    }

    private fun broadcastLog(msg: String) {
        // Filter out noisy tun2socks per-packet traffic logs
        if (msg.contains("<->") && (msg.contains("[UDP]", true) || msg.contains("[TCP]", true))) return

        val intent = Intent("AETHER_LOG").putExtra("logLine", msg)
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        // Detect when tunnel is actually ready
        if (isRunning && currentStatus != STATUS_CONNECTED) {
            if (READY_SIGNALS.any { msg.contains(it, ignoreCase = true) }) {
                broadcastStatus(STATUS_CONNECTED)
            }
        }

        // Downgrade to connecting if SOCKS proxy fails (e.g. Warp in Warp drops)
        if (msg.contains("general SOCKS server failure", ignoreCase = true) && currentStatus == STATUS_CONNECTED) {
            broadcastStatus(STATUS_CONNECTING)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_VPN") {
            Log.i("AetherVPN", "Stopping VPN Service explicitly")
            stopVpnGracefully()
            return START_NOT_STICKY
        }

        Log.i("AetherVPN", "Starting VPN Service...")

        val sharedPrefs = getSharedPreferences("aether_prefs", Context.MODE_PRIVATE)
        val showNotification = sharedPrefs.getBoolean("show_notification", true)
        if (showNotification) {
            startForeground(NOTIFICATION_ID, createNotification("Connecting..."))
        }

        isRunning = true
        broadcastStatus(STATUS_CONNECTING)

        thread {
            startAetherCore()
            Thread.sleep(1000)
            setupVpn()
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Aether VPN Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(statusText: String): android.app.Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AetherVpnService::class.java).apply {
            action = "STOP_VPN"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Aether VPN")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_secure)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun setupVpn() {
        val builder = Builder()
        builder.setSession("AetherVPN")
        builder.setMtu(1500)
        builder.addAddress("10.0.0.2", 24)
        builder.addDnsServer("1.1.1.1")
        builder.addRoute("0.0.0.0", 0)

        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
            Log.e("AetherVPN", "Failed to exclude app", e)
        }

        // Apply split tunnel exclusions
        val sharedPrefs = getSharedPreferences("aether_prefs", Context.MODE_PRIVATE)
        val excludedApps = sharedPrefs.getStringSet("split_tunnel_excluded", emptySet()) ?: emptySet()
        for (pkg in excludedApps) {
            try {
                builder.addDisallowedApplication(pkg)
                Log.i("AetherVPN", "Split tunnel: excluding $pkg")
            } catch (e: Exception) {
                Log.w("AetherVPN", "Cannot exclude $pkg", e)
            }
        }

        vpnInterface = builder.establish()

        val fd = vpnInterface?.fd ?: return
        Log.i("AetherVPN", "VPN Interface established, FD: $fd")

        startTun2Socks(fd)
    }

    private fun startTun2Socks(tunFd: Int) {
        try {
            val execFile = File(applicationInfo.nativeLibraryDir, "libtun2socks.so")
            if (!execFile.exists()) {
                broadcastLog("[VPN] ERROR: tun2socks binary missing")
                return
            }

            val configFile = File(filesDir, "tun2socks.yml")
            val configContent = """
                loglevel: "warn"
                device: "fd://$tunFd"
                proxy: "socks5://127.0.0.1:1819"
            """.trimIndent()
            FileOutputStream(configFile).use { it.write(configContent.toByteArray()) }

            broadcastLog("[VPN] Starting routing engine...")

            val result = startTun2SocksNative(execFile.absolutePath, tunFd, configFile.absolutePath)
            if (result != null && result.size == 2) {
                val pid = result[0]
                val pipeFd = result[1]
                tun2socksPid = pid
                broadcastLog("[VPN] Routing engine ready (PID $pid)")

                thread {
                    try {
                        val pfd = ParcelFileDescriptor.adoptFd(pipeFd)
                        val reader = java.io.BufferedReader(java.io.InputStreamReader(ParcelFileDescriptor.AutoCloseInputStream(pfd)))
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            broadcastLog("[TUN] $line")
                        }
                    } catch (e: Exception) {
                        Log.e("AetherVPN", "tun2socks log reader error", e)
                    }
                }
            } else {
                broadcastLog("[VPN] ERROR: Failed to spawn tun2socks")
            }

        } catch (e: Exception) {
            broadcastLog("[VPN] ERROR: ${e.message}")
        }
    }

    private fun startAetherCore() {
        try {
            val execFile = File(applicationInfo.nativeLibraryDir, "libaether.so")
            if (!execFile.exists()) {
                broadcastLog("[CORE] ERROR: Aether binary missing")
                return
            }

            broadcastLog("[CORE] Scanning for best gateway...")

            val sharedPrefs = getSharedPreferences("aether_prefs", Context.MODE_PRIVATE)
            val protocol = sharedPrefs.getString("protocol", "auto") ?: "auto"
            val scanMode = sharedPrefs.getString("scan_mode", "balanced") ?: "balanced"
            val ipVersion = sharedPrefs.getString("ip_version", "v4") ?: "v4"
            val quickReconnect = sharedPrefs.getBoolean("quick_reconnect", true)
            val masqueHttp2 = sharedPrefs.getBoolean("masque_http2", false)

            val args = mutableListOf(execFile.absolutePath)
            val pb = ProcessBuilder(args)

            when (protocol) {
                "auto", "masque" -> pb.environment()["AETHER_PROTOCOL"] = "masque"
                "wireguard" -> pb.environment()["AETHER_PROTOCOL"] = "wg"
                "gool" -> pb.environment()["AETHER_PROTOCOL"] = "gool"
            }

            when (scanMode) {
                "turbo" -> pb.environment()["AETHER_SCAN"] = "turbo"
                "balanced" -> pb.environment()["AETHER_SCAN"] = "balanced"
                "thorough" -> pb.environment()["AETHER_SCAN"] = "thorough"
                "stealth" -> pb.environment()["AETHER_SCAN"] = "stealth"
            }

            when (ipVersion) {
                "v4" -> pb.environment()["AETHER_IP"] = "v4"
                "v6" -> pb.environment()["AETHER_IP"] = "v6"
                "dual" -> pb.environment()["AETHER_IP"] = "both"
            }

            pb.environment()["AETHER_QUICK_RECONNECT"] = if (quickReconnect) "1" else "0"
            pb.environment()["AETHER_MASQUE_HTTP2"]    = if (masqueHttp2) "1" else "0"
            pb.directory(filesDir)
            pb.environment()["AETHER_SOCKS"]           = "127.0.0.1:1819"
            pb.environment()["RUST_LOG"]               = "info"

            // Endpoint discovery: use cached gateways or fresh scan
            val endpointDiscovery = sharedPrefs.getString("endpoint_discovery", "cache") ?: "cache"
            pb.environment()["AETHER_ENDPOINT_DISCOVERY"] = endpointDiscovery
            val cacheFile = java.io.File(filesDir, "masque-gateway-cache.json")
            pb.environment()["AETHER_ENDPOINT_CACHE_PATH"] = cacheFile.absolutePath
            if (endpointDiscovery == "cache") {
                broadcastLog("[CORE] Using cached endpoints for fast connect...")
            } else {
                broadcastLog("[CORE] Fresh scan mode — scanning all endpoints...")
            }

            pb.redirectErrorStream(true)

            aetherProcess = pb.start()
            Log.i("AetherVPN", "Aether core started.")

            thread {
                try {
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(aetherProcess!!.inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        broadcastLog("[CORE] $line")
                    }
                    val exitCode = aetherProcess!!.waitFor()
                    if (isRunning) broadcastLog("[CORE] Process exited with code: $exitCode")
                } catch (e: Exception) {
                    Log.e("AetherVPN", "Aether log reader error", e)
                }
            }

        } catch (e: Exception) {
            broadcastLog("[CORE] ERROR: ${e.message}")
        }
    }

    private fun stopVpnGracefully() {
        Log.i("AetherVPN", "Cleaning up VPN resources...")
        isRunning = false
        broadcastStatus(STATUS_DISCONNECTED)

        try { vpnInterface?.close() } catch (e: Exception) {}
        vpnInterface = null

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                aetherProcess?.destroyForcibly()
            } else {
                aetherProcess?.destroy()
            }
        } catch (e: Exception) {}
        aetherProcess = null

        if (tun2socksPid != -1) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    android.system.Os.kill(tun2socksPid, android.system.OsConstants.SIGKILL)
                } else {
                    Runtime.getRuntime().exec("kill -9 $tun2socksPid")
                }
            } catch (e: Exception) {}
            tun2socksPid = -1
        }

        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpnGracefully()
    }
}
