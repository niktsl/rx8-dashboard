package com.rx8.dash.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** A paired Bluetooth device as shown in the device picker. */
data class BondedDevice(
    val name: String,
    val address: String,
    /** Name looks like an OBD/ELM adapter; the picker can sort these first. */
    val likelyObd: Boolean,
)

/** Bonded-device listing and Bluetooth permission checks. Never triggers discovery or pairing. */
class BluetoothDevices(context: Context) {

    private val appContext: Context = context.applicationContext

    fun adapter(): BluetoothAdapter? = adapterOf(appContext)

    fun isSupported(): Boolean = adapter() != null

    /** Adapter on. Needs no runtime permission. */
    fun isEnabled(): Boolean = try {
        adapter()?.isEnabled == true
    } catch (e: SecurityException) {
        false
    }

    fun hasConnectPermission(): Boolean = hasConnectPermission(appContext)

    fun hasScanPermission(): Boolean = hasScanPermission(appContext)

    /** Paired devices, OBD-looking names first. Empty when Bluetooth is off/missing or BLUETOOTH_CONNECT is denied. */
    @SuppressLint("MissingPermission")
    fun bonded(): List<BondedDevice> {
        if (!hasConnectPermission()) return emptyList()
        val a = adapter() ?: return emptyList()
        val devices: Set<BluetoothDevice> = try {
            a.bondedDevices ?: emptySet()
        } catch (e: SecurityException) {
            return emptyList()
        }
        return devices.map { d ->
            val name = try {
                d.name
            } catch (e: SecurityException) {
                null
            }?.takeIf { it.isNotBlank() } ?: d.address
            BondedDevice(name = name, address = d.address, likelyObd = looksLikeObd(name))
        }.sortedWith(compareByDescending<BondedDevice> { it.likelyObd }.thenBy { it.name.lowercase() })
    }

    /** Remote device for a stored MAC address, or null when the address is invalid or Bluetooth is unavailable. */
    fun remoteDevice(address: String): BluetoothDevice? {
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return null
        val a = adapter() ?: return null
        return try {
            a.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    companion object {
        private val OBD_HINTS = listOf("obd", "elm", "vlink", "v-link", "obdii", "obd2", "konnwei", "vgate", "icar", "scan")

        fun adapterOf(context: Context): BluetoothAdapter? {
            val manager = context.getSystemService(BluetoothManager::class.java)
            return manager?.adapter
        }

        /** BLUETOOTH_CONNECT is a runtime permission only on API 31+; older releases use install-time BLUETOOTH. */
        fun hasConnectPermission(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

        fun hasScanPermission(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

        fun looksLikeObd(name: String): Boolean {
            val n = name.lowercase()
            return OBD_HINTS.any { n.contains(it) }
        }
    }
}
