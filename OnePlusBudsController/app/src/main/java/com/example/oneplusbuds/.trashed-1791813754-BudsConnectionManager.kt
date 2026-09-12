package com.example.oneplusbuds

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import java.util.UUID

class BudsConnectionManager(private val context: Context) {

    companion object {
        private const val TAG = "BudsConn"
        private const val HELLO_DELAY_MS = 2000L
        private const val REGISTER_DELAY_MS = 1500L
    }

    interface Listener {
        fun onLog(message: String)
        fun onStateChanged(state: State)
        fun onAncModeChanged(mode: OpoProtocol.AncMode)
        fun onGameModeChanged(on: Boolean)
    }

    enum class State { DISCONNECTED, SCANNING, CONNECTING, HANDSHAKING, READY, ERROR }

    var state: State = State.DISCONNECTED
        private set

    var currentAncMode: OpoProtocol.AncMode = OpoProtocol.AncMode.OFF
        private set

    var gameModeOn: Boolean = false
        private set

    private var listener: Listener? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChars = mutableListOf<BluetoothGattCharacteristic>()
    private var device: BluetoothDevice? = null

    private var ancModeIndex = 0
    private val ancCycle = listOf(
        OpoProtocol.AncMode.OFF,
        OpoProtocol.AncMode.NOISE_CANCELLATION,
        OpoProtocol.AncMode.TRANSPARENCY,
        OpoProtocol.AncMode.ADAPTIVE
    )

    private var commandQueue = mutableListOf<ByteArray>()
    private var isProcessingQueue = false

    fun setListener(l: Listener) { listener = l }

    fun log(msg: String) {
        Log.d(TAG, msg)
        handler.post { listener?.onLog(msg) }
    }

    private fun updateState(s: State) {
        state = s
        handler.post { listener?.onStateChanged(s) }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            log("Bluetooth not available or disabled")
            updateState(State.ERROR)
            return
        }
        updateState(State.SCANNING)
        log("Scanning for OPO service...")
        val scanner = adapter.bluetoothLeScanner ?: run {
            log("BLE scanner not available")
            updateState(State.ERROR)
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, scanCallback)
        handler.postDelayed({ scanner.stopScan(scanCallback) }, 15000)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: ""
            if (name.contains("Buds", ignoreCase = true) ||
                name.contains("OnePlus", ignoreCase = true) ||
                name.contains("OPPO", ignoreCase = true)
            ) {
                log("Found device: $name (${result.device.address})")
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
                    .adapter.bluetoothLeScanner.stopScan(this)
                device = result.device
                connectGatt(result.device)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectGatt(dev: BluetoothDevice) {
        updateState(State.CONNECTING)
        log("Connecting GATT to ${dev.address}...")
        bluetoothGatt = dev.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("GATT connected. Discovering services...")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("GATT disconnected")
                updateState(State.DISCONNECTED)
                bluetoothGatt?.close()
                bluetoothGatt = null
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Service discovery failed: $status")
                updateState(State.ERROR)
                return
            }
            log("Services discovered. Setting up characteristics...")
            writeChar = null
            notifyChars.clear()

            for (service in gatt.services) {
                log("Service: ${service.uuid}")
                for (ch in service.characteristics) {
                    log("  Char: ${ch.uuid} props=${ch.properties}")
                    if (ch.uuid == BleConstants.CHAR_WRITE) {
                        writeChar = ch
                        log("  -> Write char found")
                    }
                    if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                        notifyChars.add(ch)
                        enableNotifications(gatt, ch)
                    }
                }
            }

            if (writeChar == null) {
                log("WARNING: Write characteristic not found!")
                updateState(State.ERROR)
                return
            }

