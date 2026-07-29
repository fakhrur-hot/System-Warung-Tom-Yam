package com.warungtomyam.pos.ui.util

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Helper for Bluetooth scanning operations.
 * Handles Android 12+ (API 31) permission model: BLUETOOTH_SCAN + BLUETOOTH_CONNECT.
 * For older devices (API 26–30): BLUETOOTH + BLUETOOTH_ADMIN (install-time).
 *
 * Usage:
 * 1. Check/request permissions via getRequiredPermissions()
 * 2. Call getPairedDevices() for already-bonded printers
 * 3. Call startDiscovery() / stopDiscovery() for new device scanning
 */
class BluetoothHelper(private val context: Context) {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var discoveryReceiver: BroadcastReceiver? = null

    /**
     * Returns the Bluetooth permissions required based on the device's API level.
     * On API 31+ (Android 12+): BLUETOOTH_SCAN and BLUETOOTH_CONNECT.
     * On API 26–30: no runtime permissions needed (manifest-declared BLUETOOTH/BLUETOOTH_ADMIN suffice).
     */
    fun getRequiredPermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            // Pre-Android 12: BLUETOOTH and BLUETOOTH_ADMIN are normal (install-time) permissions
            emptyList()
        }
    }

    /**
     * Checks if all required Bluetooth permissions are granted.
     */
    fun hasPermissions(): Boolean {
        val required = getRequiredPermissions()
        if (required.isEmpty()) return true
        return required.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Returns true if Bluetooth is available and enabled on this device.
     */
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    /**
     * Returns already-paired (bonded) Bluetooth devices.
     * These are the most common case — printers are typically paired once via system settings.
     */
    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<DiscoveredDevice> {
        if (!hasPermissions()) return emptyList()
        val adapter = bluetoothAdapter ?: return emptyList()

        return adapter.bondedDevices
            .filter { it.name != null }
            .map { device ->
                DiscoveredDevice(
                    name = device.name ?: "Unknown",
                    macAddress = device.address
                )
            }
    }

    /**
     * Start Bluetooth discovery to find new (unpaired) devices.
     * Results arrive via the discoveredDevices StateFlow.
     */
    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        if (!hasPermissions()) return
        val adapter = bluetoothAdapter ?: return

        // Clear previous results, start with paired devices
        _discoveredDevices.value = getPairedDevices()
        _isScanning.value = true

        // Register receiver for found devices
        discoveryReceiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(
                                BluetoothDevice.EXTRA_DEVICE,
                                BluetoothDevice::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        device?.let { addDiscoveredDevice(it) }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        _isScanning.value = false
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        context.registerReceiver(discoveryReceiver, filter)

        // Cancel any ongoing discovery and start fresh
        adapter.cancelDiscovery()
        adapter.startDiscovery()
    }

    /**
     * Stop Bluetooth discovery scan.
     */
    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        bluetoothAdapter?.cancelDiscovery()
        _isScanning.value = false
        discoveryReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
                // Already unregistered
            }
        }
        discoveryReceiver = null
    }

    @SuppressLint("MissingPermission")
    private fun addDiscoveredDevice(device: BluetoothDevice) {
        val name = device.name ?: return // Skip unnamed devices
        val mac = device.address ?: return

        val current = _discoveredDevices.value.toMutableList()
        if (current.none { it.macAddress == mac }) {
            current.add(DiscoveredDevice(name = name, macAddress = mac))
            _discoveredDevices.value = current
        }
    }
}

/**
 * Represents a Bluetooth device discovered during scanning.
 */
data class DiscoveredDevice(
    val name: String,
    val macAddress: String
)
