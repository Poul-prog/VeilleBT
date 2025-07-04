package com.martin.veillebt.data.model

import android.bluetooth.BluetoothDevice

data class BleDevice(
    val device: BluetoothDevice, // L'objet BluetoothDevice d'Android
    val rssi: Int,
    var assignedName: String? = null // Nom attribué par l'utilisateur
) {
    val address: String
        get() = device.address

    // Le nom peut être celui diffusé par l'appareil ou null
    val originalName: String?
        get() = try {
            device.name // Attention : peut nécessiter la permission BLUETOOTH_CONNECT sur API 31+
        } catch (e: SecurityException) {
            null
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as BleDevice
        return address == other.address
    }

    override fun hashCode(): Int {
        return address.hashCode()
    }
}