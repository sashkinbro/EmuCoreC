package com.sbro.emucorec.core

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log

internal object MicrophoneDevices {
    private var manager: AudioManager? = null
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    @Synchronized
    fun initialize(context: Context) {
        if (manager == null) {
            val audio = context.applicationContext.getSystemService(AudioManager::class.java) ?: return
            manager = audio
            audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        }
        refresh()
    }

    private fun refresh() {
        val devices = manager?.getDevices(AudioManager.GET_DEVICES_INPUTS).orEmpty()
        runCatching {
            Ps3Core.instance.setMicrophoneDevices(
                devices.map { it.productName.toString() }.toTypedArray(),
                devices.map { it.id }.toIntArray(),
            )
        }.onFailure { Log.w("EmuCoreC-Microphone", "Could not update input devices", it) }
    }
}
