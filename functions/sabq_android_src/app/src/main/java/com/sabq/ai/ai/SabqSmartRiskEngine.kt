package com.sabq.ai.ai

import com.sabq.ai.data.local.LabReadingEntity

data class DeterministicRiskOutput(
    val category: String,
    val titleAr: String,
    val riskLevelAr: String,
    val resultClassification: String, // "تقييم خطورة غير تشخيصي" | "قياس فعلي موثق"
    val usedData: List<String>,
    val elevatingFactors: List<String>,
    val missingInformation: List<String>,
    val nextStepAr: String
)

/**
 * SABQ SMART RISK ENGINE (Android Deterministic Clinical Rules):
 * - Zero LLM guessing for numeric lab results.
 * - Zero random confidence or risk scores.
 * - Explicitly discloses used data, elevating factors, missing info, and next step.
 */
object SabqSmartRiskEngine {

    fun evaluateAll(
        intake: VoluntaryHealthIntake,
        labs: List<LabReadingEntity>
    ): List<DeterministicRiskOutput> {
        val bmi = SabqHealthAssessmentEngine.calculateRealBmi(intake.heightCm, intake.weightKg)
        val hasLab = { code: String -> labs.firstOrNull { it.testCode == code } }

        val diabetesUsed = mutableListOf<String>()
        val diabetesElevating = mutableListOf<String>()
        val diabetesMissing = mutableListOf<String>()

        if (intake.ageYears != null) {
            diabetesUsed.add("العمر: ${intake.ageYears}")
            if (intake.ageYears >= 45) diabetesElevating.add("العمر 45 عامًا فأكثر")
        } else diabetesMissing.add("العمر")

        if (bmi != null) {
            diabetesUsed.add("BMI: ${String.format(java.util.Locale.US, "%.1f", bmi)}")
            if (bmi >= 25f) diabetesElevating.add("ارتفاع مؤشر كتلة الجسم")
        } else diabetesMissing.add("الطول والوزن لحساب BMI")

        if (intake.familyHistory.any { it.contains("السكري") }) {
            diabetesUsed.add("تاريخ عائلي للسكري")
            diabetesElevating.add("تاريخ عائلي للسكري")
        }

        val glucoseLab = hasLab("GLUCOSE")
        val hba1cLab = hasLab("HBA1C")
        if (glucoseLab != null) diabetesUsed.add("سكر الدم: ${glucoseLab.measuredValue}") else diabetesMissing.add("تحليل سكر الدم الصائم")
        if (hba1cLab != null) diabetesUsed.add("HbA1c: ${hba1cLab.measuredValue}") else diabetesMissing.add("تحليل السكر التراكمي HbA1c")

        val dLevel = when {
            diabetesUsed.isEmpty() -> "بيانات غير كافية"
            diabetesElevating.size >= 2 -> "مرتفع"
            diabetesElevating.size == 1 -> "متوسط"
            else -> "منخفض"
        }

        val bpUsed = mutableListOf<String>()
        val bpElevating = mutableListOf<String>()
        val bpMissing = mutableListOf<String>()
        val bpLab = hasLab("BP")
        if (intake.ageYears != null) {
            bpUsed.add("العمر: ${intake.ageYears}")
            if (intake.ageYears >= 40) bpElevating.add("العمر 40 فأكثر")
        } else bpMissing.add("العمر")
        if (bmi != null && bmi >= 25f) bpElevating.add("زيادة الوزن")
        if (intake.familyHistory.any { it.contains("الضغط") }) {
            bpUsed.add("تاريخ عائلي لضغط الدم")
            bpElevating.add("تاريخ عائلي لارتفاع الضغط")
        }
        if (bpLab != null) bpUsed.add("الضغط الموثق: ${bpLab.measuredValue}") else bpMissing.add("قياس جهاز ضغط الدم المعتمد")

        val bpLevel = when {
            bpUsed.isEmpty() -> "بيانات غير كافية"
            bpElevating.size >= 2 -> "مرتفع"
            bpElevating.size == 1 -> "متوسط"
            else -> "منخفض"
        }

        return listOf(
            DeterministicRiskOutput(
                category = "DIABETES_RISK",
                titleAr = "تقييم عوامل خطورة السكري والسكر التراكمي",
                riskLevelAr = dLevel,
                resultClassification = if (glucoseLab != null || hba1cLab != null) "قياس فعلي موثق" else "تقييم خطورة غير تشخيصي",
                usedData = diabetesUsed,
                elevatingFactors = diabetesElevating,
                missingInformation = diabetesMissing,
                nextStepAr = "إجراء تحليل سكر صائم وHbA1c في مختبر معتمد وتسجيل النتيجة."
            ),
            DeterministicRiskOutput(
                category = "BP_RISK",
                titleAr = "تقييم عوامل خطورة ضغط الدم",
                riskLevelAr = bpLevel,
                resultClassification = if (bpLab != null) "قياس فعلي موثق" else "تقييم خطورة غير تشخيصي",
                usedData = bpUsed,
                elevatingFactors = bpElevating,
                missingInformation = bpMissing,
                nextStepAr = "قياس الضغط الانقباضي والانبساطي بجهاز طبي معاير (لا يُقاس بالكاميرا)."
            )
        )
    }
}
