import React, { useState } from 'react';
import {
  Brain,
  CheckCircle2,
  Edit3,
  Plus,
  Search,
  Trash2,
  X,
} from 'lucide-react';
import type {
  CertaintyLevel,
  ChatMessage,
  HealthScanRecord,
  MemoryCategory,
  MemoryItem,
  UserProfile,
} from '../types/sabq';

interface SmartMemoryScreenProps {
  profile: UserProfile;
  memories: MemoryItem[];
  chats: ChatMessage[];
  scans: HealthScanRecord[];
  onUpsertMemory: (item: MemoryItem) => void;
  onDeleteMemory: (id: string) => void;
}

const CATEGORY_LABELS: Record<MemoryCategory, string> = {
  PERSONAL: 'الذاكرة الشخصية',
  PROJECTS: 'ذاكرة المشروعات',
  HEALTH: 'ذاكرة المعلومات الصحية',
  CONVERSATIONS: 'سجل المحادثات',
  RECOMMENDATIONS: 'سجل التوصيات',
};

const CERTAINTY_LABELS: Record<CertaintyLevel, { label: string; colorClass: string }> = {
  CONFIRMED: { label: 'معلومة مؤكدة', colorClass: 'text-emerald-300 border-emerald-500/40 bg-emerald-500/10' },
  EXPECTATION: { label: 'توقع', colorClass: 'text-amber-300 border-amber-500/40 bg-amber-500/10' },
  INFERENCE: { label: 'استنتاج', colorClass: 'text-cyan-300 border-cyan-500/40 bg-cyan-500/10' },
};

