package com.example.oneplusbuds

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), BudsConnectionManager.Listener {

    private lateinit var connectionManager: BudsConnectionManager
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) {
            appendLog("Permissions granted")
        } else {
            appendLog("Permissions denied - app may not work")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)

        connectionManager = BudsConnectionManager(this)
        connectionManager.setListener(this)

        requestPermissions()

        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            connectionManager.startScan()
        }

        findViewById<Button>(R.id.btnDisconnect).setOnClickListener {
            connectionManager.disconnect()
        }

        findViewById<Button>(R.id.btnCycleAnc).setOnClickListener {
            connectionManager.cycleAnc()
        }

        findViewById<Button>(R.id.btnAncOff).setOnClickListener {
            connectionManager.setAncMode(OpoProtocol.AncMode.OFF)
        }

        findViewById<Button>(R.id.btnAncOn).setOnClickListener {
            connectionManager.setAncMode(OpoProtocol.AncMode.NOISE_CANCELLATION)
        }

        findViewById<Button>(R.id.btnTransparency).setOnClickListener {
            connectionManager.setAncMode(OpoProtocol.AncMode.TRANSPARENCY)
        }

        findViewById<Button>(R.id.btnGameOn).setOnClickListener {
            connectionManager.setGameMode(true)
        }

        findViewById<Button>(R.id.btnGameOff).setOnClickListener {
            connectionManager.setGameMode(false)
        }

        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            logText.text = ""
        }
    }

    private fun requestPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (perms.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            permissionLauncher.launch(perms.toTypedArray())
        }
    }

    override fun onLog(message: String) {
        runOnUiThread { appendLog(message) }
    }

    override fun onStateChanged(state: BudsConnectionManager.State) {
        runOnUiThread { statusText.text = "State: $state" }
    }

    override fun onAncModeChanged(mode: OpoProtocol.AncMode) {
        runOnUiThread {
            statusText.text = "State: ${connectionManager.state} | ANC: ${mode.label}"
        }
    }

    override fun onGameModeChanged(on: Boolean) {
        runOnUiThread {
            statusText.text = "State: ${connectionManager.state} | Game: ${if (on) "ON" else "OFF"}"
        }
    }

    private fun appendLog(msg: String) {
        logText.append("$msg\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        connectionManager.disconnect()
    }
}
