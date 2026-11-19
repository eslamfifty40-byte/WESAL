import { useEffect, useState } from 'react';
import {
  CheckCircle2,
  Crown,
  Download,
  Eye,
  EyeOff,
  FolderArchive,
  Loader2,
  Lock,
  LockOpen,
  MessageSquare,
  Search,
  ShieldCheck,
  Smartphone,
  Sparkles,
  UserPlus,
} from 'lucide-react';

interface ReleaseInfo {
  zipAvailable: boolean;
  zipSizeBytes: number;
  apkAvailable: boolean;
  apkSizeBytes: number;
  packageName: string;
}

type PreviewTab = 'login' | 'chats' | 'badges';

const VERIFICATION_SEALS = [
  { key: 'BLUE', label: 'الزرقاء', hex: '#1D9BF0', stroke: '#1D9BF0' },
  { key: 'BLACK', label: 'السمراء', hex: '#0B0E11', stroke: '#E2E8F0' },
  { key: 'GREEN', label: 'الخضراء', hex: '#00D66B', stroke: '#00D66B' },
  { key: 'GOLD', label: 'الذهبية', hex: '#E5A100', stroke: '#FDE68A' },
  { key: 'CYAN', label: 'السماوية', hex: '#00B8D4', stroke: '#00B8D4' },
  { key: 'PURPLE', label: 'البنفسجية', hex: '#7C3AED', stroke: '#7C3AED' },
  { key: 'RED', label: 'الحمراء', hex: '#DC2626', stroke: '#DC2626' },
  { key: 'SILVER', label: 'الفضية', hex: '#64748B', stroke: '#CBD5E1' },
  { key: 'ROSE', label: 'الوردية', hex: '#DB2777', stroke: '#DB2777' },
  { key: 'ORANGE', label: 'البرتقالية', hex: '#EA580C', stroke: '#EA580C' },
] as const;

const SEAL_STAR_POINTS = Array.from({ length: 24 }, (_, i) => {
  const angle = (Math.PI * 2 * i) / 24 - Math.PI / 2;
  const r = i % 2 === 0 ? 11.04 : 9.12;
  const x = (12 + r * Math.cos(angle)).toFixed(2);
  const y = (12 + r * Math.sin(angle)).toFixed(2);
  return `${x},${y}`;
}).join(' ');

