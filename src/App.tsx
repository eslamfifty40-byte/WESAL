import { Download, FolderArchive, ShieldCheck, Smartphone, AlertTriangle, CheckCircle2 } from 'lucide-react';

const PROJECT_HIGHLIGHTS = [
  'الاسم: وصال (WESAL) — الحزمة: com.wesal.messenger — Android 8.0+ (API 26)',
  'ملفات Gradle نظيفة وخالية من التكرار (settings.gradle.kts, build.gradle.kts, app/build.gradle.kts, libs.versions.toml)',
  'الأيقونة الرسمية المرفقة بجميع مقاسات mipmap + Adaptive Icon في mipmap-anydpi-v26',
  'تسجيل مجاني على Firebase Spark + تبديل وإدارة حسابات متعددة على نفس الجهاز عبر Credential Manager & Email',
  'معرف وصال فريد (Wesal ID) واكتشاف مستخدمي التطبيق مع أزرار دردشة ومكالمة صوتية وفيديو مباشرة + مطابقة SHA-256',
  'كاميرا CameraX موحدة (نشر في الحالة أو إرسال مباشر للمحادثات) + حالات 24 ساعة (صور مقصوصة، فيديو، صوتية، نصية) مع قائمة المشاهدين',
  'تشفير E2EE معزول لكل حساب (ECDH P-256 + AES-256-GCM) مع بديل inline للصور والملفات الصوتية عند عدم تفعيل Storage + مكالمات WebRTC'
];

export default function App() {
  return (
    <div dir="rtl" className="min-h-screen bg-[#06090B] text-[#F8FAFC] flex items-center justify-center p-6">
      <div className="max-w-2xl w-full rounded-2xl bg-[#0E1418] border border-[#1E2B33] p-6 sm:p-8 space-y-6 shadow-2xl">
        <div className="flex items-center gap-4">
          <img
            src="/wesal-icon.webp"
            alt="أيقونة وصال الرسمية"
            className="w-16 h-16 rounded-2xl border border-[#1E2B33] object-cover"
          />
          <div>
            <h1 className="text-2xl font-bold text-white">وصال — WESAL</h1>
            <p className="text-sm text-[#00E5FF]">حزمة المشروع المصدري لتطبيق Android Native (Kotlin + Jetpack Compose)</p>
          </div>
        </div>

        <div className="rounded-xl bg-[#151E24] border border-[#1E2B33] p-4 space-y-3">
          <div className="flex items-center justify-between text-sm">
            <span className="flex items-center gap-2 text-[#94A3B8]">
              <FolderArchive className="w-4 h-4 text-[#00E5FF]" />
              اسم الملف: <strong className="text-white">WesalAndroid.zip</strong>
            </span>
            <span className="text-[#00C853] font-semibold">187 ملفاً مصدرياً</span>
          </div>

          <a
            href="/WesalAndroid.zip"
            download="WesalAndroid.zip"
            className="w-full inline-flex items-center justify-center gap-2 rounded-xl bg-[#00C853] hover:bg-[#00b248] text-[#06090B] font-bold py-3.5 px-5 transition-colors"
          >
            <Download className="w-5 h-5" />
            تنزيل WesalAndroid.zip مباشرة
          </a>
        </div>

        <div className="space-y-2.5">
          <h2 className="text-sm font-semibold text-[#00E5FF] flex items-center gap-2">
            <Smartphone className="w-4 h-4" />
            محتويات الحزمة المصدرية
          </h2>
          <ul className="space-y-2 text-sm text-[#CBD5E1]">
            {PROJECT_HIGHLIGHTS.map((item) => (
              <li key={item} className="flex items-start gap-2">
                <CheckCircle2 className="w-4 h-4 text-[#00C853] shrink-0 mt-0.5" />
                <span>{item}</span>
              </li>
            ))}
          </ul>
        </div>

        <div className="rounded-xl bg-[#F59E0B]/10 border border-[#F59E0B]/30 p-4 text-xs text-[#FDE68A] space-y-1.5 leading-relaxed">
          <div className="flex items-center gap-2 font-bold text-[#F59E0B]">
            <AlertTriangle className="w-4 h-4" />
            ملاحظة تقنية مهمة قبل التشغيل
          </div>
          <p>
            هذا الملف يحتوي على الشيفرة المصدرية لـ Android Studio ولم يتم تشغيل Gradle Build أو اختبار APK أو تسجيل الدخول الفعلي هنا لعدم توفر Android SDK في بيئة التوليد.
          </p>
          <p className="flex items-center gap-1.5 text-[#CBD5E1]">
            <ShieldCheck className="w-3.5 h-3.5 text-[#00E5FF] shrink-0" />
            بعد فك الضغط، أضف بصمة <code className="text-white">SHA-1</code> وفعّل موفّري <code className="text-white">Google</code> و<code className="text-white">Email/Password</code> في Firebase Spark Console ثم نفّذ Gradle Sync.
          </p>
        </div>
      </div>
    </div>
  );
}
