package com.sabq.ai.ai

import com.sabq.ai.data.local.HealthRiskAssessmentEntity
import com.sabq.ai.data.local.LabReadingEntity
import java.util.Locale
import java.util.UUID

data class VoluntaryHealthIntake(
    val ageYears: Int?,
    val genderAr: String,
    val heightCm: Float?,
    val weightKg: Float?,
    val symptoms: List<String>,
    val personalHistory: List<String>,
    val familyHistory: List<String>,
    val activityLevel: String, // منخفض | متوسط | نشط
    val sleepHours: Float?
)

/**
 * SABQ SMART HEALTH ASSESSMENT Engine.
 * Evaluates risk factors strictly from voluntary user intake + real verified measurements/BMI.
 * Never fabricates numeric BP, glucose, HbA1c, CBC, or thyroid values.
 */
object SabqHealthAssessmentEngine {

    fun calculateRealBmi(heightCm: Float?, weightKg: Float?): Float? {
        if (heightCm == null || weightKg == null) return null
        if (heightCm !in 80f..250f || weightKg !in 15f..350f) return null
        val hMeters = heightCm / 100f
        return weightKg / (hMeters * hMeters)
    }

    fun classifyBmiAr(bmi: Float?): String {
        if (bmi == null) return "غير محسوب (أدخل الطول والوزن الفعليين)"
        return when {
            bmi < 18.5f -> "نقص في الوزن (${String.format(Locale.US, "%.1f", bmi)})"
            bmi < 25.0f -> "وزن طبيعي (${String.format(Locale.US, "%.1f", bmi)})"
            bmi < 30.0f -> "زيادة في الوزن (${String.format(Locale.US, "%.1f", bmi)})"
            else -> "سمنة (${String.format(Locale.US, "%.1f", bmi)})"
        }
    }

