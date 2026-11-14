import React, { useEffect, useState } from 'react';
import {
  AlertTriangle,
  CheckCircle2,
  ClipboardCheck,
  FileDown,
  Scale,
  ShieldAlert,
} from 'lucide-react';
import { generateSabqArabicPdfBlob } from '../utils/arabicPdfGenerator';
import { calculateRealBmi, classifyBmiAr, runSabqSmartRiskEngine } from '../utils/sabqRiskEngine';
import type {
  HealthScanRecord,
  LabReading,
  ReportCategoryType,
  ReportRecord,
  RiskAssessmentResult,
  UserProfile,
  VoluntaryHealthIntake,
} from '../types/sabq';

interface SmartHealthAssessmentScreenProps {
  profile: UserProfile;
  scans: HealthScanRecord[];
  labs: LabReading[];
  riskAssessments: RiskAssessmentResult[];
  reports: ReportRecord[];
  onSaveAssessment: (
    updatedProfile: Partial<UserProfile>,
    newAssessments: RiskAssessmentResult[],
    newReports: ReportRecord[],
    bmiScan?: HealthScanRecord
  ) => void;
}

const SYMPTOM_OPTIONS = [
  'عطش وتبول متكرر',
  'إرهاق وخمول مستمر',
  'صداع متكرر أو دوخة',
  'خفقان أو ضيق نفس بالمجهود',
  'تساقط شعر أو شحوب ملحوظ',
  'تقلبات وزن غير مفسرة أو حساسية للبرد/الحرارة',
];

const HISTORY_OPTIONS = [
  'تاريخ عائلي للسكري',
  'تاريخ عائلي لارتفاع ضغط الدم',
  'تاريخ عائلي لأمراض القلب',
  'تاريخ عائلي لاضطراب الغدة الدرقية',
  'تاريخ شخصي لأنيميا أو نقص حديد سابق',
];

