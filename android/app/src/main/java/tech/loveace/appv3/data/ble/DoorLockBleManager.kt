package tech.loveace.appv3.data.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.loveace.appv3.data.model.BleConnectionState
import java.util.*

/**
 * 门锁 BLE 管理器 — 扫描、连接、读写特征值
 * 移植自小程序 baseBleApi.js / antsBleApi.js
 */
@SuppressLint("MissingPermission")
class DoorLockBleManager(private val context: Context) {

    private val _connectionState = MutableStateFlow(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _lastResponse = MutableStateFlow<String?>(null)
    val lastResponse: StateFlow<String?> = _lastResponse.asStateFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var targetMac: String = ""
    private var scanJob: Job? = null

    private val serviceUuid = UUID.fromString(BleCommandHelper.SERVICE_UUID)
    private val charUuid = UUID.fromString(BleCommandHelper.CHARACTERISTIC_UUID)

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ==================== 连接 ====================

    fun connect(macAddress: String) {
        targetMac = macAddress.uppercase().replace(":", "")
        _connectionState.value = BleConnectionState.Scanning
        _lastResponse.value = null

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            _connectionState.value = BleConnectionState.Error
            return
        }

        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            _connectionState.value = BleConnectionState.Error
            return
        }

        startScan()
    }

    private fun startScan() {
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val deviceMac = device.address?.uppercase()?.replace(":", "") ?: return
                // 匹配 MAC 地址或广播数据中包含目标 MAC
                val advHex = result.scanRecord?.bytes?.let { BleCommandHelper.bytesToHex(it) } ?: ""
                if (deviceMac == targetMac || advHex.lowercase().contains(targetMac.lowercase())) {
                    Log.d(TAG, "Found target device: ${device.address}")
                    stopScan()
                    connectToDevice(device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
                _connectionState.value = BleConnectionState.Error
            }
        }
        scanCallback = callback

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(null, settings, callback)

        // 60 秒超时
        scanJob = scope.launch {
            delay(60_000)
            if (_connectionState.value == BleConnectionState.Scanning) {
                Log.w(TAG, "Scan timeout")
                stopScan()
                _connectionState.value = BleConnectionState.Error
            }
        }
    }

    private fun stopScan() {
        scanJob?.cancel()
        scanCallback?.let { scanner?.stopScan(it) }
        scanCallback = null
    }

    private fun connectToDevice(device: BluetoothDevice) {
        _connectionState.value = BleConnectionState.Connecting
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)

        // 30 秒连接超时
        scope.launch {
            delay(30_000)
            if (_connectionState.value == BleConnectionState.Connecting) {
                Log.w(TAG, "Connection timeout")
                disconnect()
                _connectionState.value = BleConnectionState.Error
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected, discovering services...")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected")
                    scope.launch { _connectionState.value = BleConnectionState.Disconnected }
                    cleanup()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                scope.launch { _connectionState.value = BleConnectionState.Error }
                return
            }
            val service = gatt.getService(serviceUuid)
            if (service == null) {
                Log.e(TAG, "Target service not found")
                scope.launch { _connectionState.value = BleConnectionState.Error }
                return
            }
            val characteristic = service.getCharacteristic(charUuid)
            if (characteristic == null) {
                Log.e(TAG, "Target characteristic not found")
                scope.launch { _connectionState.value = BleConnectionState.Error }
                return
            }
            writeCharacteristic = characteristic

            // 开启通知
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            )
            if (descriptor != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
            }

            Log.d(TAG, "BLE ready")
            scope.launch { _connectionState.value = BleConnectionState.Connected }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val hex = BleCommandHelper.bytesToHex(characteristic.value)
            Log.d(TAG, "Characteristic changed: $hex")
            scope.launch { _lastResponse.value = hex }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            val hex = BleCommandHelper.bytesToHex(value)
            Log.d(TAG, "Characteristic changed: $hex")
            scope.launch { _lastResponse.value = hex }
        }
    }

    // ==================== 发送指令 ====================

    fun sendCommand(data: ByteArray): Boolean {
        val gatt = bluetoothGatt ?: return false
        val char = writeCharacteristic ?: return false
        Log.d(TAG, "Sending: ${BleCommandHelper.bytesToHex(data)}")

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                char, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            char.value = data
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }
    }

    // ==================== 断开 ====================

    fun disconnect() {
        stopScan()
        bluetoothGatt?.disconnect()
        cleanup()
        _connectionState.value = BleConnectionState.Disconnected
    }

    private fun cleanup() {
        bluetoothGatt?.close()
        bluetoothGatt = null
        writeCharacteristic = null
    }

    fun destroy() {
        disconnect()
        scope.cancel()
    }

    companion object {
        private const val TAG = "DoorLockBleManager"
    }
}
