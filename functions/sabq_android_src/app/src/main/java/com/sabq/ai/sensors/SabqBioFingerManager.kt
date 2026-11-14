package com.sabq.ai.sensors

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.biometric.BiometricManager
import kotlin.math.abs

/**
 * SABQ BioFinger & Unified Non-Invasive Sensor Architecture.
 * Strictly prohibits treating identity fingerprint readers as medical blood/glucose sensors
 * and prohibits returning hardcoded or fabricated physiological readings.
 */
data class DeviceSensorCapabilities(
    val hasRearCamera: Boolean,
    val hasCameraFlash: Boolean,
    val hasDedicatedHeartRateSensor: Boolean,
    val hasBiometricIdentitySensor: Boolean,
    val hasAccelerometer: Boolean,
    val notesAr: String
)

data class OpticalPpgResult(
    val success: Boolean,
    val estimatedBpm: Int?,
    val signalQualityScore: Float,
    val sourceLabel: String,
    val isNonDiagnosticEstimate: Boolean = true,
    val messageAr: String
)

interface BioSensorProvider {
    val providerId: String
    val displayNameAr: String
    val isClinicallyValidated: Boolean
    fun isHardwareSupported(context: Context): Boolean
}

class CameraOpticalPpgProvider : BioSensorProvider {
    override val providerId: String = "camera_ppg_optical"
    override val displayNameAr: String = "التصوير الضوئي عبر الكاميرا (PPG)"
    override val isClinicallyValidated: Boolean = false

    override fun isHardwareSupported(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }

    /**
     * Computes heart rate (BPM) strictly from actual sampled red-channel luminance frames.
     * Returns failure if samples are insufficient, noisy, or out of physiological range.
     */
    fun estimatePulseFromRedLuminance(
        samples: List<Double>,
        timestampsMs: List<Long>
    ): OpticalPpgResult {
        if (samples.size < 120 || samples.size != timestampsMs.size) {
            return OpticalPpgResult(
                success = false,
                estimatedBpm = null,
                signalQualityScore = 0f,
                sourceLabel = displayNameAr,
                messageAr = "تعذر إجراء القياس، يرجى المحاولة مرة أخرى."
            )
        }

        val durationSec = (timestampsMs.last() - timestampsMs.first()) / 1000.0
        if (durationSec < 5.0) {
            return OpticalPpgResult(
                success = false,
                estimatedBpm = null,
                signalQualityScore = 0f,
                sourceLabel = displayNameAr,
                messageAr = "تعذر إجراء القياس، يرجى المحاولة مرة أخرى."
            )
        }

        val mean = samples.average()
        val stdDev = kotlin.math.sqrt(samples.map { (it - mean) * (it - mean) }.average())
        if (mean < 60.0 || stdDev < 0.15) {
            // Finger not covering camera/flash properly or static non-pulsatile frame
            return OpticalPpgResult(
                success = false,
                estimatedBpm = null,
                signalQualityScore = 0.1f,
                sourceLabel = displayNameAr,
                messageAr = "تعذر إجراء القياس، يرجى المحاولة مرة أخرى."
            )
        }

        // Simple moving-average detrending + peak detection
        val window = 5
        val detrended = samples.indices.map { i ->
            val start = maxOf(0, i - window)
            val end = minOf(samples.lastIndex, i + window)
            val localMean = samples.subList(start, end + 1).average()
            samples[i] - localMean
        }

        val peakTimes = mutableListOf<Long>()
        val minIntervalMs = 330L // <= 180 BPM
        for (i in 2 until detrended.size - 2) {
            val v = detrended[i]
            if (v > 0.05 && v > detrended[i - 1] && v > detrended[i + 1] &&
                v >= detrended[i - 2] && v >= detrended[i + 2]
            ) {
                val t = timestampsMs[i]
                if (peakTimes.isEmpty() || (t - peakTimes.last()) >= minIntervalMs) {
                    peakTimes.add(t)
                }
            }
        }

        if (peakTimes.size < 4) {
            return OpticalPpgResult(
                success = false,
                estimatedBpm = null,
                signalQualityScore = 0.25f,
                sourceLabel = displayNameAr,
                messageAr = "تعذر إجراء القياس، يرجى المحاولة مرة أخرى."
            )
        }

        val intervals = peakTimes.zipWithNext { a, b -> (b - a).toDouble() }
        val avgInterval = intervals.average()
        val jitter = intervals.map { abs(it - avgInterval) }.average() / avgInterval
        val bpm = (60000.0 / avgInterval).toInt()

        if (bpm !in 42..180 || jitter > 0.35) {
            return OpticalPpgResult(
                success = false,
                estimatedBpm = null,
                signalQualityScore = (1.0 - jitter).toFloat().coerceIn(0f, 1f),
                sourceLabel = displayNameAr,
                messageAr = "تعذر إجراء القياس، يرجى المحاولة مرة أخرى."
            )
        }

        val quality = (1.0 - jitter).toFloat().coerceIn(0.4f, 0.95f)
        return OpticalPpgResult(
            success = true,
            estimatedBpm = bpm,
            signalQualityScore = quality,
            sourceLabel = displayNameAr,
            isNonDiagnosticEstimate = true,
            messageAr = "تقدير غير تشخيصي عبر التصوير الضوئي للكاميرا"
        )
    }
}

class SabqBioFingerManager(private val context: Context) {
    private val providers = mutableListOf<BioSensorProvider>(CameraOpticalPpgProvider())

    fun registerFutureProvider(provider: BioSensorProvider) {
        providers.add(provider)
    }

    fun inspectHardwareCapabilities(): DeviceSensorCapabilities {
        val pm = context.packageManager
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        var hasRear = false
        var hasFlash = false
        runCatching {
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    hasRear = true
                    if (chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                        hasFlash = true
                    }
                }
            }
        }

        val hasDedicatedHr = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE) != null
        val hasAccel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
        val biometricCanAuth = BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

        val note = buildString {
            append("قارئ بصمة الهوية مخصص لتأمين الحساب فقط ولا يُستخدم لقياس الدم أو السكر أو الضغط.")
            if (hasRear && hasFlash) {
                append(" الكاميرا الخلفية والفلاش متاحان لتقدير النبض الضوئي غير التشخيصي.")
            }
            if (!hasDedicatedHr) {
                append(" لا يوجد مستشعر نبض مخصص مدمج في هذا الهاتف.")
            }
        }

        return DeviceSensorCapabilities(
            hasRearCamera = hasRear || pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
            hasCameraFlash = hasFlash,
            hasDedicatedHeartRateSensor = hasDedicatedHr,
            hasBiometricIdentitySensor = biometricCanAuth,
            hasAccelerometer = hasAccel,
            notesAr = note
        )
    }
}