    fun evaluateRiskFactors(
        userId: String,
        intake: VoluntaryHealthIntake,
        verifiedLabs: List<LabReadingEntity>
    ): List<HealthRiskAssessmentEntity> {
        val bmi = calculateRealBmi(intake.heightCm, intake.weightKg)
        val hasDiabetesFamily = intake.familyHistory.any { it.contains("السكري") }
        val hasBpFamily = intake.familyHistory.any { it.contains("الضغط") }
        val hasHeartFamily = intake.familyHistory.any { it.contains("القلب") }
        val hasThyroidFamily = intake.familyHistory.any { it.contains("الغدة") }

        val highBmi = (bmi ?: 0f) >= 25f
        val veryHighBmi = (bmi ?: 0f) >= 30f
        val ageOver40 = (intake.ageYears ?: 0) >= 40
        val lowActivity = intake.activityLevel == "منخفض"
        val poorSleep = (intake.sleepHours ?: 7f) < 6f

        val thirstSymptom = intake.symptoms.any { it.contains("عطش") || it.contains("تبول") }
        val fatigueSymptom = intake.symptoms.any { it.contains("إرهاق") || it.contains("تعب") || it.contains("شحوب") || it.contains("تساقط") }
        val dizzinessSymptom = intake.symptoms.any { it.contains("دوخة") || it.contains("صداع") || it.contains("خفقان") || it.contains("ضيق") }

        val diabetesScore = (if (veryHighBmi) 2 else if (highBmi) 1 else 0) +
            (if (hasDiabetesFamily) 2 else 0) +
            (if (ageOver40) 1 else 0) +
            (if (lowActivity) 1 else 0) +
            (if (thirstSymptom) 2 else 0)

        val bpScore = (if (veryHighBmi) 2 else if (highBmi) 1 else 0) +
            (if (hasBpFamily) 2 else 0) +
            (if (ageOver40) 1 else 0) +
            (if (poorSleep) 1 else 0) +
            (if (dizzinessSymptom) 2 else 0)

        val thyroidAnemiaScore = (if (hasThyroidFamily) 2 else 0) +
            (if (fatigueSymptom) 2 else 0) +
            (if (intake.personalHistory.any { it.contains("الأنيميا") || it.contains("الغدة") }) 2 else 0)

        val heartScore = (if (hasHeartFamily) 2 else 0) +
            (if (veryHighBmi) 1 else 0) +
            (if (lowActivity) 1 else 0) +
            (if (dizzinessSymptom) 2 else 0) +
            (if (ageOver40) 1 else 0)

        fun levelFromScore(s: Int): String = when {
            s >= 4 -> "يحتاج فحصًا طبيًا"
            s >= 2 -> "متوسط"
            else -> "منخفض"
        }

        val insufficientIntake = intake.ageYears == null && intake.symptoms.isEmpty() && intake.familyHistory.isEmpty() && bmi == null
        val verifiedCount = verifiedLabs.size

        return listOf(
            HealthRiskAssessmentEntity(
                id = UUID.randomUUID().toString(),
                userId = userId,
                category = "RISK_DIABETES",
                categoryTitleAr = "تقييم عوامل خطورة السكري والسكر التراكمي",
                riskLevel = if (insufficientIntake) "غير كافٍ" else levelFromScore(diabetesScore),
                ageYears = intake.ageYears,
                genderAr = intake.genderAr,
                bmiValue = bmi,
                rationaleAr = "تقدير معتمد على مدخلاتك الطوعية ومؤشر BMI الفعلي (${classifyBmiAr(bmi)}) دون توليد أي رقم سكري أو HbA1c وهمي. التحاليل الموثقة المسجلة: $verifiedCount.",
                recommendedTestsAr = "تحليل سكر صائم (FBS) + سكر تراكمي (HbA1c) في مختبر معتمد.",
                suggestionsAr = "تقليل السكريات المكررة، المشي المنتظم 30 دقيقة يوميًا، ومراجعة الطبيب عند العطش أو التبول المتكرر."
            ),
            HealthRiskAssessmentEntity(
                id = UUID.randomUUID().toString(),
                userId = userId,
                category = "RISK_BP",
                categoryTitleAr = "تقييم عوامل خطورة ضغط الدم",
                riskLevel = if (insufficientIntake) "غير كافٍ" else levelFromScore(bpScore),
                ageYears = intake.ageYears,
                genderAr = intake.genderAr,
                bmiValue = bmi,
                rationaleAr = "يعتمد على العمر والتاريخ العائلي والنوم والأعراض المصرّح بها، مع رفض عرض أي قياس ضغط دم مصطنع من الكاميرا أو مستشعر البصمة.",
                recommendedTestsAr = "قياس ضغط الدم بجهاز معاير بالذراع (صباحًا ومساءً) أو لدى الطبيب.",
                suggestionsAr = "تقليل الملح، تحسين جودة النوم، إدارة التوتر، وقياس الضغط فعليًا بجهاز طبي عند الصداع المتكرر."
            ),
            HealthRiskAssessmentEntity(
                id = UUID.randomUUID().toString(),
                userId = userId,
                category = "RISK_THYROID_BLOOD",
                categoryTitleAr = "تقييم عوامل خطورة الغدة الدرقية والأنيميا",
                riskLevel = if (insufficientIntake) "غير كافٍ" else levelFromScore(thyroidAnemiaScore),
                ageYears = intake.ageYears,
                genderAr = intake.genderAr,
                bmiValue = bmi,
                rationaleAr = "بناءً على الأعراض الطوعية (كالإرهاق أو تساقط الشعر أو الشحوب) والتاريخ الشخصي، ولا تُستنتج قيم TSH أو الهيموغلوبين من الصور.",
                recommendedTestsAr = "صورة دم كاملة (CBC) + مخزون الحديد (Ferritin) + وظائف الغدة الدرقية (TSH, FT4).",
                suggestionsAr = "إجراء الفحوصات المختبرية المذكورة قبل تناول أي مكملات حديد أو علاجات دوائية."
            ),
            HealthRiskAssessmentEntity(
                id = UUID.randomUUID().toString(),
                userId = userId,
                category = "HEART",
                categoryTitleAr = "تقييم مؤشرات الخطورة القلبية",
                riskLevel = if (insufficientIntake) "غير كافٍ" else levelFromScore(heartScore),
                ageYears = intake.ageYears,
                genderAr = intake.genderAr,
                bmiValue = bmi,
                rationaleAr = "مؤشر استرشادي لعوامل نمط الحياة والتاريخ العائلي، وليس بديلًا عن تخطيط القلب أو قياس الدهون.",
                recommendedTestsAr = "ملف الدهون (Lipid Profile) + تخطيط قلب (ECG) عند الخفقان أو ألم الصدر.",
                suggestionsAr = "الالتزام بنشاط هوائي معتدل ومراجعة الطوارئ فورًا عند أي ألم صدري أو ضيق تنفس."
            ),
            HealthRiskAssessmentEntity(
                id = UUID.randomUUID().toString(),
                userId = userId,
                category = "GENERAL_HEALTH",
                categoryTitleAr = "ملخص التقييم الصحي التقديري العام",
                riskLevel = if (insufficientIntake) "غير كافٍ" else levelFromScore((diabetesScore + bpScore + heartScore) / 3),
                ageYears = intake.ageYears,
                genderAr = intake.genderAr,
                bmiValue = bmi,
                rationaleAr = "خلاصة تقييم عوامل الخطورة المبني على مدخلات المستخدم وBMI الفعلي فقط — تقييم عوامل خطورة وليس تشخيصًا طبيًا.",
                recommendedTestsAr = "فحص دوري سنوي (CBC، سكر صائم، دهون، وظائف كلى وكبد).",
                suggestionsAr = "الجمع بين الفحص البصري الموجه في سَبَق والفحوصات المختبرية الموثقة لمتابعة تغيراتك بدقة."
            )
        )
    }
}
