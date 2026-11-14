package com.sabq.ai.sensors

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import java.util.Locale

data class WearableAudioStatus(
    val bluetoothHeadsetConnected: Boolean,
    val connectedDeviceName: String?,
    val ttsReady: Boolean,
    val supportsWearOsSync: Boolean,
    val statusSummaryAr: String
)

class WearableAudioManager(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    fun initTts(onReady: (Boolean) -> Unit = {}) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val res = tts?.setLanguage(Locale("ar"))
                ttsReady = res != TextToSpeech.LANG_MISSING_DATA &&
                    res != TextToSpeech.LANG_NOT_SUPPORTED
                onReady(ttsReady)
            } else {
                ttsReady = false
                onReady(false)
            }
        }
    }

    fun speakArabic(text: String, volume: Float = 0.85f) {
        if (!ttsReady) return
        val params = android.os.Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f))
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "sabq_tts_${System.currentTimeMillis()}")
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    fun inspectAudioAndWearableStatus(): WearableAudioStatus {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val btDevice = outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }

        val btPermissionGranted = if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else true

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = btManager?.adapter
        val headsetState = if (btPermissionGranted && adapter?.isEnabled == true) {
            runCatching { adapter.getProfileConnectionState(BluetoothProfile.HEADSET) }.getOrNull()
        } else null

        val isBtConnected = btDevice != null || headsetState == BluetoothProfile.STATE_CONNECTED
        val name = btDevice?.productName?.toString()

        val wearOsInstalled = runCatching {
            context.packageManager.getPackageInfo("com.google.android.wearable.app", 0)
            true
        }.getOrDefault(false)

        val summary = buildString {
            if (isBtConnected) {
                append("سماعة Bluetooth متصلة${if (!name.isNullOrBlank()) ": $name" else ""}.")
            } else {
                append("لا توجد سماعة Bluetooth متصلة حاليًا.")
            }
            if (wearOsInstalled) {
                append(" تطبيق Wear OS متوفر على الجهاز ويمكن استقبال بيانات النشاط المصرح بها.")
            } else {
                append(" مزامنة الساعات تتطلب تفعيل Wear OS أو مصدر بيانات مصرح به.")
            }
        }

        return WearableAudioStatus(
            bluetoothHeadsetConnected = isBtConnected,
            connectedDeviceName = name,
            ttsReady = ttsReady,
            supportsWearOsSync = wearOsInstalled,
            statusSummaryAr = summary
        )
    }

    fun release() {
        tts?.shutdown()
        tts = null
    }
}
