package com.sabq.ai.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sabq.ai.SabqApplication
import com.sabq.ai.ai.SabqHealthAssessmentEngine
import com.sabq.ai.ai.SabqSmartRiskEngine
import com.sabq.ai.ai.VoluntaryHealthIntake
import com.sabq.ai.data.local.LabReadingEntity
import com.sabq.ai.ui.theme.SabqColors
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

data class HealthSectionDef(
    val id: String,
    val nameAr: String,
    val unit: String,
    val badgeAr: String,
    val fieldsHintAr: String
)

private val HEALTH_CENTER_SECTIONS = listOf(
    HealthSectionDef("GLUCOSE", "1. سكر الدم (صائم / فاطر)", "mg/dL", "قياس فعلي + تقييم خطورة", "أدخل قراءة الجلوكوز من الجهاز المنزلي أو المختبر"),
    HealthSectionDef("BP", "2. ضغط الدم (انقباضي / انبساطي)", "mmHg", "قياس فعلي + تقييم خطورة", "مثال: 120/80 من جهاز ضغط معاير (لا يُقاس بالكاميرا)"),
    HealthSectionDef("HBA1C", "3. السكر التراكمي (HbA1c)", "%", "مؤشر مخبري فعلي", "أدخل النسبة المئوية الصادرة عن المختبر"),
    HealthSectionDef("CBC", "4. صورة الدم والأنيميا (CBC)", "g/dL / خلايا", "Hb, RBC, WBC, Platelets", "مثال: Hb 13.8 | RBC 4.8 | WBC 6500 | PLT 240k"),
    HealthSectionDef("THYROID", "5. الغدة الدرقية", "mIU/L", "TSH / T3 / T4", "أدخل قيم TSH وT3 وT4 المخبرية الفعلية"),
    HealthSectionDef("PULSE", "6. القلب والنبض", "bpm", "تقدير بصري PPG أو جهاز", "من حساس البصمة الضوئي الحقيقي أو ساعة/جهاز طبي"),
    HealthSectionDef("RESPIRATORY_SPO2", "7. التنفس والأكسجين (SpO2)", "%", "مقياس التأكسج + التنفس", "أدخل نسبة الأكسجين SpO2 ومعدل التنفس من جهاز Pulse Oximeter"),
    HealthSectionDef("BMI", "8. الوزن والطول ومؤشر BMI", "kg/m²", "معادلة حقيقية وزن/طول²", "أدخل الوزن والطول لحساب BMI الحقيقي وتتبع الوزن"),
    HealthSectionDef("BODY_COMPOSITION", "9. نسبة الدهون والعضلات والوزن", "% / kg", "بيانات حقيقية فقط", "من ميزان InBody/DEXA الفعلي (بدون تخمين)"),
    HealthSectionDef("LIVER_KIDNEY", "10. الكبد والكلى (منفصلتان)", "U/L · mg/dL", "ALT/AST/ALP/Bili & Creat/eGFR/Urea", "سجّل مؤشرات الكبد ومؤشرات الكلى الموثقة بشكل منفصل"),
    HealthSectionDef("FACE_EYE_SKIN", "11. الوجه والعين والبشرة", "فحص بصري", "CameraX + رفع صور متعدد", "فحص بصري غير تشخيصي للعينين والبشرة مع مقارنة السابقة"),
    HealthSectionDef("HAIR_SCALP", "12. الشعر وفروة الرأس", "فحص بصري", "CameraX + رفع صور متعدد", "فحص كثافة الشعر والفراغات وفروة الرأس")
)

