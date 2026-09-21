package com.babybracelet.gateway

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var gateway: BraceletGateway
    private lateinit var babyState: TextView
    private lateinit var motherState: TextView
    private lateinit var temperature: TextView
    private lateinit var alert: TextView
    private lateinit var connectButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createUi()
        gateway = BraceletGateway(this) { state -> runOnUiThread { render(state) } }
        connectButton.setOnClickListener { requestPermissionsAndStart() }
    }

    override fun onDestroy() {
        gateway.close()
        super.onDestroy()
    }

    private fun createUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 72, 48, 48)
        }
        fun label(text: String, size: Float = 18f) = TextView(this).apply {
            this.text = text
            textSize = size
            setPadding(0, 20, 0, 20)
        }
        root.addView(label("Baby Bracelet Gateway", 28f))
        babyState = label("Baby bracelet: not connected")
        motherState = label("Mother bracelet: not connected")
        temperature = label("Current temperature: --.- °C", 24f)
        alert = label("Waiting for baby data", 22f)
        connectButton = Button(this).apply { text = "Connect bracelets" }
        root.addView(babyState)
        root.addView(motherState)
        root.addView(temperature)
        root.addView(alert)
        root.addView(connectButton)
        setContentView(root)
    }

    private fun requestPermissionsAndStart() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            gateway.start()
        } else {
            requestPermissions(permissions, 100)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 100 && results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) {
            gateway.start()
        }
    }

    private fun render(state: GatewayState) {
        babyState.text = "Baby bracelet: ${state.baby}"
        motherState.text = "Mother bracelet: ${state.mother}"
        temperature.text = state.temperature?.let { "Current temperature: %.1f °C".format(it / 10.0) }
            ?: "Current temperature: --.- °C"
        alert.text = when {
            state.temperature == null -> "Waiting for baby data"
            state.temperature >= 375 -> "ALERT: Temperature high"
            else -> "Safe temperature"
        }
        alert.setTextColor(if (state.temperature != null && state.temperature >= 375) Color.RED else Color.rgb(0, 108, 76))
    }
}

data class GatewayState(
    val baby: String = "not connected",
    val mother: String = "not connected",
    val temperature: Int? = null
)

@Suppress("DEPRECATION", "MissingPermission")
class BraceletGateway(
    context: Context,
    private val onState: (GatewayState) -> Unit
) {
    private val appContext = context.applicationContext
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var scanner: BluetoothLeScanner? = null
    private var babyGatt: BluetoothGatt? = null
    private var motherGatt: BluetoothGatt? = null
    private var babyAddress: String? = null
    private var motherAddress: String? = null
    private var babyTemperature: BluetoothGattCharacteristic? = null
    private var motherTemperature: BluetoothGattCharacteristic? = null
    private var state = GatewayState()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: return
            if (name == BABY_NAME && babyGatt == null) {
                babyAddress = result.device.address
                update(baby = "connecting")
                babyGatt = result.device.connectGatt(appContext, false, gattCallback)
            }
            if (name == MOTHER_NAME && motherGatt == null) {
                motherAddress = result.device.address
                update(mother = "connecting")
                motherGatt = result.device.connectGatt(appContext, false, gattCallback)
            }
            if (babyGatt != null && motherGatt != null) scanner?.stopScan(this)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val isBaby = gatt.device.address == babyAddress
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                update(if (isBaby) "discovering services" else null,
                       if (!isBaby) "discovering services" else null)
                gatt.discoverServices()
            } else {
                if (isBaby) {
                    babyGatt = null; babyTemperature = null; update(baby = "disconnected")
                } else {
                    motherGatt = null; motherTemperature = null; update(mother = "disconnected")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val isBaby = gatt.device.address == babyAddress
            val characteristic = gatt.services.flatMap { it.characteristics }.firstOrNull {
                it.uuid == TEMPERATURE_UUID &&
                (if (isBaby) {
                    (it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                } else {
                    (it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
                })
            } ?: run {
                update(if (isBaby) "temperature characteristic not found" else null,
                       if (!isBaby) "temperature characteristic not found" else null)
                return
            }
            if (isBaby) {
                babyTemperature = characteristic
                gatt.setCharacteristicNotification(characteristic, true)
                val cccd = characteristic.getDescriptor(CCCD) ?: return
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)
            } else {
                motherTemperature = characteristic
                update(mother = "connected; ready for temperatures")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (gatt.device.address == babyAddress) {
                update(baby = if (status == BluetoothGatt.GATT_SUCCESS) "connected; notifications enabled" else "notification setup failed")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (gatt.device.address != babyAddress || characteristic.value.size != 2) return
            val value = (characteristic.value[0].toInt() and 0xFF) or (characteristic.value[1].toInt() shl 8)
            update(temperature = value)
            val destination = motherTemperature ?: return
            destination.value = characteristic.value.copyOf()
            destination.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            motherGatt?.writeCharacteristic(destination)
        }
    }

    fun start() {
        if (adapter == null || !adapter.isEnabled) {
            update(baby = "Bluetooth is off", mother = "Bluetooth is off")
            return
        }
        scanner = adapter.bluetoothLeScanner
        update(baby = "scanning", mother = "scanning")
        scanner?.startScan(scanCallback)
    }

    fun close() {
        scanner?.stopScan(scanCallback)
        babyGatt?.close()
        motherGatt?.close()
    }

    private fun update(baby: String? = null, mother: String? = null, temperature: Int? = state.temperature) {
        state = state.copy(baby = baby ?: state.baby, mother = mother ?: state.mother, temperature = temperature)
        onState(state)
    }

    companion object {
        private const val BABY_NAME = "BABY-BRACELET"
        private const val MOTHER_NAME = "MOTHER-BRACELET"
        private val CCCD = java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val TEMPERATURE_UUID = java.util.UUID.fromString("a0010000-37c4-19b5-8842-6fe254913a7b")
    }
}
