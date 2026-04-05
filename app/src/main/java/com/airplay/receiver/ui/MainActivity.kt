package com.airplay.receiver.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.airplay.receiver.R
import com.airplay.receiver.server.AirPlayMdnsService
import com.airplay.receiver.service.AirPlayService

/**
 * Main launcher activity for Android TV.
 * Shows the service status, device name, IP address,
 * and controls to start/stop the AirPlay service.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvDeviceName: TextView
    private lateinit var tvIpAddress: TextView
    private lateinit var btnToggle: Button
    private lateinit var cbAutoStart: CheckBox

    private var isServiceRunning = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AirPlayService.ACTION_STATUS_CHANGED) {
                val status = intent.getStringExtra(AirPlayService.EXTRA_STATUS) ?: ""
                tvStatus.text = status
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvDeviceName = findViewById(R.id.tvDeviceName)
        tvIpAddress = findViewById(R.id.tvIpAddress)
        btnToggle = findViewById(R.id.btnToggle)
        cbAutoStart = findViewById(R.id.cbAutoStart)

        val prefs = getSharedPreferences("airplay_prefs", MODE_PRIVATE)

        // Load auto-start preference
        cbAutoStart.isChecked = prefs.getBoolean("auto_start", true)
        cbAutoStart.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_start", isChecked).apply()
        }

        // Display device info
        val deviceName = prefs.getString("device_name", null)
            ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        tvDeviceName.text = "${getString(R.string.device_name_label)} $deviceName"

        val mdnsService = AirPlayMdnsService(this)
        tvIpAddress.text = "${getString(R.string.ip_address_label)} ${mdnsService.getIpAddressString()}"

        btnToggle.setOnClickListener {
            if (isServiceRunning) {
                stopAirPlayService()
            } else {
                startAirPlayService()
            }
        }

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }

        // Auto-start the service
        startAirPlayService()
    }

    override fun onResume() {
        super.onResume()

        val filter = IntentFilter(AirPlayService.ACTION_STATUS_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }

        // Refresh IP
        val mdnsService = AirPlayMdnsService(this)
        tvIpAddress.text = "${getString(R.string.ip_address_label)} ${mdnsService.getIpAddressString()}"
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
    }

    private fun startAirPlayService() {
        AirPlayService.start(this)
        isServiceRunning = true
        btnToggle.text = getString(R.string.btn_stop)
        tvStatus.text = getString(R.string.status_service_running)
    }

    private fun stopAirPlayService() {
        AirPlayService.stop(this)
        isServiceRunning = false
        btnToggle.text = getString(R.string.btn_start)
        tvStatus.text = getString(R.string.status_waiting)
    }
}