export const SmartHealthAssessmentScreen: React.FC<SmartHealthAssessmentScreenProps> = ({
  profile,
  scans,
  labs,
  riskAssessments,
  reports,
  onSaveAssessment,
}) => {
  const existingIntake: VoluntaryHealthIntake = profile.intake || {
    symptoms: [],
    personalHistory: [],
    familyHistory: [],
    dailyActivityLevel: 'moderate',
  };

  const [age, setAge] = useState<string>(existingIntake.age ? String(existingIntake.age) : '');
  const [gender, setGender] = useState<'male' | 'female' | ''>(existingIntake.gender || '');
  const [heightCm, setHeightCm] = useState<string>(profile.heightCm ? String(profile.heightCm) : '');
  const [weightKg, setWeightKg] = useState<string>(profile.weightKg ? String(profile.weightKg) : '');
  const [sleepHours, setSleepHours] = useState<string>(
    existingIntake.sleepHours ? String(existingIntake.sleepHours) : ''
  );
  const [activity, setActivity] = useState<'low' | 'moderate' | 'high' | ''>(
    existingIntake.dailyActivityLevel || 'moderate'
  );
  const [symptoms, setSymptoms] = useState<string[]>(existingIntake.symptoms || []);
  const [familyHistory, setFamilyHistory] = useState<string[]>([
    ...(existingIntake.familyHistory || []),
    ...(existingIntake.personalHistory || []),
  ]);
  const [notes, setNotes] = useState<string>(existingIntake.notes || '');
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [savedNotice, setSavedNotice] = useState<string | null>(null);

  useEffect(() => {
    const inc = profile.intake;
    setAge(inc?.age ? String(inc.age) : '');
    setGender(inc?.gender || '');
    setHeightCm(profile.heightCm ? String(profile.heightCm) : '');
    setWeightKg(profile.weightKg ? String(profile.weightKg) : '');
    setSleepHours(inc?.sleepHours ? String(inc.sleepHours) : '');
    setActivity(inc?.dailyActivityLevel || 'moderate');
    setSymptoms(inc?.symptoms || []);
    setFamilyHistory([...(inc?.familyHistory || []), ...(inc?.personalHistory || [])]);
    setNotes(inc?.notes || '');
  }, [profile.userId, profile.heightCm, profile.weightKg, profile.intake?.updatedAt]);

  const toggleItem = (list: string[], setList: (next: string[]) => void, item: string) => {
    setList(list.includes(item) ? list.filter((x) => x !== item) : [...list, item]);
  };

  const liveBmi = calculateRealBmi(
    heightCm ? Number(heightCm) : undefined,
    weightKg ? Number(weightKg) : undefined
  );

  const handleRunDeterministicEngine = (e: React.FormEvent) => {
    e.preventDefault();
    setErrorMsg(null);
    setSavedNotice(null);

    const ageNum = age.trim() ? Number(age) : undefined;
    if (ageNum !== undefined && (!Number.isFinite(ageNum) || ageNum < 10 || ageNum > 110)) {
      setErrorMsg('يرجى إدخال عمر صحيح بين 10 و110 سنوات.');
      return;
    }

    const hNum = heightCm.trim() ? Number(heightCm) : undefined;
    if (hNum !== undefined && (!Number.isFinite(hNum) || hNum < 80 || hNum > 250)) {
      setErrorMsg('يرجى إدخال طول صحيح بالسنتيمتر (بين 80 و250 سم).');
      return;
    }

    const wNum = weightKg.trim() ? Number(weightKg) : undefined;
    if (wNum !== undefined && (!Number.isFinite(wNum) || wNum < 15 || wNum > 350)) {
      setErrorMsg('يرجى إدخال وزن صحيح بالكيلوجرام (بين 15 و350 كجم).');
      return;
    }

    const sleepNum = sleepHours.trim() ? Number(sleepHours) : undefined;
    if (sleepNum !== undefined && (!Number.isFinite(sleepNum) || sleepNum < 2 || sleepNum > 16)) {
      setErrorMsg('يرجى إدخال ساعات نوم صحيحة (بين 2 و16 ساعة).');
      return;
    }

    const intakePayload: VoluntaryHealthIntake = {
      age: ageNum,
      gender,
      symptoms,
      personalHistory: familyHistory.filter((x) => x.includes('شخصي')),
      familyHistory: familyHistory.filter((x) => !x.includes('شخصي')),
      dailyActivityLevel: activity,
      sleepHours: sleepNum,
      notes: notes.trim(),
      updatedAt: new Date().toISOString(),
    };

    if (!intakePayload.age && symptoms.length === 0 && familyHistory.length === 0 && liveBmi === null && labs.length === 0) {
      setErrorMsg('يرجى إدخال العمر أو الطول والوزن الفعليين أو الأعراض الطوعية ليحسب المحرك عوامل الخطورة بدقة.');
      return;
    }

    // Pure Deterministic Rules Engine (SABQ SMART RISK ENGINE) — Zero random/AI guessing
    const assessments = runSabqSmartRiskEngine({
      userId: profile.userId,
      intake: intakePayload,
      heightCm: hNum,
      weightKg: wNum,
      labs,
    });

    const nowIso = new Date().toISOString();
    const createdReports: ReportRecord[] = assessments.map((r) => {
      const rType = r.sectionId as ReportCategoryType;
      return {
        id: `rep_${r.category}_${Date.now()}_${Math.random().toString(36).slice(2, 5)}`,
        userId: profile.userId,
        reportType: rType,
        title: r.titleAr,
        createdAt: nowIso,
        scansCount: 0,
        labsCount: labs.length,
        comparisonSummary: `تقييم حتمي (${r.resultClassification}) بناءً على: ${r.usedData.join('، ') || 'بيانات أولية'}.`,
        sourceLabel: r.source,
        snapshotScans: [],
        snapshotLabs: labs,
        snapshotRisks: [r],
      };
    });

    let bmiScan: HealthScanRecord | undefined;
    if (liveBmi !== null) {
      const bmiClass = classifyBmiAr(liveBmi);
      bmiScan = {
        id: `scan_bmi_${Date.now()}`,
        userId: profile.userId,
        scanType: 'BODY_BMI',
        scanTitleAr: 'الوزن ومؤشر كتلة الجسم (BMI)',
        summary: `مؤشر كتلة الجسم المحسوب: ${liveBmi} (${bmiClass}) من الطول ${heightCm} سم والوزن ${weightKg} كجم.`,
        observations: [`BMI = ${weightKg} ÷ (${(Number(heightCm) / 100).toFixed(2)})² = ${liveBmi}`],
        potentialIssues: [`التصنيف: ${bmiClass}`],
        severityLevel: bmiClass,
        limitations: 'حساب رياضي مباشر يعتمد على دقة الطول والوزن المدخلين.',
        recommendations: ['الحفاظ على نشاط بدني منتظم وتغذية متوازنة.'],
        doctorConsultationCriteria: 'استشر طبيبًا عند التغير المفاجئ في الوزن.',
        confidenceScore: 1,
        isNonDiagnosticEstimate: false,
        source: 'حساب رياضي مباشر من إدخال المستخدم الفعلي',
        measuredMetric: { label: 'BMI', value: String(liveBmi), unit: 'kg/m²' },
        timestamp: nowIso,
      };
    }

    onSaveAssessment(
      { heightCm: hNum ?? profile.heightCm, weightKg: wNum ?? profile.weightKg, intake: intakePayload },
      assessments,
      createdReports,
      bmiScan
    );
    setSavedNotice('تم حساب التقييم الصحي الحتمي (SABQ SMART RISK ENGINE) وحفظ التقارير بنجاح.');
  };

  const downloadRiskPdf = async (risk: RiskAssessmentResult) => {
    const rType = risk.sectionId as ReportCategoryType;
    const { blob, filename } = await generateSabqArabicPdfBlob({
      profile,
      reportType: rType,
      scans,
      labs,
      riskAssessments: [risk],
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
  };

  return (
    <div className="space-y-6 pb-8">
      <div className="rounded-3xl border border-cyan-500/30 bg-[#0C0E1A] p-5 space-y-2">
        <div className="flex items-center gap-2.5">
          <ClipboardCheck className="h-6 w-6 text-cyan-400" />
          <h2 className="text-lg font-bold text-white">
            SABQ SMART RISK ENGINE — محرك التقييم الصحي الحتمي
          </h2>
        </div>
        <p className="text-xs leading-relaxed text-slate-300">
          يعمل هذا المحرك بقواعد طبية واضحة وحتمية دون تخمين الذكاء الاصطناعي ودون أي أرقام تحاليل مخترعة، ويوضح دائمًا البيانات المستخدمة والمعلومات الناقصة وهل النتيجة تقييم خطورة أم قياسًا فعليًا.
        </p>
      </div>

      <form
        onSubmit={handleRunDeterministicEngine}
        className="rounded-3xl border border-violet-500/25 bg-[#0C0E1A] p-5 space-y-5"
      >
        <h3 className="text-base font-bold text-white">1. البيانات الصحية الطوعية</h3>

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <div>
            <label className="mb-1 block text-xs text-slate-300">العمر (سنة)</label>
            <input type="number" min="10" max="110" value={age} onChange={(e) => setAge(e.target.value)} placeholder="مثال: 35" className="num-en w-full rounded-xl border border-slate-700 bg-[#06070D] px-3 py-2 text-sm text-white" />
          </div>

          <div>
            <label className="mb-1 block text-xs text-slate-300">الجنس</label>
            <select value={gender} onChange={(e) => setGender(e.target.value as any)} className="w-full rounded-xl border border-slate-700 bg-[#06070D] px-3 py-2 text-sm text-white">
              <option value="">اختياري</option>
              <option value="male">ذكر</option>
              <option value="female">أنثى</option>
            </select>
          </div>

          <div>
            <label className="mb-1 block text-xs text-slate-300">الطول الفعلي (سم)</label>
            <input type="number" min="80" max="250" value={heightCm} onChange={(e) => setHeightCm(e.target.value)} placeholder="مثال: 175" className="num-en w-full rounded-xl border border-slate-700 bg-[#06070D] px-3 py-2 text-sm text-white" />
          </div>

          <div>
            <label className="mb-1 block text-xs text-slate-300">الوزن الفعلي (كجم)</label>
            <input type="number" min="15" max="350" value={weightKg} onChange={(e) => setWeightKg(e.target.value)} placeholder="مثال: 75" className="num-en w-full rounded-xl border border-slate-700 bg-[#06070D] px-3 py-2 text-sm text-white" />
          </div>
        </div>

        {liveBmi !== null && (
          <div className="flex items-center gap-2 rounded-2xl border border-emerald-500/35 bg-emerald-950/20 px-4 py-2.5 text-xs text-emerald-200">
            <Scale className="h-4 w-4 text-emerald-400" />
            <span>
              مؤشر كتلة الجسم الفعلي (BMI = الوزن ÷ مربع الطول):{' '}
              <strong className="num-en text-white">{liveBmi}</strong> ({classifyBmiAr(liveBmi)})
            </span>
          </div>
        )}

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <div>
            <label className="mb-1 block text-xs text-slate-300">مستوى النشاط اليومي</label>
            <select value={activity} onChange={(e) => setActivity(e.target.value as any)} className="w-full rounded-xl border border-slate-700 bg-[#06070D] px-3 py-2 text-sm text-white">
              <option value="low">منخفض (جلوس معظم اليوم)</option>
              <option value="moderate">متوسط (مشي وحركة معتدلة)</option>
              <option value="high">نشط (رياضة منتظمة)</option>
            </select>
          </div>
          <div>
            <label className="mb-1 block text-xs text-slate-300">متوسط ساعات النوم</label>
            <input type="number" step="0.5" min="2" max="16" value={sleepHours} onChange={(e) => setSleepHours(e.target.value)} placeholder="مثال: 7" className="num-en w-full rounded-xl border border-slate-700 bg-[#06070D] px-3 py-2 text-sm text-white" />
          </div>
        </div>

        <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
          <div className="space-y-2 rounded-2xl border border-slate-800 bg-[#080A12] p-4">
            <div className="text-xs font-bold text-cyan-300">الأعراض الحالية (اختياري)</div>
            {SYMPTOM_OPTIONS.map((sym) => (
              <label key={sym} className="flex items-center gap-2 text-xs text-slate-200">
                <input type="checkbox" checked={symptoms.includes(sym)} onChange={() => toggleItem(symptoms, setSymptoms, sym)} className="h-4 w-4 accent-cyan-400" />
                <span>{sym}</span>
              </label>
            ))}
          </div>

          <div className="space-y-2 rounded-2xl border border-slate-800 bg-[#080A12] p-4">
            <div className="text-xs font-bold text-violet-300">التاريخ العائلي أو الشخصي (اختياري)</div>
            {HISTORY_OPTIONS.map((hist) => (
              <label key={hist} className="flex items-center gap-2 text-xs text-slate-200">
                <input type="checkbox" checked={familyHistory.includes(hist)} onChange={() => toggleItem(familyHistory, setFamilyHistory, hist)} className="h-4 w-4 accent-violet-400" />
                <span>{hist}</span>
              </label>
            ))}
          </div>
        </div>

        <input
          type="text"
          value={notes}
          onChange={(e) => setNotes(e.target.value)}
          placeholder="ملاحظات إضافية تود أخذها بالاعتبار..."
          className="w-full rounded-xl border border-slate-700 bg-[#06070D] px-3.5 py-2 text-xs text-white"
        />

        {errorMsg && (
          <div className="flex items-center gap-2 rounded-xl border border-rose-500/40 bg-rose-500/10 p-3 text-xs text-rose-200">
            <AlertTriangle className="h-4 w-4 shrink-0" />
            <span>{errorMsg}</span>
          </div>
        )}
        {savedNotice && (
          <div className="flex items-center gap-2 rounded-xl border border-emerald-500/40 bg-emerald-500/10 p-3 text-xs text-emerald-200">
            <CheckCircle2 className="h-4 w-4 shrink-0" />
            <span>{savedNotice}</span>
          </div>
        )}

        <button
          type="submit"
          className="flex min-h-[48px] w-full items-center justify-center gap-2 rounded-xl bg-gradient-to-l from-violet-600 to-cyan-500 px-5 py-3 text-sm font-bold text-white"
        >
          <ShieldAlert className="h-5 w-5" />
          <span>تشغيل محرك التقييم الحتمي وحفظ التقارير</span>
        </button>
      </form>

      {riskAssessments.length > 0 && (
        <div className="space-y-4">
          <h3 className="text-base font-bold text-white">نتائج SABQ SMART RISK ENGINE الشفافة</h3>
          <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
            {riskAssessments.map((item) => (
              <div
                key={item.id}
                className="rounded-3xl border border-violet-500/25 bg-[#0C0E1A] p-4 space-y-2.5 text-xs"
              >
                <div className="flex items-center justify-between gap-2">
                  <div>
                    <h4 className="text-sm font-bold text-white">{item.titleAr}</h4>
                    <span className="text-[10px] text-cyan-300">{item.resultClassification}</span>
                  </div>
                  <span
                    className={`rounded-lg px-2.5 py-1 text-[11px] font-bold ${
                      item.riskLevelAr === 'منخفض'
                        ? 'bg-emerald-500/20 text-emerald-300'
                        : item.riskLevelAr === 'متوسط'
                        ? 'bg-amber-500/20 text-amber-300'
                        : item.riskLevelAr === 'مرتفع'
                        ? 'bg-rose-500/20 text-rose-300'
                        : 'bg-slate-700/40 text-slate-300'
                    }`}
                  >
                    {item.riskLevelAr}
                  </span>
                </div>

                <p className="text-slate-200 leading-relaxed">{item.summary}</p>
                <div className="text-slate-300">
                  <strong>البيانات المستخدمة:</strong> {item.usedData?.join(' · ') || 'لا يوجد'}
                </div>
                <div className="text-amber-200">
                  <strong>عوامل رفعت الخطورة:</strong> {item.elevatingFactors?.join(' · ') || 'لا توجد عوامل مرتفعة'}
                </div>
                <div className="text-slate-400">
                  <strong>المعلومات الناقصة:</strong> {item.missingInformation?.join(' · ') || 'مكتمل'}
                </div>
                <div className="text-emerald-200">
                  <strong>الخطوة التالية المناسبة:</strong> {item.nextStepAr}
                </div>

                <button
                  onClick={() => downloadRiskPdf(item)}
                  className="flex min-h-[38px] w-full items-center justify-center gap-1.5 rounded-xl border border-cyan-500/40 bg-cyan-500/10 px-3 py-1.5 font-bold text-cyan-200 hover:bg-cyan-500/20"
                >
                  <FileDown className="h-3.5 w-3.5" />
                  <span>تنزيل تقرير PDF مستقل</span>
                </button>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
};