function WesalSealSvg({
  hex,
  stroke,
  className = 'w-4 h-4',
}: {
  hex: string;
  stroke?: string;
  className?: string;
}) {
  return (
    <svg viewBox="0 0 24 24" className={`${className} shrink-0`} aria-hidden="true">
      <polygon
        points={SEAL_STAR_POINTS}
        fill={hex}
        stroke={stroke ?? hex}
        strokeWidth="1.2"
        strokeLinejoin="round"
      />
      <path
        d="M7 12.5 L10.6 16.1 L17.3 8.6"
        fill="none"
        stroke="#FFFFFF"
        strokeWidth="2.4"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

const SAMPLE_CHATS = [
  {
    id: 'official',
    name: 'WESAL Messenger',
    preview: 'إشعارات وصال الرسمية والتحديثات المعتمدة',
    time: 'الآن',
    sealHex: '#1D9BF0',
    hidden: false,
    unread: 1,
  },
  {
    id: 'admin2',
    name: 'إدارة الدعم والأمان',
    preview: 'تم التحقق من الحساب رقم ID: 10002',
    time: '10:15 م',
    sealHex: '#F59E0B',
    hidden: false,
    unread: 0,
  },
  {
    id: 'hidden1',
    name: 'محادثة مشفّرة خاصة',
    preview: 'محتوى محمي بكلمة مرور المحادثات المخفية',
    time: '09:42 م',
    sealHex: '#00D9E8',
    hidden: true,
    unread: 2,
  },
] as const;

function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '—';
  const mb = bytes / (1024 * 1024);
  if (mb >= 1) return `${mb.toFixed(1)} MB`;
  return `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

export default function App() {
  const [releaseInfo, setReleaseInfo] = useState<ReleaseInfo | null>(null);
  const [downloadingZip, setDownloadingZip] = useState(false);
  const [downloadingApk, setDownloadingApk] = useState(false);
  const [downloadFeedback, setDownloadFeedback] = useState<string | null>(null);
  const [downloadError, setDownloadError] = useState<string | null>(null);

  const [previewTab, setPreviewTab] = useState<PreviewTab>('login');
  const [authMode, setAuthMode] = useState<'signin' | 'signup'>('signin');
  const [showPasswordPreview, setShowPasswordPreview] = useState(false);
  const [hiddenUnlocked, setHiddenUnlocked] = useState(false);
  const [chatSearch, setChatSearch] = useState('');
  const [selectedSealKey, setSelectedSealKey] = useState<string>('BLUE');

  useEffect(() => {
    let active = true;
    fetch('/api/release-info')
      .then((res) => (res.ok ? res.json() : null))
      .then((data: ReleaseInfo | null) => {
        if (active && data) setReleaseInfo(data);
      })
      .catch(() => {
        // Silent fallback if endpoint is unavailable during static build preview
      });
    return () => {
      active = false;
    };
  }, []);

  async function triggerTrackedDownload(
    url: string,
    filename: string,
    setBusy: (busy: boolean) => void
  ) {
    setDownloadError(null);
    setDownloadFeedback(null);
    setBusy(true);
    try {
      const response = await fetch(url);
      if (!response.ok) {
        throw new Error(
          response.status === 404
            ? `الملف ${filename} غير متوفر بعد على الخادم.`
            : `تعذر بدء التنزيل (رمز الحالة ${response.status}).`
        );
      }
      const blob = await response.blob();
      const objectUrl = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = objectUrl;
      anchor.download = filename;
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      setTimeout(() => URL.revokeObjectURL(objectUrl), 15000);
      setDownloadFeedback(`بدأ تنزيل ${filename} (${formatBytes(blob.size)})`);
    } catch (err) {
      setDownloadError(err instanceof Error ? err.message : 'حدث خطأ أثناء تحضير الملف للتنزيل.');
    } finally {
      setBusy(false);
    }
  }

  const visibleChats = SAMPLE_CHATS.filter((c) => {
    const modeMatch = hiddenUnlocked ? c.hidden : !c.hidden;
    const queryMatch = !chatSearch.trim() || c.name.includes(chatSearch.trim()) || c.preview.includes(chatSearch.trim());
    return modeMatch && queryMatch;
  });

  const activeSeal = VERIFICATION_SEALS.find((s) => s.key === selectedSealKey) ?? VERIFICATION_SEALS[0];

  return (
    <div dir="rtl" className="min-h-screen bg-[#06090B] text-[#F8FAFC] flex flex-col">
      {/* Zone 1 - Top Bar Contract: Brand wordmark — Concise nav links — 1 Primary Action */}
      <header className="border-b border-[#1E2B33] bg-[#0B1115]/95 backdrop-blur-md sticky top-0 z-30">
        <div className="max-w-6xl mx-auto px-5 sm:px-8 h-16 flex items-center justify-between gap-8">
          <a href="#top" className="text-lg font-bold tracking-tight text-white whitespace-nowrap shrink-0">
            WESAL Messenger
          </a>

          <nav className="hidden md:flex items-center gap-6 text-sm font-medium text-[#A0B3BE]">
            <a href="#preview" className="hover:text-[#00D9E8] transition-colors whitespace-nowrap shrink-0">
              معاينة التطبيق
            </a>
            <a href="#downloads" className="hover:text-[#00D9E8] transition-colors whitespace-nowrap shrink-0">
              التنزيلات
            </a>
            <a href="#security" className="hover:text-[#00D9E8] transition-colors whitespace-nowrap shrink-0">
              الأمان والتشفير
            </a>
            <a href="#seals" className="hover:text-[#00D9E8] transition-colors whitespace-nowrap shrink-0">
              التوثيق والإطارات
            </a>
          </nav>

          <button
            type="button"
            disabled={downloadingZip}
            onClick={() => void triggerTrackedDownload('/WesalAndroid.zip', 'WesalAndroid.zip', setDownloadingZip)}
            className="inline-flex items-center justify-center gap-2 px-4 py-2 rounded-xl bg-[#00D66B] hover:bg-[#00b95b] disabled:opacity-75 text-[#06090B] text-xs font-bold transition-colors whitespace-nowrap shrink-0 cursor-pointer"
          >
            {downloadingZip ? <Loader2 className="w-4 h-4 animate-spin" /> : <Download className="w-4 h-4" />}
            <span>{downloadingZip ? 'جاري التحضير...' : 'تنزيل الحزمة'}</span>
          </button>
        </div>
      </header>

      <main id="top" className="flex-1 max-w-6xl w-full mx-auto px-5 sm:px-8 py-10 lg:py-14 space-y-16">
        {/* Hero Split + Interactive WESAL Mobile Preview */}
        <section id="preview" className="grid grid-cols-1 lg:grid-cols-12 gap-10 lg:gap-12 items-start">
          <div className="lg:col-span-6 space-y-6">
            <div className="flex items-center gap-4">
              <img
                src="/wesal-icon.webp"
                alt="شعار تطبيق WESAL Messenger"
                className="w-16 h-16 rounded-2xl border border-[#1E2B33] object-cover bg-[#0E1519]"
              />
              <div>
                <h1 className="text-3xl sm:text-4xl font-bold text-white leading-tight text-balance">
                  وصال — WESAL Messenger
                </h1>
                <p className="text-sm text-[#00D9E8] mt-1">
                  تطبيق مراسلة أندرويد أصلي · com.wesal.messenger
                </p>
              </div>
            </div>

            <p className="text-base text-[#CBD5E1] leading-relaxed max-w-2xl">
              منصة تواصل مشفّرة بالكامل بلغة Kotlin وJetpack Compose مع تشفير E2EE للمحادثات والوسائط،
              حماية المحادثات المخفية بكلمة مرور مستقلة، لوحة إدارة وصلاحيات مشرفين متكاملة، ومكتبة شارات وإطارات ملكية.
            </p>

            {/* Primary Download Hub */}
            <div id="downloads" className="rounded-2xl bg-[#0E1519] border border-[#1E2B33] p-5 space-y-4">
              <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-[#A0B3BE] tabular-nums">
                <span>الحزمة المصدرية: <strong className="text-white">WesalAndroid.zip</strong></span>
                <span>
                  {releaseInfo?.zipAvailable ? `الحجم: ${formatBytes(releaseInfo.zipSizeBytes)}` : 'Kotlin + Jetpack Compose'}
                  {' · '}
                  {releaseInfo?.apkAvailable ? `APK جاهز (${formatBytes(releaseInfo.apkSizeBytes)})` : 'Android 8.0+ (API 26)'}
                </span>
              </div>

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <button
                  type="button"
                  disabled={downloadingZip}
                  onClick={() => void triggerTrackedDownload('/WesalAndroid.zip', 'WesalAndroid.zip', setDownloadingZip)}
                  className="min-h-[48px] inline-flex items-center justify-center gap-2 rounded-xl bg-[#00D66B] hover:bg-[#00b95b] disabled:opacity-75 text-[#06090B] font-bold px-4 py-3 text-sm transition-colors cursor-pointer whitespace-nowrap"
                >
                  {downloadingZip ? (
                    <>
                      <Loader2 className="w-4 h-4 animate-spin" />
                      <span>جاري تحضير WesalAndroid.zip...</span>
                    </>
                  ) : (
                    <>
                      <FolderArchive className="w-4 h-4" />
                      <span>تنزيل WesalAndroid.zip</span>
                    </>
                  )}
                </button>

                <button
                  type="button"
                  disabled={downloadingApk || releaseInfo?.apkAvailable === false}
                  onClick={() => void triggerTrackedDownload('/download/app-debug.apk', 'WESAL-Messenger-debug.apk', setDownloadingApk)}
                  className="min-h-[48px] inline-flex items-center justify-center gap-2 rounded-xl bg-[#131D23] hover:bg-[#19262E] disabled:opacity-50 border border-[#00D9E8]/50 text-[#00D9E8] font-bold px-4 py-3 text-sm transition-colors cursor-pointer whitespace-nowrap"
                >
                  {downloadingApk ? (
                    <>
                      <Loader2 className="w-4 h-4 animate-spin" />
                      <span>جاري تحضير ملف APK...</span>
                    </>
                  ) : (
                    <>
                      <Smartphone className="w-4 h-4" />
                      <span>{releaseInfo?.apkAvailable ? 'تنزيل تطبيق WESAL (.apk)' : 'ملف APK قيد التجهيز'}</span>
                    </>
                  )}
                </button>
              </div>

              {downloadFeedback && (
                <div className="flex items-center gap-2 text-xs text-[#00D66B]">
                  <CheckCircle2 className="w-4 h-4 shrink-0" />
                  <span>{downloadFeedback}</span>
                </div>
              )}

              {downloadError && (
                <div className="text-xs text-[#FF5E73] bg-[#FF5E73]/10 border border-[#FF5E73]/30 rounded-xl px-3 py-2">
                  {downloadError}
                </div>
              )}
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 pt-2 text-sm text-[#CBD5E1]">
              <div className="border-t border-[#1E2B33] pt-3 space-y-1">
                <h2 className="font-semibold text-white">01. المعرّف والأدوار الإدارية</h2>
                <p className="text-xs text-[#A0B3BE] leading-relaxed">
                  توليد وبحث المعرفات الرقمية من 10000 حتى 1000000، مع عزل وحماية حسابي الإدارة (10001 و10002) وصلاحيات المشرفين المساعدين.
                </p>
              </div>
              <div className="border-t border-[#1E2B33] pt-3 space-y-1">
                <h2 className="font-semibold text-white">02. الخصوصية والوسائط المتعددة</h2>
                <p className="text-xs text-[#A0B3BE] leading-relaxed">
                  قفل المحادثات المخفية مع حفظ الجلسة أثناء التصفح، وإرسال عدة صور أو فيديوهات أو مستندات دفعة واحدة بتشفير كامل.
                </p>
              </div>
            </div>
          </div>

          {/* Interactive Mobile App Preview */}
          <div className="lg:col-span-6 flex flex-col items-center">
            <div className="w-full max-w-[390px] space-y-3">
              {/* Interactive Screen Selector */}
              <div className="grid grid-cols-3 gap-1 p-1 rounded-xl bg-[#0E1519] border border-[#1E2B33]">
                <button
                  type="button"
                  onClick={() => setPreviewTab('login')}
                  className={`py-2 px-2 rounded-lg text-xs font-semibold transition-colors whitespace-nowrap cursor-pointer ${
                    previewTab === 'login' ? 'bg-[#00D9E8] text-[#041014]' : 'text-[#A0B3BE] hover:text-white'
                  }`}
                >
                  تسجيل الدخول
                </button>
                <button
                  type="button"
                  onClick={() => setPreviewTab('chats')}
                  className={`py-2 px-2 rounded-lg text-xs font-semibold transition-colors whitespace-nowrap cursor-pointer ${
                    previewTab === 'chats' ? 'bg-[#00D9E8] text-[#041014]' : 'text-[#A0B3BE] hover:text-white'
                  }`}
                >
                  المحادثات المخفية
                </button>
                <button
                  type="button"
                  onClick={() => setPreviewTab('badges')}
                  className={`py-2 px-2 rounded-lg text-xs font-semibold transition-colors whitespace-nowrap cursor-pointer ${
                    previewTab === 'badges' ? 'bg-[#00D9E8] text-[#041014]' : 'text-[#A0B3BE] hover:text-white'
                  }`}
                >
                  الشارات والإطارات
                </button>
              </div>

              {/* Phone Viewport */}
              <div className="rounded-[28px] bg-[#070B0D] border border-[#1F2E37] p-5 shadow-2xl min-h-[540px] flex flex-col justify-between">
                {previewTab === 'login' && (
                  <div className="space-y-4">
                    {/* Centered Header matching PhoneLoginScreen.kt Item 9 */}
                    <div className="flex flex-col items-center text-center gap-2 pt-1">
                      <div className="w-16 h-16 rounded-2xl bg-[#0E1519] border border-[#00D9E8]/55 flex items-center justify-center p-1.5">
                        <img
                          src="/wesal-icon.webp"
                          alt="WESAL Messenger Logo"
                          className="w-full h-full rounded-xl object-cover"
                        />
                      </div>
                      <span className="text-sm font-bold text-[#00D9E8]">WESAL Messenger</span>
                      <h2 className="text-lg font-bold text-white">
                        {authMode === 'signin' ? 'مرحبًا بك في وِصال' : 'إنشاء حساب جديد'}
                      </h2>
                      <p className="text-xs text-[#A0B3BE]">تواصل مع من تحب، أينما كنت</p>
                    </div>

                    {/* Segmented Sign In / Sign Up */}
                    <div className="grid grid-cols-2 gap-1.5 p-1 rounded-xl bg-[#0E1519] border border-[#1F2E37]">
                      <button
                        type="button"
                        onClick={() => setAuthMode('signin')}
                        className={`h-10 rounded-lg text-xs font-bold transition-colors cursor-pointer ${
                          authMode === 'signin' ? 'bg-[#00D9E8] text-[#041014]' : 'text-[#A0B3BE]'
                        }`}
                      >
                        تسجيل الدخول
                      </button>
                      <button
                        type="button"
                        onClick={() => setAuthMode('signup')}
                        className={`h-10 rounded-lg text-xs font-bold transition-colors cursor-pointer ${
                          authMode === 'signup' ? 'bg-[#00D9E8] text-[#041014]' : 'text-[#A0B3BE]'
                        }`}
                      >
                        إنشاء حساب جديد
                      </button>
                    </div>

                    {/* Fields */}
                    <div className="space-y-2.5 text-xs">
                      <div className="rounded-xl bg-[#0E1519] border border-[#1F2E37] px-3.5 py-3 text-[#A0B3BE]">
                        البريد الإلكتروني
                      </div>
                      <div className="rounded-xl bg-[#0E1519] border border-[#1F2E37] px-3.5 py-3 flex items-center justify-between text-[#A0B3BE]">
                        <span>{showPasswordPreview ? 'Wesal@2026' : 'كلمة المرور'}</span>
                        <button
                          type="button"
                          onClick={() => setShowPasswordPreview((v) => !v)}
                          aria-label="إظهار أو إخفاء كلمة المرور"
                          className="text-[#A0B3BE] hover:text-white cursor-pointer"
                        >
                          {showPasswordPreview ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                        </button>
                      </div>
                    </div>

                    <div className="w-full rounded-xl bg-[#00D9E8] text-[#041014] text-center py-3 text-xs font-bold">
                      {authMode === 'signin' ? 'تسجيل الدخول إلى وِصال' : 'إنشاء الحساب والانضمام'}
                    </div>

                    <div className="w-full rounded-xl bg-[#0E1519] border border-[#1F2E37] text-white text-center py-2.5 text-xs font-semibold">
                      المتابعة باستخدام Google
                    </div>
                  </div>
                )}

                {previewTab === 'chats' && (
                  <div className="space-y-3">
                    <div className="flex items-center justify-between">
                      <span className="text-base font-bold text-white">المحادثات</span>
                      <button
                        type="button"
                        onClick={() => setHiddenUnlocked((u) => !u)}
                        className={`inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold transition-colors cursor-pointer ${
                          hiddenUnlocked
                            ? 'bg-[#00D66B]/20 text-[#00D66B]'
                            : 'bg-[#0E1519] border border-[#1F2E37] text-[#00D9E8]'
                        }`}
                      >
                        {hiddenUnlocked ? <LockOpen className="w-3.5 h-3.5" /> : <Lock className="w-3.5 h-3.5" />}
                        <span>{hiddenUnlocked ? 'إغلاق المخفية' : 'المحادثات المخفية'}</span>
                      </button>
                    </div>

                    <div className="relative">
                      <Search className="w-4 h-4 text-[#7D8B99] absolute right-3 top-3" />
                      <input
                        type="text"
                        value={chatSearch}
                        onChange={(e) => setChatSearch(e.target.value)}
                        placeholder="ابحث في المحادثات..."
                        className="w-full rounded-full bg-[#0E1519] border border-[#1F2E37] pr-9 pl-3 py-2 text-xs text-white placeholder-[#7D8B99] focus:outline-none focus:border-[#00D9E8]"
                      />
                    </div>

                    <div className="divide-y divide-[#1E2B33]">
                      {visibleChats.map((chat) => (
                        <div key={chat.id} className="py-3 flex items-center gap-3">
                          {chat.id === 'official' ? (
                            <img
                              src="/wesal-icon.webp"
                              alt="أيقونة وصال الرسمية"
                              className="w-11 h-11 rounded-full border border-[#00D9E8]/60 object-cover shrink-0"
                            />
                          ) : (
                            <div className="w-11 h-11 rounded-full bg-[#131D23] border border-[#00D9E8]/50 flex items-center justify-center shrink-0">
                              <MessageSquare className="w-5 h-5 text-[#00D9E8]" />
                            </div>
                          )}
                          <div className="flex-1 min-w-0">
                            <div className="flex items-center justify-between gap-2">
                              <div className="flex items-center gap-1.5 min-w-0">
                                <span className="text-sm font-bold text-white truncate">{chat.name}</span>
                                <WesalSealSvg
                                  hex={chat.sealHex}
                                  className="w-4 h-4"
                                />
                              </div>
                              <span className="text-[11px] text-[#7D8B99] shrink-0">{chat.time}</span>
                            </div>
                            <p className="text-xs text-[#A0B3BE] truncate mt-0.5">{chat.preview}</p>
                          </div>
                        </div>
                      ))}
                      {visibleChats.length === 0 && (
                        <div className="py-10 text-center text-xs text-[#7D8B99]">
                          لا توجد محادثات مطابقة للبحث الحالي
                        </div>
                      )}
                    </div>
                  </div>
                )}

                {previewTab === 'badges' && (
                  <div id="seals" className="space-y-4">
                    <div className="text-center space-y-2">
                      <div className="w-24 h-24 mx-auto rounded-2xl overflow-hidden border border-[#F59E0B]/50 bg-[#0E1519]">
                        <img
                          src="/assets/aistudio/file_00000000a0f4824689d079254c38c4b5_bd782c7f.jpg"
                          alt="الإطار الملكي لإدارة WESAL"
                          className="w-full h-full object-cover"
                        />
                      </div>
                      <div className="flex items-center justify-center gap-1.5">
                        <span className="text-sm font-bold text-white">إدارة WESAL Messenger</span>
                        <WesalSealSvg hex={activeSeal.hex} stroke={activeSeal.stroke} className="w-4 h-4" />
                      </div>
                      <p className="text-xs text-[#A0B3BE]">
                        الشارة المحددة: {activeSeal.label} · 10 ألوان توثيق معتمدة
                      </p>
                    </div>

                    <div className="grid grid-cols-5 gap-2">
                      {VERIFICATION_SEALS.map((seal) => (
                        <button
                          key={seal.key}
                          type="button"
                          onClick={() => setSelectedSealKey(seal.key)}
                          className={`p-2 rounded-xl border text-center transition-colors cursor-pointer ${
                            selectedSealKey === seal.key
                              ? 'bg-[#131D23] border-[#00D9E8]'
                              : 'bg-[#0E1519] border-[#1E2B33] hover:border-[#2F4450]'
                          }`}
                        >
                          <WesalSealSvg hex={seal.hex} stroke={seal.stroke} className="w-5 h-5 mx-auto" />
                          <span className="block text-[10px] text-[#CBD5E1] mt-1">{seal.label}</span>
                        </button>
                      ))}
                    </div>
                  </div>
                )}

                <div className="pt-4 border-t border-[#1E2B33] flex items-center justify-between text-[11px] text-[#7D8B99]">
                  <span>معاينة تفاعلية · WESAL</span>
                  <span className="tabular-nums">ID: 10001 – 1000000</span>
                </div>
              </div>
            </div>
          </div>
        </section>

        {/* Architecture & Security Overview */}
        <section id="security" className="border-t border-[#1E2B33] pt-10 grid grid-cols-1 md:grid-cols-3 gap-8">
          <div className="space-y-2">
            <ShieldCheck className="w-5 h-5 text-[#00D9E8]" />
            <h3 className="text-base font-bold text-white">تشفير E2EE وأمان المحادثات</h3>
            <p className="text-sm text-[#A0B3BE] leading-relaxed">
              تشفير ECDH P-256 + AES-256-GCM لكل حساب، وقفل المحادثات المخفية بـ PBKDF2 داخل Android Keystore مع استمرارية الجلسة أثناء التصفح.
            </p>
          </div>
          <div className="space-y-2">
            <Crown className="w-5 h-5 text-[#F59E0B]" />
            <h3 className="text-base font-bold text-white">الإدارة الكاملة والمشرفين</h3>
            <p className="text-sm text-[#A0B3BE] leading-relaxed">
              صلاحيات أدمن كاملة لحسابي 10001 و10002 وتوزيع صلاحيات دقيقة للمشرفين المساعدين مع تخصيص المظهر العام للتطبيق.
            </p>
          </div>
          <div className="space-y-2">
            <div className="flex items-center gap-2 text-[#00D66B]">
              <Sparkles className="w-5 h-5" />
              <UserPlus className="w-4 h-4" />
            </div>
            <h3 className="text-base font-bold text-white">الشارات والإطارات والاشتراكات</h3>
            <p className="text-sm text-[#A0B3BE] leading-relaxed">
              تقديم طلبات التوثيق بالهوية من الإعدادات، إطارات ملفات شفافة وتأثيرات قص الخلفية والنيون عبر جميع شاشات التطبيق.
            </p>
          </div>
        </section>
      </main>

      <footer className="border-t border-[#1E2B33] py-6 px-5 sm:px-8 text-xs text-[#7D8B99]">
        <div className="max-w-6xl mx-auto flex flex-col sm:flex-row items-center justify-between gap-3">
          <span>WESAL Messenger · com.wesal.messenger</span>
          <span>بوابة التنزيل الرسمية والمعاينة التفاعلية</span>
        </div>
      </footer>
    </div>
  );
}
