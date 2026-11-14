import React, { useEffect, useState } from 'react';
import {
  Activity,
  AlertCircle,
  Camera,
  CheckCircle2,
  Cpu,
  FileDown,
  Fingerprint,
  HeartPulse,
  Plus,
  Scale,
  ShieldAlert,
  Trash2,
} from 'lucide-react';
import {
  HEALTH_CENTER_SECTIONS,
  HealthScanRecord,
  HealthSectionId,
  LabReading,
  ReportCategoryType,
  ReportRecord,
  RiskAssessmentResult,
  UploadedImageRecord,
  UserProfile,
} from '../types/sabq';
import { DeviceHardwareCapabilities, inspectDeviceCapabilities } from '../utils/ppgProcessor';
import { calculateRealBmi, classifyBmiAr, runSabqSmartRiskEngine } from '../utils/sabqRiskEngine';
import { generateSabqArabicPdfBlob } from '../utils/arabicPdfGenerator';
import { RealImageUploadPanel } from './RealImageUploadPanel';

interface LabsAndBioFingerScreenProps {
  profile: UserProfile;
  labs: LabReading[];
  scans: HealthScanRecord[];
  reports: ReportRecord[];
  initialLabCode?: string;
  onAddLabReading: (reading: LabReading) => void;
  onDeleteLabReading: (id: string) => void;
  onSaveScanImageAndReport: (
    uploaded: UploadedImageRecord | null,
    scans: HealthScanRecord[],
    reports: ReportRecord[],
    updatedProfile?: Partial<UserProfile>
  ) => void;
  onGoToPpgScan: () => void;
  onGoToGuidedCamera: (mode: string) => void;
}

const SECTION_DEFAULT_UNIT: Record<HealthSectionId, string> = {
  GLUCOSE: 'mg/dL',
  BP: 'mmHg',
  HBA1C: '%',
  CBC: 'مركب',
  THYROID: 'مركب',
  PULSE: 'bpm',
  RESPIRATORY_SPO2: '% / rpm',
  BMI: 'kg/m²',
  BODY_COMPOSITION: '% / kg',
  LIVER_KIDNEY: 'مركب',
  FACE_EYE_SKIN: 'تقدير بصري',
  HAIR_SCALP: 'تقدير بصري',
};

function mapInitialCodeToSection(code?: string): HealthSectionId {
  if (!code) return 'GLUCOSE';
  const upper = code.toUpperCase();
  if (HEALTH_CENTER_SECTIONS.some((s) => s.id === upper)) return upper as HealthSectionId;
  if (upper === 'SPO2' || upper === 'RESPIRATORY') return 'RESPIRATORY_SPO2';
  if (upper === 'HB') return 'CBC';
  if (upper === 'LIVER' || upper === 'KIDNEY') return 'LIVER_KIDNEY';
  if (upper === 'LIPIDS') return 'BODY_COMPOSITION';
  if (upper === 'SKIN' || upper === 'FACE' || upper === 'EYE') return 'FACE_EYE_SKIN';
  if (upper === 'HAIR') return 'HAIR_SCALP';
  if (upper === 'BODY') return 'BMI';
  return 'GLUCOSE';
}

function parseStrictNumber(raw: string): number | null {
  const trimmed = raw.trim();
  if (!trimmed) return null;
  const n = Number(trimmed);
  return Number.isFinite(n) ? n : null;
}

function validateNumericRange(
  raw: string,
  min: number,
  max: number,
  labelAr: string
): { value?: number; error?: string } {
  if (!raw.trim()) return {};
  const n = Number(raw.trim());
  if (!Number.isFinite(n) || n < min || n > max) {
    return { error: `قيمة ${labelAr} غير منطقية؛ يرجى إدخال رقم بين ${min} و${max}.` };
  }
  return { value: n };
}

