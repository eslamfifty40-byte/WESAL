export type ActiveTab =
  | 'home'
  | 'scan'
  | 'assessment'
  | 'assistant'
  | 'reports'
  | 'memory'
  | 'labs'
  | 'settings';

export type CertaintyLevel = 'CONFIRMED' | 'EXPECTATION' | 'INFERENCE';

export type MemoryCategory =
  | 'PERSONAL'
  | 'PROJECTS'
  | 'HEALTH'
  | 'CONVERSATIONS'
  | 'RECOMMENDATIONS';

export type HealthSectionId =
  | 'GLUCOSE'
  | 'BP'
  | 'HBA1C'
  | 'CBC'
  | 'THYROID'
  | 'PULSE'
  | 'RESPIRATORY_SPO2'
  | 'BMI'
  | 'BODY_COMPOSITION'
  | 'LIVER_KIDNEY'
  | 'FACE_EYE_SKIN'
  | 'HAIR_SCALP';

export type ScanCategoryType =
  | 'FACE'
  | 'EYE'
  | 'SKIN'
  | 'HAIR'
  | 'BODY_POSTURE'
  | 'BODY_BMI'
  | 'BIOFINGER_PPG'
  | 'RESPIRATORY'
  | 'FACE_EYE_SKIN'
  | 'HAIR_SCALP';

export type RiskAssessmentCategory =
  | 'DIABETES_RISK'
  | 'BP_RISK'
  | 'HBA1C_RISK'
  | 'ANEMIA_CBC_RISK'
  | 'THYROID_RISK'
  | 'HEART_RISK'
  | 'LIVER_KIDNEY_RISK'
  | 'GENERAL_HEALTH';

export type ReportCategoryType =
  | HealthSectionId
  | ScanCategoryType
  | RiskAssessmentCategory
  | 'THYROID_BLOOD_RISK'
  | 'LABS'
  | 'COMPREHENSIVE';

export interface VoluntaryHealthIntake {
  age?: number;
  gender?: 'male' | 'female' | '';
  symptoms: string[];
  personalHistory: string[];
  familyHistory: string[];
  dailyActivityLevel: 'low' | 'moderate' | 'high' | '';
  sleepHours?: number;
  notes?: string;
  updatedAt?: string;
}

export interface RiskAssessmentResult {
  id: string;
  userId: string;
  category: RiskAssessmentCategory | 'THYROID_BLOOD_RISK';
  sectionId: HealthSectionId;
  titleAr: string;
  riskLevelAr: 'منخفض' | 'متوسط' | 'مرتفع' | 'بيانات غير كافية';
  resultClassification: 'تقييم خطورة غير تشخيصي' | 'قياس فعلي موثق';
  usedData: string[];
  elevatingFactors: string[];
  missingInformation: string[];
  summary: string;
  nextStepAr: string;
  recommendations: string[];
  doctorConsultationCriteria: string;
  source: string;
  timestamp: string;
}

export interface UserProfile {
  userId: string;
  displayName: string;
  heightCm?: number;
  weightKg?: number;
  intake?: VoluntaryHealthIntake;
  createdAt: string;
}

export interface UploadedImageRecord {
  id: string;
  userId: string;
  sessionId?: string;
  sectionId: HealthSectionId | 'SMART_SCAN';
  filename: string;
  url: string;
  mimeType: string;
  sizeBytes: number;
  sourceOrigin: 'camera' | 'gallery' | 'file';
  uploadedAt: string;
}

export interface ScanSessionRecord {
  id: string;
  userId: string;
  startedAt: string;
  completedAt?: string;
  currentPhase: string;
  stageStatus: Record<string, boolean>;
  imageIds: string[];
}

export interface MemoryItem {
  id: string;
  userId: string;
  category: MemoryCategory;
  certainty: CertaintyLevel;
  title: string;
  content: string;
  source: string;
  createdAt: string;
  updatedAt: string;
}

export interface ChatMessage {
  id: string;
  userId: string;
  role: 'user' | 'assistant';
  content: string;
  certainty?: CertaintyLevel;
  imagePreview?: string;
  timestamp: string;
}

export interface HealthScanRecord {
  id: string;
  sessionId?: string;
  imageId?: string;
  userId: string;
  scanType: ScanCategoryType;
  scanTitleAr: string;
  summary: string;
  observations: string[];
  potentialIssues: string[];
  severityLevel?: string;
  limitations?: string;
  recommendations: string[];
  doctorConsultationCriteria: string;
  confidenceScore: number | null;
  isNonDiagnosticEstimate: boolean;
  source: string;
  isAreaVisible?: boolean;
  angleGuidanceAr?: string;
  measuredMetric?: {
    label: string;
    value: string;
    unit: string;
  };
  imageUrl?: string;
  savedImageDataUrl?: string;
  timestamp: string;
}

