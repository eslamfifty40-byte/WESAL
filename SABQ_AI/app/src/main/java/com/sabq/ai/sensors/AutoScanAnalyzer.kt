package com.sabq.ai.sensors

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import kotlin.math.abs

enum class SmartScanStage {
    FACE_STAGE,
    BODY_STAGE,
    HAIR_STAGE,
    REVIEW_STAGE
}

data class AutoFrameAssessment(
    val stage: SmartScanStage,
    val brightness: Double,
    val motionScore: Double,
    val framingQualityScore: Float,
    val isReadyForAutoScan: Boolean,
    val statusAr: String
)

/**
 * SABQ SMART SCAN Guided CameraX Analyzer.
 * Uses ML Kit FaceDetection (FACE_STAGE & HAIR_STAGE) and ML Kit PoseDetection (BODY_STAGE)
 * alongside lighting/motion checks to guide the user in Arabic and automatically capture
 * optimal frames without requiring a manual shutter button press.
 */
class AutoScanAnalyzer(
    private val currentStageProvider: () -> SmartScanStage,
    private val onStatusUpdate: (AutoFrameAssessment) -> Unit,
    private val onStageFrameCaptured: (stage: SmartScanStage, fullFrame: Bitmap, hairRegionCrop: Bitmap?) -> Unit
) : ImageAnalysis.Analyzer {

    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .build()
    )

    private val poseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )

    private var lastProcessedMs = 0L
    private var lastAnalyzeTriggerMs = 0L
    private var prevSampleLuma: IntArray? = null
    private var steadyFramesCount = 0

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val stage = currentStageProvider()
        if (stage == SmartScanStage.REVIEW_STAGE) {
            image.close()
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastProcessedMs < 240L) {
            image.close()
            return
        }
        lastProcessedMs = now

        val yPlane = image.planes.firstOrNull()
        val mediaImage = image.image
        if (yPlane == null || mediaImage == null) {
            image.close()
            return
        }

        val buffer = yPlane.buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)

        val step = maxOf(1, data.size / 1200)
        val sampleCount = data.size / step
        val currentLuma = IntArray(sampleCount)
        var sum = 0L
        var iOut = 0
        var iIn = 0
        while (iIn < data.size && iOut < sampleCount) {
            val v = data[iIn].toInt() and 0xFF
            currentLuma[iOut++] = v
            sum += v
            iIn += step
        }

        val brightness = if (iOut > 0) sum.toDouble() / iOut else 0.0
        var motionSum = 0.0
        val prev = prevSampleLuma
        if (prev != null && prev.size == currentLuma.size) {
            for (k in currentLuma.indices) {
                motionSum += abs(currentLuma[k] - prev[k])
            }
        }
        val motionScore = if (prev != null) motionSum / currentLuma.size else 20.0
        prevSampleLuma = currentLuma

        if (brightness < 48.0) {
            steadyFramesCount = 0
            onStatusUpdate(
                AutoFrameAssessment(
                    stage = stage,
                    brightness = brightness,
                    motionScore = motionScore,
                    framingQualityScore = 0.2f,
                    isReadyForAutoScan = false,
                    statusAr = "الإضاءة ضعيفة — وجّه إضاءة مناسبة على وجهك"
                )
            )
            image.close()
            return
        }

        if (brightness > 240.0) {
            steadyFramesCount = 0
            onStatusUpdate(
                AutoFrameAssessment(
                    stage = stage,
                    brightness = brightness,
                    motionScore = motionScore,
                    framingQualityScore = 0.3f,
                    isReadyForAutoScan = false,
                    statusAr = "الإضاءة قوية جدًا — خفف الإضاءة المباشرة"
                )
            )
            image.close()
            return
        }

        if (motionScore > 14.0) {
            steadyFramesCount = 0
            onStatusUpdate(
                AutoFrameAssessment(
                    stage = stage,
                    brightness = brightness,
                    motionScore = motionScore,
                    framingQualityScore = 0.4f,
                    isReadyForAutoScan = false,
                    statusAr = "الحركة سريعة — اثبت قليلًا حتى تكتمل اللقطة..."
                )
            )
            image.close()
            return
        }

        val inputImage = InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees)
        val frameWidth = if (image.imageInfo.rotationDegrees % 180 == 90) image.height else image.width
        val frameHeight = if (image.imageInfo.rotationDegrees % 180 == 90) image.width else image.height

        when (stage) {
            SmartScanStage.FACE_STAGE -> {
                faceDetector.process(inputImage)
                    .addOnSuccessListener { faces ->
                        val face = faces.firstOrNull()
                        val msg: String
                        var ready = false
                        var score = 0.4f
                        if (face == null) {
                            steadyFramesCount = 0
                            msg = "ضع وجهك داخل الإطار الإرشادي بوضوح"
                        } else {
                            val box = face.boundingBox
                            val widthRatio = box.width().toFloat() / maxOf(1, frameWidth)
                            val centerX = (box.centerX().toFloat() / maxOf(1, frameWidth))
                            val yaw = abs(face.headEulerAngleY)
                            val roll = abs(face.headEulerAngleZ)
                            when {
                                widthRatio < 0.24f -> {
                                    steadyFramesCount = 0
                                    msg = "اقترب قليلًا من الكاميرا"
                                }
                                widthRatio > 0.72f -> {
                                    steadyFramesCount = 0
                                    msg = "ابتعد قليلًا"
                                }
                                abs(centerX - 0.5f) > 0.2f -> {
                                    steadyFramesCount = 0
                                    msg = "وسّط وجهك داخل الإطار"
                                }
                                yaw > 16f || roll > 14f -> {
                                    steadyFramesCount = 0
                                    msg = "انظر مباشرة إلى الكاميرا"
                                }
                                else -> {
                                    steadyFramesCount += 1
                                    score = 0.92f
                                    if (steadyFramesCount >= 3) {
                                        ready = true
                                        msg = "ممتاز! جاري التقاط وتحليل الوجه والبشرة والعينين تلقائيًا..."
                                    } else {
                                        msg = "اثبت قليلًا حتى تكتمل اللقطة..."
                                    }
                                }
                            }
                        }

                        onStatusUpdate(AutoFrameAssessment(stage, brightness, motionScore, score, ready, msg))
                        if (ready && now - lastAnalyzeTriggerMs > 6000L) {
                            lastAnalyzeTriggerMs = now
                            steadyFramesCount = 0
                            val rotated = rotateBitmapIfNeeded(image.toBitmap(), image.imageInfo.rotationDegrees)
                            onStageFrameCaptured(stage, rotated, null)
                        }
                    }
                    .addOnCompleteListener { image.close() }
            }

            SmartScanStage.BODY_STAGE -> {
                poseDetector.process(inputImage)
                    .addOnSuccessListener { pose ->
                        val nose = pose.getPoseLandmark(PoseLandmark.NOSE)
                        val leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
                        val rightShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
                        val leftHip = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)
                        val leftAnkle = pose.getPoseLandmark(PoseLandmark.LEFT_ANKLE)
                        val rightAnkle = pose.getPoseLandmark(PoseLandmark.RIGHT_ANKLE)

                        val msg: String
                        var ready = false
                        var score = 0.35f

                        when {
                            nose == null || leftShoulder == null || rightShoulder == null -> {
                                steadyFramesCount = 0
                                msg = "ابتعد قليلًا ليظهر الجسم كاملًا أمام الكاميرا"
                            }
                            leftHip == null || (leftAnkle == null && rightAnkle == null) -> {
                                steadyFramesCount = 0
                                msg = "اجعل قدميك ظاهرتين في الإطار أو ارجع للخلف قليلًا"
                            }
                            else -> {
                                val minConf = minOf(
                                    nose.inFrameLikelihood,
                                    leftShoulder.inFrameLikelihood,
                                    rightShoulder.inFrameLikelihood,
                                    (leftAnkle ?: rightAnkle)!!.inFrameLikelihood
                                )
                                if (minConf < 0.55f) {
                                    steadyFramesCount = 0
                                    msg = "تأكد من وضوح الجسم كاملًا من الرأس إلى القدمين"
                                } else {
                                    steadyFramesCount += 1
                                    score = 0.9f
                                    if (steadyFramesCount >= 3) {
                                        ready = true
                                        msg = "وضعية الجسم مثالية — جاري الالتقاط والتحليل التلقائي..."
                                    } else {
                                        msg = "قف باستقامة وثبات قليلًا..."
                                    }
                                }
                            }
                        }

                        onStatusUpdate(AutoFrameAssessment(stage, brightness, motionScore, score, ready, msg))
                        if (ready && now - lastAnalyzeTriggerMs > 6000L) {
                            lastAnalyzeTriggerMs = now
                            steadyFramesCount = 0
                            val rotated = rotateBitmapIfNeeded(image.toBitmap(), image.imageInfo.rotationDegrees)
                            onStageFrameCaptured(stage, rotated, null)
                        }
                    }
                    .addOnCompleteListener { image.close() }
            }

            SmartScanStage.HAIR_STAGE -> {
                faceDetector.process(inputImage)
                    .addOnSuccessListener { faces ->
                        val face = faces.firstOrNull()
                        val msg: String
                        var ready = false
                        var score = 0.5f

                        if (face != null) {
                            val topRatio = face.boundingBox.top.toFloat() / maxOf(1, frameHeight)
                            if (topRatio < 0.14f) {
                                steadyFramesCount = 0
                                msg = "اخفض رأسك قليلًا أو ابتعد ليظهر الشعر وخط الجبهة بوضوح"
                            } else {
                                steadyFramesCount += 1
                                score = 0.9f
                                if (steadyFramesCount >= 3) {
                                    ready = true
                                    msg = "جاري التقاط صورة الشعر المرجعية تلقائيًا..."
                                } else {
                                    msg = "اثبت قليلًا لالتقاط الشعر وفروة الرأس الظاهرة..."
                                }
                            }
                        } else {
                            // Top-of-head / crown tilted down where face is hidden can still be captured cleanly when steady
                            steadyFramesCount += 1
                            score = 0.85f
                            if (steadyFramesCount >= 4) {
                                ready = true
                                msg = "تم تثبيت زاوية الشعر — جاري الالتقاط التلقائي..."
                            } else {
                                msg = "وجّه أعلى الرأس وخط الشعر أمام الكاميرا بثبات..."
                            }
                        }

                        onStatusUpdate(AutoFrameAssessment(stage, brightness, motionScore, score, ready, msg))
                        if (ready && now - lastAnalyzeTriggerMs > 6000L) {
                            lastAnalyzeTriggerMs = now
                            steadyFramesCount = 0
                            val rotated = rotateBitmapIfNeeded(image.toBitmap(), image.imageInfo.rotationDegrees)
                            val hairCrop = extractHairRegionCrop(rotated)
                            onStageFrameCaptured(stage, rotated, hairCrop)
                        }
                    }
                    .addOnCompleteListener { image.close() }
            }

            SmartScanStage.REVIEW_STAGE -> image.close()
        }
    }

    private fun rotateBitmapIfNeeded(src: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return src
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    /**
     * Crops the upper 62% centered 76% of the SAME camera session frame to isolate
     * visible hair, hairline, and visible scalp angle for the Hair report.
     */
    fun extractHairRegionCrop(fullFrame: Bitmap): Bitmap {
        val x = (fullFrame.width * 0.12f).toInt().coerceAtLeast(0)
        val w = (fullFrame.width * 0.76f).toInt().coerceAtMost(fullFrame.width - x)
        val h = (fullFrame.height * 0.62f).toInt().coerceAtMost(fullFrame.height)
        return Bitmap.createBitmap(fullFrame, x, 0, w, h)
    }

    fun close() {
        runCatching { faceDetector.close() }
        runCatching { poseDetector.close() }
    }
}