function explainLabValueAr(
  sectionId: HealthSectionId,
  val: string,
  subs: Record<string, string>,
  testCode?: string
): string {
  switch (sectionId) {
    case 'GLUCOSE': {
      const n = Number(val);
      if (!Number.isFinite(n)) return 'قراءة سكر مسجلة للمتابعة الدورية.';
      if (n < 70) return 'قراءة منخفضة (< 70 mg/dL) — راقب أعراض الهبوط واستشر الطبيب فورًا عند الدوخة أو التعرق.';
      if (n <= 99) return 'ضمن النطاق الطبيعي الصائم المرجعي (70–99 mg/dL).';
      if (n <= 125) return 'نطاق ما قبل السكري المرجعي عند الصيام (100–125 mg/dL)، أو طبيعي بعد الأكل مباشرة؛ يفضل مقارنة الصيام.';
      return 'قراءة مرتفعة (≥ 126 mg/dL للصائم أو مرتفعة نسبيًا) — يلزم التقييم الطبي والتحليل التأكيدي.';
    }
    case 'HBA1C': {
      const n = Number(val);
      if (!Number.isFinite(n)) return 'نتيجة السكر التراكمي HbA1c محفوظة.';
      if (n < 5.7) return 'ضمن النطاق الطبيعي (< 5.7%)؛ يعكس متوسط سكر الدم خلال آخر 3 أشهر.';
      if (n < 6.5) return 'ضمن نطاق ما قبل السكري (5.7%–6.4%) — المتابعة الغذائية والحركية ومراجعة الطبيب مهمة.';
      return 'نسبة 6.5% فأعلى تستدعي مراجعة الطبيب لتقييم الخطة العلاجية وتأكيد التشخيص.';
    }
    case 'BP': {
      const sys = Number(subs.systolic);
      const dia = Number(subs.diastolic);
      if (!Number.isFinite(sys) || !Number.isFinite(dia)) return 'قياس ضغط دم موثق.';
      if (sys >= 140 || dia >= 90) return `قراءة مرتفعة (${sys}/${dia} mmHg) — أعد القياس بعد راحة 5 دقائق وراجع الطبيب.`;
      if (sys >= 130 || dia >= 80) return `ضغط مرتفع نسبيًا (${sys}/${dia} mmHg) — ينصح بتقليل الملح والمتابعة الدورية.`;
      if (sys >= 120 && dia < 80) return `ضغط مائل للارتفاع (${sys}/${dia} mmHg) — حافظ على نمط حياة متوازن.`;
      return `ضمن النطاق الطبيعي (${sys}/${dia} mmHg).`;
    }
    case 'CBC': {
      const notes: string[] = [];
      const hb = parseStrictNumber(subs.hb || '');
      const rbc = parseStrictNumber(subs.rbc || '');
      const wbc = parseStrictNumber(subs.wbc || '');
      const plt = parseStrictNumber(subs.platelets || '');
      if (hb !== null) {
        if (hb < 12) notes.push(`الهيموجلوبين Hb (${hb} g/dL) أقل من النطاق الشائع — قد يشير لمؤشر أنيميا يستدعي مراجعة الطبيب.`);
        else if (hb > 17.5) notes.push(`الهيموجلوبين Hb (${hb} g/dL) أعلى من المعتاد — يفضل مراجعة الطبيب.`);
        else notes.push(`الهيموجلوبين Hb (${hb} g/dL) ضمن النطاق الشائع (12–17.5).`);
      }
      if (rbc !== null) {
        notes.push(
          rbc < 4.0 || rbc > 6.1
            ? `كريات الدم الحمراء RBC (${rbc} M/uL) خارج النطاق المعتاد (4.0–6.1).`
            : `كريات الدم الحمراء RBC (${rbc} M/uL) ضمن النطاق المعتاد.`
        );
      }
      if (wbc !== null) {
        notes.push(
          wbc < 4.0 || wbc > 11.0
            ? `كريات الدم البيضاء WBC (${wbc} K/uL) خارج النطاق الشائع (4.0–11.0) — تعكس الاستجابة المناعية.`
            : `كريات الدم البيضاء WBC (${wbc} K/uL) ضمن النطاق الشائع.`
        );
      }
      if (plt !== null) {
        notes.push(
          plt < 150 || plt > 450
            ? `الصفائح الدموية Platelets (${plt} K/uL) خارج النطاق المعتاد (150–450).`
            : `الصفائح الدموية Platelets (${plt} K/uL) ضمن النطاق الشائع.`
        );
      }
      return notes.join(' ') || 'نتائج صورة الدم الكاملة مسجلة للمقارنة الزمنية.';
    }
    case 'THYROID': {
      const notes: string[] = [];
      const tsh = parseStrictNumber(subs.tsh || '');
      const t3 = parseStrictNumber(subs.t3 || '');
      const t4 = parseStrictNumber(subs.t4 || '');
      if (tsh !== null) {
        if (tsh < 0.4) notes.push(`TSH (${tsh}) منخفض عن النطاق الشائع (0.4–4.0 mIU/L) — قد يرتبط بنشاط الغدة.`);
        else if (tsh > 4.0) notes.push(`TSH (${tsh}) مرتفع عن النطاق الشائع (0.4–4.0 mIU/L) — قد يرتبط بخمول الغدة.`);
        else notes.push(`TSH (${tsh} mIU/L) ضمن النطاق المختبري الشائع (0.4–4.0).`);
      }
      if (t3 !== null) notes.push(`قيمة T3 المسجلة (${t3}) — يراجعها طبيب الغدد مع وحدة المختبر.`);
      if (t4 !== null) notes.push(`قيمة T4 المسجلة (${t4}) — يراجعها طبيب الغدد ضمن الصورة الهرمونية الكاملة.`);
      return notes.join(' ') || 'نتائج هرمونات الغدة الدرقية مسجلة للمتابعة.';
    }
    case 'PULSE': {
      const bpm = parseStrictNumber(val);
      if (bpm === null) return 'قراءة نبض محفوظة.';
      if (bpm < 55) return `نبض منخفض نسبيًا (${bpm} bpm) — طبيعي أحيانًا للرياضيين، واستشر طبيبًا عند وجود دوخة.`;
      if (bpm <= 100) return `نبض القلب وقت الراحة (${bpm} bpm) ضمن النطاق المرجعي المعتاد للبالغين (60–100 bpm).`;
      return `نبض مرتفع عن 100 bpm (${bpm} bpm) — قِس مجددًا بعد الراحة، وراجع الطبيب إذا استمر دون مجهود.`;
    }
    case 'RESPIRATORY_SPO2': {
      const notes: string[] = [];
      const spo2 = parseStrictNumber(subs.spo2 || '');
      const resp = parseStrictNumber(subs.respiratoryRate || '');
      if (spo2 !== null) {
        if (spo2 < 92) notes.push(`تنبيه: نسبة الأكسجين SpO2 (${spo2}%) منخفضة بوضوح — يلزم تقييم طبي عاجل.`);
        else if (spo2 < 95) notes.push(`نسبة الأكسجين SpO2 (${spo2}%) أقل قليلًا من المعتاد (95%–100%) — يفضل مراجعة الطبيب.`);
        else notes.push(`نسبة الأكسجين SpO2 (${spo2}%) ضمن النطاق الطبيعي الموثق (95%–100%).`);
      }
      if (resp !== null) {
        if (resp < 12 || resp > 20) notes.push(`معدل التنفس (${resp} نفس/دقيقة) خارج نطاق الراحة المعتاد (12–20).`);
        else notes.push(`معدل التنفس (${resp} نفس/دقيقة) ضمن نطاق الراحة الطبيعي (12–20).`);
      }
      return notes.join(' ') || 'قراءة التنفس والأكسجين محفوظة بمصدرها.';
    }
    case 'BMI': {
      const bmi = parseStrictNumber(subs.bmi || '');
      if (bmi !== null) {
        return `مؤشر كتلة الجسم BMI = ${bmi} (${classifyBmiAr(bmi)}) محسوبًا رياضيًا من الطول والوزن الفعليين.`;
      }
      return 'قياس الطول والوزن ومؤشر كتلة الجسم محفوظ للمتابعة.';
    }
    case 'BODY_COMPOSITION': {
      const parts: string[] = [];
      const fat = parseStrictNumber(subs.fatPct || '');
      const mus = parseStrictNumber(subs.muscleKg || '');
      const wt = parseStrictNumber(subs.weightKg || '');
      if (fat !== null) parts.push(`نسبة الدهون المقاسة ${fat}% (تختلف الحدود المرجعية حسب الجنس والعمر).`);
      if (mus !== null) parts.push(`كتلة العضلات المسجلة ${mus} كجم.`);
      if (wt !== null) parts.push(`الوزن المرجعي ${wt} كجم للمقارنة الزمنية.`);
      return parts.join(' ') || 'قياسات تركيب الجسم محفوظة من مقياس فعلي.';
    }
    case 'LIVER_KIDNEY': {
      const notes: string[] = [];
      if (testCode === 'LIVER') {
        const alt = parseStrictNumber(subs.alt || '');
        const ast = parseStrictNumber(subs.ast || '');
        const alp = parseStrictNumber(subs.alp || '');
        const bili = parseStrictNumber(subs.bilirubin || '');
        if (alt !== null) notes.push(alt > 45 ? `ALT (${alt}) أعلى من المعتاد غالبًا (حتى ~45 U/L).` : `ALT (${alt}) ضمن الحدود الشائعة.`);
        if (ast !== null) notes.push(ast > 40 ? `AST (${ast}) أعلى من المعتاد غالبًا (حتى ~40 U/L).` : `AST (${ast}) ضمن الحدود الشائعة.`);
        if (alp !== null) notes.push(`ALP (${alp} U/L) محفوظ للمقارنة وفق مرجع المختبر.`);
        if (bili !== null) notes.push(bili > 1.2 ? `Bilirubin (${bili}) مرتفع عن النطاق الشائع (0.1–1.2 mg/dL).` : `Bilirubin (${bili}) ضمن النطاق الشائع.`);
      } else {
        const cr = parseStrictNumber(subs.creatinine || '');
        const egfr = parseStrictNumber(subs.egfr || '');
        const urea = parseStrictNumber(subs.urea || '');
        if (cr !== null) notes.push(cr > 1.3 ? `Creatinine (${cr}) أعلى قليلًا من النطاق الشائع (0.6–1.3 mg/dL) — راجع الطبيب.` : `Creatinine (${cr}) ضمن النطاق الشائع.`);
        if (egfr !== null) notes.push(egfr < 60 ? `eGFR (${egfr}) أقل من 60 — يستلزم تقييم طبيب كلى.` : `eGFR (${egfr}) يعكس كفاءة الترشيح الكلوي المسجلة.`);
        if (urea !== null) notes.push(`Urea (${urea}) محفوظ لمتابعة توازن السوائل ووظيفة الكلى.`);
      }
      return notes.join(' ') || 'تحليل وظائف الكبد/الكلى محفوظ للمتابعة الدورية.';
    }
    default:
      return 'تم حفظ القراءة بمصدرها وتاريخها داخل سجل المستخدم المعزول.';
  }
}