export interface LabReading {
  id: string;
  userId: string;
  sectionId: HealthSectionId;
  testCode: string;
  testNameAr: string;
  measuredValue: string;
  unit: string;
  subMetrics?: Record<string, string>;
  source: string;
  explanationAr?: string;
  notes?: string;
  recordedAt: string;
}

export interface ReportRecord {
  id: string;
  userId: string;
  sessionId?: string;
  reportType: ReportCategoryType;
  title: string;
  createdAt: string;
  scansCount: number;
  labsCount: number;
  comparisonSummary: string;
  sourceLabel?: string;
  referenceImageUrl?: string;
  referenceImageDataUrl?: string;
  snapshotScans: HealthScanRecord[];
  snapshotLabs: LabReading[];
  snapshotRisks?: RiskAssessmentResult[];
}

export interface UserSettings {
  voiceEnabled: boolean;
  voiceVolume: number;
  darkMode: boolean;
  fontScale: 'sm' | 'md' | 'lg';
  notificationsEnabled: boolean;
  quietHoursEnabled: boolean;
  quietStartHour: number;
  quietEndHour: number;
  cameraAllowed: boolean;
  micAllowed: boolean;
  biometricLockEnabled: boolean;
}

export interface UserWorkspaceData {
  profile: UserProfile;
  settings: UserSettings;
  sessions: ScanSessionRecord[];
  images: UploadedImageRecord[];
  memories: MemoryItem[];
  chats: ChatMessage[];
  scans: HealthScanRecord[];
  labs: LabReading[];
  riskAssessments: RiskAssessmentResult[];
  reports: ReportRecord[];
}

export interface ClinicalLabDefinition {
  code: string;
  sectionId: HealthSectionId;
  nameAr: string;
  unit: string;
  placeholder: string;
  descriptionAr: string;
}

export const HEALTH_CENTER_SECTIONS: Array<{
  id: HealthSectionId;
  titleAr: string;
  shortAr: string;
  kind: 'MEASUREMENT_AND_RISK' | 'BODY_METRIC' | 'VISUAL_UPLOAD';
  summaryAr: string;
}> = [
  { id: 'GLUCOSE', titleAr: '1. السكر وسكر الدم', shortAr: 'سكر الدم', kind: 'MEASUREMENT_AND_RISK', summaryAr: 'تقييم خطورة السكري ومتابعة القراءات الفعلية وتطورها' },
  { id: 'BP', titleAr: '2. ضغط الدم', shortAr: 'ضغط الدم', kind: 'MEASUREMENT_AND_RISK', summaryAr: 'الضغط الانقباضي والانبساطي وتقييم الخطورة (لا يُقاس بالكاميرا)' },
  { id: 'HBA1C', titleAr: '3. السكر التراكمي HbA1c', shortAr: 'التراكمي', kind: 'MEASUREMENT_AND_RISK', summaryAr: 'متابعة نسبة التحليل المخبري الفعلية أو تقييم الخطورة' },
  { id: 'CBC', titleAr: '4. الدم والأنيميا CBC', shortAr: 'صورة الدم', kind: 'MEASUREMENT_AND_RISK', summaryAr: 'الهيموجلوبين، كريات الدم الحمراء، البيضاء، والصفائح' },
  { id: 'THYROID', titleAr: '5. الغدة الدرقية', shortAr: 'الغدة الدرقية', kind: 'MEASUREMENT_AND_RISK', summaryAr: 'تسجيل TSH وT3 وT4 وتقييم الأعراض والعوامل' },
  { id: 'PULSE', titleAr: '6. القلب والنبض', shortAr: 'القلب والنبض', kind: 'MEASUREMENT_AND_RISK', summaryAr: 'النبض الضوئي PPG من الكاميرا أو الأجهزة الخارجية المعتمدة' },
  { id: 'RESPIRATORY_SPO2', titleAr: '7. التنفس والأكسجين', shortAr: 'التنفس وSpO2', kind: 'MEASUREMENT_AND_RISK', summaryAr: 'تقدير التنفس وتسجيل نسبة الأكسجين من الجهاز المعتمد' },
  { id: 'BMI', titleAr: '8. الوزن ومؤشر كتلة الجسم', shortAr: 'الوزن وBMI', kind: 'BODY_METRIC', summaryAr: 'حساب BMI تلقائيًا مع رسم بياني لتطور الوزن' },
  { id: 'BODY_COMPOSITION', titleAr: '9. الدهون وتركيب الجسم', shortAr: 'الدهون والعضلات', kind: 'BODY_METRIC', summaryAr: 'نسبة الدهون وكتلة العضلات المقاسة فعليًا وتطورها' },
  { id: 'LIVER_KIDNEY', titleAr: '10. وظائف الكبد والكلى', shortAr: 'الكبد والكلى', kind: 'MEASUREMENT_AND_RISK', summaryAr: 'ALT/AST/ALP/Bilirubin وCreatinine/eGFR/Urea' },
  { id: 'FACE_EYE_SKIN', titleAr: '11. الوجه والعين والبشرة', shortAr: 'الوجه والبشرة', kind: 'VISUAL_UPLOAD', summaryAr: 'التجاعيد، الحبوب، التصبغات، الهالات، الانتفاخ، المسام، والروتين' },
  { id: 'HAIR_SCALP', titleAr: '12. الشعر وفروة الرأس', shortAr: 'الشعر والفروة', kind: 'VISUAL_UPLOAD', summaryAr: 'الكثافة الظاهرة، الفراغات، خط الشعر، القشرة، والمقارنة بالصور السابقة' },
];