            updateState(State.HANDSHAKING)
            log("Starting handshake...")
            sendRaw(OpoProtocol.HELLO)
            handler.postDelayed({
                sendRaw(OpoProtocol.REGISTER)
                handler.postDelayed({
                    log("Handshake complete. Ready for commands.")
                    updateState(State.READY)
                }, REGISTER_DELAY_MS)
            }, HELLO_DELAY_MS)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, ch: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val msg = OpoProtocol.parseResponse(value)
            log(msg)
            parseAncResponse(value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, ch: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val value = ch.value ?: return
            onCharacteristicChanged(gatt, ch, value)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(ch, true)
        val descriptor = ch.getDescriptor(BleConstants.CCCD)
        if (descriptor != null) {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendRaw(data: ByteArray) {
        val gatt = bluetoothGatt ?: run {
            log("Cannot send: no GATT connection")
            return
        }
        val ch = writeChar ?: run {
            log("Cannot send: no write characteristic")
            return
        }
        @Suppress("DEPRECATION")
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        @Suppress("DEPRECATION")
        ch.value = data
        @Suppress("DEPRECATION")
        val ok = gatt.writeCharacteristic(ch)
        log("TX: ${data.joinToString(" ") { "%02X".format(it) }} -> ${if (ok) "OK" else "FAIL"}")
    }

    private fun queueCommand(data: ByteArray, label: String) {
        log("Queue: $label")
        commandQueue.add(data)
        if (!isProcessingQueue) {
            isProcessingQueue = true
            processQueue()
        }
    }

    private fun processQueue() {
        if (commandQueue.isEmpty()) {
            isProcessingQueue = false
            return
        }
        val cmd = commandQueue.removeAt(0)
        sendRaw(cmd)
        handler.postDelayed({ processQueue() }, 300L)
    }

    fun cycleAnc() {
        if (state != State.READY) {
            log("Not ready (state=$state)")
            return
        }
        ancModeIndex = (ancModeIndex + 1) % ancCycle.size
        val mode = ancCycle[ancModeIndex]
        currentAncMode = mode
        log("Cycling ANC -> ${mode.label}")
        queueCommand(OpoProtocol.buildAncCommand(mode), "ANC ${mode.label}")
        handler.post { listener?.onAncModeChanged(mode) }
    }

    fun setAncMode(mode: OpoProtocol.AncMode) {
        if (state != State.READY) return
        currentAncMode = mode
        ancModeIndex = ancCycle.indexOf(mode).coerceAtLeast(0)
        queueCommand(OpoProtocol.buildAncCommand(mode), "ANC ${mode.label}")
        handler.post { listener?.onAncModeChanged(mode) }
    }

    fun setGameMode(on: Boolean) {
        if (state != State.READY) {
            log("Not ready (state=$state)")
            return
        }
        gameModeOn = on
        log("Game Mode -> ${if (on) "ON" else "OFF"}")
        queueCommand(OpoProtocol.buildGameModeCommand(on), "Game Mode")
        handler.post { listener?.onGameModeChanged(on) }
    }

    fun disconnect() {
        log("Disconnecting...")
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        writeChar = null
        notifyChars.clear()
        updateState(State.DISCONNECTED)
    }

    private fun parseAncResponse(data: ByteArray) {
        if (data.size >= 6) {
            for (i in 0..data.size - 6) {
                if (data[i] == 0x04.toByte() && data[i + 1] == 0x04.toByte()) {
                    val modeByte = data.getOrNull(i + 5)?.toInt() ?: return
                    val mode = when (modeByte) {
                        0x01 -> OpoProtocol.AncMode.OFF
                        0x02 -> OpoProtocol.AncMode.NOISE_CANCELLATION
                        0x04 -> OpoProtocol.AncMode.TRANSPARENCY
                        0x08 -> OpoProtocol.AncMode.ADAPTIVE
                        else -> return
                    }
                    currentAncMode = mode
                    ancModeIndex = ancCycle.indexOf(mode).coerceAtLeast(0)
                    log("Parsed ANC mode: ${mode.label}")
                    handler.post { listener?.onAncModeChanged(mode) }
                    return
                }
            }
        }
    }
}
