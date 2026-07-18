package com.cluvexstudio.aether

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import android.content.pm.PackageManager
import android.os.Build
import android.net.TrafficStats
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.net.InetAddress

class MainActivity : AppCompatActivity() {

    private var isConnected = false
    private var isConnecting = false

    private lateinit var statusTextTitle: TextView
    private lateinit var statusTextDesc: TextView
    private lateinit var powerIcon: ImageView
    private lateinit var connectButtonLayout: FrameLayout
    private lateinit var statsRow: LinearLayout
    private lateinit var textDownload: TextView
    private lateinit var textUpload: TextView
    private lateinit var textPing: TextView

    companion object { 
        const val VPN_REQUEST_CODE = 1001 
        val logBuffer = java.lang.StringBuilder()
    }

    private var isLogUpdateScheduled = false

    private val dataHandler = Handler(Looper.getMainLooper())
    private var dataRunnable: Runnable? = null
    private var pingRunnable: Runnable? = null
    private var initialRxBytes: Long = 0
    private var initialTxBytes: Long = 0

    private val logReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val line = intent?.getStringExtra("logLine") ?: return
            if (logBuffer.length > 50000) logBuffer.delete(0, logBuffer.length - 25000)
            logBuffer.append(line).append("\n")
        }
    }

    private val statusReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra("status") ?: return
            runOnUiThread {
                when (status) {
                    AetherVpnService.STATUS_CONNECTING   -> showConnecting()
                    AetherVpnService.STATUS_CONNECTED    -> showConnected()
                    AetherVpnService.STATUS_DISCONNECTED -> showDisconnected()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusTextTitle     = findViewById(R.id.statusTextTitle)
        statusTextDesc      = findViewById(R.id.statusTextDesc)
        powerIcon           = findViewById(R.id.powerIcon)
        connectButtonLayout = findViewById(R.id.connectButtonLayout)
        statsRow            = findViewById(R.id.statsRow)
        textDownload        = findViewById(R.id.textDownload)
        textUpload          = findViewById(R.id.textUpload)
        textPing            = findViewById(R.id.textPing)

        connectButtonLayout.setOnClickListener { toggleConnection() }

        findViewById<ImageView>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        
        findViewById<ImageView>(R.id.btnLogs)?.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).apply {
            registerReceiver(logReceiver,    android.content.IntentFilter("AETHER_LOG"))
            registerReceiver(statusReceiver, android.content.IntentFilter(AetherVpnService.ACTION_STATUS))
        }

        syncState()
    }

    override fun onResume() {
        super.onResume()
        syncState()
    }

    private fun syncState() {
        when (AetherVpnService.currentStatus) {
            AetherVpnService.STATUS_CONNECTING   -> { if (!isConnecting) showConnecting() }
            AetherVpnService.STATUS_CONNECTED    -> { if (!isConnected)  showConnected()  }
            AetherVpnService.STATUS_DISCONNECTED -> { if (isConnected || isConnecting) showDisconnected() }
        }
    }

    private fun showConnecting() {
        isConnecting = true; isConnected = false
        statusTextTitle.text = "Connecting..."
        statusTextTitle.setTextColor(0xFFFFA726.toInt())
        statusTextDesc.text = "Scanning for best gateway..."
        powerIcon.setColorFilter(0xFFFFA726.toInt())
        statsRow.visibility = View.GONE
    }

    private fun showConnected() {
        isConnecting = false; isConnected = true
        statusTextTitle.text = "Connected"
        statusTextTitle.setTextColor(ContextCompat.getColor(this, R.color.status_connected))
        statusTextDesc.text = "Tap to disconnect"
        powerIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_connected))
        statsRow.visibility = View.VISIBLE
        startDataTracking()
        startPingTracking()
    }

    private fun showDisconnected() {
        isConnecting = false; isConnected = false
        statusTextTitle.text = "Disconnected"
        statusTextTitle.setTextColor(ContextCompat.getColor(this, R.color.text_main))
        statusTextDesc.text = "Click to connect"
        powerIcon.setColorFilter(ContextCompat.getColor(this, R.color.text_muted))
        statsRow.visibility = View.GONE
        stopDataTracking()
        stopPingTracking()
    }

    private fun toggleConnection() {
        if (!isConnected && !isConnecting) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1002)
                }
            }
            val intent = VpnService.prepare(this)
            if (intent != null) startActivityForResult(intent, VPN_REQUEST_CODE)
            else startVpnService()
        } else {
            stopVpnService()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) startVpnService()
    }

    private fun startVpnService() {
        showConnecting()
        ContextCompat.startForegroundService(this, Intent(this, AetherVpnService::class.java))
    }

    private fun stopVpnService() {
        showDisconnected()
        startService(Intent(this, AetherVpnService::class.java).apply { action = "STOP_VPN" })
    }

    // ─── Data tracking ──────────────────────────────────────────────────────────

    private fun startDataTracking() {
        val uid = Process.myUid()
        initialRxBytes = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0)
        initialTxBytes = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0)
        dataRunnable = object : Runnable {
            override fun run() {
                if (!isConnected) return
                val rx = (TrafficStats.getUidRxBytes(uid) - initialRxBytes).coerceAtLeast(0)
                val tx = (TrafficStats.getUidTxBytes(uid) - initialTxBytes).coerceAtLeast(0)
                textDownload.text = formatBytes(rx)
                textUpload.text   = formatBytes(tx)
                dataHandler.postDelayed(this, 1000)
            }
        }
        dataHandler.post(dataRunnable!!)
    }

    private fun stopDataTracking() {
        dataRunnable?.let { dataHandler.removeCallbacks(it) }
        dataRunnable = null
    }

    // ─── Ping tracking ──────────────────────────────────────────────────────────

    private fun startPingTracking() {
        pingRunnable = object : Runnable {
            override fun run() {
                if (!isConnected) return
                Thread {
                    val ping = measurePing("1.1.1.1")
                    runOnUiThread {
                        textPing.text = if (ping >= 0) "${ping}ms" else "-- ms"
                    }
                }.start()
                dataHandler.postDelayed(this, 3000)
            }
        }
        dataHandler.post(pingRunnable!!)
    }

    private fun stopPingTracking() {
        pingRunnable?.let { dataHandler.removeCallbacks(it) }
        pingRunnable = null
        textPing.text = "-- ms"
    }

    private fun measurePing(host: String): Long {
        return try {
            val start = System.currentTimeMillis()
            val addr = InetAddress.getByName(host)
            if (addr.isReachable(2000)) System.currentTimeMillis() - start else -1L
        } catch (e: Exception) { -1L }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.2f MB".format(mb)
        return "%.2f GB".format(mb / 1024.0)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDataTracking(); stopPingTracking()
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).apply {
            unregisterReceiver(logReceiver)
            unregisterReceiver(statusReceiver)
        }
    }
}
