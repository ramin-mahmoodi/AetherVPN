package com.cluvexstudio.aether

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
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

class MainActivity : AppCompatActivity() {

    private var isConnected = false
    private lateinit var statusTextTitle: TextView
    private lateinit var statusTextDesc: TextView
    private lateinit var powerIcon: ImageView
    private lateinit var connectButtonLayout: FrameLayout

    // Advanced Panel
    private lateinit var advancedToggle: LinearLayout
    private lateinit var advancedToggleIcon: ImageView
    private lateinit var advancedPanel: LinearLayout
    private var isAdvancedExpanded = false

    // Settings elements
    private lateinit var spinnerProtocol: Spinner
    private lateinit var radioGroupScanMode: RadioGroup
    private lateinit var radioGroupIpVersion: RadioGroup
    private lateinit var radioGroupMasque: RadioGroup
    private lateinit var switchQuickReconnect: Switch
    private lateinit var switchNotification: Switch

    // Data Usage
    private lateinit var textDownload: TextView
    private lateinit var textUpload: TextView
    private var initialRxBytes: Long = 0
    private var initialTxBytes: Long = 0
    private val dataHandler = Handler(Looper.getMainLooper())
    private var dataRunnable: Runnable? = null

    // Logs
    private lateinit var logText: TextView
    private lateinit var logScrollView: ScrollView

    companion object {
        const val VPN_REQUEST_CODE = 1001
    }

    private val logBuffer = StringBuilder()
    private var isLogUpdateScheduled = false

    private val logReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val logLine = intent?.getStringExtra("logLine") ?: return
            
            // Limit buffer size to prevent memory issues
            if (logBuffer.length > 50000) {
                logBuffer.delete(0, logBuffer.length - 25000)
            }
            
            logBuffer.append(logLine).append("\n")
            
            if (!isLogUpdateScheduled) {
                isLogUpdateScheduled = true
                logText.postDelayed({
                    logText.text = logBuffer.toString()
                    logScrollView.post {
                        logScrollView.fullScroll(View.FOCUS_DOWN)
                    }
                    isLogUpdateScheduled = false
                }, 500)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind Views
        statusTextTitle = findViewById(R.id.statusTextTitle)
        statusTextDesc = findViewById(R.id.statusTextDesc)
        powerIcon = findViewById(R.id.powerIcon)
        connectButtonLayout = findViewById(R.id.connectButtonLayout)
        
        advancedToggle = findViewById(R.id.advancedToggle)
        advancedToggleIcon = findViewById(R.id.advancedToggleIcon)
        advancedPanel = findViewById(R.id.advancedPanel)
        
        spinnerProtocol = findViewById(R.id.spinnerProtocol)
        radioGroupScanMode = findViewById(R.id.radioGroupScanMode)
        radioGroupIpVersion = findViewById(R.id.radioGroupIpVersion)
        radioGroupMasque = findViewById(R.id.radioGroupMasque)
        switchQuickReconnect = findViewById(R.id.switchQuickReconnect)
        switchNotification = findViewById(R.id.switchNotification)
        
        textDownload = findViewById(R.id.textDownload)
        textUpload = findViewById(R.id.textUpload)
        
        logText = findViewById(R.id.logText)
        logScrollView = findViewById(R.id.logScrollView)

        // Setup Spinners and Controls
        setupControls()
        loadSettings()

        // Listeners
        connectButtonLayout.setOnClickListener {
            toggleConnection()
        }

        advancedToggle.setOnClickListener {
            isAdvancedExpanded = !isAdvancedExpanded
            advancedPanel.visibility = if (isAdvancedExpanded) View.VISIBLE else View.GONE
            advancedToggleIcon.rotation = if (isAdvancedExpanded) 180f else 0f
            if (isAdvancedExpanded) {
                advancedPanel.post { updateThumbs() }
            }
        }

        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
            .registerReceiver(logReceiver, android.content.IntentFilter("AETHER_LOG"))
            
        updateUIState()
    }

