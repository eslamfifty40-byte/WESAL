import type {
  HealthSectionId,
  LabReading,
  RiskAssessmentResult,
  VoluntaryHealthIntake,
} from '../types/sabq';

/**
 * SABQ SMART RISK ENGINE
 * Purely deterministic clinical rules engine:
 * - Never calls an LLM to invent lab numbers.
 * - Never generates random risk scores or random confidence values.
 * - Explicitly distinguishes between 'تقييم خطورة غير تشخيصي' and 'قياس فعلي موثق'.
 */

export function calculateRealBmi(heightCm?: number, weightKg?: number): number | null {
  if (!heightCm || !weightKg) return null;
  if (!Number.isFinite(heightCm) || !Number.isFinite(weightKg)) return null;
  if (heightCm < 80 || heightCm > 250 || weightKg < 15 || weightKg > 350) return null;
  const hMeters = heightCm / 100;
  return Number((weightKg / (hMeters * hMeters)).toFixed(1));
}

export function classifyBmiAr(bmi: number | null): string {
  if (bmi === null) return 'غير محسوب';
  if (bmi < 18.5) return 'نقص في الوزن';
  if (bmi < 25) return 'وزن طبيعي';
  if (bmi < 30) return 'زيادة في الوزن';
  return 'سمنة';
}

