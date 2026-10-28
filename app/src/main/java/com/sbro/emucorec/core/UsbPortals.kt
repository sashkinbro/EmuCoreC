package com.sbro.emucorec.core

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

internal object UsbPortals {
    private const val PERMISSION_ACTION = "com.sbro.emucorec.USB_PORTAL_PERMISSION"
    private val supported = setOf(0x1430 to 0x0150, 0x0e6f to 0x0129, 0x0e6f to 0x0241, 0x0e6f to 0x200a)
    private val asked = mutableSetOf<String>()
    private var watching = false

    private fun isPortal(device: UsbDevice) = (device.vendorId to device.productId) in supported

    private fun requestPermissions(context: Context) {
        val manager = context.getSystemService(UsbManager::class.java) ?: return
        manager.deviceList.values.filter(::isPortal).forEach { device ->
            if (!manager.hasPermission(device) && asked.add(device.deviceName)) {
                val intent = Intent(PERMISSION_ACTION).setPackage(context.packageName)
                val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE)
                runCatching { manager.requestPermission(device, pending) }.onFailure { asked.remove(device.deviceName) }
            }
        }
    }

    @Synchronized
    fun watch(context: Context) {
        val app = context.applicationContext
        if (!watching) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        UsbManager.ACTION_USB_DEVICE_ATTACHED -> requestPermissions(app)
                        UsbManager.ACTION_USB_DEVICE_DETACHED -> IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                            ?.let { asked.remove(it.deviceName) }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            watching = true
        }
        requestPermissions(app)
    }

    fun prepareForGame(context: Context) {
        val manager = context.getSystemService(UsbManager::class.java)
        val connections = manager?.deviceList?.values.orEmpty().filter { isPortal(it) && manager?.hasPermission(it) == true }
            .mapNotNull { runCatching { manager?.openDevice(it) }.getOrNull() }
        try {
            Ps3Core.instance.prepareUsbDevices(connections.map { it.fileDescriptor }.toIntArray())
        } finally {
            connections.forEach { it.close() }
        }
    }
}