    private fun setupControls() {
        val adapter = ArrayAdapter.createFromResource(
            this, R.array.protocol_entries, R.layout.spinner_item
        )
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinnerProtocol.adapter = adapter

        val sharedPrefs = getSharedPreferences("aether_prefs", Context.MODE_PRIVATE)

        spinnerProtocol.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val proto = when (position) {
                    1 -> "masque"
                    2 -> "wireguard"
                    3 -> "gool"
                    else -> "auto"
                }
                sharedPrefs.edit().putString("protocol", proto).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        radioGroupScanMode.setOnCheckedChangeListener { group, checkedId ->
            val thumb = findViewById<View>(R.id.thumbScanMode)
            val button = group.findViewById<RadioButton>(checkedId)
            button?.post {
                thumb.animate().x(button.x).setDuration(200).start()
                val params = thumb.layoutParams
                params.width = button.width
                thumb.layoutParams = params
            }
            val mode = when (checkedId) {
                R.id.radioBalanced -> "balanced"
                R.id.radioThorough -> "thorough"
                R.id.radioStealth -> "stealth"
                else -> "turbo"
            }
            sharedPrefs.edit().putString("scan_mode", mode).apply()
        }

        radioGroupIpVersion.setOnCheckedChangeListener { group, checkedId ->
            val thumb = findViewById<View>(R.id.thumbIpVersion)
            val button = group.findViewById<RadioButton>(checkedId)
            button?.post {
                thumb.animate().x(button.x).setDuration(200).start()
                val params = thumb.layoutParams
                params.width = button.width
                thumb.layoutParams = params
            }
            val ip = when (checkedId) {
                R.id.radioIpv6 -> "v6"
                R.id.radioDual -> "dual"
                else -> "v4"
            }
            sharedPrefs.edit().putString("ip_version", ip).apply()
        }

        radioGroupMasque.setOnCheckedChangeListener { group, checkedId ->
            val thumb = findViewById<View>(R.id.thumbMasque)
            val button = group.findViewById<RadioButton>(checkedId)
            button?.post {
                thumb.animate().x(button.x).setDuration(200).start()
                val params = thumb.layoutParams
                params.width = button.width
                thumb.layoutParams = params
            }
            val h2 = checkedId == R.id.radioHttp2
            sharedPrefs.edit().putBoolean("masque_http2", h2).apply()
        }

        switchQuickReconnect.setOnCheckedChangeListener { _, isChecked ->
            sharedPrefs.edit().putBoolean("quick_reconnect", isChecked).apply()
        }

        switchNotification.setOnCheckedChangeListener { _, isChecked ->
            sharedPrefs.edit().putBoolean("show_notification", isChecked).apply()
        }
    }

    private fun loadSettings() {
        val sharedPrefs = getSharedPreferences("aether_prefs", Context.MODE_PRIVATE)
        
        when (sharedPrefs.getString("protocol", "auto")) {
            "masque" -> spinnerProtocol.setSelection(1)
            "wireguard" -> spinnerProtocol.setSelection(2)
            "gool" -> spinnerProtocol.setSelection(3)
            else -> spinnerProtocol.setSelection(0)
        }
        
        when (sharedPrefs.getString("scan_mode", "balanced")) {
            "turbo" -> radioGroupScanMode.check(R.id.radioTurbo)
            "balanced" -> radioGroupScanMode.check(R.id.radioBalanced)
            "thorough" -> radioGroupScanMode.check(R.id.radioThorough)
            "stealth" -> radioGroupScanMode.check(R.id.radioStealth)
        }
        
        when (sharedPrefs.getString("ip_version", "v4")) {
            "v4" -> radioGroupIpVersion.check(R.id.radioIpv4)
            "v6" -> radioGroupIpVersion.check(R.id.radioIpv6)
            "dual" -> radioGroupIpVersion.check(R.id.radioDual)
        }
        
        if (sharedPrefs.getBoolean("masque_http2", false)) {
            radioGroupMasque.check(R.id.radioHttp2)
        } else {
            radioGroupMasque.check(R.id.radioHttp3)
        }
        
        switchQuickReconnect.isChecked = sharedPrefs.getBoolean("quick_reconnect", true)
        switchNotification.isChecked = sharedPrefs.getBoolean("show_notification", true)
        
        findViewById<View>(android.R.id.content).post {
            updateThumbs()
        }
    }

