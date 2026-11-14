package com.sabq.ai

import com.sabq.ai.ai.SabqHealthAssessmentEngine
import com.sabq.ai.ai.SabqSmartRiskEngine
import com.sabq.ai.ai.VoluntaryHealthIntake
import com.sabq.ai.sensors.CameraOpticalPpgProvider
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

class SabqValidationTests {

    @Test
    fun `optical ppg rejects insufficient or flat signal without returning fake numbers`() {
        val provider = CameraOpticalPpgProvider()
        val flatSamples = List(150) { 100.0 }
        val timestamps = List(150) { it * 33L }
        val result = provider.estimatePulseFromRedLuminance(flatSamples, timestamps)
        assertFalse(result.success)
        assertNull(result.estimatedBpm)
        assertEquals("تعذر إجراء القياس، يرجى المحاولة مرة أخرى.", result.messageAr)
    }

    @Test
    fun `optical ppg estimates realistic pulse from periodic pulsatile wave`() {
        val provider = CameraOpticalPpgProvider()
        // 72 BPM = 1.2 Hz over 8 seconds at 30 fps (240 samples)
        val timestamps = List(240) { (it * 33.33).toLong() }
        val samples = timestamps.map { t ->
            140.0 + 3.5 * sin(2.0 * Math.PI * 1.2 * (t / 1000.0))
        }
        val result = provider.estimatePulseFromRedLuminance(samples, timestamps)
        assertTrue(result.success)
        assertTrue(result.isNonDiagnosticEstimate)
        assertNotNull(result.estimatedBpm)
        assertTrue(result.estimatedBpm!! in 68..76)
    }

    @Test
    fun `bmi calculation validates real inputs and rejects impossible values`() {
        val validBmi = SabqHealthAssessmentEngine.calculateRealBmi(175f, 70f)
        assertNotNull(validBmi)
        assertEquals(22.86f, validBmi!!, 0.1f)

        assertNull(SabqHealthAssessmentEngine.calculateRealBmi(0f, 70f))
        assertNull(SabqHealthAssessmentEngine.calculateRealBmi(null, 70f))
    }

    @Test
    fun `risk factor assessment returns non-diagnostic risk levels without fabricating glucose or bp`() {
        val intake = VoluntaryHealthIntake(
            ageYears = 45,
            genderAr = "ذكر",
            heightCm = 175f,
            weightKg = 92f,
            symptoms = listOf("عطش وتبول متكرر"),
            personalHistory = emptyList(),
            familyHistory = listOf("السكري", "الضغط"),
            activityLevel = "منخفض",
            sleepHours = 5.5f
        )
        val assessments = SabqHealthAssessmentEngine.evaluateRiskFactors("user_1", intake, emptyList())
        assertEquals(5, assessments.size)
        assessments.forEach { item ->
            assertTrue(item.disclaimerAr.contains("ليس تشخيصًا طبيًا"))
            assertFalse(item.rationaleAr.contains("120/80"))
        }

        val deterministic = SabqSmartRiskEngine.evaluateAll(intake, emptyList())
        assertEquals(2, deterministic.size)
        deterministic.forEach { d ->
            assertEquals("تقييم خطورة غير تشخيصي", d.resultClassification)
            assertTrue(d.missingInformation.isNotEmpty())
            assertTrue(d.elevatingFactors.isNotEmpty())
        }
    }
}