export const SmartMemoryScreen: React.FC<SmartMemoryScreenProps> = ({
  profile,
  memories,
  chats,
  scans,
  onUpsertMemory,
  onDeleteMemory,
}) => {
  const [activeCat, setActiveCat] = useState<MemoryCategory | 'ALL'>('ALL');
  const [search, setSearch] = useState('');
  const [editingId, setEditingId] = useState<string | null>(null);
  const [title, setTitle] = useState('');
  const [content, setContent] = useState('');
  const [category, setCategory] = useState<MemoryCategory>('PERSONAL');
  const [certainty, setCertainty] = useState<CertaintyLevel>('CONFIRMED');
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);

  const handleSave = (e: React.FormEvent) => {
    e.preventDefault();
    if (!title.trim() || !content.trim()) return;
    const now = new Date().toISOString();
    onUpsertMemory({
      id: editingId || `mem_${Date.now()}`,
      userId: profile.userId,
      category,
      certainty,
      title: title.trim(),
      content: content.trim(),
      source: 'إدخال وتأكيد مباشر من المستخدم',
      createdAt: now,
      updatedAt: now,
    });
    setEditingId(null);
    setTitle('');
    setContent('');
  };

  const startEdit = (m: MemoryItem) => {
    setEditingId(m.id);
    setTitle(m.title);
    setContent(m.content);
    setCategory(m.category);
    setCertainty(m.certainty);
  };

  const filteredMemories = memories.filter((m) => {
    const matchCat = activeCat === 'ALL' || m.category === activeCat;
    const matchQ =
      !search.trim() ||
      m.title.includes(search.trim()) ||
      m.content.includes(search.trim());
    return matchCat && matchQ;
  });

  const allRecommendations = scans.flatMap((s) =>
    s.recommendations.map((rec, idx) => ({
      id: `${s.id}_rec_${idx}`,
      scanTitle: s.scanTitleAr,
      text: rec,
      timestamp: s.timestamp,
    }))
  );

  return (
    <div className="space-y-6 pb-8">
      <div className="rounded-3xl border border-violet-500/25 bg-[#0C0E1A] p-5">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <span className="flex h-11 w-11 items-center justify-center rounded-2xl bg-violet-500/20 text-cyan-300">
              <Brain className="h-6 w-6" />
            </span>
            <div>
              <h2 className="text-lg font-bold text-white">الذاكرة الذكية لـ {profile.displayName}</h2>
              <p className="text-xs text-slate-400">
                فصل صارم بين المعلومات المؤكدة والتوقعات والاستنتاجات — مع تحكم كامل في التعديل والحذف
              </p>
            </div>
          </div>

          <div className="relative w-full sm:w-64">
            <Search className="pointer-events-none absolute right-3 top-3 h-4 w-4 text-slate-500" />
            <input
              type="text"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="ابحث في الذاكرة..."
              className="w-full rounded-xl border border-slate-700 bg-[#06070D] py-2 pr-9 pl-3 text-xs text-white focus:border-cyan-400 focus:outline-none"
            />
          </div>
        </div>

        {/* Category Filter Tabs */}
        <div className="mt-4 flex flex-wrap gap-2">
          <button
            onClick={() => setActiveCat('ALL')}
            className={`min-h-[38px] rounded-xl px-3 py-1.5 text-xs font-bold ${
              activeCat === 'ALL'
                ? 'bg-gradient-to-l from-violet-600 to-cyan-500 text-white'
                : 'border border-slate-800 bg-[#121526] text-slate-300'
            }`}
          >
            الكل
          </button>
          {(Object.keys(CATEGORY_LABELS) as MemoryCategory[]).map((cat) => (
            <button
              key={cat}
              onClick={() => setActiveCat(cat)}
              className={`min-h-[38px] rounded-xl px-3 py-1.5 text-xs font-bold ${
                activeCat === cat
                  ? 'bg-gradient-to-l from-violet-600 to-cyan-500 text-white'
                  : 'border border-slate-800 bg-[#121526] text-slate-300'
              }`}
            >
              {CATEGORY_LABELS[cat]}
            </button>
          ))}
        </div>
      </div>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-12">
        {/* Add / Edit Memory Form */}
        <form
          onSubmit={handleSave}
          className="h-fit space-y-3 rounded-3xl border border-violet-500/25 bg-[#0C0E1A] p-5 lg:col-span-4"
        >
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-bold text-white">
              {editingId ? 'تعديل عنصر ذاكرة' : 'إضافة معلومة إلى الذاكرة'}
            </h3>
            {editingId && (
              <button
                type="button"
                onClick={() => {
                  setEditingId(null);
                  setTitle('');
                  setContent('');
                }}
                className="text-xs text-slate-400 hover:text-white"
              >
                <X className="h-4 w-4" />
              </button>
            )}
          </div>

          <input
            type="text"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="عنوان المعلومة أو المشروع"
            className="w-full rounded-xl border border-slate-700 bg-[#06070D] px-3 py-2.5 text-sm text-white focus:border-cyan-400 focus:outline-none"
          />
          <textarea
            rows={3}
            value={content}
            onChange={(e) => setContent(e.target.value)}
            placeholder="التفاصيل التي تريد من سَبَق تذكرها..."
            className="w-full rounded-xl border border-slate-700 bg-[#06070D] px-3 py-2.5 text-sm text-white focus:border-cyan-400 focus:outline-none"
          />

          <div className="grid grid-cols-2 gap-2">
            <select
              value={category}
              onChange={(e) => setCategory(e.target.value as MemoryCategory)}
              className="rounded-xl border border-slate-700 bg-[#06070D] px-2.5 py-2 text-xs text-white"
            >
              <option value="PERSONAL">الذاكرة الشخصية</option>
              <option value="PROJECTS">ذاكرة المشروعات</option>
              <option value="HEALTH">ذاكرة المعلومات الصحية</option>
            </select>
            <select
              value={certainty}
              onChange={(e) => setCertainty(e.target.value as CertaintyLevel)}
              className="rounded-xl border border-slate-700 bg-[#06070D] px-2.5 py-2 text-xs text-white"
            >
              <option value="CONFIRMED">معلومة مؤكدة</option>
              <option value="EXPECTATION">توقع</option>
              <option value="INFERENCE">استنتاج</option>
            </select>
          </div>

          <button
            type="submit"
            className="flex min-h-[44px] w-full items-center justify-center gap-2 rounded-xl bg-gradient-to-l from-violet-600 to-cyan-500 px-4 py-2 text-xs font-bold text-white"
          >
            <Plus className="h-4 w-4" />
            <span>{editingId ? 'حفظ التعديلات' : 'حفظ في الذاكرة'}</span>
          </button>
        </form>

        {/* Memory List / Conversation Log / Recommendations Log */}
        <div className="space-y-3 lg:col-span-8">
          {activeCat === 'CONVERSATIONS' ? (
            <div className="space-y-2.5">
              {chats.length === 0 ? (
                <div className="rounded-2xl border border-slate-800 bg-[#0C0E1A] p-6 text-center text-xs text-slate-400">
                  سجل المحادثات فارغ لهذا المستخدم.
                </div>
              ) : (
                chats.map((c) => (
                  <div
                    key={c.id}
                    className="rounded-2xl border border-slate-800 bg-[#0C0E1A] p-3.5 text-xs"
                  >
                    <div className="mb-1 flex items-center justify-between text-slate-400">
                      <span className="font-bold text-cyan-300">
                        {c.role === 'user' ? profile.displayName : 'سَبَق AI'}
                      </span>
                      <span className="num-en">
                        {new Date(c.timestamp).toLocaleString('en-GB')}
                      </span>
                    </div>
                    <p className="text-slate-200">{c.content}</p>
                  </div>
                ))
              )}
            </div>
          ) : activeCat === 'RECOMMENDATIONS' ? (
            <div className="space-y-2.5">
              {allRecommendations.length === 0 ? (
                <div className="rounded-2xl border border-slate-800 bg-[#0C0E1A] p-6 text-center text-xs text-slate-400">
                  لا توجد توصيات مسجلة بعد. ستظهر هنا التوصيات المستخلصة من فحوصك الفعلية.
                </div>
              ) : (
                allRecommendations.map((r) => (
                  <div
                    key={r.id}
                    className="flex items-start gap-3 rounded-2xl border border-emerald-500/25 bg-[#0C0E1A] p-3.5 text-xs"
                  >
                    <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-emerald-400" />
                    <div>
                      <div className="font-bold text-white">{r.text}</div>
                      <div className="mt-1 text-[11px] text-slate-400">
                        {r.scanTitle} · <span className="num-en">{new Date(r.timestamp).toLocaleDateString('en-GB')}</span>
                      </div>
                    </div>
                  </div>
                ))
              )}
            </div>
          ) : filteredMemories.length === 0 ? (
            <div className="rounded-2xl border border-slate-800 bg-[#0C0E1A] p-8 text-center text-sm text-slate-400">
              لا توجد عناصر محفوظة في هذا القسم بعد. أضف معلومة مؤكدة أو مشروعًا من النموذج المجاور.
            </div>
          ) : (
            filteredMemories.map((item) => {
              const badge = CERTAINTY_LABELS[item.certainty];
              return (
                <div
                  key={item.id}
                  className="rounded-2xl border border-violet-500/20 bg-[#0C0E1A] p-4"
                >
                  <div className="flex items-start justify-between gap-3">
                    <div>
                      <div className="flex flex-wrap items-center gap-2">
                        <h4 className="text-sm font-bold text-white">{item.title}</h4>
                        <span className={`rounded-md border px-2 py-0.5 text-[10px] font-bold ${badge.colorClass}`}>
                          {badge.label}
                        </span>
                        <span className="text-[11px] text-slate-400">
                          {CATEGORY_LABELS[item.category]}
                        </span>
                      </div>
                      <p className="mt-1.5 text-xs leading-relaxed text-slate-200">{item.content}</p>
                      <div className="mt-2 text-[11px] text-slate-500">
                        المصدر: {item.source} · <span className="num-en">{new Date(item.updatedAt).toLocaleDateString('en-GB')}</span>
                      </div>
                    </div>

                    <div className="flex items-center gap-1">
                      <button
                        onClick={() => startEdit(item)}
                        aria-label="تعديل"
                        className="rounded-lg p-2 text-slate-400 hover:bg-slate-800 hover:text-cyan-300"
                      >
                        <Edit3 className="h-4 w-4" />
                      </button>
                      <button
                        onClick={() => setConfirmDeleteId(item.id)}
                        aria-label="حذف"
                        className="rounded-lg p-2 text-slate-400 hover:bg-slate-800 hover:text-rose-400"
                      >
                        <Trash2 className="h-4 w-4" />
                      </button>
                    </div>
                  </div>

                  {confirmDeleteId === item.id && (
                    <div className="mt-3 flex items-center justify-between rounded-xl border border-rose-500/40 bg-rose-950/20 px-3 py-2 text-xs">
                      <span className="text-rose-200">تأكيد حذف هذه المعلومة نهائيًا؟</span>
                      <div className="flex gap-2">
                        <button
                          onClick={() => {
                            onDeleteMemory(item.id);
                            setConfirmDeleteId(null);
                          }}
                          className="rounded-lg bg-rose-600 px-3 py-1 font-bold text-white"
                        >
                          نعم، احذف
                        </button>
                        <button
                          onClick={() => setConfirmDeleteId(null)}
                          className="rounded-lg border border-slate-700 px-3 py-1 text-slate-300"
                        >
                          إلغاء
                        </button>
                      </div>
                    </div>
                  )}
                </div>
              );
            })
          )}
        </div>
      </div>
    </div>
  );
};