export const LabsAndBioFingerScreen: React.FC<LabsAndBioFingerScreenProps> = ({
  profile,
  labs,
  scans,
  reports,
  initialLabCode,
  onAddLabReading,
  onDeleteLabReading,
  onSaveScanImageAndReport,
  onGoToPpgScan,
  onGoToGuidedCamera,
}) => {
  const [capabilities, setCapabilities] = useState<DeviceHardwareCapabilities | null>(null);
  const [activeSection, setActiveSection] = useState<HealthSectionId>(() =>
    mapInitialCodeToSection(initialLabCode)
  );

  // Dynamic input states per section
  const [singleVal, setSingleVal] = useState('');
  const [systolic, setSystolic] = useState('');
  const [diastolic, setDiastolic] = useState('');
  // CBC
  const [hbVal, setHbVal] = useState('');
  const [rbcVal, setRbcVal] = useState('');
  const [wbcVal, setWbcVal] = useState('');
  const [pltVal, setPltVal] = useState('');
  // Thyroid
  const [tshVal, setTshVal] = useState('');
  const [t3Val, setT3Val] = useState('');
  const [t4Val, setT4Val] = useState('');
  // Respiratory / SpO2
  const [spo2Val, setSpo2Val] = useState('');
  const [respRateVal, setRespRateVal] = useState('');
  // BMI & Body Composition
  const [heightCm, setHeightCm] = useState(profile.heightCm ? String(profile.heightCm) : '');
  const [weightKg, setWeightKg] = useState(profile.weightKg ? String(profile.weightKg) : '');
  const [bodyFatPct, setBodyFatPct] = useState('');
  const [muscleKg, setMuscleKg] = useState('');
  // Liver (sub-section 1)
  const [altVal, setAltVal] = useState('');
  const [astVal, setAstVal] = useState('');
  const [alpVal, setAlpVal] = useState('');
  const [biliVal, setBiliVal] = useState('');
  // Kidney (sub-section 2)
  const [creatVal, setCreatVal] = useState('');
  const [egfrVal, setEgfrVal] = useState('');
  const [ureaVal, setUreaVal] = useState('');
  const [lkFilter, setLkFilter] = useState<'ALL' | 'LIVER' | 'KIDNEY'>('ALL');

  const [source, setSource] = useState('نتيجة مختبر طبي معتمد');
  const [notes, setNotes] = useState('');
  const [formMsg, setFormMsg] = useState<{ type: 'ok' | 'err'; text: string } | null>(null);

  useEffect(() => {
    inspectDeviceCapabilities().then(setCapabilities);
  }, []);

  useEffect(() => {
    setHeightCm(profile.heightCm ? String(profile.heightCm) : '');
    setWeightKg(profile.weightKg ? String(profile.weightKg) : '');
  }, [profile.userId, profile.heightCm, profile.weightKg]);

  useEffect(() => {
    if (initialLabCode) {
      setActiveSection(mapInitialCodeToSection(initialLabCode));
      if (initialLabCode.toUpperCase() === 'LIVER') setLkFilter('LIVER');
      else if (initialLabCode.toUpperCase() === 'KIDNEY') setLkFilter('KIDNEY');
    }
  }, [initialLabCode]);

  const currentMeta = HEALTH_CENTER_SECTIONS.find((s) => s.id === activeSection)!;
  const rawSectionLabs = labs.filter(
    (l) => l.sectionId === activeSection || l.testCode === activeSection
  );
  const sectionLabs =
    activeSection === 'LIVER_KIDNEY' && lkFilter !== 'ALL'
      ? rawSectionLabs.filter((l) => l.testCode === lkFilter)
      : rawSectionLabs;

  // Deterministic Risk Evaluation for current section
  const sectionRisk: RiskAssessmentResult | undefined = runSabqSmartRiskEngine({
    userId: profile.userId,
    intake: profile.intake || {
      symptoms: [],
      personalHistory: [],
      familyHistory: [],
      dailyActivityLevel: '',
    },
    heightCm: profile.heightCm,
    weightKg: profile.weightKg,
    labs,
  }).find((r) => r.sectionId === activeSection);

  const liveBmiPreview = calculateRealBmi(
    heightCm.trim() ? Number(heightCm) : undefined,
    weightKg.trim() ? Number(weightKg) : undefined
  );

  const saveLiverSubSection = () => {
    setFormMsg(null);
    const altCheck = validateNumericRange(altVal, 1, 2000, 'ALT');
    const astCheck = validateNumericRange(astVal, 1, 2000, 'AST');
    const alpCheck = validateNumericRange(alpVal, 5, 2000, 'ALP');
    const biliCheck = validateNumericRange(biliVal, 0.05, 40, 'Bilirubin');
    const firstErr = altCheck.error || astCheck.error || alpCheck.error || biliCheck.error;
    if (firstErr) {
      setFormMsg({ type: 'err', text: firstErr });
      return;
    }

    const parts = [
      altVal.trim() && `ALT:${altVal.trim()}`,
      astVal.trim() && `AST:${astVal.trim()}`,
      alpVal.trim() && `ALP:${alpVal.trim()}`,
      biliVal.trim() && `Bilirubin:${biliVal.trim()}`,
    ].filter(Boolean);

    if (parts.length === 0) {
      setFormMsg({ type: 'err', text: 'أدخل قيمة واحدة على الأقل في قسم وظائف الكبد (ALT, AST, ALP, Bilirubin).' });
      return;
    }

    const subMetrics = {
      alt: altVal.trim(),
      ast: astVal.trim(),
      alp: alpVal.trim(),
      bilirubin: biliVal.trim(),
    };
    const measuredValue = parts.join(' | ');
    const reading: LabReading = {
      id: `lab_LIVER_${Date.now()}`,
      userId: profile.userId,
      sectionId: 'LIVER_KIDNEY',
      testCode: 'LIVER',
      testNameAr: 'وظائف الكبد (ALT / AST / ALP / Bilirubin)',
      measuredValue,
      unit: 'U/L · mg/dL',
      subMetrics,
      source,
      explanationAr: explainLabValueAr('LIVER_KIDNEY', measuredValue, subMetrics, 'LIVER'),
      notes: notes.trim(),
      recordedAt: new Date().toISOString(),
    };
    onAddLabReading(reading);
    setAltVal('');
    setAstVal('');
    setAlpVal('');
    setBiliVal('');
    setNotes('');
    setFormMsg({ type: 'ok', text: 'تم حفظ قراءة وظائف الكبد المستقلة بنجاح.' });
  };

  const saveKidneySubSection = () => {
    setFormMsg(null);
    const crCheck = validateNumericRange(creatVal, 0.1, 25, 'Creatinine');
    const egfrCheck = validateNumericRange(egfrVal, 2, 200, 'eGFR');
    const ureaCheck = validateNumericRange(ureaVal, 2, 300, 'Urea');
    const firstErr = crCheck.error || egfrCheck.error || ureaCheck.error;
    if (firstErr) {
      setFormMsg({ type: 'err', text: firstErr });
      return;
    }

    const parts = [
      creatVal.trim() && `Creatinine:${creatVal.trim()}`,
      egfrVal.trim() && `eGFR:${egfrVal.trim()}`,
      ureaVal.trim() && `Urea:${ureaVal.trim()}`,
    ].filter(Boolean);

    if (parts.length === 0) {
      setFormMsg({ type: 'err', text: 'أدخل قيمة واحدة على الأقل في قسم وظائف الكلى (Creatinine, eGFR, Urea).' });
      return;
    }

    const subMetrics = {
      creatinine: creatVal.trim(),
      egfr: egfrVal.trim(),
      urea: ureaVal.trim(),
    };
    const measuredValue = parts.join(' | ');
    const reading: LabReading = {
      id: `lab_KIDNEY_${Date.now()}`,
      userId: profile.userId,
      sectionId: 'LIVER_KIDNEY',
      testCode: 'KIDNEY',
      testNameAr: 'وظائف الكلى (Creatinine / eGFR / Urea)',
      measuredValue,
      unit: 'mg/dL · mL/min',
      subMetrics,
      source,
      explanationAr: explainLabValueAr('LIVER_KIDNEY', measuredValue, subMetrics, 'KIDNEY'),
      notes: notes.trim(),
      recordedAt: new Date().toISOString(),
    };
    onAddLabReading(reading);
    setCreatVal('');
    setEgfrVal('');
    setUreaVal('');
    setNotes('');
    setFormMsg({ type: 'ok', text: 'تم حفظ قراءة وظائف الكلى المستقلة بنجاح.' });
  };

  const handleSaveSectionMeasurement = (e: React.FormEvent) => {
    e.preventDefault();
    if (activeSection === 'LIVER_KIDNEY') return;
    setFormMsg(null);
    const nowIso = new Date().toISOString();
    let measuredValue = '';
    let subMetrics: Record<string, string> = {};
    let testCode: string = activeSection;

    if (activeSection === 'GLUCOSE') {
      const chk = validateNumericRange(singleVal, 20, 700, 'سكر الدم');
      if (chk.error) {
        setFormMsg({ type: 'err', text: chk.error });
        return;
      }
      if (chk.value === undefined) {
        setFormMsg({ type: 'err', text: 'يرجى إدخال قراءة سكر الدم الفعلية (mg/dL).' });
        return;
      }
      measuredValue = String(chk.value);
    } else if (activeSection === 'HBA1C') {
      const chk = validateNumericRange(singleVal, 3.0, 20.0, 'السكر التراكمي HbA1c');
      if (chk.error) {
        setFormMsg({ type: 'err', text: chk.error });
        return;
      }
      if (chk.value === undefined) {
        setFormMsg({ type: 'err', text: 'يرجى إدخال نسبة السكر التراكمي الفعلية (%).' });
        return;
      }
      measuredValue = String(chk.value);
    } else if (activeSection === 'PULSE') {
      const chk = validateNumericRange(singleVal, 30, 220, 'معدل النبض');
      if (chk.error) {
        setFormMsg({ type: 'err', text: chk.error });
        return;
      }
      if (chk.value === undefined) {
        setFormMsg({ type: 'err', text: 'يرجى إدخال معدل النبض الفعلي (bpm) أو تشغيل القياس الضوئي PPG.' });
        return;
      }
      measuredValue = String(chk.value);
    } else if (activeSection === 'BP') {
      const sChk = validateNumericRange(systolic, 60, 260, 'الضغط الانقباضي');
      const dChk = validateNumericRange(diastolic, 35, 160, 'الضغط الانبساطي');
      if (sChk.error || dChk.error) {
        setFormMsg({ type: 'err', text: sChk.error || dChk.error! });
        return;
      }
      if (sChk.value === undefined || dChk.value === undefined) {
        setFormMsg({ type: 'err', text: 'يرجى إدخال الضغط الانقباضي والانبساطي الفعليين.' });
        return;
      }
      if (sChk.value <= dChk.value) {
        setFormMsg({ type: 'err', text: 'يجب أن يكون الضغط الانقباضي أعلى من الانبساطي.' });
        return;
      }
      measuredValue = `${sChk.value}/${dChk.value}`;
      subMetrics = { systolic: String(sChk.value), diastolic: String(dChk.value) };
    } else if (activeSection === 'CBC') {
      const c1 = validateNumericRange(hbVal, 2, 25, 'Hb');
      const c2 = validateNumericRange(rbcVal, 1, 10, 'RBC');
      const c3 = validateNumericRange(wbcVal, 0.5, 150, 'WBC');
      const c4 = validateNumericRange(pltVal, 10, 1500, 'Platelets');
      const firstErr = c1.error || c2.error || c3.error || c4.error;
      if (firstErr) {
        setFormMsg({ type: 'err', text: firstErr });
        return;
      }
      if (!hbVal.trim() && !rbcVal.trim() && !wbcVal.trim() && !pltVal.trim()) {
        setFormMsg({ type: 'err', text: 'أدخل على الأقل قيمة واحدة من صورة الدم (Hb, RBC, WBC, Platelets).' });
        return;
      }
      subMetrics = { hb: hbVal.trim(), rbc: rbcVal.trim(), wbc: wbcVal.trim(), platelets: pltVal.trim() };
      measuredValue = [
        hbVal.trim() && `Hb:${hbVal.trim()}`,
        rbcVal.trim() && `RBC:${rbcVal.trim()}`,
        wbcVal.trim() && `WBC:${wbcVal.trim()}`,
        pltVal.trim() && `PLT:${pltVal.trim()}`,
      ]
        .filter(Boolean)
        .join(' | ');
    } else if (activeSection === 'THYROID') {
      const c1 = validateNumericRange(tshVal, 0.001, 150, 'TSH');
      const c2 = validateNumericRange(t3Val, 0.1, 30, 'T3');
      const c3 = validateNumericRange(t4Val, 0.1, 40, 'T4');
      const firstErr = c1.error || c2.error || c3.error;
      if (firstErr) {
        setFormMsg({ type: 'err', text: firstErr });
        return;
      }
      if (!tshVal.trim() && !t3Val.trim() && !t4Val.trim()) {
        setFormMsg({ type: 'err', text: 'أدخل قيمة TSH أو T3 أو T4 المخبرية.' });
        return;
      }
      subMetrics = { tsh: tshVal.trim(), t3: t3Val.trim(), t4: t4Val.trim() };
      measuredValue = [
        tshVal.trim() && `TSH:${tshVal.trim()}`,
        t3Val.trim() && `T3:${t3Val.trim()}`,
        t4Val.trim() && `T4:${t4Val.trim()}`,
      ]
        .filter(Boolean)
        .join(' | ');
    } else if (activeSection === 'RESPIRATORY_SPO2') {
      const c1 = validateNumericRange(spo2Val, 50, 100, 'نسبة الأكسجين SpO2');
      const c2 = validateNumericRange(respRateVal, 5, 60, 'معدل التنفس');
      if (c1.error || c2.error) {
        setFormMsg({ type: 'err', text: c1.error || c2.error! });
        return;
      }
      if (!spo2Val.trim() && !respRateVal.trim()) {
        setFormMsg({ type: 'err', text: 'أدخل نسبة الأكسجين SpO2 من جهاز معتمد أو معدل التنفس.' });
        return;
      }
      testCode = 'SPO2';
      subMetrics = { spo2: spo2Val.trim(), respiratoryRate: respRateVal.trim() };
      measuredValue = [
        spo2Val.trim() && `SpO2:${spo2Val.trim()}%`,
        respRateVal.trim() && `Resp:${respRateVal.trim()}/m`,
      ]
        .filter(Boolean)
        .join(' | ');
    } else if (activeSection === 'BMI') {
      const h = Number(heightCm);
      const w = Number(weightKg);
      const bmi = calculateRealBmi(h, w);
      if (bmi === null) {
        setFormMsg({ type: 'err', text: 'أدخل طولًا صحيحًا (80–250 سم) ووزنًا صحيحًا (15–350 كجم).' });
        return;
      }
      measuredValue = `${bmi} (${classifyBmiAr(bmi)})`;
      subMetrics = { heightCm: String(h), weightKg: String(w), bmi: String(bmi) };
      onSaveScanImageAndReport(null, [], [], { heightCm: h, weightKg: w });
    } else if (activeSection === 'BODY_COMPOSITION') {
      const c1 = validateNumericRange(bodyFatPct, 3, 65, 'نسبة الدهون');
      const c2 = validateNumericRange(muscleKg, 10, 120, 'كتلة العضلات');
      const c3 = validateNumericRange(weightKg, 15, 350, 'الوزن');
      const firstErr = c1.error || c2.error || c3.error;
      if (firstErr) {
        setFormMsg({ type: 'err', text: firstErr });
        return;
      }
      if (!bodyFatPct.trim() && !muscleKg.trim() && !weightKg.trim()) {
        setFormMsg({ type: 'err', text: 'أدخل نسبة الدهون أو كتلة العضلات أو الوزن من مقياس فعلي.' });
        return;
      }
      subMetrics = { fatPct: bodyFatPct.trim(), muscleKg: muscleKg.trim(), weightKg: weightKg.trim() };
      measuredValue = [
        bodyFatPct.trim() && `الدهون:${bodyFatPct.trim()}%`,
        muscleKg.trim() && `العضلات:${muscleKg.trim()}kg`,
        weightKg.trim() && `الوزن:${weightKg.trim()}kg`,
      ]
        .filter(Boolean)
        .join(' | ');
    }

    const explanationAr = explainLabValueAr(activeSection, measuredValue, subMetrics, testCode);
    const reading: LabReading = {
      id: `lab_${activeSection}_${Date.now()}`,
      userId: profile.userId,
      sectionId: activeSection,
      testCode,
      testNameAr: currentMeta.titleAr,
      measuredValue,
      unit: SECTION_DEFAULT_UNIT[activeSection],
      subMetrics,
      source,
      explanationAr,
      notes: notes.trim(),
      recordedAt: nowIso,
    };

    onAddLabReading(reading);
    setSingleVal('');
    setSystolic('');
    setDiastolic('');
    setNotes('');
    setFormMsg({ type: 'ok', text: `تم حفظ القراءة الفعلية في قسم ${currentMeta.shortAr} بنجاح.` });
  };

  const generateSectionIndependentPdf = async () => {
    setFormMsg(null);
    try {
      const rType = activeSection as ReportCategoryType;
      const sectionScans = scans.filter(
        (s) =>
          s.scanType === activeSection ||
          (activeSection === 'FACE_EYE_SKIN' && ['FACE', 'EYE', 'SKIN', 'FACE_EYE_SKIN'].includes(s.scanType)) ||
          (activeSection === 'HAIR_SCALP' && ['HAIR', 'HAIR_SCALP'].includes(s.scanType))
      );
      const { blob, filename } = await generateSabqArabicPdfBlob({
        profile,
        reportType: rType,
        scans: sectionScans,
        labs: rawSectionLabs,
        riskAssessments: sectionRisk ? [sectionRisk] : [],
        referenceImageDataUrl: sectionScans[0]?.imageUrl || sectionScans[0]?.savedImageDataUrl,
        previousReport: reports.find((r) => r.reportType === rType),
      });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      a.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 10000);
    } catch (err: any) {
      setFormMsg({ type: 'err', text: err?.message || 'تعذر إنشاء ملف PDF لهذا القسم، حاول مرة أخرى.' });
    }
  };

  // Weight/BMI Chart Points for section 8
  const bmiHistory = labs
    .filter((l) => l.sectionId === 'BMI' && l.subMetrics?.weightKg)
    .slice(0, 8)
    .reverse();

  // Blood Pressure Chart Points for section 2
  const bpHistory = labs
    .filter((l) => l.sectionId === 'BP' && l.subMetrics?.systolic && l.subMetrics?.diastolic)
    .slice(0, 8)
    .reverse();

  return (
    <div className="space-y-6 pb-8">
      {/* Header + Hardware Transparency */}
      <div className="rounded-3xl border border-violet-500/25 bg-[#0C0E1A] p-5 space-y-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <span className="flex h-11 w-11 items-center justify-center rounded-2xl bg-cyan-500/15 text-cyan-300">
              <Fingerprint className="h-6 w-6" />
            </span>
            <div>
              <h2 className="text-lg font-bold text-white">
                SABQ COMPLETE HEALTH CENTER — المركز الصحي المتكامل (12 قسمًا)
              </h2>
              <p className="text-xs text-slate-400">
                قياسات موثقة، تقييم خطورة حتمي، ورفع صور حقيقي — دون أي أرقام طبية مخترعة
              </p>
            </div>
          </div>

          <button
            onClick={generateSectionIndependentPdf}
            className="flex min-h-[42px] items-center gap-2 rounded-xl bg-gradient-to-l from-violet-600 to-cyan-500 px-4 py-2 text-xs font-bold text-white"
          >
            <FileDown className="h-4 w-4" />
            <span>تنزيل تقرير {currentMeta.shortAr} PDF</span>
          </button>
        </div>

        {capabilities && (
          <div className="grid grid-cols-1 gap-3 md:grid-cols-2 text-xs">
            <div className="rounded-2xl border border-slate-800 bg-[#080A12] p-3 flex items-center gap-2 text-slate-300">
              <Cpu className="h-4 w-4 shrink-0 text-cyan-400" />
              <span>
                الكاميرا: {capabilities.hasCamera ? 'متاحة (PPG + تحليل مظهر)' : 'غير متاحة'} · قارئ البصمة للأمان فقط.
              </span>
            </div>
            <div className="rounded-2xl border border-amber-500/30 bg-amber-950/15 p-3 flex items-center gap-2 text-amber-200">
              <AlertCircle className="h-4 w-4 shrink-0 text-amber-400" />
              <span>لا تُستخرج قيم السكر أو الضغط أو الهيموجلوبين أو الدهون من صورة الوجه أو البصمة.</span>
            </div>
          </div>
        )}
      </div>

      {/* 12 Health Center Navigation Cards */}
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-4">
        {HEALTH_CENTER_SECTIONS.map((sec) => {
          const count =
            sec.kind === 'VISUAL_UPLOAD'
              ? scans.filter((s) =>
                  sec.id === 'FACE_EYE_SKIN'
                    ? ['FACE', 'EYE', 'SKIN', 'FACE_EYE_SKIN'].includes(s.scanType)
                    : ['HAIR', 'HAIR_SCALP'].includes(s.scanType)
                ).length
              : labs.filter((l) => l.sectionId === sec.id || l.testCode === sec.id).length;

          const active = activeSection === sec.id;
          return (
            <button
              key={sec.id}
              onClick={() => {
                setFormMsg(null);
                setActiveSection(sec.id);
              }}
              className={`rounded-2xl border p-3 text-right transition-colors ${
                active
                  ? 'border-cyan-400 bg-gradient-to-l from-violet-600/25 to-cyan-500/20 text-white'
                  : 'border-slate-800 bg-[#0D101D] text-slate-300 hover:border-violet-400/50'
              }`}
            >
              <div className="flex items-center justify-between gap-1">
                <span className="text-xs font-bold truncate">{sec.titleAr}</span>
                <span className="num-en rounded-md bg-black/40 px-1.5 py-0.5 text-[10px] text-cyan-300">
                  {count}
                </span>
              </div>
            </button>
          );
        })}
      </div>

      {/* Active Section Workspace */}
      {activeSection === 'FACE_EYE_SKIN' || activeSection === 'HAIR_SCALP' ? (
        <div className="space-y-4">
          <div className="flex flex-wrap items-center justify-between gap-2 rounded-2xl border border-slate-800 bg-[#0C0E1A] p-4">
            <span className="text-xs text-slate-300">{currentMeta.summaryAr}</span>
            <button
              onClick={() => onGoToGuidedCamera(activeSection === 'HAIR_SCALP' ? 'HAIR' : 'FACE')}
              className="flex items-center gap-1.5 rounded-xl border border-cyan-500/40 bg-cyan-500/10 px-3 py-1.5 text-xs font-bold text-cyan-300"
            >
              <Camera className="h-4 w-4" />
              <span>فتح الفحص التلقائي بالكاميرا الحية</span>
            </button>
          </div>

          <RealImageUploadPanel
            key={`${profile.userId}_${activeSection}`}
            userId={profile.userId}
            sectionId={activeSection}
            titleAr={currentMeta.titleAr}
            subtitleAr={currentMeta.summaryAr}
            previousScan={scans.find((s) =>
              activeSection === 'FACE_EYE_SKIN'
                ? ['FACE', 'SKIN', 'EYE', 'FACE_EYE_SKIN'].includes(s.scanType)
                : ['HAIR', 'HAIR_SCALP'].includes(s.scanType)
            )}
            onImageUploadedAndAnalyzed={(uploaded, scanRec) => {
              const rType = activeSection as ReportCategoryType;
              const rep: ReportRecord = {
                id: `rep_${activeSection}_${Date.now()}`,
                userId: profile.userId,
                reportType: rType,
                title: `تقرير ${currentMeta.shortAr} المستقل`,
                createdAt: new Date().toISOString(),
                scansCount: 1,
                labsCount: 0,
                comparisonSummary: 'تحليل من صورة فعلية مرفوعة عبر الخادم.',
                sourceLabel: scanRec.source,
                referenceImageUrl: uploaded.url,
                snapshotScans: [scanRec],
                snapshotLabs: [],
              };
              onSaveScanImageAndReport(uploaded, [scanRec], [rep]);
            }}
          />
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-12">
          {/* Structured Measurement Form */}
          <form
            onSubmit={handleSaveSectionMeasurement}
            className="rounded-3xl border border-violet-500/25 bg-[#0C0E1A] p-5 space-y-4 lg:col-span-6"
          >
            <div>
              <h3 className="text-base font-bold text-white">{currentMeta.titleAr}</h3>
              <p className="mt-1 text-xs text-slate-400">{currentMeta.summaryAr}</p>
            </div>

            {activeSection === 'PULSE' && (
              <div className="rounded-2xl border border-cyan-500/30 bg-cyan-950/15 p-3.5 space-y-2 text-xs">
                <p className="text-cyan-200">
                  يمكنك محاولة قياس النبض الضوئي (PPG) عبر الكاميرا الخلفية أو إدخال قراءة ساعة/جهاز طبي أدناه. كاميرا الهاتف لا تجري تخطيط قلب ECG.
                </p>
                <button
                  type="button"
                  onClick={onGoToPpgScan}
                  className="rounded-xl bg-cyan-500 px-3 py-1.5 font-bold text-black"
                >
                  تشغيل قياس النبض الضوئي PPG
                </button>
              </div>
            )}

            {activeSection === 'RESPIRATORY_SPO2' && (
              <div className="rounded-2xl border border-cyan-500/30 bg-cyan-950/15 p-3.5 space-y-2 text-xs">
                <p className="text-cyan-200">
                  لا تُستنتج نسبة الأكسجين SpO2 من صورة الوجه؛ سجّلها من جهاز تأكسج نبضي أو ساعة معتمدة، أو شغّل PPG لتقدير معدل التنفس عند توفر جودة إشارة كافية.
                </p>
                <button
                  type="button"
                  onClick={onGoToPpgScan}
                  className="rounded-xl bg-cyan-500 px-3 py-1.5 font-bold text-black"
                >
                  تقدير التنفس عبر مستشعر PPG الضوئي
                </button>
              </div>
            )}

            {activeSection === 'BP' ? (
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="mb-1 block text-xs text-slate-300">الضغط الانقباضي (mmHg)</label>
                  <input type="number" min="60" max="260" value={systolic} onChange={(e) => setSystolic(e.target.value)} placeholder="120" className="num-en w-full rounded-xl border border-slate-700 bg-[#06070D] px-3 py-2 text-sm text-white" />
                </div>
                <div>
                  <label className="mb-1 block text-xs text-slate-300">الضغط الانبساطي (mmHg)</label>
                  <input type="number" min="35" max="160" value={diastolic} onChange={(e) => setDiastolic(e.target.value)} placeholder="80" className="num-en w-full rounded-xl border border-slate-700 bg-[#06070D] px-3 py-2 text-sm text-white" />
                </div>
              </div>
            ) : activeSection === 'CBC' ? (
              <div className="space-y-2">
                <div className="grid grid-cols-2 gap-3">
                  <input type="number" step="0.1" value={hbVal} onChange={(e) => setHbVal(e.target.value)} placeholder="الهيموجلوبين Hb (g/dL)" className="num-en rounded-xl border border-slate-700 bg-[#06070D] px-3 py-2 text-xs text-white" />
                  <input type="number" step="0.01" value={rbcVal} onChange={(e) => setRbcVal(e.target.value)} placeholder="الحمراء RBC (M/uL)" className="num-en rounded-xl border border-slate-700 bg-[#06070D] px-3 py-2 text-xs text-white" />
                  <input type="number" step="0.1" value={wbcVal} onChange={(e) => setWbcVal(e.target.value)} placeholder="البيضاء WBC (K/uL)" className="num-en rounded-xl border border-slate-700 bg-[#06070D] px-3 py-2 text-xs text-white" />
                  <input type="number" step="1" value={pltVal} onChange={(e) => setPltVal(e.target.value)} placeholder="الصفائح Platelets (K/uL)" className="num-en rounded-xl border border-slate-700 bg-[#06070D] px-3 py-2 text-xs text-white" />
                </div>
                <p className="text-[11px] text-amber-200/80">
                  تنبيه شفافية: لا يُستنتج الهيموجلوبين أو الأنيميا من مظهر الوجه أو الشفاه.
                </p>
              </div>
            ) : activeSection === 'THYROID' ? (
              <div className="grid grid-cols-3 gap-2">
                <input type="number" step="0.01" value={tshVal} onChange={(e) => setTshVal(e.target.value)} placeholder="TSH (mIU/L)" className="num-en rounded-xl border border-slate-700 bg-[#06070D] px-3 py-2 text-xs text-white" />
                <input type="number" step="0.01" value={t3Val} onChange={(e) => setT3Val(e.target.value)} placeholder="T3" className="num-en rounded-xl border border-slate-700 bg-[#06070D] px-3 py-2 text-xs text-white" />
                <input type="number" step="0.01" value={t4Val} onChange={(e) => setT4Val(e.target.value)} placeholder="T4" className="num-en rounded-xl border border-slate-700 bg-[#06070D] px-3 py-2 text-xs text-white" />
              </div>
            ) : activeSection === 'RESPIRATORY_SPO2' ? (
              <div className="grid grid-cols-2 gap-3">
                <input type="number" step="0.1" min="50" max="100" value={spo2Val} onChange={(e) => setSpo2Val(e.target.value)} placeholder="الأكسجين SpO2 % (جهاز)" className="num-en rounded-xl border border-slate-700 bg-[#06070D] px-3 py-2 text-xs text-white" />
                <input type="number" min="5" max="60" value={respRateVal} onChange={(e) => setRespRateVal(e.target.value)} placeholder="معدل التنفس / دقيقة" className="num-en rounded-xl border border-slate-700 bg-[#06070D] px-3 py-2 text-xs text-white" />
              </div>
            ) : activeSection === 'BMI' ? (
              <div className="space-y-3">
                <div className="grid grid-cols-2 gap-3">
                  <input type="number" min="80" max="250" value={heightCm} onChange={(e) => setHeightCm(e.target.value)} placeholder="الطول (سم) مثال 175" className="num-en rounded-xl border border-slate-700 bg-[#06070D] px-3 py-2 text-sm text-white" />
                  <input type="number" step="0.1" min="15" max="350" value={weightKg} onChange={(e) => setWeightKg(e.target.value)} placeholder="الوزن (كجم) مثال 72" className="num-en rounded-xl border border-slate-700 bg-[#06070D] px-3 py-2 text-sm text-white" />
                </div>
                {liveBmiPreview !== null && (
                  <div className="flex items-center gap-2 rounded-xl border border-emerald-500/35 bg-emerald-950/20 px-3 py-2 text-xs text-emerald-200">
                    <Scale className="h-4 w-4 text-emerald-400" />
                    <span>
                      معاينة BMI التلقائية: <strong className="num-en text-white">{liveBmiPreview}</strong> ({classifyBmiAr(liveBmiPreview)})
                    </span>
                  </div>
                )}
              </div>
            ) : activeSection === 'BODY_COMPOSITION' ? (
              <div className="grid grid-cols-3 gap-2">
                <input type="number" step="0.1" value={bodyFatPct} onChange={(e) => setBodyFatPct(e.target.value)} placeholder="الدهون %" className="num-en rounded-xl border border-slate-700 bg-[#06070D] px-3 py-2 text-xs text-white" />
                <input type="number" step="0.1" value={muscleKg} onChange={(e) => setMuscleKg(e.target.value)} placeholder="العضلات kg" className="num-en rounded-xl border border-slate-700 bg-[#06070D] px-3 py-2 text-xs text-white" />
                <input type="number" step="0.1" value={weightKg} onChange={(e) => setWeightKg(e.target.value)} placeholder="الوزن kg" className="num-en rounded-xl border border-slate-700 bg-[#06070D] px-3 py-2 text-xs text-white" />
              </div>
            ) : activeSection === 'LIVER_KIDNEY' ? (
              <div className="space-y-4">
                {/* Independent Sub-section 1: Liver */}
                <div className="rounded-2xl border border-cyan-500/30 bg-[#080A12] p-3.5 space-y-2.5">
                  <div className="text-xs font-bold text-cyan-300">القسم الفرعي 1: وظائف الكبد المستقلة</div>
                  <p className="text-[11px] text-slate-400">
                    أهمية الفحص: تقييم سلامة إنزيمات الكبد (ALT, AST, ALP) والصفراء (Bilirubin) خصوصًا عند الإجهاد أو الأدوية.
                  </p>
                  <div className="grid grid-cols-2 gap-2">
                    <input type="number" step="0.1" value={altVal} onChange={(e) => setAltVal(e.target.value)} placeholder="ALT (U/L)" className="num-en rounded-xl border border-slate-700 bg-[#06070D] px-3 py-2 text-xs text-white" />
                    <input type="number" step="0.1" value={astVal} onChange={(e) => setAstVal(e.target.value)} placeholder="AST (U/L)" className="num-en rounded-xl border border-slate-700 bg-[#06070D] px-3 py-2 text-xs text-white" />
                    <input type="number" step="0.1" value={alpVal} onChange={(e) => setAlpVal(e.target.value)} placeholder="ALP (U/L)" className="num-en rounded-xl border border-slate-700 bg-[#06070D] px-3 py-2 text-xs text-white" />
                    <input type="number" step="0.01" value={biliVal} onChange={(e) => setBiliVal(e.target.value)} placeholder="Bilirubin (mg/dL)" className="num-en rounded-xl border border-slate-700 bg-[#06070D] px-3 py-2 text-xs text-white" />
                  </div>
                  <button
                    type="button"
                    onClick={saveLiverSubSection}
                    className="flex min-h-[40px] w-full items-center justify-center gap-1.5 rounded-xl bg-cyan-500/20 border border-cyan-400/45 px-3 py-2 text-xs font-bold text-cyan-200 hover:bg-cyan-500/30"
                  >
                    <Plus className="h-3.5 w-3.5" />
                    <span>حفظ تحاليل الكبد المستقلة</span>
                  </button>
                </div>

                {/* Independent Sub-section 2: Kidney */}
                <div className="rounded-2xl border border-violet-500/30 bg-[#080A12] p-3.5 space-y-2.5">
                  <div className="text-xs font-bold text-violet-300">القسم الفرعي 2: وظائف الكلى المستقلة</div>
                  <p className="text-[11px] text-slate-400">
                    أهمية الفحص: تقييم كفاءة الترشيح الكلوي وتوازن السوائل عبر Creatinine وeGFR وUrea.
                  </p>
                  <div className="grid grid-cols-3 gap-2">
                    <input type="number" step="0.01" value={creatVal} onChange={(e) => setCreatVal(e.target.value)} placeholder="Creatinine" className="num-en rounded-xl border border-slate-700 bg-[#06070D] px-3 py-2 text-xs text-white" />
                    <input type="number" step="0.1" value={egfrVal} onChange={(e) => setEgfrVal(e.target.value)} placeholder="eGFR" className="num-en rounded-xl border border-slate-700 bg-[#06070D] px-3 py-2 text-xs text-white" />
                    <input type="number" step="0.1" value={ureaVal} onChange={(e) => setUreaVal(e.target.value)} placeholder="Urea" className="num-en rounded-xl border border-slate-700 bg-[#06070D] px-3 py-2 text-xs text-white" />
                  </div>
                  <button
                    type="button"
                    onClick={saveKidneySubSection}
                    className="flex min-h-[40px] w-full items-center justify-center gap-1.5 rounded-xl bg-violet-500/20 border border-violet-400/45 px-3 py-2 text-xs font-bold text-violet-200 hover:bg-violet-500/30"
                  >
                    <Plus className="h-3.5 w-3.5" />
                    <span>حفظ تحاليل الكلى المستقلة</span>
                  </button>
                </div>
              </div>
            ) : (
              <div>
                <label className="mb-1 block text-xs text-slate-300">
                  القيمة المقاسة ({SECTION_DEFAULT_UNIT[activeSection]})
                </label>
                <input
                  type="number"
                  step="any"
                  value={singleVal}
                  onChange={(e) => setSingleVal(e.target.value)}
                  placeholder={activeSection === 'HBA1C' ? 'مثال: 5.5' : activeSection === 'PULSE' ? 'مثال: 72' : 'مثال: 94'}
                  className="num-en w-full rounded-xl border border-slate-700 bg-[#06070D] px-3.5 py-2.5 text-sm text-white"
                />
              </div>
            )}

            <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
              <select
                value={source}
                onChange={(e) => setSource(e.target.value)}
                className="rounded-xl border border-slate-700 bg-[#06070D] px-3 py-2 text-xs text-white"
              >
                <option value="نتيجة مختبر طبي معتمد">نتيجة مختبر طبي معتمد</option>
                <option value="جهاز قياس منزلي معتمد">جهاز قياس منزلي معتمد</option>
                <option value="ساعة ذكية / مستشعر خارجي مرخص">ساعة ذكية / مستشعر خارجي مرخص</option>
                <option value="حساب مباشر / إدخال المستخدم الفعلي">حساب مباشر / إدخال المستخدم</option>
              </select>
              <input
                type="text"
                value={notes}
                onChange={(e) => setNotes(e.target.value)}
                placeholder="ملاحظة (مثل: صائم 8 ساعات)"
                className="rounded-xl border border-slate-700 bg-[#06070D] px-3 py-2 text-xs text-white"
              />
            </div>

            {formMsg && (
              <div
                className={`rounded-xl border p-2.5 text-xs ${
                  formMsg.type === 'ok'
                    ? 'border-emerald-500/40 bg-emerald-500/10 text-emerald-200'
                    : 'border-rose-500/40 bg-rose-500/10 text-rose-200'
                }`}
              >
                {formMsg.text}
              </div>
            )}

            {activeSection !== 'LIVER_KIDNEY' && (
              <button
                type="submit"
                className="flex min-h-[46px] w-full items-center justify-center gap-2 rounded-xl bg-gradient-to-l from-violet-600 to-cyan-500 px-4 py-2.5 text-sm font-bold text-white"
              >
                <Plus className="h-4 w-4" />
                <span>حفظ القياس الفعلي في السجل</span>
              </button>
            )}

            {/* Real SVG Blood Pressure Evolution Chart for Section 2 */}
            {activeSection === 'BP' && bpHistory.length > 0 && (
              <div className="rounded-2xl border border-slate-800 bg-[#080A12] p-3 space-y-2">
                <div className="flex items-center gap-1.5 text-xs font-bold text-cyan-300">
                  <HeartPulse className="h-4 w-4" />
                  <span>رسم بياني لتطور ضغط الدم ({bpHistory.length} قراءات)</span>
                </div>
                <svg viewBox="0 0 320 100" className="h-28 w-full">
                  {(() => {
                    const sysVals = bpHistory.map((b) => Number(b.subMetrics?.systolic || 120));
                    const diaVals = bpHistory.map((b) => Number(b.subMetrics?.diastolic || 80));
                    const minV = Math.min(...diaVals) - 10;
                    const maxV = Math.max(...sysVals) + 10;
                    const span = Math.max(10, maxV - minV);
                    const toX = (i: number) => (bpHistory.length === 1 ? 160 : 24 + (i * 272) / (bpHistory.length - 1));
                    const toY = (v: number) => 82 - ((v - minV) / span) * 60;
                    const sysPoly = sysVals.map((v, i) => `${toX(i)},${toY(v)}`).join(' ');
                    const diaPoly = diaVals.map((v, i) => `${toX(i)},${toY(v)}`).join(' ');
                    return (
                      <>
                        {bpHistory.length > 1 && (
                          <>
                            <polyline fill="none" stroke="#00D4FF" strokeWidth="2.2" points={sysPoly} />
                            <polyline fill="none" stroke="#A855F7" strokeWidth="2.2" points={diaPoly} />
                          </>
                        )}
                        {bpHistory.map((_, idx) => (
                          <g key={idx}>
                            <circle cx={toX(idx)} cy={toY(sysVals[idx])} r="3.5" fill="#00D4FF" />
                            <text x={toX(idx)} y={toY(sysVals[idx]) - 6} fill="#00D4FF" fontSize="8" textAnchor="middle">
                              {sysVals[idx]}/{diaVals[idx]}
                            </text>
                            <circle cx={toX(idx)} cy={toY(diaVals[idx])} r="3.5" fill="#A855F7" />
                          </g>
                        ))}
                      </>
                    );
                  })()}
                </svg>
              </div>
            )}

            {/* Real SVG Weight Chart for BMI Section */}
            {activeSection === 'BMI' && bmiHistory.length > 0 && (
              <div className="rounded-2xl border border-slate-800 bg-[#080A12] p-3 space-y-2">
                <div className="flex items-center gap-1.5 text-xs font-bold text-cyan-300">
                  <Scale className="h-4 w-4" />
                  <span>رسم بياني لتغير الوزن الفعلي ({bmiHistory.length} قراءات)</span>
                </div>
                <svg viewBox="0 0 320 90" className="h-24 w-full">
                  {(() => {
                    const weights = bmiHistory.map((b) => Number(b.subMetrics?.weightKg || 70));
                    const minW = Math.min(...weights) - 2;
                    const maxW = Math.max(...weights) + 2;
                    const span = Math.max(1, maxW - minW);
                    const pts = weights.map((w, i) => {
                      const x = weights.length === 1 ? 160 : 20 + (i * 280) / (weights.length - 1);
                      const y = 72 - ((w - minW) / span) * 52;
                      return { x, y, w };
                    });
                    const poly = pts.map((p) => `${p.x},${p.y}`).join(' ');
                    return (
                      <>
                        {pts.length > 1 && (
                          <polyline fill="none" stroke="#00D4FF" strokeWidth="2.5" points={poly} />
                        )}
                        {pts.map((p, idx) => (
                          <g key={idx}>
                            <circle cx={p.x} cy={p.y} r="4" fill="#8B5CF6" stroke="#00D4FF" strokeWidth="1.5" />
                            <text x={p.x} y={p.y - 8} fill="#E2E8F0" fontSize="9" textAnchor="middle">
                              {p.w}kg
                            </text>
                          </g>
                        ))}
                      </>
                    );
                  })()}
                </svg>
              </div>
            )}
          </form>

          {/* Section History + Comparison + Deterministic Risk Panel */}
          <div className="space-y-4 lg:col-span-6">
            {sectionRisk && (
              <div className="rounded-3xl border border-cyan-500/30 bg-[#0C0E1A] p-4 space-y-2 text-xs">
                <div className="flex items-center justify-between">
                  <span className="flex items-center gap-1.5 font-bold text-cyan-300">
                    <ShieldAlert className="h-4 w-4" />
                    <span>تقييم عوامل الخطورة ({sectionRisk.resultClassification})</span>
                  </span>
                  <span className="rounded-lg bg-violet-500/20 px-2 py-0.5 font-bold text-violet-200">
                    {sectionRisk.riskLevelAr}
                  </span>
                </div>
                <p className="text-slate-200">{sectionRisk.summary}</p>
                {sectionRisk.missingInformation.length > 0 && (
                  <p className="text-amber-200">
                    <strong>معلومات ناقصة:</strong> {sectionRisk.missingInformation.join(' · ')}
                  </p>
                )}
                <p className="text-emerald-200">
                  <strong>الخطوة التالية:</strong> {sectionRisk.nextStepAr}
                </p>
              </div>
            )}

            <div className="rounded-3xl border border-slate-800 bg-[#0C0E1A] p-4 space-y-2.5">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span className="text-xs font-bold text-white">
                  تاريخ قراءات {currentMeta.shortAr} ({sectionLabs.length})
                </span>
                {activeSection === 'LIVER_KIDNEY' && (
                  <div className="flex gap-1">
                    {(['ALL', 'LIVER', 'KIDNEY'] as const).map((f) => (
                      <button
                        key={f}
                        type="button"
                        onClick={() => setLkFilter(f)}
                        className={`rounded-lg px-2 py-0.5 text-[11px] font-bold ${
                          lkFilter === f
                            ? 'bg-cyan-500 text-black'
                            : 'border border-slate-700 text-slate-300'
                        }`}
                      >
                        {f === 'ALL' ? 'الكل' : f === 'LIVER' ? 'الكبد' : 'الكلى'}
                      </button>
                    ))}
                  </div>
                )}
              </div>

              {sectionLabs.length >= 2 && (
                <div className="rounded-xl border border-cyan-500/30 bg-cyan-950/15 px-3 py-2 text-[11px] text-cyan-200">
                  <strong>مقارنة زمنية:</strong> الأحدث:{' '}
                  <span className="num-en font-bold text-white">{sectionLabs[0].measuredValue}</span> (
                  <span className="num-en">{new Date(sectionLabs[0].recordedAt).toLocaleDateString('en-GB')}</span>)
                  {' · '}السابق:{' '}
                  <span className="num-en text-slate-200">{sectionLabs[1].measuredValue}</span> (
                  <span className="num-en">{new Date(sectionLabs[1].recordedAt).toLocaleDateString('en-GB')}</span>)
                </div>
              )}

              {sectionLabs.length === 0 ? (
                <div className="rounded-xl border border-slate-800 bg-[#080A12] p-4 text-xs text-slate-400">
                  لا توجد قراءات مسجلة في هذا القسم حتى الآن. أدخل قراءة موثقة لحفظها ومقارنتها لاحقًا.
                </div>
              ) : (
                <div className="max-h-80 space-y-2 overflow-y-auto">
                  {sectionLabs.map((item) => (
                    <div
                      key={item.id}
                      className="rounded-xl border border-slate-800 bg-[#080A12] p-3 text-xs space-y-1"
                    >
                      <div className="flex items-center justify-between">
                        <span className="flex items-center gap-1.5 font-bold text-white">
                          <CheckCircle2 className="h-3.5 w-3.5 text-emerald-400" />
                          {activeSection === 'LIVER_KIDNEY' && (
                            <span className="rounded bg-violet-500/20 px-1.5 py-0.5 text-[10px] text-violet-200">
                              {item.testCode === 'LIVER' ? 'الكبد' : 'الكلى'}
                            </span>
                          )}
                          <span className="num-en text-cyan-300">{item.measuredValue}</span>
                        </span>
                        <button
                          type="button"
                          onClick={() => onDeleteLabReading(item.id)}
                          aria-label="حذف القراءة"
                          className="p-1 text-slate-400 hover:text-rose-400"
                        >
                          <Trash2 className="h-3.5 w-3.5" />
                        </button>
                      </div>
                      {item.explanationAr && <p className="text-slate-200">{item.explanationAr}</p>}
                      {item.notes && <p className="text-[11px] text-slate-300">ملاحظة: {item.notes}</p>}
                      <div className="text-[11px] text-slate-400">
                        المصدر: {item.source} ·{' '}
                        <span className="num-en">{new Date(item.recordedAt).toLocaleString('en-GB')}</span>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
