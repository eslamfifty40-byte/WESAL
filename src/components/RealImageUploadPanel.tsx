import React, { useEffect, useRef, useState } from 'react';
import {
  AlertTriangle,
  Camera,
  CheckCircle2,
  FolderOpen,
  Image as ImageIcon,
  Loader2,
  RefreshCw,
  Upload,
} from 'lucide-react';
import {
  prepareAndCompressImage,
  PreparedImagePreview,
  uploadImageMultipart,
} from '../utils/imageUploader';
import { apiFetchJson } from '../services/apiClient';
import { resolveApiUrl } from '../apiBase';
import { isCapacitorNativePlatform, pickImageWithCapacitorNative } from '../utils/capacitorCamera';
import type { HealthScanRecord, HealthSectionId, UploadedImageRecord } from '../types/sabq';

interface RealImageUploadPanelProps {
  userId: string;
  sectionId: 'FACE_EYE_SKIN' | 'HAIR_SCALP' | 'BODY_POSTURE';
  titleAr: string;
  subtitleAr: string;
  previousScan?: HealthScanRecord;
  onImageUploadedAndAnalyzed: (
    uploaded: UploadedImageRecord,
    scanResult: HealthScanRecord
  ) => void;
}

export const RealImageUploadPanel: React.FC<RealImageUploadPanelProps> = ({
  userId,
  sectionId,
  titleAr,
  subtitleAr,
  previousScan,
  onImageUploadedAndAnalyzed,
}) => {
  const [prepared, setPrepared] = useState<PreparedImagePreview | null>(null);
  const [sourceOrigin, setSourceOrigin] = useState<'camera' | 'gallery' | 'file'>('gallery');
  const [preparing, setPreparing] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [analyzing, setAnalyzing] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [uploadedRecord, setUploadedRecord] = useState<UploadedImageRecord | null>(null);
  const [analysisResult, setAnalysisResult] = useState<HealthScanRecord | null>(null);

  const inFlightRef = useRef(false);
  const mountedRef = useRef(true);
  const isNativeCap = isCapacitorNativePlatform();

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  // Reset panel state strictly when userId or sectionId changes
  useEffect(() => {
    setPrepared(null);
    setUploadedRecord(null);
    setAnalysisResult(null);
    setErrorMsg(null);
    setPreparing(false);
    setUploading(false);
    setAnalyzing(false);
    inFlightRef.current = false;
  }, [userId, sectionId]);

  // Revoke previous object URL on change or unmount to avoid memory leaks
  useEffect(() => {
    return () => {
      if (prepared?.previewUrl) {
        URL.revokeObjectURL(prepared.previewUrl);
      }
    };
  }, [prepared?.previewUrl]);

  const prepareFile = async (file: File, origin: 'camera' | 'gallery' | 'file') => {
    if (!file || inFlightRef.current) return;
    setErrorMsg(null);
    setUploadedRecord(null);
    setAnalysisResult(null);
    setSourceOrigin(origin);
    setPreparing(true);

    try {
      const prep = await prepareAndCompressImage(file);
      if (mountedRef.current) {
        setPrepared(prep);
      } else {
        URL.revokeObjectURL(prep.previewUrl);
      }
    } catch (err: any) {
      if (mountedRef.current) {
        setPrepared(null);
        setErrorMsg(err?.message || 'تعذر تجهيز الصورة المختارة.');
      }
    } finally {
      if (mountedRef.current) setPreparing(false);
    }
  };

  const handleFileChosen = async (
    e: React.ChangeEvent<HTMLInputElement>,
    origin: 'camera' | 'gallery' | 'file'
  ) => {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;
    await prepareFile(file, origin);
  };

  const handleNativePick = async (origin: 'camera' | 'gallery') => {
    if (inFlightRef.current || preparing) return;
    setErrorMsg(null);
    try {
      const result = await pickImageWithCapacitorNative(origin);
      if (!result || 'cancelled' in result) return;
      await prepareFile(result.file, origin);
    } catch (err: any) {
      if (mountedRef.current) {
        setErrorMsg(err?.message || 'تعذر الوصول إلى الكاميرا أو المعرض.');
      }
    }
  };

  const handleUploadAndAnalyze = async () => {
    if (!prepared || inFlightRef.current) return;
    inFlightRef.current = true;
    setErrorMsg(null);
    setUploading(true);
    setAnalyzing(false);

    try {
      // Step 1: Real multipart/form-data upload to server (reuse if already uploaded for retry!)
      let uploaded = uploadedRecord;
      if (!uploaded) {
        uploaded = await uploadImageMultipart({
          userId,
          sectionId: sectionId as HealthSectionId,
          sourceOrigin,
          prepared,
        });
        if (!mountedRef.current) return;
        setUploadedRecord(uploaded);
      }
      setUploading(false);
      setAnalyzing(true);

      // Step 2: Analyze the verified uploaded file on server (60s timeout for vision AI)
      const data = await apiFetchJson<{
        ok?: boolean;
        imageProcessed?: boolean;
        isAreaVisible?: boolean;
        summary?: string;
        observations?: string[];
        potentialIssues?: string[];
        recommendations?: string[];
        doctorConsultationCriteria?: string;
        confidenceScore?: number;
        source?: string;
        error?: string;
      }>(
        '/api/ai/analyze-uploaded-image',
        {
          method: 'POST',
          body: JSON.stringify({
            userId,
            filename: uploaded.filename,
            sectionId,
            previousComparisonContext: previousScan
              ? `الفحص السابق (${new Date(previousScan.timestamp).toLocaleDateString('en-GB')}): ${previousScan.summary}`
              : undefined,
          }),
        },
        60000
      );
      if (!mountedRef.current) return;

      if (!data.ok || !data.imageProcessed) {
        throw new Error(data.error || 'لم تكتمل معالجة الصورة المرفوعة — لم تُحفظ نتيجة.');
      }

      if (!data.isAreaVisible) {
        setErrorMsg(
          data.summary ||
            'الصورة المرفوعة لا تُظهر المنطقة المطلوبة بوضوح كافٍ. الرجاء اختيار صورة أوضح وإعادة المحاولة.'
        );
        return;
      }

      const scanRec: HealthScanRecord = {
        id: `scan_${sectionId}_${Date.now()}`,
        imageId: uploaded.id,
        userId,
        scanType: sectionId,
        scanTitleAr: titleAr,
        summary: data.summary || '',
        observations: data.observations || [],
        potentialIssues: data.potentialIssues || [],
        recommendations: data.recommendations || [],
        doctorConsultationCriteria: data.doctorConsultationCriteria || '',
        confidenceScore: typeof data.confidenceScore === 'number' ? data.confidenceScore : null,
        isNonDiagnosticEstimate: true,
        source: data.source || 'تحليل فعلي للصورة المرفوعة',
        isAreaVisible: Boolean(data.isAreaVisible),
        imageUrl: uploaded.url,
        timestamp: new Date().toISOString(),
      };

      setAnalysisResult(scanRec);
      onImageUploadedAndAnalyzed(uploaded, scanRec);
    } catch (err: any) {
      if (mountedRef.current) {
        setErrorMsg(err?.message || 'حدث خطأ أثناء رفع أو تحليل الصورة. يمكنك إعادة المحاولة دون فقدان الصورة.');
      }
    } finally {
      if (mountedRef.current) {
        setUploading(false);
        setAnalyzing(false);
      }
      inFlightRef.current = false;
    }
  };

  const previousImgRaw = previousScan?.imageUrl || previousScan?.savedImageDataUrl;
  const previousImgSrc = previousImgRaw ? resolveApiUrl(previousImgRaw) : undefined;

  return (
    <div className="rounded-3xl border border-violet-500/25 bg-[#0C0E1A] p-5 space-y-4">
      <div>
        <h3 className="text-base font-bold text-white">{titleAr}</h3>
        <p className="text-xs text-slate-400">{subtitleAr}</p>
      </div>

      {/* Three Real Pickers: Camera, Gallery, Files (with native Capacitor Camera support on APK) */}
      <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
        {isNativeCap ? (
          <button
            type="button"
            onClick={() => handleNativePick('camera')}
            className="flex min-h-[44px] cursor-pointer items-center justify-center gap-2 rounded-xl border border-cyan-500/40 bg-cyan-500/10 px-3 py-2 text-xs font-bold text-cyan-200 hover:bg-cyan-500/20"
          >
            <Camera className="h-4 w-4" />
            <span>التقاط بالكاميرا</span>
          </button>
        ) : (
          <label className="flex min-h-[44px] cursor-pointer items-center justify-center gap-2 rounded-xl border border-cyan-500/40 bg-cyan-500/10 px-3 py-2 text-xs font-bold text-cyan-200 hover:bg-cyan-500/20">
            <Camera className="h-4 w-4" />
            <span>التقاط بالكاميرا</span>
            <input
              type="file"
              accept="image/*"
              capture="environment"
              className="hidden"
              onChange={(e) => handleFileChosen(e, 'camera')}
            />
          </label>
        )}

        {isNativeCap ? (
          <button
            type="button"
            onClick={() => handleNativePick('gallery')}
            className="flex min-h-[44px] cursor-pointer items-center justify-center gap-2 rounded-xl border border-violet-500/40 bg-violet-500/10 px-3 py-2 text-xs font-bold text-violet-200 hover:bg-violet-500/20"
          >
            <ImageIcon className="h-4 w-4" />
            <span>من معرض الصور</span>
          </button>
        ) : (
          <label className="flex min-h-[44px] cursor-pointer items-center justify-center gap-2 rounded-xl border border-violet-500/40 bg-violet-500/10 px-3 py-2 text-xs font-bold text-violet-200 hover:bg-violet-500/20">
            <ImageIcon className="h-4 w-4" />
            <span>من معرض الصور</span>
            <input
              type="file"
              accept="image/*"
              className="hidden"
              onChange={(e) => handleFileChosen(e, 'gallery')}
            />
          </label>
        )}

        <label className="flex min-h-[44px] cursor-pointer items-center justify-center gap-2 rounded-xl border border-slate-700 bg-[#121526] px-3 py-2 text-xs font-bold text-slate-200 hover:border-cyan-400">
          <FolderOpen className="h-4 w-4" />
          <span>من ملفات الهاتف</span>
          <input
            type="file"
            accept="image/*"
            className="hidden"
            onChange={(e) => handleFileChosen(e, 'file')}
          />
        </label>
      </div>

      {preparing && (
        <div className="flex items-center justify-center gap-2 rounded-2xl border border-cyan-500/35 bg-[#080A12] p-3 text-xs text-cyan-300">
          <Loader2 className="h-4 w-4 animate-spin" />
          <span>جاري قراءة الصورة وتجهيز المعاينة...</span>
        </div>
      )}

      {/* Image Preview BEFORE sending + File stats */}
      {prepared && (
        <div className="rounded-2xl border border-slate-800 bg-[#080A12] p-4 space-y-3">
          <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-slate-300">
            <span className="font-bold text-cyan-300">معاينة الصورة قبل التحليل</span>
            <span className="num-en text-[11px] text-slate-400">
              {prepared.width}×{prepared.height} · {(prepared.compressedSizeBytes / 1024).toFixed(0)} KB ({prepared.mimeType.replace('image/', '').toUpperCase()})
            </span>
          </div>

          <img
            src={prepared.previewUrl}
            alt="معاينة الصورة المختارة"
            className="mx-auto max-h-64 rounded-xl border border-cyan-500/30 object-contain"
          />

          {uploadedRecord && (
            <div className="text-center text-[11px] text-emerald-300">
              تم تأكيد حفظ الصورة على الخادم ({uploadedRecord.filename})
            </div>
          )}

          <button
            type="button"
            disabled={uploading || analyzing}
            onClick={handleUploadAndAnalyze}
            className="flex min-h-[46px] w-full items-center justify-center gap-2 rounded-xl bg-gradient-to-l from-violet-600 to-cyan-500 px-4 py-2 text-sm font-bold text-white disabled:opacity-50"
          >
            {uploading ? (
              <>
                <Loader2 className="h-4 w-4 animate-spin" />
                <span>جاري رفع الصورة إلى الخادم (multipart)...</span>
              </>
            ) : analyzing ? (
              <>
                <Loader2 className="h-4 w-4 animate-spin" />
                <span>تم الرفع — جاري التحليل الفعلي للصورة...</span>
              </>
            ) : errorMsg ? (
              <>
                <RefreshCw className="h-4 w-4" />
                <span>إعادة المحاولة (الصورة محفوظة)</span>
              </>
            ) : (
              <>
                <Upload className="h-4 w-4" />
                <span>رفع الصورة وتنفيذ التحليل الفعلي</span>
              </>
            )}
          </button>
        </div>
      )}

      {errorMsg && (
        <div className="flex items-start gap-2 rounded-2xl border border-rose-500/40 bg-rose-500/10 p-3 text-xs text-rose-200">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
          <span>{errorMsg}</span>
        </div>
      )}

      {analysisResult && (
        <div className="rounded-2xl border border-emerald-500/35 bg-emerald-950/15 p-4 space-y-2.5 text-xs">
          <div className="flex items-center justify-between">
            <span className="flex items-center gap-1.5 font-bold text-emerald-300">
              <CheckCircle2 className="h-4 w-4" />
              <span>تم التحليل الفعلي للصورة المرفوعة</span>
            </span>
            <span className="text-[11px] text-slate-400">تقدير غير تشخيصي</span>
          </div>
          <p className="text-slate-100 leading-relaxed">{analysisResult.summary}</p>
          {analysisResult.observations.length > 0 && (
            <div className="text-slate-300">
              <strong>الملاحظات الظاهرة:</strong> {analysisResult.observations.join(' · ')}
            </div>
          )}
          {analysisResult.potentialIssues.length > 0 && (
            <div className="text-amber-200">
              <strong>العلامات المحتملة:</strong> {analysisResult.potentialIssues.join(' · ')}
            </div>
          )}
          {analysisResult.recommendations.length > 0 && (
            <div className="text-cyan-200">
              <strong>روتين وتوصيات العناية:</strong> {analysisResult.recommendations.join(' · ')}
            </div>
          )}
          {analysisResult.doctorConsultationCriteria && (
            <div className="text-slate-300">
              <strong>متى تراجع الطبيب:</strong> {analysisResult.doctorConsultationCriteria}
            </div>
          )}
        </div>
      )}

      {/* Visual & Text Comparison with Previous Scan */}
      {previousScan && (
        <div className="rounded-2xl border border-slate-800 bg-[#080A12] p-3.5 text-xs space-y-2">
          <div className="font-bold text-cyan-300">
            سجل الفحص السابق للمقارنة ({new Date(previousScan.timestamp).toLocaleDateString('en-GB')})
          </div>
          <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
            {previousImgSrc && (
              <img
                src={previousImgSrc}
                alt="الصورة السابقة للمقارنة"
                className="h-24 w-28 shrink-0 rounded-xl border border-violet-500/35 object-cover"
              />
            )}
            <p className="text-slate-300 leading-relaxed">{previousScan.summary}</p>
          </div>
        </div>
      )}
    </div>
  );
};
