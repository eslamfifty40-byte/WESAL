import React from 'react';
import {
  Activity,
  Brain,
  ChevronLeft,
  Droplets,
  FileText,
  Fingerprint,
  Heart,
  Scale,
  Settings,
  Shield,
  Sparkles,
  UserCheck,
  Wind,
} from 'lucide-react';
import {
  ActiveTab,
  HealthScanRecord,
  LabReading,
  OFFICIAL_SABQ_ICON,
  UserProfile,
} from '../types/sabq';

interface HomeScreenProps {
  profile: UserProfile;
  scans: HealthScanRecord[];
  labs: LabReading[];
  memoriesCount: number;
  onNavigate: (tab: ActiveTab, subMode?: string) => void;
}

export const HomeScreen: React.FC<HomeScreenProps> = ({
  profile,
  scans,
  labs,
  memoriesCount,
  onNavigate,
}) => {
  const rightNodes = [
    { label: 'القلب', icon: Heart, target: 'labs' as ActiveTab, sub: 'PULSE' },
    { label: 'التنفس', icon: Wind, target: 'labs' as ActiveTab, sub: 'SPO2' },
    { label: 'الوزن', icon: Scale, target: 'scan' as ActiveTab, sub: 'BODY' },
    { label: 'المناعة', icon: Shield, target: 'labs' as ActiveTab, sub: 'CBC' },
  ];

  const leftNodes = [
    { label: 'الدم', icon: Droplets, target: 'labs' as ActiveTab, sub: 'HB' },
    { label: 'الغدة', icon: Activity, target: 'labs' as ActiveTab, sub: 'THYROID' },
    { label: 'البشرة', icon: Sparkles, target: 'scan' as ActiveTab, sub: 'SKIN' },
    { label: 'الشعر', icon: UserCheck, target: 'scan' as ActiveTab, sub: 'HAIR' },
  ];

  const quickButtons = [
    { title: 'تحليل الوجه والبشرة', sub: 'موجه تلقائيًا', tab: 'scan' as ActiveTab, mode: 'FACE', icon: Sparkles },
    { title: 'تحليل الشعر', sub: 'من نفس الجلسة', tab: 'scan' as ActiveTab, mode: 'HAIR', icon: UserCheck },
    { title: 'التقييم الصحي التقديري', sub: 'عوامل الخطورة وBMI', tab: 'assessment' as ActiveTab, mode: undefined, icon: Activity },
    { title: 'المؤشرات الحيوية', sub: 'BioFinger والتحاليل', tab: 'labs' as ActiveTab, mode: undefined, icon: Heart },
    { title: 'المساعد الذكي', sub: 'العقل الإضافي', tab: 'assistant' as ActiveTab, mode: undefined, icon: Brain },
    { title: 'تقاريري', sub: '12 تقرير PDF مستقل', tab: 'reports' as ActiveTab, mode: undefined, icon: FileText },
    { title: 'ذاكرتي الصحية', sub: 'الذاكرة الذكية المصنفة', tab: 'memory' as ActiveTab, mode: undefined, icon: Shield },
    { title: 'الإعدادات', sub: 'الحسابات والخصوصية', tab: 'settings' as ActiveTab, mode: undefined, icon: Settings },
  ];

  return (
    <div className="space-y-6 pb-8">
      {/* Hero Banner / Reference Centerpiece */}
      <div className="relative overflow-hidden rounded-3xl border border-violet-500/25 bg-gradient-to-b from-[#0E1122] via-[#090B14] to-[#06070D] p-5 md:p-7">
        <div className="pointer-events-none absolute -top-24 left-1/2 h-64 w-64 -translate-x-1/2 rounded-full bg-cyan-500/10 blur-3xl" />

        {/* Welcome Header */}
        <div className="relative flex flex-col items-center text-center">
          <div className="mb-3 flex items-center gap-3">
            <img
              src={OFFICIAL_SABQ_ICON}
              alt="أيقونة سَبَق الرسمية"
              referrerPolicy="no-referrer"
              className="h-14 w-14 rounded-2xl border border-violet-400/40 object-cover shadow-lg shadow-violet-600/20"
            />
            <div className="text-right">
              <h1 className="text-2xl font-extrabold tracking-tight text-white">سَبَق</h1>
              <p className="text-xs font-semibold tracking-wide text-cyan-400">SABQ AI • مساعدك الصحي الذكي</p>
            </div>
          </div>

          <h2 className="mt-1 text-xl font-bold text-white">
            مرحبًا بك، {profile.displayName}
          </h2>
          <p className="mt-1 max-w-md text-xs text-slate-400 md:text-sm">
            ابدأ الفحص الشامل وفق إمكانيات جهازك الفعلية — بدون أي قراءات مختلقة
          </p>
        </div>

        {/* Anatomical Silhouette + 8 System Nodes matching reference layout */}
        <div className="relative mt-6 grid grid-cols-3 items-center gap-2 md:mx-auto md:max-w-xl">
          {/* Start side (Right in RTL): Heart, Breath, Weight, Immunity */}
          <div className="flex flex-col items-center gap-3">
            {rightNodes.map((node) => {
              const Icon = node.icon;
              return (
                <button
                  key={node.label}
                  onClick={() => onNavigate(node.target, node.sub)}
                  className="group flex min-h-[44px] flex-col items-center gap-1 rounded-2xl px-2 py-1.5 transition-transform active:scale-95"
                >
                  <span className="flex h-12 w-12 items-center justify-center rounded-full border border-violet-400/45 bg-[#121528] text-cyan-300 shadow-[0_0_16px_rgba(139,92,246,0.22)] transition-colors group-hover:border-cyan-400 group-hover:text-white">
                    <Icon className="h-5 w-5" />
                  </span>
                  <span className="text-[11px] font-medium text-slate-200">{node.label}</span>
                </button>
              );
            })}
          </div>

          {/* Center Holographic Anatomical Body */}
          <div className="relative flex flex-col items-center justify-center py-4">
            <div className="relative flex h-64 w-40 items-center justify-center md:h-72 md:w-44">
              {/* Concentric cyan scan rings */}
              <div className="absolute bottom-4 h-10 w-36 rounded-full border border-cyan-400/40 shadow-[0_0_24px_rgba(0,212,255,0.25)]" />
              <div className="absolute bottom-2 h-14 w-44 rounded-full border border-violet-500/30" />
              {/* Glowing SVG Anatomical Silhouette */}
              <svg
                viewBox="0 0 160 320"
                className="h-full w-full drop-shadow-[0_0_18px_rgba(0,212,255,0.5)]"
                aria-label="خريطة الجسم التفاعلية"
              >
                <defs>
                  <linearGradient id="sabqBodyGrad" x1="0%" y1="0%" x2="0%" y2="100%">
                    <stop offset="0%" stopColor="#00D4FF" stopOpacity="0.95" />
                    <stop offset="50%" stopColor="#3B82F6" stopOpacity="0.78" />
                    <stop offset="100%" stopColor="#8B5CF6" stopOpacity="0.7" />
                  </linearGradient>
                </defs>
                {/* Head */}
                <circle cx="80" cy="36" r="21" fill="none" stroke="url(#sabqBodyGrad)" strokeWidth="2.4" />
                <circle cx="80" cy="34" r="13" fill="rgba(0,212,255,0.16)" />
                {/* Neck & Shoulders */}
                <path
                  d="M71 57 L71 70 L40 82 L30 156 L42 158 L50 102 L54 176 L58 296 L72 296 L78 192 L82 192 L88 296 L102 296 L106 176 L110 102 L118 158 L130 156 L120 82 L89 70 L89 57"
                  fill="rgba(0,212,255,0.12)"
                  stroke="url(#sabqBodyGrad)"
                  strokeWidth="2.2"
                  strokeLinejoin="round"
                />
                {/* Heart / Chest Node */}
                <circle cx="86" cy="108" r="7" fill="#00D4FF" opacity="0.85" />
                <circle cx="86" cy="108" r="14" fill="none" stroke="#A855F7" strokeWidth="1.5" strokeDasharray="3 3" />
                {/* Spine / Neural vertical line */}
                <line x1="80" y1="62" x2="80" y2="178" stroke="#00D4FF" strokeWidth="1.2" strokeDasharray="3 3" opacity="0.7" />
              </svg>
            </div>
            <div className="mt-2 flex items-center gap-3 text-xs text-slate-400">
              <span>الفحوص: <strong className="num-en text-cyan-300">{scans.length}</strong></span>
              <span aria-hidden="true">·</span>
              <span>التحاليل: <strong className="num-en text-violet-300">{labs.length}</strong></span>
            </div>
          </div>

          {/* End side (Left in RTL): Blood, Thyroid, Skin, Hair */}
          <div className="flex flex-col items-center gap-3">
            {leftNodes.map((node) => {
              const Icon = node.icon;
              return (
                <button
                  key={node.label}
                  onClick={() => onNavigate(node.target, node.sub)}
                  className="group flex min-h-[44px] flex-col items-center gap-1 rounded-2xl px-2 py-1.5 transition-transform active:scale-95"
                >
                  <span className="flex h-12 w-12 items-center justify-center rounded-full border border-violet-400/45 bg-[#121528] text-cyan-300 shadow-[0_0_16px_rgba(139,92,246,0.22)] transition-colors group-hover:border-cyan-400 group-hover:text-white">
                    <Icon className="h-5 w-5" />
                  </span>
                  <span className="text-[11px] font-medium text-slate-200">{node.label}</span>
                </button>
              );
            })}
          </div>
        </div>

        {/* Primary CTA matching reference */}
        <div className="mt-6 md:mx-auto md:max-w-md">
          <button
            onClick={() => onNavigate('scan', 'FACE_EYE')}
            className="flex min-h-[54px] w-full items-center justify-center gap-3 rounded-2xl bg-gradient-to-l from-violet-600 via-purple-600 to-cyan-500 px-6 py-3.5 text-base font-bold text-white shadow-[0_0_26px_rgba(139,92,246,0.45)] transition-transform hover:brightness-110 active:scale-[0.99]"
          >
            <Fingerprint className="h-6 w-6 text-cyan-200" />
            <span>ابدأ الفحص الشامل</span>
          </button>
        </div>
      </div>

      {/* Required Home Screen Action Grid */}
      <div>
        <div className="mb-3 flex items-center justify-between">
          <h3 className="text-sm font-bold text-slate-200">الأقسام والخدمات الأساسية</h3>
          <button
            onClick={() => onNavigate('settings')}
            className="flex items-center gap-1 text-xs font-medium text-cyan-400 hover:text-cyan-300"
          >
            <Settings className="h-3.5 w-3.5" />
            <span>الإعدادات والخصوصية</span>
          </button>
        </div>

        <div className="grid grid-cols-2 gap-3 md:grid-cols-3">
          {quickButtons.map((btn) => {
            const Icon = btn.icon;
            return (
              <button
                key={btn.title}
                onClick={() => onNavigate(btn.tab, btn.mode)}
                className="flex min-h-[76px] items-center justify-between rounded-2xl border border-violet-500/20 bg-[#0E101C] p-3.5 text-right transition-colors hover:border-cyan-400/50 hover:bg-[#131729]"
              >
                <div className="flex items-center gap-3">
                  <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-violet-500/15 text-cyan-300">
                    <Icon className="h-5 w-5" />
                  </span>
                  <div>
                    <div className="text-sm font-bold text-white">{btn.title}</div>
                    <div className="text-[11px] text-slate-400">{btn.sub}</div>
                  </div>
                </div>
                <ChevronLeft className="h-4 w-4 shrink-0 text-slate-500" />
              </button>
            );
          })}
        </div>
      </div>
    </div>
  );
};