export function runSabqSmartRiskEngine(params: {
  userId: string;
  intake: VoluntaryHealthIntake;
  heightCm?: number;
  weightKg?: number;
  labs: LabReading[];
}): RiskAssessmentResult[] {
  const { userId, intake, heightCm, weightKg, labs } = params;
  const nowIso = new Date().toISOString();
  const bmi = calculateRealBmi(heightCm, weightKg);

  const age = intake.age;
  const symptoms = intake.symptoms || [];
  const family = intake.familyHistory || [];
  const personal = intake.personalHistory || [];
  const activity = intake.dailyActivityLevel;
  const sleepHours = intake.sleepHours;

  const hasLab = (code: string) => labs.find((l) => l.testCode === code);

  const commonIntakeCount =
    (age ? 1 : 0) +
    (bmi !== null ? 1 : 0) +
    (symptoms.length > 0 ? 1 : 0) +
    (family.length > 0 ? 1 : 0) +
    (personal.length > 0 ? 1 : 0) +
    (activity ? 1 : 0) +
    (sleepHours ? 1 : 0);

  const buildResult = (
    cfg: Omit<RiskAssessmentResult, 'id' | 'userId' | 'source' | 'timestamp'>
  ): RiskAssessmentResult => ({
    id: `risk_${cfg.category}_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`,
    userId,
    source: 'SABQ SMART RISK ENGINE (قواعد طبية حتمية من مدخلات المستخدم والتحاليل الموثقة)',
    timestamp: nowIso,
    ...cfg,
  });

  // 1. Diabetes & HbA1c Risk
  const glucoseLab = hasLab('GLUCOSE');
  const hba1cLab = hasLab('HBA1C');
  const diabetesUsed: string[] = [];
  const diabetesElevating: string[] = [];
  const diabetesMissing: string[] = [];

  if (age) {
    diabetesUsed.push(`العمر: ${age} عامًا`);
    if (age >= 45) diabetesElevating.push('العمر 45 عامًا فأكثر');
  } else diabetesMissing.push('العمر');

  if (bmi !== null) {
    diabetesUsed.push(`مؤشر كتلة الجسم (BMI): ${bmi}`);
    if (bmi >= 25) diabetesElevating.push(`ارتفاع مؤشر كتلة الجسم (${bmi} — ${classifyBmiAr(bmi)})`);
  } else diabetesMissing.push('الطول والوزن لحساب BMI');

  if (family.some((f) => f.includes('السكري'))) {
    diabetesUsed.push('التاريخ العائلي للسكري');
    diabetesElevating.push('وجود تاريخ عائلي للإصابة بالسكري');
  }
  if (activity) {
    diabetesUsed.push(`النشاط البدني: ${activity === 'low' ? 'منخفض' : activity === 'moderate' ? 'متوسط' : 'نشط'}`);
    if (activity === 'low') diabetesElevating.push('انخفاض النشاط البدني اليومي');
  } else diabetesMissing.push('مستوى النشاط اليومي');

  if (symptoms.some((s) => s.includes('عطش') || s.includes('تبول'))) {
    diabetesUsed.push('أعراض عطش/تبول متكرر');
    diabetesElevating.push('الإبلاغ عن عطش شديد أو تبول متكرر');
  }
  if (glucoseLab) {
    diabetesUsed.push(`سكر الدم الموثق: ${glucoseLab.measuredValue} ${glucoseLab.unit}`);
    const gNum = Number(glucoseLab.measuredValue);
    if (Number.isFinite(gNum) && gNum >= 126) diabetesElevating.push(`قراءة سكر دم مرتفعة (${gNum} mg/dL)`);
    else if (Number.isFinite(gNum) && gNum >= 100) diabetesElevating.push(`قراءة سكر دم في نطاق ما قبل السكري للصائم (${gNum} mg/dL)`);
  } else diabetesMissing.push('تحليل سكر الدم الصائم (FBS)');
  if (hba1cLab) {
    diabetesUsed.push(`التراكمي الموثق: ${hba1cLab.measuredValue}%`);
    const a1cNum = Number(hba1cLab.measuredValue);
    if (Number.isFinite(a1cNum) && a1cNum >= 6.5) diabetesElevating.push(`نسبة HbA1c مرتفعة (${a1cNum}%)`);
    else if (Number.isFinite(a1cNum) && a1cNum >= 5.7) diabetesElevating.push(`نسبة HbA1c ضمن ما قبل السكري (${a1cNum}%)`);
  } else diabetesMissing.push('تحليل السكر التراكمي (HbA1c)');

  const dScore = diabetesElevating.length;
  const diabetesLevel: RiskAssessmentResult['riskLevelAr'] =
    commonIntakeCount === 0 && !glucoseLab && !hba1cLab
      ? 'بيانات غير كافية'
      : dScore >= 3
      ? 'مرتفع'
      : dScore >= 1
      ? 'متوسط'
      : 'منخفض';

  // Separate HbA1c independent evaluation (never converts risk into fake HbA1c %)
  const hba1cValNum = hba1cLab ? Number(hba1cLab.measuredValue) : NaN;
  const hba1cLevel: RiskAssessmentResult['riskLevelAr'] = hba1cLab
    ? Number.isFinite(hba1cValNum) && hba1cValNum >= 6.5
      ? 'مرتفع'
      : Number.isFinite(hba1cValNum) && hba1cValNum >= 5.7
      ? 'متوسط'
      : 'منخفض'
    : commonIntakeCount === 0 && !glucoseLab
    ? 'بيانات غير كافية'
    : diabetesLevel;

  // 2. Blood Pressure Risk
  const bpLab = hasLab('BP');
  const bpUsed: string[] = [];
  const bpElevating: string[] = [];
  const bpMissing: string[] = [];

  if (age) {
    bpUsed.push(`العمر: ${age} عامًا`);
    if (age >= 40) bpElevating.push('العمر 40 عامًا فأكثر');
  } else bpMissing.push('العمر');

  if (bmi !== null) {
    bpUsed.push(`BMI: ${bmi}`);
    if (bmi >= 25) bpElevating.push(`زيادة الوزن (${bmi})`);
  } else bpMissing.push('الطول والوزن');

  if (family.some((f) => f.includes('ضغط'))) {
    bpUsed.push('التاريخ العائلي لارتفاع الضغط');
    bpElevating.push('تاريخ عائلي لارتفاع ضغط الدم');
  }
  if (sleepHours !== undefined) {
    bpUsed.push(`ساعات النوم: ${sleepHours}`);
    if (sleepHours < 6) bpElevating.push(`قلة النوم (${sleepHours} ساعات)`);
  } else bpMissing.push('متوسط ساعات النوم');

  if (symptoms.some((s) => s.includes('صداع') || s.includes('دوخة'))) {
    bpUsed.push('أعراض صداع/دوخة');
    bpElevating.push('الإبلاغ عن صداع متكرر أو دوخة');
  }
  if (bpLab) {
    bpUsed.push(`قياس ضغط الدم الفعلي: ${bpLab.measuredValue} ${bpLab.unit}`);
    const sysNum = Number(bpLab.subMetrics?.systolic);
    const diaNum = Number(bpLab.subMetrics?.diastolic);
    if (Number.isFinite(sysNum) && Number.isFinite(diaNum) && (sysNum >= 130 || diaNum >= 80)) {
      bpElevating.push(`قياس ضغط دم مرتفع (${sysNum}/${diaNum} mmHg)`);
    }
  } else bpMissing.push('قياس ضغط الدم بجهاز الكف الطبي المعتمد');

  const bpLevel: RiskAssessmentResult['riskLevelAr'] =
    commonIntakeCount === 0 && !bpLab
      ? 'بيانات غير كافية'
      : bpElevating.length >= 3
      ? 'مرتفع'
      : bpElevating.length >= 1
      ? 'متوسط'
      : 'منخفض';

  // 3. Anemia / CBC Risk
  const cbcLab = hasLab('CBC');
  const cbcUsed: string[] = [];
  const cbcElevating: string[] = [];
  const cbcMissing: string[] = [];

  if (intake.gender) cbcUsed.push(`الجنس: ${intake.gender === 'female' ? 'أنثى' : 'ذكر'}`);
  else cbcMissing.push('الجنس');

  if (personal.some((p) => p.includes('أنيميا') || p.includes('حديد'))) {
    cbcUsed.push('تاريخ شخصي سابق للأنيميا');
    cbcElevating.push('تاريخ سابق لنقص الحديد أو الأنيميا');
  }
  if (symptoms.some((s) => s.includes('إرهاق') || s.includes('شحوب') || s.includes('تساقط'))) {
    cbcUsed.push('أعراض إرهاق أو شحوب أو تساقط شعر');
    cbcElevating.push('وجود إرهاق مستمر أو شحوب أو تساقط شعر');
  }
  if (cbcLab) {
    cbcUsed.push(`نتيجة CBC الموثقة: ${cbcLab.measuredValue}`);
    const hbNum = Number(cbcLab.subMetrics?.hb);
    if (Number.isFinite(hbNum) && hbNum < 12) {
      cbcElevating.push(`انخفاض الهيموجلوبين المسجل (Hb: ${hbNum} g/dL)`);
    }
  } else cbcMissing.push('تحليل صورة الدم الكاملة (CBC) ومخزون الحديد (Ferritin)');

  const cbcLevel: RiskAssessmentResult['riskLevelAr'] =
    cbcUsed.length === 0
      ? 'بيانات غير كافية'
      : cbcElevating.length >= 2
      ? 'مرتفع'
      : cbcElevating.length === 1
      ? 'متوسط'
      : 'منخفض';

  // 4. Thyroid Risk
  const thyroidLab = hasLab('THYROID');
  const thUsed: string[] = [];
  const thElevating: string[] = [];
  const thMissing: string[] = [];

  if (family.some((f) => f.includes('الغدة'))) {
    thUsed.push('تاريخ عائلي للغدة الدرقية');
    thElevating.push('تاريخ عائلي لاضطرابات الغدة الدرقية');
  }
  if (symptoms.some((s) => s.includes('وزن') || s.includes('برد') || s.includes('إرهاق'))) {
    thUsed.push('أعراض مرتبطة بالأيض أو التعب');
    thElevating.push('تقلبات وزن أو حساسية حرارة/برودة أو خمول');
  }
  if (thyroidLab) {
    thUsed.push(`TSH الموثق: ${thyroidLab.measuredValue} ${thyroidLab.unit}`);
    const tshNum = Number(thyroidLab.subMetrics?.tsh);
    if (Number.isFinite(tshNum) && (tshNum < 0.4 || tshNum > 4.0)) {
      thElevating.push(`قيمة TSH خارج النطاق المرجعي الشائع (${tshNum} mIU/L)`);
    }
  } else thMissing.push('تحليل TSH وT3 وT4 المخبري');

  const thLevel: RiskAssessmentResult['riskLevelAr'] =
    thUsed.length === 0
      ? 'بيانات غير كافية'
      : thElevating.length >= 2
      ? 'مرتفع'
      : thElevating.length === 1
      ? 'متوسط'
      : 'منخفض';

  // 5. Heart Risk
  const pulseLab = hasLab('PULSE');
  const heartUsed: string[] = [];
  const heartElevating: string[] = [];
  const heartMissing: string[] = [];

  if (age) {
    heartUsed.push(`العمر: ${age}`);
    if (age >= 45) heartElevating.push('العمر 45 عامًا فأكثر');
  } else heartMissing.push('العمر');

  if (bmi !== null) {
    heartUsed.push(`BMI: ${bmi}`);
    if (bmi >= 30) heartElevating.push(`مؤشر كتلة الجسم المرتفع (${bmi})`);
  }
  if (family.some((f) => f.includes('القلب'))) {
    heartUsed.push('تاريخ عائلي لأمراض القلب');
    heartElevating.push('تاريخ عائلي لأمراض القلب والشرايين');
  }
  if (symptoms.some((s) => s.includes('خفقان') || s.includes('ضيق'))) {
    heartUsed.push('أعراض خفقان أو ضيق نفس');
    heartElevating.push('الإبلاغ عن خفقان أو ضيق تنفس بالمجهود');
  }
  if (pulseLab) {
    heartUsed.push(`النبض المسجل: ${pulseLab.measuredValue} bpm`);
    const pNum = Number(pulseLab.measuredValue);
    if (Number.isFinite(pNum) && (pNum > 100 || pNum < 50)) {
      heartElevating.push(`نبض خارج نطاق الراحة المعتاد (${pNum} bpm)`);
    }
  } else heartMissing.push('تحليل الدهون (Lipid Profile) وتخطيط قلب عند الأعراض');

  const heartLevel: RiskAssessmentResult['riskLevelAr'] =
    commonIntakeCount === 0 && !pulseLab
      ? 'بيانات غير كافية'
      : heartElevating.length >= 3
      ? 'مرتفع'
      : heartElevating.length >= 1
      ? 'متوسط'
      : 'منخفض';

  // 6. Liver & Kidney Risk
  const liverLab = hasLab('LIVER');
  const kidneyLab = hasLab('KIDNEY');
  const lkUsed: string[] = [];
  const lkElevating: string[] = [];
  const lkMissing: string[] = [];
  if (bmi !== null) {
    lkUsed.push(`BMI: ${bmi}`);
    if (bmi >= 30) lkElevating.push('ارتفاع مؤشر كتلة الجسم (عامل خطورة للكبد الدهني)');
  }
  if (liverLab) {
    lkUsed.push(`وظائف الكبد الموثقة: ${liverLab.measuredValue}`);
    const altN = Number(liverLab.subMetrics?.alt);
    const astN = Number(liverLab.subMetrics?.ast);
    const bilN = Number(liverLab.subMetrics?.bilirubin);
    if ((Number.isFinite(altN) && altN > 45) || (Number.isFinite(astN) && astN > 40) || (Number.isFinite(bilN) && bilN > 1.2)) {
      lkElevating.push('ارتفاع في إنزيمات الكبد أو البيليروبين عن النطاق الشائع');
    }
  } else lkMissing.push('تحاليل الكبد (ALT, AST, ALP, Bilirubin)');
  if (kidneyLab) {
    lkUsed.push(`وظائف الكلى الموثقة: ${kidneyLab.measuredValue}`);
    const crN = Number(kidneyLab.subMetrics?.creatinine);
    const egfrN = Number(kidneyLab.subMetrics?.egfr);
    if ((Number.isFinite(crN) && crN > 1.3) || (Number.isFinite(egfrN) && egfrN < 60)) {
      lkElevating.push('مؤشر كلى يستدعي المتابعة الطبية (Creatinine/eGFR)');
    }
  } else lkMissing.push('تحاليل الكلى (Creatinine, eGFR, Urea)');

  const lkLevel: RiskAssessmentResult['riskLevelAr'] =
    lkUsed.length === 0 ? 'بيانات غير كافية' : lkElevating.length >= 2 ? 'مرتفع' : lkElevating.length > 0 ? 'متوسط' : 'منخفض';

  return [
    buildResult({
      category: 'DIABETES_RISK',
      sectionId: 'GLUCOSE' as HealthSectionId,
      titleAr: 'تقييم عوامل خطورة سكر الدم',
      riskLevelAr: diabetesLevel,
      resultClassification: glucoseLab ? 'قياس فعلي موثق' : 'تقييم خطورة غير تشخيصي',
      usedData: diabetesUsed,
      elevatingFactors: diabetesElevating,
      missingInformation: diabetesMissing,
      summary:
        diabetesLevel === 'بيانات غير كافية'
          ? 'لا توجد مدخلات كافية لتقييم عوامل خطورة سكر الدم بعد.'
          : `مستوى عوامل الخطورة المحسوب حتميًا: ${diabetesLevel} بناءً على ${diabetesUsed.length} مدخلًا فعليًا دون اختلاق أي رقم سكر من الوجه أو البصمة.`,
      nextStepAr: 'إجراء تحليل سكر صائم (FBS) في مختبر معتمد أو جهاز قياس سكر الدم وتسجيل القيمة هنا.',
      recommendations: [
        'الاعتماد على وجبات متوازنة منخفضة السكريات البسيطة.',
        'المشي المنتظم 150 دقيقة أسبوعيًا عند القدرة.',
      ],
      doctorConsultationCriteria: 'راجع الطبيب فورًا عند العطش الشديد، التبول المتكرر، أو قراءة سكر مرتفعة.',
    }),
    buildResult({
      category: 'HBA1C_RISK',
      sectionId: 'HBA1C' as HealthSectionId,
      titleAr: 'قسم السكر التراكمي (HbA1c)',
      riskLevelAr: hba1cLevel,
      resultClassification: hba1cLab ? 'قياس فعلي موثق' : 'تقييم خطورة غير تشخيصي',
      usedData: hba1cLab
        ? [`نتيجة HbA1c المخبرية الموثقة: ${hba1cLab.measuredValue}% (${hba1cLab.source})`]
        : diabetesUsed,
      elevatingFactors: diabetesElevating,
      missingInformation: hba1cLab ? [] : ['نتيجة تحليل السكر التراكمي HbA1c المخبري الفعلي'],
      summary: hba1cLab
        ? `نسبة HbA1c الفعلية المسجلة: ${hba1cLab.measuredValue}% وتقييمها: ${hba1cLevel}.`
        : `لا يوجد تحليل HbA1c مخبري مسجل حتى الآن — يُعرض تقييم خطورة السكري فقط (${hba1cLevel}) دون تحويله أبدًا إلى نسبة HbA1c وهمية.`,
      nextStepAr: 'إجراء تحليل HbA1c في المختبر الطبي وتسجيل القيمة وتاريخها ومصدرها.',
      recommendations: [
        'لا يُقدر السكر التراكمي بالكاميرا أو البصمة؛ يتطلب عينة دم مخبرية.',
        'متابعة التغذية والنشاط البدني والمراجعة الدورية مع الطبيب.',
      ],
      doctorConsultationCriteria: 'راجع الطبيب عند HbA1c ≥ 5.7% أو ظهور أعراض ارتفاع السكر.',
    }),
    buildResult({
      category: 'BP_RISK',
      sectionId: 'BP',
      titleAr: 'تقييم عوامل خطورة ضغط الدم',
      riskLevelAr: bpLevel,
      resultClassification: bpLab ? 'قياس فعلي موثق' : 'تقييم خطورة غير تشخيصي',
      usedData: bpUsed,
      elevatingFactors: bpElevating,
      missingInformation: bpMissing,
      summary:
        bpLevel === 'بيانات غير كافية'
          ? 'أدخل العمر أو الطول والوزن أو قراءة جهاز ضغط معتمد لتقييم الخطورة.'
          : `مستوى الخطورة التقديري لضغط الدم: ${bpLevel} (لا يُقاس الضغط بالكاميرا أو البصمة نهائيًا).`,
      nextStepAr: 'قياس الضغط بجهاز الكف المعاير وتسجيل الانقباضي والانبساطي في قسم ضغط الدم.',
      recommendations: [
        'تقليل الملح والأطعمة المصنعة.',
        'الانتظام في النوم 7–8 ساعات يوميًا.',
      ],
      doctorConsultationCriteria: 'راجع الطبيب عند قراءة >= 140/90 أو صداع شديد مفاجئ مع دوخة.',
    }),
    buildResult({
      category: 'ANEMIA_CBC_RISK',
      sectionId: 'CBC',
      titleAr: 'تقييم عوامل خطورة الأنيميا وصورة الدم (CBC)',
      riskLevelAr: cbcLevel,
      resultClassification: cbcLab ? 'قياس فعلي موثق' : 'تقييم خطورة غير تشخيصي',
      usedData: cbcUsed,
      elevatingFactors: cbcElevating,
      missingInformation: cbcMissing,
      summary: `تقييم عوامل الأنيميا: ${cbcLevel} — ولا يُستنتج الهيموجلوبين من لون الوجه أو الشفاه مطلقًا.`,
      nextStepAr: 'إجراء تحليل صورة دم كاملة (CBC) وإدخال قيم Hb وRBC وWBC والصفائح في قسم CBC.',
      recommendations: [
        'لا تتناول مكملات حديد دون تحليل دم مؤكد واستشارة طبية.',
      ],
      doctorConsultationCriteria: 'راجع الطبيب عند استمرار الإرهاق أو الدوخة أو تساقط الشعر.',
    }),
    buildResult({
      category: 'THYROID_RISK',
      sectionId: 'THYROID',
      titleAr: 'تقييم عوامل خطورة الغدة الدرقية',
      riskLevelAr: thLevel,
      resultClassification: thyroidLab ? 'قياس فعلي موثق' : 'تقييم خطورة غير تشخيصي',
      usedData: thUsed,
      elevatingFactors: thElevating,
      missingInformation: thMissing,
      summary: `تقييم عوامل خطورة الغدة الدرقية: ${thLevel} بناءً على الأعراض والتاريخ المصرح به والنتائج المسجلة فقط.`,
      nextStepAr: 'فحص TSH وT3 وT4 في مختبر طبي وإدخال القيم في قسم الغدة الدرقية.',
      recommendations: ['مراقبة انتظام النوم والوزن ومناقشة الأعراض مع طبيب الباطنة/الغدد.'],
      doctorConsultationCriteria: 'تجب مراجعة الطبيب عند تغيرات الوزن المفاجئة أو خفقان القلب.',
    }),
    buildResult({
      category: 'HEART_RISK',
      sectionId: 'PULSE',
      titleAr: 'تقييم عوامل الخطورة القلبية',
      riskLevelAr: heartLevel,
      resultClassification: pulseLab ? 'قياس فعلي موثق' : 'تقييم خطورة غير تشخيصي',
      usedData: heartUsed,
      elevatingFactors: heartElevating,
      missingInformation: heartMissing,
      summary: `تقييم عوامل الخطورة القلبية: ${heartLevel} (كاميرا الهاتف لا تجري تخطيط قلب ECG).`,
      nextStepAr: 'متابعة النبض وملف الدهون وممارسة نشاط هوائي معتدل.',
      recommendations: ['تجنب التدخين والدهون المتحولة، والمتابعة الدورية لضغط الدم والدهون.'],
      doctorConsultationCriteria: 'اطلب الرعاية الطارئة فورًا عند ألم الصدر أو ضيق النفس الحاد.',
    }),
    buildResult({
      category: 'LIVER_KIDNEY_RISK',
      sectionId: 'LIVER_KIDNEY',
      titleAr: 'تقييم عوامل وظائف الكبد والكلى',
      riskLevelAr: lkLevel,
      resultClassification: liverLab || kidneyLab ? 'قياس فعلي موثق' : 'تقييم خطورة غير تشخيصي',
      usedData: lkUsed,
      elevatingFactors: lkElevating,
      missingInformation: lkMissing,
      summary: `الحالة: ${lkLevel}. يتطلب تقييم الكبد والكلى الفعلي تحاليل مخبرية (ALT/AST/Creatinine/eGFR).`,
      nextStepAr: 'تسجيل تحاليل الكبد والكلى المخبرية الدورية في قسم وظائف الكبد والكلى.',
      recommendations: ['شرب كميات كافية من الماء وتجنب المسكنات العشوائية دون وصفة.'],
      doctorConsultationCriteria: 'راجع الطبيب فورًا عند اصفرار العينين أو تورم الساقين أو تغير لون البول.',
    }),
  ];
}