    private fun updateThumbs() {
        val rbScan = radioGroupScanMode.findViewById<RadioButton>(radioGroupScanMode.checkedRadioButtonId)
        val thumbScan = findViewById<View>(R.id.thumbScanMode)
        if (rbScan != null) {
            thumbScan.x = rbScan.x
            thumbScan.layoutParams.width = rbScan.width
            thumbScan.requestLayout()
        }

        val rbIp = radioGroupIpVersion.findViewById<RadioButton>(radioGroupIpVersion.checkedRadioButtonId)
        val thumbIp = findViewById<View>(R.id.thumbIpVersion)
        if (rbIp != null) {
            thumbIp.x = rbIp.x
            thumbIp.layoutParams.width = rbIp.width
            thumbIp.requestLayout()
        }

        val rbMasque = radioGroupMasque.findViewById<RadioButton>(radioGroupMasque.checkedRadioButtonId)
        val thumbMasque = findViewById<View>(R.id.thumbMasque)
        if (rbMasque != null) {
            thumbMasque.x = rbMasque.x
            thumbMasque.layoutParams.width = rbMasque.width
            thumbMasque.requestLayout()
        }
    }

    private fun startDataTracking() {
        val uid = Process.myUid()
        initialRxBytes = TrafficStats.getUidRxBytes(uid)
        initialTxBytes = TrafficStats.getUidTxBytes(uid)
        
        if (initialRxBytes == TrafficStats.UNSUPPORTED.toLong()) initialRxBytes = 0
        if (initialTxBytes == TrafficStats.UNSUPPORTED.toLong()) initialTxBytes = 0

        dataRunnable = object : Runnable {
            override fun run() {
                if (!isConnected) return
                
                var rx = TrafficStats.getUidRxBytes(uid)
                var tx = TrafficStats.getUidTxBytes(uid)
                
                if (rx == TrafficStats.UNSUPPORTED.toLong()) rx = 0
                if (tx == TrafficStats.UNSUPPORTED.toLong()) tx = 0
                
                val currentRx = Math.max(0, rx - initialRxBytes)
                val currentTx = Math.max(0, tx - initialTxBytes)
                
                textDownload.text = formatBytes(currentRx)
                textUpload.text = formatBytes(currentTx)
                
                dataHandler.postDelayed(this, 1000)
            }
        }
        dataHandler.post(dataRunnable!!)
    }
    
    private fun stopDataTracking() {
        dataRunnable?.let { dataHandler.removeCallbacks(it) }
        dataRunnable = null
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.2f MB", mb)
        val gb = mb / 1024.0
        return String.format("%.2f GB", gb)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDataTracking()
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
            .unregisterReceiver(logReceiver)
    }

    private fun toggleConnection() {
        if (!isConnected) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1002)
                }
            }
            val intent = VpnService.prepare(this)
            if (intent != null) {
                startActivityForResult(intent, VPN_REQUEST_CODE)
            } else {
                startVpnService()
            }
        } else {
            stopVpnService()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            startVpnService()
        }
    }

    private fun updateUIState() {
        if (isConnected) {
            statusTextTitle.text = "Connected"
            statusTextTitle.setTextColor(ContextCompat.getColor(this, R.color.status_connected))
            statusTextDesc.text = "Tap to disconnect"
            powerIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_connected))
            startDataTracking()
        } else {
            statusTextTitle.text = "Disconnected"
            statusTextTitle.setTextColor(ContextCompat.getColor(this, R.color.text_main))
            statusTextDesc.text = "Click to connect"
            powerIcon.setColorFilter(ContextCompat.getColor(this, R.color.text_muted))
            stopDataTracking()
        }
    }

    private fun startVpnService() {
        isConnected = true
        updateUIState()
        
        val serviceIntent = Intent(this, AetherVpnService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun stopVpnService() {
        isConnected = false
        updateUIState()
        
        val serviceIntent = Intent(this, AetherVpnService::class.java)
        serviceIntent.action = "STOP_VPN"
        startService(serviceIntent)
    }
}
