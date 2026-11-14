import React, { useEffect, useRef, useState } from 'react';
import {
  Brain,
  ImagePlus,
  Loader2,
  Mic,
  MicOff,
  PlusCircle,
  Send,
  Sparkles,
  Volume2,
  VolumeX,
} from 'lucide-react';
import {
  blobToDataUrl,
  prepareAndCompressImage,
} from '../utils/imageUploader';
import { apiFetchJson } from '../services/apiClient';
import type {
  CertaintyLevel,
  ChatMessage,
  HealthScanRecord,
  LabReading,
  MemoryCategory,
  MemoryItem,
  UserProfile,
  UserSettings,
} from '../types/sabq';

interface SecondBrainScreenProps {
  profile: UserProfile;
  settings: UserSettings;
  chats: ChatMessage[];
  memories: MemoryItem[];
  scans: HealthScanRecord[];
  labs: LabReading[];
  onAddChat: (msg: ChatMessage) => void;
  onAddMemory: (item: MemoryItem) => void;
}

interface ProactiveSuggestion {
  title: string;
  body: string;
  certainty: string;
  basis: string;
}

export const SecondBrainScreen: React.FC<SecondBrainScreenProps> = ({
  profile,
  settings,
  chats,
  memories,
  scans,
  labs,
  onAddChat,
  onAddMemory,
}) => {
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const [imagePreview, setImagePreview] = useState<string | null>(null);
  const [recording, setRecording] = useState(false);
  const [proactiveLoading, setProactiveLoading] = useState(false);
  const [proactiveList, setProactiveList] = useState<ProactiveSuggestion[]>([]);
  const [proactiveMessage, setProactiveMessage] = useState<string | null>(null);
  const [suggestedMemories, setSuggestedMemories] = useState<
    Array<{ category: MemoryCategory; certainty: CertaintyLevel; title: string; content: string }>
  >([]);

  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const micStreamRef = useRef<MediaStream | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const endOfChatRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    endOfChatRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [chats.length]);

  useEffect(() => {
    return () => {
      if (mediaRecorderRef.current && mediaRecorderRef.current.state !== 'inactive') {
        try {
          mediaRecorderRef.current.stop();
        } catch {
          // ignore
        }
      }
      if (micStreamRef.current) {
        micStreamRef.current.getTracks().forEach((t) => t.stop());
        micStreamRef.current = null;
      }
    };
  }, []);

  const isQuietHoursNow = (): boolean => {
    if (!settings.quietHoursEnabled) return false;
    const h = new Date().getHours();
    const s = settings.quietStartHour;
    const e = settings.quietEndHour;
    return s <= e ? h >= s && h < e : h >= s || h < e;
  };

  const speakText = (text: string) => {
    if (!settings.voiceEnabled || !('speechSynthesis' in window)) return;
    window.speechSynthesis.cancel();
    const utter = new SpeechSynthesisUtterance(text);
    utter.lang = 'ar-EG';
    utter.volume = settings.voiceVolume;
    window.speechSynthesis.speak(utter);
  };

  const handleImagePick = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;
    try {
      const prep = await prepareAndCompressImage(file);
      const dataUrl = await blobToDataUrl(prep.compressedBlob);
      URL.revokeObjectURL(prep.previewUrl);
      setImagePreview(dataUrl);
      setProactiveMessage(null);
    } catch (err: any) {
      setProactiveMessage(err?.message || 'تعذر تجهيز الصورة المختارة للمساعد.');
    }
  };

  const toggleVoiceRecording = async () => {
    if (!settings.micAllowed) {
      setProactiveMessage('الميكروفون معطل من الإعدادات. يمكنك تفعيله أولًا.');
      return;
    }

    if (recording && mediaRecorderRef.current) {
      mediaRecorderRef.current.stop();
      setRecording(false);
      return;
    }

    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      micStreamRef.current = stream;
      const recorder = new MediaRecorder(stream);
      chunksRef.current = [];
      recorder.ondataavailable = (ev) => {
        if (ev.data.size > 0) chunksRef.current.push(ev.data);
      };
      recorder.onstop = async () => {
        stream.getTracks().forEach((t) => t.stop());
        micStreamRef.current = null;
        const blob = new Blob(chunksRef.current, { type: recorder.mimeType || 'audio/webm' });
        const reader = new FileReader();
        reader.onload = async () => {
          const base64 = String(reader.result);
          try {
            const data = await apiFetchJson<{ text?: string }>('/api/ai/transcribe', {
              method: 'POST',
              body: JSON.stringify({ audioBase64: base64, mimeType: blob.type }),
            });
            const transcript = (data.text || '').trim();
            if (transcript) {
              setInput((prev) => (prev ? `${prev} ${transcript}` : transcript));
            }
          } catch (err: any) {
            setProactiveMessage(err?.message || 'تعذر تفريغ التسجيل الصوتي حاليًا — تحقق من الاتصال.');
          }
        };
        reader.readAsDataURL(blob);
      };
      mediaRecorderRef.current = recorder;
      recorder.start();
      setRecording(true);
    } catch {
      setProactiveMessage('تعذر تشغيل الميكروفون. تأكد من منح صلاحية الصوت للمتصفح.');
    }
  };

  const fetchProactiveInsights = async () => {
    setProactiveLoading(true);
    setProactiveMessage(null);
    try {
      const data = await apiFetchJson<{ suggestions?: ProactiveSuggestion[]; message?: string }>(
        '/api/ai/proactive',
        {
          method: 'POST',
          body: JSON.stringify({
            memories: memories.map((m) => ({
              category: m.category,
              certainty: m.certainty,
              title: m.title,
              content: m.content,
            })),
            recentScans: scans.slice(0, 5).map((s) => ({
              scanType: s.scanTitleAr,
              summary: s.summary,
            })),
            quietHoursActive: isQuietHoursNow(),
          }),
        }
      );
      setProactiveList(data.suggestions || []);
      if (data.message) setProactiveMessage(data.message);
    } catch (err: any) {
      setProactiveMessage(err?.message || 'تعذر تحديث المقترحات الاستباقية حاليًا.');
    } finally {
      setProactiveLoading(false);
    }
  };

  const handleSend = async (e: React.FormEvent) => {
    e.preventDefault();
    if ((!input.trim() && !imagePreview) || sending) return;

    const userText = input.trim();
    const attachedImg = imagePreview;
    setInput('');
    setImagePreview(null);
    setSuggestedMemories([]);

    const userMsg: ChatMessage = {
      id: `msg_${Date.now()}`,
      userId: profile.userId,
      role: 'user',
      content: userText || 'تحليل الصورة المرفقة',
      imagePreview: attachedImg || undefined,
      timestamp: new Date().toISOString(),
    };
    onAddChat(userMsg);
    setSending(true);

    try {
      const data = await apiFetchJson<{
        reply?: string;
        error?: string;
        certaintyClassification?: CertaintyLevel;
        suggestedMemories?: Array<{
          category: MemoryCategory;
          certainty: CertaintyLevel;
          title: string;
          content: string;
        }>;
      }>(
        '/api/ai/chat',
        {
          method: 'POST',
          body: JSON.stringify({
            message: userText,
            history: chats.slice(-6).map((c) => ({ role: c.role, content: c.content })),
            memories: memories.map((m) => ({
              category: m.category,
              certainty: m.certainty,
              title: m.title,
              content: m.content,
            })),
            recentScans: scans.slice(0, 5).map((s) => ({
              scanType: s.scanTitleAr,
              summary: s.summary,
              timestamp: s.timestamp,
            })),
            labReadings: labs.slice(0, 8),
            imageBase64: attachedImg || undefined,
          }),
        },
        60000
      );
      const replyText = data.reply || data.error || 'تعذر الرد حاليًا.';

      const assistantMsg: ChatMessage = {
        id: `msg_ai_${Date.now()}`,
        userId: profile.userId,
        role: 'assistant',
        content: replyText,
        certainty: (data.certaintyClassification as CertaintyLevel) || 'INFERENCE',
        timestamp: new Date().toISOString(),
      };
      onAddChat(assistantMsg);

      if (Array.isArray(data.suggestedMemories) && data.suggestedMemories.length > 0) {
        setSuggestedMemories(data.suggestedMemories);
      }
      if (settings.voiceEnabled) {
        speakText(replyText);
      }
    } catch (err: any) {
      onAddChat({
        id: `msg_err_${Date.now()}`,
        userId: profile.userId,
        role: 'assistant',
        content: err?.message || 'تعذر الاتصال بالمساعد حاليًا. تأكد من الاتصال بالإنترنت وحاول مجددًا.',
        timestamp: new Date().toISOString(),
      });
    } finally {
      setSending(false);
    }
  };

  return (
    <div className="grid grid-cols-1 gap-6 pb-8 lg:grid-cols-12">
      {/* Main Conversational Second Brain */}
      <div className="flex h-[680px] flex-col rounded-3xl border border-violet-500/25 bg-[#0C0E1A] p-4 lg:col-span-8">
        <div className="mb-3 flex items-center justify-between border-b border-slate-800 pb-3">
          <div className="flex items-center gap-3">
            <span className="flex h-10 w-10 items-center justify-center rounded-2xl bg-gradient-to-br from-violet-600 to-cyan-500 text-white">
              <Brain className="h-5 w-5" />
            </span>
            <div>
              <h2 className="text-base font-bold text-white">العقل الإضافي الذكي</h2>
              <p className="text-[11px] text-slate-400">
                يفهم العربية واللهجة المصرية · يحلل الصور · يربط ذاكرتك بدون تخمين
              </p>
            </div>
          </div>
          <button
            onClick={() => 'speechSynthesis' in window && window.speechSynthesis.cancel()}
            className="flex items-center gap-1 rounded-xl border border-slate-700 px-2.5 py-1.5 text-xs text-slate-300 hover:border-cyan-400"
          >
            {settings.voiceEnabled ? <Volume2 className="h-4 w-4 text-cyan-400" /> : <VolumeX className="h-4 w-4" />}
            <span>إيقاف الصوت</span>
          </button>
        </div>

        {/* Messages */}
        <div className="flex-1 space-y-3 overflow-y-auto px-1 py-2">
          {chats.length === 0 && (
            <div className="rounded-2xl border border-slate-800 bg-[#080A12] p-5 text-center text-xs text-slate-300 space-y-2">
              <p className="font-bold text-cyan-300">أهلًا بك في العقل الإضافي لـ سَبَق</p>
              <p>
                تقدر تسألني بالعربي أو بالمصري عن نتائج فحوصك، روتين البشرة والشعر، أو تشاركني مشروع عايز تتابعه وتحفظ خطواته في ذاكرتك الذكية.
              </p>
            </div>
          )}

          {chats.map((m) => (
            <div
              key={m.id}
              className={`flex flex-col ${m.role === 'user' ? 'items-start' : 'items-end'}`}
            >
              <div
                className={`max-w-[85%] rounded-2xl px-4 py-3 text-sm leading-relaxed ${
                  m.role === 'user'
                    ? 'border border-violet-500/40 bg-violet-600/20 text-white'
                    : 'border border-cyan-500/25 bg-[#12162A] text-slate-100'
                }`}
              >
                {m.imagePreview && (
                  <img
                    src={m.imagePreview}
                    alt="صورة مرسلة للمساعد"
                    className="mb-2 max-h-40 rounded-xl object-cover"
                  />
                )}
                <p className="whitespace-pre-wrap">{m.content}</p>
                <div className="mt-1.5 flex items-center justify-between gap-4 text-[10px] text-slate-400">
                  <span className="num-en">
                    {new Date(m.timestamp).toLocaleTimeString('en-GB', {
                      hour: '2-digit',
                      minute: '2-digit',
                    })}
                  </span>
                  {m.role === 'assistant' && (
                    <button
                      onClick={() => speakText(m.content)}
                      className="text-cyan-400 hover:underline"
                    >
                      استماع صوتي
                    </button>
                  )}
                </div>
              </div>
            </div>
          ))}

          {sending && (
            <div className="flex items-center gap-2 text-xs text-cyan-300">
              <Loader2 className="h-4 w-4 animate-spin" />
              <span>جاري التحليل والربط بذاكرتك المتاحة...</span>
            </div>
          )}
          <div ref={endOfChatRef} />
        </div>

        {/* Suggested Memories Extraction */}
        {suggestedMemories.length > 0 && (
          <div className="my-2 rounded-2xl border border-emerald-500/35 bg-emerald-950/20 p-3 text-xs">
            <div className="mb-1.5 font-bold text-emerald-300">
              هل ترغب في حفظ هذه المعلومات في ذاكرتك الذكية؟
            </div>
            <div className="flex flex-wrap gap-2">
              {suggestedMemories.map((sm, idx) => (
                <button
                  key={idx}
                  onClick={() => {
                    onAddMemory({
                      id: `mem_${Date.now()}_${idx}`,
                      userId: profile.userId,
                      category: sm.category || 'PERSONAL',
                      certainty: sm.certainty || 'CONFIRMED',
                      title: sm.title,
                      content: sm.content,
                      source: 'موافقة المستخدم من المحادثة',
                      createdAt: new Date().toISOString(),
                      updatedAt: new Date().toISOString(),
                    });
                    setSuggestedMemories((prev) => prev.filter((_, i) => i !== idx));
                  }}
                  className="flex items-center gap-1.5 rounded-xl border border-emerald-400/40 bg-[#080A12] px-3 py-1.5 text-emerald-200 hover:bg-emerald-500/20"
                >
                  <PlusCircle className="h-3.5 w-3.5" />
                  <span>حفظ: {sm.title}</span>
                </button>
              ))}
            </div>
          </div>
        )}

        {/* Composer */}
        <form onSubmit={handleSend} className="mt-2 space-y-2 border-t border-slate-800 pt-3">
          {imagePreview && (
            <div className="flex items-center gap-2 text-xs text-cyan-300">
              <img src={imagePreview} alt="معاينة" className="h-12 w-12 rounded-lg object-cover" />
              <span>صورة مرفقة للتحليل</span>
              <button type="button" onClick={() => setImagePreview(null)} className="text-rose-400">
                إزالة
              </button>
            </div>
          )}
          <div className="flex items-center gap-2">
            <label className="flex h-11 w-11 shrink-0 cursor-pointer items-center justify-center rounded-xl border border-slate-700 bg-[#121526] text-slate-300 hover:border-cyan-400">
              <ImagePlus className="h-5 w-5" />
              <input type="file" accept="image/*" className="hidden" onChange={handleImagePick} />
            </label>
            <button
              type="button"
              onClick={toggleVoiceRecording}
              aria-label="تسجيل صوتي"
              className={`flex h-11 w-11 shrink-0 items-center justify-center rounded-xl border ${
                recording
                  ? 'border-rose-500 bg-rose-500/20 text-rose-300'
                  : 'border-slate-700 bg-[#121526] text-slate-300 hover:border-cyan-400'
              }`}
            >
              {recording ? <MicOff className="h-5 w-5" /> : <Mic className="h-5 w-5" />}
            </button>
            <input
              type="text"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              placeholder="اسأل سَبَق بالعربية أو المصرية..."
              className="h-11 flex-1 rounded-xl border border-slate-700 bg-[#06070D] px-4 text-sm text-white focus:border-cyan-400 focus:outline-none"
            />
            <button
              type="submit"
              disabled={sending}
              aria-label="إرسال"
              className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-gradient-to-l from-violet-600 to-cyan-500 text-white disabled:opacity-50"
            >
              <Send className="h-5 w-5" />
            </button>
          </div>
        </form>
      </div>

      {/* Proactive Second Brain Panel */}
      <div className="space-y-4 lg:col-span-4">
        <div className="rounded-3xl border border-violet-500/25 bg-[#0C0E1A] p-5 space-y-3">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <Sparkles className="h-5 w-5 text-cyan-400" />
              <h3 className="text-base font-bold text-white">المقترحات الاستباقية</h3>
            </div>
            <button
              onClick={fetchProactiveInsights}
              disabled={proactiveLoading}
              className="rounded-xl border border-cyan-500/40 bg-cyan-500/10 px-3 py-1.5 text-xs font-bold text-cyan-300 hover:bg-cyan-500/20"
            >
              {proactiveLoading ? 'جاري الفحص...' : 'تحديث الآن'}
            </button>
          </div>

          <p className="text-xs text-slate-400 leading-relaxed">
            يربط سَبَق معلوماتك المحفوظة ومشروعاتك وفحوصك لتقديم خطوات عملية — مع احترام أوقات الهدوء ومنع ادعاء قراءة الأفكار.
          </p>

          {proactiveMessage && (
            <div className="rounded-xl border border-slate-800 bg-[#080A12] p-3 text-xs text-slate-300">
              {proactiveMessage}
            </div>
          )}

          <div className="space-y-2.5">
            {proactiveList.map((item, idx) => (
              <div
                key={idx}
                className="rounded-2xl border border-violet-500/30 bg-[#121528] p-3.5 text-xs space-y-1.5"
              >
                <div className="flex items-center justify-between">
                  <span className="font-bold text-cyan-300">{item.title}</span>
                  <span className="text-[10px] text-violet-300">
                    {item.certainty === 'CONFIRMED'
                      ? 'معلومة مؤكدة'
                      : item.certainty === 'EXPECTATION'
                      ? 'توقع'
                      : 'استنتاج'}
                  </span>
                </div>
                <p className="text-slate-200">{item.body}</p>
                <p className="text-[11px] text-slate-400">الأساس: {item.basis}</p>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
};
