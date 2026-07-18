package com.cluvexstudio.aether

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var spinnerProtocol: Spinner
    private lateinit var radioGroupScanMode: RadioGroup
    private lateinit var radioGroupIpVersion: RadioGroup
    private lateinit var radioGroupMasque: RadioGroup
    private lateinit var switchQuickReconnect: Switch
    private lateinit var switchNotification: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        spinnerProtocol = findViewById(R.id.spinnerProtocol)
        radioGroupScanMode = findViewById(R.id.radioGroupScanMode)
        radioGroupIpVersion = findViewById(R.id.radioGroupIpVersion)
        radioGroupMasque = findViewById(R.id.radioGroupMasque)
        switchQuickReconnect = findViewById(R.id.switchQuickReconnect)
        switchNotification = findViewById(R.id.switchNotification)

        val adapter = ArrayAdapter.createFromResource(
            this, R.array.protocol_entries, R.layout.spinner_item
        )
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinnerProtocol.adapter = adapter

        val prefs = getSharedPreferences("aether_prefs", Context.MODE_PRIVATE)

        // Load saved settings
        when (prefs.getString("protocol", "auto")) {
            "masque"    -> spinnerProtocol.setSelection(1)
            "wireguard" -> spinnerProtocol.setSelection(2)
            "gool"      -> spinnerProtocol.setSelection(3)
            else        -> spinnerProtocol.setSelection(0)
        }
        when (prefs.getString("scan_mode", "balanced")) {
            "turbo"    -> radioGroupScanMode.check(R.id.radioTurbo)
            "balanced" -> radioGroupScanMode.check(R.id.radioBalanced)
            "thorough" -> radioGroupScanMode.check(R.id.radioThorough)
            "stealth"  -> radioGroupScanMode.check(R.id.radioStealth)
        }
        when (prefs.getString("ip_version", "v4")) {
            "v4"  -> radioGroupIpVersion.check(R.id.radioIpv4)
            "v6"  -> radioGroupIpVersion.check(R.id.radioIpv6)
            "dual"-> radioGroupIpVersion.check(R.id.radioDual)
        }
        if (prefs.getBoolean("masque_http2", false)) radioGroupMasque.check(R.id.radioHttp2)
        else radioGroupMasque.check(R.id.radioHttp3)

        switchQuickReconnect.isChecked = prefs.getBoolean("quick_reconnect", true)
        switchNotification.isChecked   = prefs.getBoolean("show_notification", true)

        // Animate thumbs after layout
        radioGroupScanMode.post { updateThumbs() }

        // Listeners
        spinnerProtocol.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val proto = when (pos) { 1 -> "masque"; 2 -> "wireguard"; 3 -> "gool"; else -> "auto" }
                prefs.edit().putString("protocol", proto).apply()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        radioGroupScanMode.setOnCheckedChangeListener { group, checkedId ->
            animateThumb(R.id.thumbScanMode, group, checkedId)
            val mode = when (checkedId) {
                R.id.radioBalanced -> "balanced"; R.id.radioThorough -> "thorough"
                R.id.radioStealth  -> "stealth";  else -> "turbo"
            }
            prefs.edit().putString("scan_mode", mode).apply()
        }
        radioGroupIpVersion.setOnCheckedChangeListener { group, checkedId ->
            animateThumb(R.id.thumbIpVersion, group, checkedId)
            val ip = when (checkedId) { R.id.radioIpv6 -> "v6"; R.id.radioDual -> "dual"; else -> "v4" }
            prefs.edit().putString("ip_version", ip).apply()
        }
        radioGroupMasque.setOnCheckedChangeListener { group, checkedId ->
            animateThumb(R.id.thumbMasque, group, checkedId)
            prefs.edit().putBoolean("masque_http2", checkedId == R.id.radioHttp2).apply()
        }
        switchQuickReconnect.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("quick_reconnect", c).apply() }
        switchNotification.setOnCheckedChangeListener   { _, c -> prefs.edit().putBoolean("show_notification", c).apply() }

        findViewById<android.widget.LinearLayout>(R.id.btnSplitTunnel).setOnClickListener {
            startActivity(Intent(this, SplitTunnelActivity::class.java))
        }
    }

    private fun animateThumb(thumbId: Int, group: RadioGroup, checkedId: Int) {
        val thumb = findViewById<View>(thumbId)
        val button = group.findViewById<RadioButton>(checkedId)
        button?.post {
            thumb.animate().x(button.x).setDuration(200).start()
            val p = thumb.layoutParams; p.width = button.width; thumb.layoutParams = p
        }
    }

    private fun updateThumbs() {
        listOf(
            Triple(R.id.thumbScanMode,   radioGroupScanMode,   radioGroupScanMode.checkedRadioButtonId),
            Triple(R.id.thumbIpVersion,  radioGroupIpVersion,  radioGroupIpVersion.checkedRadioButtonId),
            Triple(R.id.thumbMasque,     radioGroupMasque,     radioGroupMasque.checkedRadioButtonId)
        ).forEach { (thumbId, group, checkedId) ->
            val thumb = findViewById<View>(thumbId)
            val btn   = group.findViewById<RadioButton>(checkedId) ?: return@forEach
            group.post {
                thumb.animate().x(btn.x).setDuration(0).start()
                val p = thumb.layoutParams; p.width = btn.width; thumb.layoutParams = p
            }
        }
    }
}