@Composable
fun ClinicalLabsScreen(app: SabqApplication, userId: String) {
    val dao = app.database.sabqDao()
    val scope = rememberCoroutineScope()
    val readings by dao.observeLabReadings(userId).collectAsState(initial = emptyList())
    val userProfile by dao.observeUsers().collectAsState(initial = emptyList())
    val activeProfile = userProfile.firstOrNull { it.userId == userId }

    var selectedSection by remember { mutableStateOf(HEALTH_CENTER_SECTIONS.first()) }
    var primaryInput by remember { mutableStateOf("") }
    var secondaryInput by remember { mutableStateOf("") }
    var thirdInput by remember { mutableStateOf("") }
    var fourthInput by remember { mutableStateOf("") }
    var sourceInput by remember { mutableStateOf("تحليل مخبري معتمد / جهاز طبي منزلي") }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val deterministicRisks = remember(readings, activeProfile) {
        SabqSmartRiskEngine.evaluateAll(
            VoluntaryHealthIntake(
                ageYears = null,
                genderAr = "غير محدد",
                heightCm = activeProfile?.heightCm,
                weightKg = activeProfile?.weightKg,
                symptoms = emptyList(),
                personalHistory = emptyList(),
                familyHistory = emptyList(),
                activityLevel = "متوسط",
                sleepHours = null
            ),
            readings
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "المركز الصحي الشامل — 12 قسمًا فعليًا",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "لا يختلق سَبَق أي قراءة مخبرية أو طبية. كل نتيجة تُسجل من قياس فعلي، وكل تقييم خطورة مبني على قواعد SABQ SMART RISK ENGINE الحتمية.",
                fontSize = 12.sp,
                color = SabqColors.Warning
            )
        }

        item {
            Surface(
                color = SabqColors.Surface,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, SabqColors.Cyan.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(selectedSection.nameAr, fontWeight = FontWeight.Bold, color = SabqColors.Cyan, fontSize = 15.sp)
                    Text(selectedSection.fieldsHintAr, color = SabqColors.TextMuted, fontSize = 11.sp)

                    when (selectedSection.id) {
                        "BP" -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = primaryInput,
                                    onValueChange = { primaryInput = it },
                                    label = { Text("الانقباضي Systolic") },
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = secondaryInput,
                                    onValueChange = { secondaryInput = it },
                                    label = { Text("الانبساطي Diastolic") },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        "BMI" -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = primaryInput,
                                    onValueChange = { primaryInput = it },
                                    label = { Text("الوزن (kg)") },
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = secondaryInput,
                                    onValueChange = { secondaryInput = it },
                                    label = { Text("الطول (cm)") },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        "CBC" -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = primaryInput, onValueChange = { primaryInput = it }, label = { Text("Hb (g/dL)") }, modifier = Modifier.weight(1f))
                                OutlinedTextField(value = secondaryInput, onValueChange = { secondaryInput = it }, label = { Text("RBC") }, modifier = Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = thirdInput, onValueChange = { thirdInput = it }, label = { Text("WBC") }, modifier = Modifier.weight(1f))
                                OutlinedTextField(value = fourthInput, onValueChange = { fourthInput = it }, label = { Text("Platelets") }, modifier = Modifier.weight(1f))
                            }
                        }
                        "THYROID" -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = primaryInput, onValueChange = { primaryInput = it }, label = { Text("TSH") }, modifier = Modifier.weight(1f))
                                OutlinedTextField(value = secondaryInput, onValueChange = { secondaryInput = it }, label = { Text("T3") }, modifier = Modifier.weight(1f))
                                OutlinedTextField(value = thirdInput, onValueChange = { thirdInput = it }, label = { Text("T4") }, modifier = Modifier.weight(1f))
                            }
                        }
                        "LIVER_KIDNEY" -> {
                            OutlinedTextField(
                                value = primaryInput,
                                onValueChange = { primaryInput = it },
                                label = { Text("وظائف الكبد: ALT / AST / ALP / Bilirubin") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = secondaryInput,
                                onValueChange = { secondaryInput = it },
                                label = { Text("وظائف الكلى: Creatinine / eGFR / Urea") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        else -> {
                            OutlinedTextField(
                                value = primaryInput,
                                onValueChange = { primaryInput = it },
                                label = { Text("القيمة الفعلية (${selectedSection.unit})") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    OutlinedTextField(
                        value = sourceInput,
                        onValueChange = { sourceInput = it },
                        label = { Text("المصدر الفعلي للقياس") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = {
                            scope.launch {
                                val subMetrics = JSONObject()
                                val measured: String = when (selectedSection.id) {
                                    "BP" -> {
                                        val sys = primaryInput.trim()
                                        val dia = secondaryInput.trim()
                                        if (sys.isBlank() || dia.isBlank()) "" else {
                                            subMetrics.put("systolic", sys)
                                            subMetrics.put("diastolic", dia)
                                            "$sys/$dia"
                                        }
                                    }
                                    "BMI" -> {
                                        val w = primaryInput.toFloatOrNull()
                                        val h = secondaryInput.toFloatOrNull()
                                        val bmi = SabqHealthAssessmentEngine.calculateRealBmi(h, w)
                                        if (bmi == null) "" else {
                                            subMetrics.put("weightKg", w)
                                            subMetrics.put("heightCm", h)
                                            String.format(Locale.US, "%.1f", bmi)
                                        }
                                    }
                                    "CBC" -> {
                                        if (primaryInput.isBlank()) "" else {
                                            subMetrics.put("hb", primaryInput.trim())
                                            if (secondaryInput.isNotBlank()) subMetrics.put("rbc", secondaryInput.trim())
                                            if (thirdInput.isNotBlank()) subMetrics.put("wbc", thirdInput.trim())
                                            if (fourthInput.isNotBlank()) subMetrics.put("platelets", fourthInput.trim())
                                            "Hb ${primaryInput.trim()} g/dL"
                                        }
                                    }
                                    "THYROID" -> {
                                        if (primaryInput.isBlank()) "" else {
                                            subMetrics.put("tsh", primaryInput.trim())
                                            if (secondaryInput.isNotBlank()) subMetrics.put("t3", secondaryInput.trim())
                                            if (thirdInput.isNotBlank()) subMetrics.put("t4", thirdInput.trim())
                                            "TSH ${primaryInput.trim()}"
                                        }
                                    }
                                    "LIVER_KIDNEY" -> {
                                        if (primaryInput.isBlank() && secondaryInput.isBlank()) "" else {
                                            if (primaryInput.isNotBlank()) subMetrics.put("liver", primaryInput.trim())
                                            if (secondaryInput.isNotBlank()) subMetrics.put("kidney", secondaryInput.trim())
                                            "كبد: ${primaryInput.ifBlank { "-" }} | كلى: ${secondaryInput.ifBlank { "-" }}"
                                        }
                                    }
                                    else -> primaryInput.trim()
                                }

                                if (measured.isNotBlank() && sourceInput.isNotBlank()) {
                                    dao.insertLabReading(
                                        LabReadingEntity(
                                            id = UUID.randomUUID().toString(),
                                            userId = userId,
                                            sectionId = selectedSection.id,
                                            testCode = selectedSection.id,
                                            testNameAr = selectedSection.nameAr,
                                            measuredValue = measured,
                                            unit = selectedSection.unit,
                                            subMetricsJson = subMetrics.toString(),
                                            source = sourceInput.trim(),
                                            explanationAr = "قراءة موثقة مسجلة في قسم ${selectedSection.nameAr}"
                                        )
                                    )
                                    primaryInput = ""
                                    secondaryInput = ""
                                    thirdInput = ""
                                    fourthInput = ""
                                    statusMessage = "تم حفظ القراءة الفعلية بنجاح في سجل المستخدم المعزول."
                                } else {
                                    statusMessage = "يرجى إدخال قيمة صالحة ومصدر فعلي قبل الحفظ."
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("حفظ القراءة الموثقة")
                    }

                    if (statusMessage != null) {
                        Text(statusMessage!!, fontSize = 11.sp, color = SabqColors.Emerald)
                    }
                }
            }
        }

        items(HEALTH_CENTER_SECTIONS) { section ->
            val sectionReadings = readings.filter { it.sectionId == section.id || it.testCode == section.id }
            val latest = sectionReadings.firstOrNull()
            val previous = sectionReadings.getOrNull(1)
            Surface(
                onClick = {
                    selectedSection = section
                    statusMessage = null
                },
                color = SabqColors.Surface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, if (selectedSection.id == section.id) SabqColors.Cyan else SabqColors.Border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(section.nameAr, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                        Text(section.badgeAr, color = SabqColors.Cyan, fontSize = 10.sp)
                    }
                    if (latest != null) {
                        Text("الأحدث: ${latest.measuredValue} ${latest.unit}", color = SabqColors.Emerald, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        if (previous != null) {
                            Text("السابق للمقارنة: ${previous.measuredValue} ${previous.unit}", color = SabqColors.TextMuted, fontSize = 11.sp)
                        }
                        Text("المصدر: ${latest.source}", color = SabqColors.TextMuted, fontSize = 11.sp)
                    } else {
                        Text("لا توجد قراءة مسجلة — لم يتم توليد أي رقم افتراضي", color = SabqColors.TextMuted, fontSize = 11.sp)
                    }
                }
            }
        }

        item {
            Text(
                "محرك SABQ SMART RISK ENGINE الحتمي",
                fontWeight = FontWeight.Bold,
                color = SabqColors.Violet,
                fontSize = 15.sp
            )
        }

        items(deterministicRisks) { risk ->
            Surface(
                color = SabqColors.Surface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, SabqColors.Violet.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${risk.titleAr} — ${risk.riskLevelAr}", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                    Text("التصنيف: ${risk.resultClassification}", color = SabqColors.Cyan, fontSize = 11.sp)
                    Text("البيانات المستخدمة: ${risk.usedData.ifEmpty { listOf("لا توجد بيانات مدخلة بعد") }.joinToString("، ")}", color = SabqColors.TextMuted, fontSize = 11.sp)
                    if (risk.missingInformation.isNotEmpty()) {
                        Text("البيانات الناقصة: ${risk.missingInformation.joinToString("، ")}", color = SabqColors.Warning, fontSize = 11.sp)
                    }
                    Text("الخطوة التالية: ${risk.nextStepAr}", color = Color.White, fontSize = 11.sp)
                }
            }
        }
    }
}
