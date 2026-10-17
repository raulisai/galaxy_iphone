package dev.wristbridge.watch.bridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object BlePermissions {
    /** Runtime permissions required in P1. Notifications are optional for the link but needed for cards. */
    val REQUIRED = arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    fun missing(context: Context): List<String> =
        REQUIRED.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }

    /** Bluetooth permissions are the prerequisite of the connectedDevice foreground service type. */
    fun bluetoothGranted(context: Context): Boolean =
        listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
}
