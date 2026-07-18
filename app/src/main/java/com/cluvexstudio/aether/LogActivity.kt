package com.cluvexstudio.aether

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class LogActivity : AppCompatActivity() {

    private lateinit var logText: TextView
    private lateinit var logScrollView: ScrollView
    private var isLogUpdateScheduled = false

    // We can access MainActivity's logBuffer directly since it's the same process,
    // or better, AetherVpnService can keep the log buffer.
    // For now, we will just read from MainActivity.getLogBuffer() or let MainActivity keep it public.
    
    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val line = intent?.getStringExtra("logLine") ?: return
            if (!isLogUpdateScheduled) {
                isLogUpdateScheduled = true
                logText.postDelayed({
                    logText.text = MainActivity.logBuffer.toString()
                    logScrollView.post { logScrollView.fullScroll(View.FOCUS_DOWN) }
                    isLogUpdateScheduled = false
                }, 500)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)

        logText = findViewById(R.id.logText)
        logScrollView = findViewById(R.id.logScrollView)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        
        findViewById<View>(R.id.btnClear).setOnClickListener {
            MainActivity.logBuffer.clear()
            logText.text = ""
        }

        // Initial load
        logText.text = MainActivity.logBuffer.toString()
        logScrollView.post { logScrollView.fullScroll(View.FOCUS_DOWN) }

        LocalBroadcastManager.getInstance(this)
            .registerReceiver(logReceiver, IntentFilter("AETHER_LOG"))
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(logReceiver)
    }
}