export const CLINICAL_LAB_MODULES: ClinicalLabDefinition[] = [
  {
    code: 'GLUCOSE',
    sectionId: 'GLUCOSE',
    nameAr: 'سكر الدم',
    unit: 'mg/dL',
    placeholder: 'مثال: 95',
    descriptionAr: 'يتطلب جهاز قياس سكر الدم أو تحليل مختبر معتمد — لا يُستخرج من الوجه أو البصمة.',
  },
  {
    code: 'BP',
    sectionId: 'BP',
    nameAr: 'ضغط الدم',
    unit: 'mmHg',
    placeholder: 'مثال: 120/80',
    descriptionAr: 'يتطلب جهاز قياس ضغط دم معتمد (انقباضي / انبساطي) — لا يُخترع من الكاميرا.',
  },
  {
    code: 'HBA1C',
    sectionId: 'HBA1C',
    nameAr: 'السكر التراكمي HbA1c',
    unit: '%',
    placeholder: 'مثال: 5.4',
    descriptionAr: 'تحليل دم مخبري يقيس متوسط السكر خلال 3 أشهر.',
  },
  {
    code: 'CBC',
    sectionId: 'CBC',
    nameAr: 'صورة الدم الكاملة CBC',
    unit: 'مركب',
    placeholder: 'Hb / RBC / WBC / Platelets',
    descriptionAr: 'إدخال الهيموجلوبين وكريات الدم الحمراء والبيضاء والصفائح من المختبر.',
  },
  {
    code: 'THYROID',
    sectionId: 'THYROID',
    nameAr: 'الغدة الدرقية (TSH / T3 / T4)',
    unit: 'mIU/L',
    placeholder: 'مثال: 2.1',
    descriptionAr: 'تحليل هرمونات الغدة الدرقية بالمختبر الطبي.',
  },
  {
    code: 'PULSE',
    sectionId: 'PULSE',
    nameAr: 'النبض',
    unit: 'bpm',
    placeholder: 'مثال: 72',
    descriptionAr: 'قياس ضوئي PPG عند جودة الإشارة المناسبة أو من جهاز قياس خارجي.',
  },
  {
    code: 'SPO2',
    sectionId: 'RESPIRATORY_SPO2',
    nameAr: 'التنفس والأكسجين (SpO2)',
    unit: '%',
    placeholder: 'مثال: 98',
    descriptionAr: 'قراءة SpO2 من جهاز تأكسج نبضي أو ساعة معتمدة، وتقدير التنفس عند توفر البيانات.',
  },
  {
    code: 'LIVER',
    sectionId: 'LIVER_KIDNEY',
    nameAr: 'وظائف الكبد (ALT / AST / ALP / Bilirubin)',
    unit: 'U/L',
    placeholder: 'نتائج الإنزيمات',
    descriptionAr: 'تحاليل الكبد المخبرية الفعلية وتقييم العوامل المرتبطة.',
  },
  {
    code: 'KIDNEY',
    sectionId: 'LIVER_KIDNEY',
    nameAr: 'وظائف الكلى (Creatinine / eGFR / Urea)',
    unit: 'mg/dL',
    placeholder: 'نتائج الكلى',
    descriptionAr: 'تحاليل الكلى المخبرية الفعلية وتقييم العوامل المرتبطة.',
  },
];

export const OFFICIAL_SABQ_ICON =
  '/assets/aistudio/file_000000009ab48246a96b09825f10731a_3efd1f9a.png';
