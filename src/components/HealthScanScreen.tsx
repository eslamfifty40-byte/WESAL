import React, { useEffect, useRef, useState } from 'react';
import {
  Activity,
  AlertTriangle,
  Camera,
  CheckCircle2,
  Eye,
  FileDown,
  FileText,
  Fingerprint,
  Loader2,
  Play,
  RefreshCw,
  Sparkles,
  SwitchCamera,
  Upload,
  UserCheck,
  XCircle,
} from 'lucide-react';
import { analyzePpgSamples, PpgSampleResult } from '../utils/ppgProcessor';
import {
  captureVideoFrameAndHairCrop,
  evaluateSmartScanStageFrame,
  SmartScanStage,
  StageFrameGuidance,
} from '../utils/autoScanDetector';
import { generateSabqArabicPdfBlob } from '../utils/arabicPdfGenerator';
import { blobToDataUrl, prepareAndCompressImage, PreparedImagePreview, uploadImageMultipart } from '../utils/imageUploader';
import { apiFetchJson } from '../services/apiClient';
import { resolveApiUrl } from '../apiBase';
import { isCapacitorNativePlatform, pickImageWithCapacitorNative } from '../utils/capacitorCamera';
import { loadScanSessionDraft, saveScanSessionDraft } from '../services/userStore';
import type {
  HealthScanRecord,
  LabReading,
  ReportCategoryType,
  ReportRecord,
  UploadedImageRecord,
  UserProfile,
} from '../types/sabq';

interface HealthScanScreenProps {
  profile: UserProfile;
  scans: HealthScanRecord[];
  labs: LabReading[];
  reports: ReportRecord[];
  cameraAllowed: boolean;
  initialMode?: string;
  onSaveScanAndReport: (
    scansToAdd: HealthScanRecord[],
    reportsToAdd: ReportRecord[],
    updatedHeightWeight?: { heightCm?: number; weightKg?: number },
    extraLabs?: LabReading[],
    uploadedImage?: UploadedImageRecord
  ) => void;
  onOpenReportTab: () => void;
}

type GuidedPhase = 'FACE_STAGE' | 'BODY_STAGE' | 'HAIR_STAGE' | 'REVIEW_STAGE' | 'BIOFINGER';

export const HealthScanScreen: React.FC<HealthScanScreenProps> = ({
  profile,
  scans,
  labs,
  reports,
  cameraAllowed,
  initialMode,
  onSaveScanAndReport,
  onOpenReportTab,
}) => {
  const [phase, setPhase] = useState<GuidedPhase>(() => {
    if (initialMode === 'BIOFINGER') return 'BIOFINGER';
    if (initialMode === 'BODY') return 'BODY_STAGE';
    if (initialMode === 'HAIR') return 'HAIR_STAGE';
    if (initialMode === 'FACE' || initialMode === 'FACE_EYE') return 'FACE_STAGE';
    const d = loadScanSessionDraft<{ phase?: GuidedPhase }>(profile.userId);
    return d?.phase || 'FACE_STAGE';
  });

  const videoRef = useRef<HTMLVideoElement | null>(null);
  const ppgCanvasRef = useRef<HTMLCanvasElement | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const prevGrayRef = useRef<Uint8Array | null>(null);
  const lastCaptureTimeRef = useRef<number>(0);
  const busyStageRef = useRef<boolean>(false);
  const evaluatingRef = useRef<boolean>(false);
  const mountedRef = useRef<boolean>(true);
  const draftLoadedForUserRef = useRef<string>(profile.userId);
  const stageAbortRef = useRef<AbortController | null>(null);
  const autoCapturePausedRef = useRef<boolean>(false);
  const cameraFacingRef = useRef<'user' | 'environment'>('user');
  const [preferRearGuided, setPreferRearGuided] = useState(false);
  const isNativeCap = isCapacitorNativePlatform();

  const [cameraConsent, setCameraConsent] = useState(false);
  const [savePhotosConsent, setSavePhotosConsent] = useState(true);
  const [cameraActive, setCameraActive] = useState(false);
  const [cameraError, setCameraError] = useState<string | null>(null);

  const [liveGuidance, setLiveGuidance] = useState<StageFrameGuidance | null>(null);
  const [transitionBanner, setTransitionBanner] = useState<string | null>(() => {
    const d = loadScanSessionDraft<{ transitionBanner?: string | null }>(profile.userId);
    return d?.transitionBanner || null;
  });
  const [processingStage, setProcessingStage] = useState(false);
  const [processingText, setProcessingText] = useState('جاري تحليل المرحلة وحفظ التقرير المستقل...');
  const [autoCapturePaused, setAutoCapturePaused] = useState(false);
  const [stageError, setStageError] = useState<string | null>(null);
  const [fallbackPrepared, setFallbackPrepared] = useState<{
    prep: PreparedImagePreview;
    uploaded?: UploadedImageRecord;
    base64: string;
  } | null>(null);

  // Session state for the 3 guided stages + 5 region outcomes
  const [sessionId, setSessionId] = useState(() => `smart_sess_${Date.now()}`);
  const [stagePhotos, setStagePhotos] = useState<{
    face?: string;
    body?: string;
    hair?: string;
  }>({});
  const [stageResults, setStageResults] = useState<Partial<Record<
    'FACE' | 'EYE' | 'SKIN' | 'BODY_POSTURE' | 'HAIR',
    HealthScanRecord
  >>>(() => {
    const d = loadScanSessionDraft<{
      stageResults?: Partial<Record<'FACE' | 'EYE' | 'SKIN' | 'BODY_POSTURE' | 'HAIR', HealthScanRecord>>;
    }>(profile.userId);
    return d?.stageResults || {};
  });

  // BioFinger PPG state
  const [ppgRunning, setPpgRunning] = useState(false);
  const [ppgProgress, setPpgProgress] = useState(0);
  const [ppgResult, setPpgResult] = useState<PpgSampleResult | null>(null);
  const ppgTimerRef = useRef<number | null>(null);

  const abortInFlightStage = () => {
    if (stageAbortRef.current) {
      stageAbortRef.current.abort();
      stageAbortRef.current = null;
    }
    busyStageRef.current = false;
    if (mountedRef.current) {
      setProcessingStage(false);
    }
  };

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      if (stageAbortRef.current) {
        stageAbortRef.current.abort();
        stageAbortRef.current = null;
      }
    };
  }, []);

  // Reload isolated draft whenever userId changes so no session data leaks between users
  useEffect(() => {
    if (draftLoadedForUserRef.current !== profile.userId) {
      draftLoadedForUserRef.current = profile.userId;
      abortInFlightStage();
      stopCamera();
      autoCapturePausedRef.current = false;
      setAutoCapturePaused(false);
      setCameraConsent(false);
      setFallbackPrepared(null);
      setStagePhotos({});
      setPpgResult(null);
      setStageError(null);
      setSessionId(`smart_sess_${Date.now()}`);
      const userDraft = loadScanSessionDraft<{
        phase?: GuidedPhase;
        stageResults?: Partial<Record<'FACE' | 'EYE' | 'SKIN' | 'BODY_POSTURE' | 'HAIR', HealthScanRecord>>;
        transitionBanner?: string | null;
      }>(profile.userId);
      setStageResults(userDraft?.stageResults || {});
      setTransitionBanner(userDraft?.transitionBanner || null);
      if (!initialMode) {
        setPhase(userDraft?.phase || 'FACE_STAGE');
      }
    }
  }, [profile.userId]);

  useEffect(() => {
    saveScanSessionDraft(profile.userId, {
      phase,
      stageResults,
      transitionBanner,
    });
  }, [profile.userId, phase, stageResults, transitionBanner]);

  // Revoke fallback preview URL on change/unmount
  useEffect(() => {
    return () => {
      if (fallbackPrepared?.prep.previewUrl) {
        URL.revokeObjectURL(fallbackPrepared.prep.previewUrl);
      }
    };
  }, [fallbackPrepared?.prep.previewUrl]);

  useEffect(() => {
    if (initialMode === 'BIOFINGER') setPhase('BIOFINGER');
    else if (initialMode === 'BODY') setPhase('BODY_STAGE');
    else if (initialMode === 'HAIR') setPhase('HAIR_STAGE');
    else if (initialMode === 'FACE' || initialMode === 'FACE_EYE') setPhase('FACE_STAGE');
  }, [initialMode]);

  // Stop camera strictly when leaving component or hiding tab
  useEffect(() => {
    const onVisibility = () => {
      if (document.hidden) stopCamera();
    };
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      document.removeEventListener('visibilitychange', onVisibility);
      stopCamera();
    };
  }, []);

  // Auto-open selected front/rear camera once consent granted in guided camera stages
  useEffect(() => {
    const isGuidedCameraStage =
      phase === 'FACE_STAGE' || phase === 'BODY_STAGE' || phase === 'HAIR_STAGE';
    const expectedFacing: 'user' | 'environment' = preferRearGuided ? 'environment' : 'user';
    if (isGuidedCameraStage && cameraConsent && cameraAllowed && (!cameraActive || cameraFacingRef.current !== expectedFacing)) {
      startCamera(preferRearGuided);
    }
    if (phase === 'REVIEW_STAGE' || (phase === 'BIOFINGER' && cameraActive && cameraFacingRef.current !== 'environment')) {
      stopCamera();
    }
  }, [phase, cameraConsent, cameraAllowed, preferRearGuided]);

  // Battery-friendly sequential live guidance & auto-capture loop (~4 fps, never overlapping)
  useEffect(() => {
    const isGuidedStage =
      phase === 'FACE_STAGE' || phase === 'BODY_STAGE' || phase === 'HAIR_STAGE';
    if (!isGuidedStage || !cameraActive) return;

    let cancelled = false;
    let timerId: number | null = null;

    const tick = async () => {
      if (cancelled || !mountedRef.current) return;
      const video = videoRef.current;
      if (video && video.readyState >= 2 && video.videoWidth > 0 && !busyStageRef.current && !evaluatingRef.current) {
        evaluatingRef.current = true;
        try {
          const { guidance, grayFrame } = await evaluateSmartScanStageFrame(
            phase as SmartScanStage,
            video,
            prevGrayRef.current
          );
          if (!cancelled && mountedRef.current) {
            prevGrayRef.current = grayFrame;
            setLiveGuidance(guidance);

            const now = Date.now();
            if (
              !autoCapturePausedRef.current &&
              !busyStageRef.current &&
              guidance.isReadyToCapture &&
              now - lastCaptureTimeRef.current > 4000
            ) {
              lastCaptureTimeRef.current = now;
              const { fullFrameDataUrl, hairCropDataUrl } = captureVideoFrameAndHairCrop(video);
              const imageToSend = phase === 'HAIR_STAGE' ? hairCropDataUrl : fullFrameDataUrl;
              setProcessingText('جاري تحليل المرحلة وحفظ التقرير المستقل...');
              await executeStageAnalysis(phase, imageToSend);
            }
          }
        } catch {
          // keep loop resilient
        } finally {
          evaluatingRef.current = false;
        }
      }
      if (!cancelled && mountedRef.current) {
        timerId = window.setTimeout(tick, 250);
      }
    };

    timerId = window.setTimeout(tick, 250);
    return () => {
      cancelled = true;
      if (timerId !== null) window.clearTimeout(timerId);
    };
  }, [phase, cameraActive, savePhotosConsent]);

  const stopCamera = () => {
    if (ppgTimerRef.current) {
      window.clearInterval(ppgTimerRef.current);
      ppgTimerRef.current = null;
    }
    if (streamRef.current) {
      streamRef.current.getTracks().forEach((t) => t.stop());
      streamRef.current = null;
    }
    setCameraActive(false);
    setPpgRunning(false);
  };

  const startCamera = async (preferRear: boolean): Promise<boolean> => {
    setCameraError(null);
    setStageError(null);
    if (!cameraAllowed) {
      setCameraError('تم إيقاف إذن الكاميرا من الإعدادات.');
      return false;
    }
    if (!navigator.mediaDevices?.getUserMedia) {
      setCameraError('الكاميرا غير مدعومة في هذا المتصفح. يمكنك استخدام خيار رفع صورة للمرحلة الحالية.');
      return false;
    }
    try {
      stopCamera();
      const stream = await navigator.mediaDevices.getUserMedia({
        video: {
          facingMode: preferRear ? { ideal: 'environment' } : { ideal: 'user' },
          width: { ideal: 1280 },
          height: { ideal: 720 },
        },
        audio: false,
      });
      if (!mountedRef.current) {
        stream.getTracks().forEach((t) => t.stop());
        return false;
      }
      streamRef.current = stream;
      cameraFacingRef.current = preferRear ? 'environment' : 'user';
      setCameraActive(true);
      if (videoRef.current) {
        videoRef.current.srcObject = stream;
        await videoRef.current.play().catch(() => {});
      }
      return true;
    } catch {
      if (mountedRef.current) {
        setCameraError('تعذر فتح الكاميرا. امنح الإذن للمتصفح أو استخدم التقاط/رفع صورة للمرحلة الحالية.');
      }
      return false;
    }
  };

  const buildIndependentReport = (
    type: ReportCategoryType,
    title: string,
    scanRecord: HealthScanRecord
  ): ReportRecord => {
    const prevSame = reports.find((r) => r.reportType === type);
    return {
      id: `rep_${type}_${Date.now()}_${Math.random().toString(36).slice(2, 5)}`,
      userId: profile.userId,
      reportType: type,
      title,
      createdAt: new Date().toISOString(),
      scansCount: 1,
      labsCount: 0,
      comparisonSummary: prevSame
        ? `مقارنة بالتقرير السابق (${prevSame.title}) بتاريخ ${new Date(
            prevSame.createdAt
          ).toLocaleDateString('en-GB')}.`
        : 'التقرير الأول المسجل لهذا القسم.',
      referenceImageUrl: scanRecord.imageUrl,
      referenceImageDataUrl: scanRecord.savedImageDataUrl,
      snapshotScans: [scanRecord],
      snapshotLabs: [],
    };
  };

  const executeStageAnalysis = async (
    targetStage: 'FACE_STAGE' | 'BODY_STAGE' | 'HAIR_STAGE',
    imageBase64: string,
    uploadedMeta?: UploadedImageRecord,
    mimeType = 'image/jpeg'
  ) => {
    const ctrl = new AbortController();
    stageAbortRef.current = ctrl;
    busyStageRef.current = true;
    setProcessingStage(true);
    setProcessingText('جاري تحليل المرحلة وحفظ التقرير المستقل...');
    setStageError(null);

    const apiStage =
      targetStage === 'FACE_STAGE' ? 'face' : targetStage === 'BODY_STAGE' ? 'body' : 'hair';

    try {
      const data = await apiFetchJson<any>(
        '/api/ai/smart-scan-stage',
        {
          method: 'POST',
          body: JSON.stringify({
            stage: apiStage,
            imageBase64,
            mimeType,
          }),
          signal: ctrl.signal,
        },
        65000
      );
      if (ctrl.signal.aborted || !mountedRef.current) return;

      const nowIso = new Date().toISOString();
      const newScans: HealthScanRecord[] = [];
      const newReports: ReportRecord[] = [];

      if (apiStage === 'face') {
        if (!data.face?.isVisible) {
          autoCapturePausedRef.current = true;
          setAutoCapturePaused(true);
          setStageError(data.face?.guidanceAr || 'الوجه غير واضح بالصورة — عدّل وضعيتك أمام الكاميرا ثم اضغط التقاط أو استئناف.');
          return;
        }
        if (savePhotosConsent) {
          setStagePhotos((p) => ({ ...p, face: imageBase64 }));
        }
        const mappings: Array<{ key: 'face' | 'eye' | 'skin'; type: 'FACE' | 'EYE' | 'SKIN'; title: string }> = [
          { key: 'face', type: 'FACE', title: 'تحليل الوجه' },
          { key: 'eye', type: 'EYE', title: 'تحليل العين' },
          { key: 'skin', type: 'SKIN', title: 'تحليل البشرة' },
        ];
        for (const m of mappings) {
          const reg = data[m.key];
          if (!reg) continue;
          const rec: HealthScanRecord = {
            id: `scan_${m.type}_${Date.now()}_${m.key}`,
            sessionId,
            userId: profile.userId,
            scanType: m.type,
            scanTitleAr: m.title,
            summary: reg.summary,
            observations: reg.observations || [],
            potentialIssues: reg.potentialIssues || [],
            severityLevel: reg.severityLevel,
            limitations: reg.limitations,
            recommendations: reg.recommendations || [],
            doctorConsultationCriteria: reg.doctorConsultationCriteria,
            confidenceScore: typeof reg.confidenceScore === 'number' ? reg.confidenceScore : null,
            isNonDiagnosticEstimate: true,
            source: data.source,
            isAreaVisible: Boolean(reg.isVisible),
            angleGuidanceAr: reg.guidanceAr,
            savedImageDataUrl: savePhotosConsent ? imageBase64 : undefined,
            imageUrl: uploadedMeta?.url,
            imageId: uploadedMeta?.id,
            timestamp: nowIso,
          };
          newScans.push(rec);
          newReports.push(buildIndependentReport(m.type, `تقرير ${m.title} المستقل`, rec));
          setStageResults((prev) => ({ ...prev, [m.type]: rec }));
        }
        onSaveScanAndReport(newScans, newReports, undefined, undefined, uploadedMeta);
        setFallbackPrepared(null);
        autoCapturePausedRef.current = false;
        setAutoCapturePaused(false);
        lastCaptureTimeRef.current = Date.now();
        setTransitionBanner('ممتاز! انتهى فحص الوجه. الآن نحتاج إلى فحص الجسم بالكامل.');
        setPhase('BODY_STAGE');
      } else if (apiStage === 'body') {
        if (!data.body?.isVisible) {
          autoCapturePausedRef.current = true;
          setAutoCapturePaused(true);
          setStageError(data.body?.guidanceAr || 'الجسم بالكامل غير ظاهر — ابتعد قليلًا ليظهر الرأس والقدمان ثم أعد المحاولة.');
          return;
        }
        if (savePhotosConsent) {
          setStagePhotos((p) => ({ ...p, body: imageBase64 }));
        }
        const reg = data.body;
        const rec: HealthScanRecord = {
          id: `scan_BODY_${Date.now()}`,
          sessionId,
          userId: profile.userId,
          scanType: 'BODY_POSTURE',
          scanTitleAr: 'تحليل الجسم ووضعية الوقوف',
          summary: reg.summary,
          observations: reg.observations || [],
          potentialIssues: reg.potentialIssues || [],
          severityLevel: reg.severityLevel,
          limitations: reg.limitations,
          recommendations: reg.recommendations || [],
          doctorConsultationCriteria: reg.doctorConsultationCriteria,
          confidenceScore: typeof reg.confidenceScore === 'number' ? reg.confidenceScore : null,
          isNonDiagnosticEstimate: true,
          source: data.source,
          isAreaVisible: Boolean(reg.isVisible),
          savedImageDataUrl: savePhotosConsent ? imageBase64 : undefined,
          imageUrl: uploadedMeta?.url,
          imageId: uploadedMeta?.id,
          timestamp: nowIso,
        };
        newScans.push(rec);
        newReports.push(buildIndependentReport('BODY_POSTURE', 'تقرير الجسم ووضعية الوقوف المستقل', rec));
        setStageResults((prev) => ({ ...prev, BODY_POSTURE: rec }));
        onSaveScanAndReport(newScans, newReports, undefined, undefined, uploadedMeta);
        setFallbackPrepared(null);
        autoCapturePausedRef.current = false;
        setAutoCapturePaused(false);
        lastCaptureTimeRef.current = Date.now();
        setTransitionBanner('الآن ننتقل إلى فحص الشعر وفروة الرأس.');
        setPhase('HAIR_STAGE');
      } else if (apiStage === 'hair') {
        if (!data.hair?.isVisible) {
          autoCapturePausedRef.current = true;
          setAutoCapturePaused(true);
          setStageError(
            data.hair?.guidanceAr ||
              'منطقة الشعر أو فروة الرأس غير واضحة — قرّب الرأس وأظهر خط الشعر بوضوح ثم أعد المحاولة.'
          );
          return;
        }
        if (savePhotosConsent) {
          setStagePhotos((p) => ({ ...p, hair: imageBase64 }));
        }
        const reg = data.hair;
        const rec: HealthScanRecord = {
          id: `scan_HAIR_${Date.now()}`,
          sessionId,
          userId: profile.userId,
          scanType: 'HAIR',
          scanTitleAr: 'تحليل الشعر وفروة الرأس',
          summary: reg.summary,
          observations: reg.observations || [],
          potentialIssues: reg.potentialIssues || [],
          severityLevel: reg.severityLevel,
          limitations: reg.limitations,
          recommendations: reg.recommendations || [],
          doctorConsultationCriteria: reg.doctorConsultationCriteria,
          confidenceScore: typeof reg.confidenceScore === 'number' ? reg.confidenceScore : null,
          isNonDiagnosticEstimate: true,
          source: data.source,
          isAreaVisible: Boolean(reg.isVisible),
          savedImageDataUrl: savePhotosConsent ? imageBase64 : undefined,
          imageUrl: uploadedMeta?.url,
          imageId: uploadedMeta?.id,
          timestamp: nowIso,
        };
        newScans.push(rec);
        newReports.push(buildIndependentReport('HAIR', 'تقرير الشعر وفروة الرأس المستقل', rec));
        setStageResults((prev) => ({ ...prev, HAIR: rec }));
        onSaveScanAndReport(newScans, newReports, undefined, undefined, uploadedMeta);
        setFallbackPrepared(null);
        autoCapturePausedRef.current = false;
        setAutoCapturePaused(false);
        lastCaptureTimeRef.current = Date.now();
        setTransitionBanner('اكتمل الفحص الموجّه الذكي — يمكنك مراجعة جميع النتائج أو إعادة تصوير أي مرحلة.');
        setPhase('REVIEW_STAGE');
      }
    } catch (err: any) {
      if (ctrl.signal.aborted || !mountedRef.current) return;
      autoCapturePausedRef.current = true;
      setAutoCapturePaused(true);
      setStageError(err?.message || 'تعذر الاتصال بمحرك التحليل، يرجى المحاولة مرة أخرى.');
    } finally {
      if (stageAbortRef.current === ctrl) {
        stageAbortRef.current = null;
        busyStageRef.current = false;
        lastCaptureTimeRef.current = Date.now();
        if (mountedRef.current) setProcessingStage(false);
      }
    }
  };

  const manualCaptureCurrentFrame = async () => {
    if (busyStageRef.current) return;
    if (phase !== 'FACE_STAGE' && phase !== 'BODY_STAGE' && phase !== 'HAIR_STAGE') return;
    const video = videoRef.current;
    if (!video || video.readyState < 2 || video.videoWidth === 0) {
      setStageError('الكاميرا غير جاهزة بعد — انتظر لحظة أو استخدم التقاط/رفع صورة من جهازك.');
      return;
    }
    const { fullFrameDataUrl, hairCropDataUrl } = captureVideoFrameAndHairCrop(video);
    const imageToSend = phase === 'HAIR_STAGE' ? hairCropDataUrl : fullFrameDataUrl;
    lastCaptureTimeRef.current = Date.now();
    await executeStageAnalysis(phase, imageToSend);
  };

  const processUploadedStageFile = async (
    file: File,
    sourceOrigin: 'camera' | 'gallery' | 'file' = 'gallery'
  ) => {
    if (!file) return;
    if (phase !== 'FACE_STAGE' && phase !== 'BODY_STAGE' && phase !== 'HAIR_STAGE') return;

    // Prioritise user's manual upload over any stuck auto-capture request
    abortInFlightStage();
    autoCapturePausedRef.current = true;
    setAutoCapturePaused(true);

    const ctrl = new AbortController();
    stageAbortRef.current = ctrl;
    setStageError(null);
    busyStageRef.current = true;
    setProcessingStage(true);
    setProcessingText('جاري تجهيز وضغط الصورة...');

    try {
      const prep = await prepareAndCompressImage(file);
      if (ctrl.signal.aborted || !mountedRef.current) {
        URL.revokeObjectURL(prep.previewUrl);
        return;
      }
      const base64Data = await blobToDataUrl(prep.compressedBlob);
      if (ctrl.signal.aborted || !mountedRef.current) {
        URL.revokeObjectURL(prep.previewUrl);
        return;
      }
      setFallbackPrepared({ prep, base64: base64Data });

      setProcessingText('جاري رفع الصورة إلى الخادم...');
      const uploaded = await uploadImageMultipart({
        userId: profile.userId,
        sectionId: 'SMART_SCAN',
        sessionId,
        sourceOrigin,
        prepared: prep,
        signal: ctrl.signal,
      });
      if (ctrl.signal.aborted || !mountedRef.current) return;
      setFallbackPrepared({ prep, uploaded, base64: base64Data });

      stageAbortRef.current = null;
      busyStageRef.current = false;
      await executeStageAnalysis(phase, base64Data, uploaded, prep.mimeType);
    } catch (err: any) {
      if (ctrl.signal.aborted || !mountedRef.current) return;
      setStageError(err?.message || 'فشل رفع الصورة — يمكنك المحاولة مرة أخرى دون فقدان الصورة.');
    } finally {
      if (stageAbortRef.current === ctrl) {
        stageAbortRef.current = null;
        busyStageRef.current = false;
        if (mountedRef.current) setProcessingStage(false);
      }
    }
  };

  const handleFallbackUpload = async (
    e: React.ChangeEvent<HTMLInputElement>,
    sourceOrigin: 'camera' | 'gallery' | 'file' = 'gallery'
  ) => {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;
    await processUploadedStageFile(file, sourceOrigin);
  };

  const handleNativeFallbackPick = async (sourceOrigin: 'camera' | 'gallery') => {
    try {
      const res = await pickImageWithCapacitorNative(sourceOrigin);
      if (!res || 'cancelled' in res) return;
      await processUploadedStageFile(res.file, sourceOrigin);
    } catch (err: any) {
      if (mountedRef.current) {
        setStageError(err?.message || 'تعذر التقاط أو اختيار الصورة.');
      }
    }
  };

  const retryFallbackStage = async () => {
    if (!fallbackPrepared || busyStageRef.current) return;
    if (phase !== 'FACE_STAGE' && phase !== 'BODY_STAGE' && phase !== 'HAIR_STAGE') return;

    const ctrl = new AbortController();
    stageAbortRef.current = ctrl;
    setStageError(null);
    busyStageRef.current = true;
    setProcessingStage(true);
    setProcessingText('جاري إعادة المحاولة...');
    try {
      let uploaded = fallbackPrepared.uploaded;
      if (!uploaded) {
        setProcessingText('جاري رفع الصورة إلى الخادم...');
        uploaded = await uploadImageMultipart({
          userId: profile.userId,
          sectionId: 'SMART_SCAN',
          sessionId,
          sourceOrigin: 'gallery',
          prepared: fallbackPrepared.prep,
          signal: ctrl.signal,
        });
        if (ctrl.signal.aborted || !mountedRef.current) return;
        setFallbackPrepared((prev) => (prev ? { ...prev, uploaded } : prev));
      }
      stageAbortRef.current = null;
      busyStageRef.current = false;
      await executeStageAnalysis(phase, fallbackPrepared.base64, uploaded, fallbackPrepared.prep.mimeType);
    } catch (err: any) {
      if (ctrl.signal.aborted || !mountedRef.current) return;
      setStageError(err?.message || 'تعذرت إعادة المحاولة — الصورة ما زالت محفوظة.');
    } finally {
      if (stageAbortRef.current === ctrl) {
        stageAbortRef.current = null;
        busyStageRef.current = false;
        if (mountedRef.current) setProcessingStage(false);
      }
    }
  };

  const downloadStagePdf = async (rec: HealthScanRecord) => {
    setStageError(null);
    try {
      const { blob, filename } = await generateSabqArabicPdfBlob({
        profile,
        reportType: rec.scanType,
        scans: [rec],
        labs: [],
        referenceImageDataUrl: rec.imageUrl || rec.savedImageDataUrl,
        previousReport: reports.find((r) => r.reportType === rec.scanType),
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
      setStageError(err?.message || 'تعذر إنشاء ملف PDF لهذه المرحلة.');
    }
  };

  const runOpticalPpgMeasurement = async () => {
    if (ppgRunning) return;
    setPpgResult(null);
    setStageError(null);
    if (!cameraActive || cameraFacingRef.current !== 'environment') {
      const started = await startCamera(true);
      if (!started) return;
    }

    // Wait briefly for video stream dimensions if just started
    let waitCount = 0;
    while (videoRef.current && videoRef.current.videoWidth === 0 && waitCount < 25) {
      await new Promise((r) => window.setTimeout(r, 120));
      waitCount++;
    }

    if (!videoRef.current || !ppgCanvasRef.current || videoRef.current.videoWidth === 0) {
      setStageError('تعذر جاهزية الكاميرا للقياس، تأكد من الإذن وحاول مرة أخرى.');
      return;
    }

    setPpgRunning(true);
    setPpgProgress(0);
    const samples: number[] = [];
    const timestamps: number[] = [];
    const startMs = performance.now();
    const durationMs = 7000;

    ppgTimerRef.current = window.setInterval(() => {
      if (!mountedRef.current) {
        if (ppgTimerRef.current) window.clearInterval(ppgTimerRef.current);
        ppgTimerRef.current = null;
        return;
      }
      const video = videoRef.current;
      const canvas = ppgCanvasRef.current;
      if (!video || !canvas || video.videoWidth === 0) return;
      canvas.width = 64;
      canvas.height = 64;
      const ctx = canvas.getContext('2d');
      if (!ctx) return;
      ctx.drawImage(video, 0, 0, 64, 64);
      const frame = ctx.getImageData(16, 16, 32, 32).data;
      let redSum = 0;
      for (let i = 0; i < frame.length; i += 4) redSum += frame[i];
      samples.push(redSum / (frame.length / 4));
      timestamps.push(performance.now());
      const elapsed = performance.now() - startMs;
      setPpgProgress(Math.min(100, Math.round((elapsed / durationMs) * 100)));

      if (elapsed >= durationMs) {
        if (ppgTimerRef.current) window.clearInterval(ppgTimerRef.current);
        ppgTimerRef.current = null;
        setPpgRunning(false);
        const res = analyzePpgSamples(samples, timestamps);
        setPpgResult(res);

        if (res.success && res.bpm !== null) {
          const nowIso = new Date().toISOString();
          const pulseScan: HealthScanRecord = {
            id: `scan_ppg_${Date.now()}`,
            sessionId,
            userId: profile.userId,
            scanType: 'BIOFINGER_PPG',
            scanTitleAr: 'تقرير النبض الضوئي (PPG)',
            summary: `تقدير معدل النبض الضوئي: ${res.bpm} نبضة/دقيقة`,
            observations: [`جودة الإشارة الضوئية: ${Math.round(res.signalQuality * 100)}%`],
            potentialIssues: ['تقدير غير تشخيصي يعتمد على ثبات الإصبع.'],
            severityLevel: 'تقدير ضوئي',
            limitations: 'لا يُعد جهاز تخطيط قلب طبي.',
            recommendations: ['للمتابعة السريرية استخدم جهازًا طبيًا معتمدًا.'],
            doctorConsultationCriteria: 'راجع الطبيب عند وجود خفقان مستمر أو دوخة.',
            confidenceScore: res.signalQuality,
            isNonDiagnosticEstimate: true,
            source: 'التصوير الضوئي عبر الكاميرا (PPG) — تقدير غير تشخيصي',
            measuredMetric: { label: 'النبض', value: String(res.bpm), unit: 'bpm' },
            timestamp: nowIso,
          };
          const createdScans: HealthScanRecord[] = [pulseScan];
          const createdReports: ReportRecord[] = [
            buildIndependentReport('BIOFINGER_PPG', 'تقرير النبض المستقل', pulseScan),
          ];
          const extraLabs: LabReading[] = [
            {
              id: `lab_ppg_${Date.now()}`,
              userId: profile.userId,
              sectionId: 'PULSE',
              testCode: 'PULSE',
              testNameAr: '6. القلب والنبض (PPG)',
              measuredValue: String(res.bpm),
              unit: 'bpm',
              subMetrics: { bpm: String(res.bpm), quality: String(Math.round(res.signalQuality * 100)) },
              source: 'قياس ضوئي PPG من الكاميرا (تقدير غير تشخيصي)',
              explanationAr: `جودة الإشارة الضوئية: ${Math.round(res.signalQuality * 100)}% — تقدير غير تشخيصي ولا يعرض تخطيط قلب ECG.`,
              recordedAt: nowIso,
            },
          ];

          if (res.respiratoryEstimate !== null) {
            const respScan: HealthScanRecord = {
              ...pulseScan,
              id: `scan_resp_${Date.now()}`,
              scanType: 'RESPIRATORY',
              scanTitleAr: 'تقدير معدل التنفس',
              summary: `تقدير معدل التنفس من تباين الموجة الضوئية: ${res.respiratoryEstimate} نفس/دقيقة`,
              measuredMetric: { label: 'التنفس', value: String(res.respiratoryEstimate), unit: 'rpm' },
            };
            createdScans.push(respScan);
            createdReports.push(
              buildIndependentReport('RESPIRATORY', 'تقرير التنفس المستقل', respScan)
            );
            extraLabs.push({
              id: `lab_resp_${Date.now()}`,
              userId: profile.userId,
              sectionId: 'RESPIRATORY_SPO2',
              testCode: 'SPO2',
              testNameAr: '7. التنفس والأكسجين (تقدير التنفس PPG)',
              measuredValue: `Resp:${res.respiratoryEstimate}/m`,
              unit: 'rpm',
              subMetrics: { respiratoryRate: String(res.respiratoryEstimate) },
              source: 'تقدير معدل التنفس من إشارة PPG الصالحة',
              explanationAr: 'تقدير معدل التنفس فقط؛ نسبة الأكسجين SpO2 تتطلب جهاز تأكسج نبضي مخصص.',
              recordedAt: nowIso,
            });
          }

          onSaveScanAndReport(createdScans, createdReports, undefined, extraLabs);
        }
      }
    }, 33);
  };

  const stepperItems: Array<{ id: GuidedPhase; label: string; stepNum: string }> = [
    { id: 'FACE_STAGE', label: 'الوجه والعين والبشرة', stepNum: '1' },
    { id: 'BODY_STAGE', label: 'الجسم كاملًا', stepNum: '2' },
    { id: 'HAIR_STAGE', label: 'الشعر وفروة الرأس', stepNum: '3' },
    { id: 'REVIEW_STAGE', label: 'مراجعة الفحص', stepNum: '4' },
    { id: 'BIOFINGER', label: 'النبض الضوئي', stepNum: '5' },
  ];

  const completionRows: Array<{ key: 'FACE' | 'SKIN' | 'EYE' | 'BODY_POSTURE' | 'HAIR'; label: string }> = [
    { key: 'FACE', label: 'فحص الوجه' },
    { key: 'SKIN', label: 'فحص البشرة' },
    { key: 'EYE', label: 'فحص العين' },
    { key: 'BODY_POSTURE', label: 'فحص الجسم' },
    { key: 'HAIR', label: 'فحص الشعر' },
  ];

  return (
    <div className="space-y-5 pb-8">
      {/* Guided SABQ SMART SCAN Header & Stepper */}
      <div className="rounded-3xl border border-violet-500/25 bg-[#0C0E1A] p-5 space-y-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div>
            <h2 className="text-lg font-bold text-white">SABQ SMART SCAN — الفحص الصحي الذكي الموجّه</h2>
            <p className="text-xs text-slate-400">
              الوجه ← الجسم كاملًا ← الشعر وفروة الرأس ← المراجعة والتقارير المستقلة
            </p>
          </div>
          <button
            onClick={onOpenReportTab}
            className="rounded-xl border border-cyan-500/40 bg-cyan-500/10 px-3 py-1.5 text-xs font-bold text-cyan-300"
          >
            التقارير ({reports.length})
          </button>
        </div>

        <div className="grid grid-cols-2 gap-2 sm:grid-cols-5">
          {stepperItems.map((item) => {
            const active = phase === item.id;
            return (
              <button
                key={item.id}
                onClick={() => {
                  abortInFlightStage();
                  autoCapturePausedRef.current = false;
                  setAutoCapturePaused(false);
                  lastCaptureTimeRef.current = Date.now();
                  setStageError(null);
                  setTransitionBanner(null);
                  setPhase(item.id);
                }}
                className={`flex min-h-[46px] items-center justify-center gap-1.5 rounded-2xl border px-2 py-2 text-xs font-bold transition-colors ${
                  active
                    ? 'border-cyan-400 bg-gradient-to-l from-violet-600/35 to-cyan-500/25 text-white'
                    : 'border-slate-800 bg-[#121526] text-slate-400 hover:text-white'
                }`}
              >
                <span className="num-en flex h-5 w-5 items-center justify-center rounded-full bg-black/40 text-[10px]">
                  {item.stepNum}
                </span>
                <span className="truncate">{item.label}</span>
              </button>
            );
          })}
        </div>

        {transitionBanner && (
          <div className="rounded-2xl border border-emerald-500/40 bg-emerald-500/10 px-4 py-3 text-xs font-bold text-emerald-200">
            {transitionBanner}
          </div>
        )}
      </div>

      {/* Camera Stages 1, 2, 3 */}
      {(phase === 'FACE_STAGE' || phase === 'BODY_STAGE' || phase === 'HAIR_STAGE') && (
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-12">
          <div className="space-y-3 rounded-3xl border border-violet-500/25 bg-[#0C0E1A] p-5 lg:col-span-7">
            <div className="flex items-center justify-between">
              <span className="text-sm font-bold text-cyan-300">
                {phase === 'FACE_STAGE'
                  ? 'المرحلة 1: لقطة الوجه التلقائية'
                  : phase === 'BODY_STAGE'
                  ? 'المرحلة 2: لقطة الجسم كاملًا (Pose Detection)'
                  : 'المرحلة 3: لقطة الشعر وفروة الرأس'}
              </span>
              {cameraActive && (
                <div className="flex items-center gap-2">
                  <button
                    type="button"
                    onClick={() => setPreferRearGuided((v) => !v)}
                    className="inline-flex items-center gap-1 rounded-lg border border-cyan-500/40 px-2.5 py-1 text-[11px] text-cyan-200"
                  >
                    <SwitchCamera className="h-3.5 w-3.5" />
                    <span>{preferRearGuided ? 'أمامية' : 'خلفية'}</span>
                  </button>
                  <button
                    onClick={() => {
                      abortInFlightStage();
                      stopCamera();
                      setCameraConsent(false);
                    }}
                    className="rounded-lg border border-rose-500/40 px-2.5 py-1 text-[11px] text-rose-300"
                  >
                    إغلاق الكاميرا
                  </button>
                </div>
              )}
            </div>

            {!cameraConsent ? (
              <div className="rounded-2xl border border-cyan-500/35 bg-[#080A12] p-5 space-y-3">
                <p className="text-xs leading-relaxed text-slate-200">
                  عند موافقتك، تفتح الكاميرا تلقائيًا وتوجهك برسائل عربية واضحة لضبط المسافة والزاوية ثم تلتقط اللقطات تلقائيًا أو يدويًا، كما يمكنك التصوير بكاميرا الهاتف أو رفع صورة من المعرض مباشرة.
                </p>
                <label className="flex items-center gap-2 text-xs text-slate-300">
                  <input
                    type="checkbox"
                    checked={savePhotosConsent}
                    onChange={(e) => setSavePhotosConsent(e.target.checked)}
                    className="h-4 w-4 accent-cyan-400"
                  />
                  <span>أوافق على حفظ الصور المرجعية داخل سجل فحوصي وتقارير PDF الخاصة بي</span>
                </label>
                <div className="flex flex-wrap gap-2">
                  <button
                    onClick={() => {
                      autoCapturePausedRef.current = false;
                      setAutoCapturePaused(false);
                      setStageError(null);
                      lastCaptureTimeRef.current = Date.now();
                      setCameraConsent(true);
                    }}
                    className="flex min-h-[48px] flex-1 items-center justify-center gap-2 rounded-xl bg-gradient-to-l from-violet-600 to-cyan-500 px-4 py-2 text-sm font-bold text-white"
                  >
                    <Camera className="h-5 w-5" />
                    <span>موافق — بدء الفحص الموجّه الآن</span>
                  </button>
                  {isNativeCap ? (
                    <button
                      type="button"
                      onClick={() => handleNativeFallbackPick('camera')}
                      className="flex min-h-[48px] cursor-pointer items-center justify-center gap-2 rounded-xl border border-cyan-500/40 bg-[#121526] px-4 py-2 text-xs font-bold text-cyan-200"
                    >
                      <Camera className="h-4 w-4 text-cyan-400" />
                      <span>التقاط بكاميرا الهاتف</span>
                    </button>
                  ) : (
                    <label className="flex min-h-[48px] cursor-pointer items-center justify-center gap-2 rounded-xl border border-cyan-500/40 bg-[#121526] px-4 py-2 text-xs font-bold text-cyan-200">
                      <Camera className="h-4 w-4 text-cyan-400" />
                      <span>التقاط بكاميرا الهاتف</span>
                      <input
                        type="file"
                        accept="image/*"
                        capture="environment"
                        className="hidden"
                        onChange={(e) => handleFallbackUpload(e, 'camera')}
                      />
                    </label>
                  )}
                  {isNativeCap ? (
                    <button
                      type="button"
                      onClick={() => handleNativeFallbackPick('gallery')}
                      className="flex min-h-[48px] cursor-pointer items-center justify-center gap-2 rounded-xl border border-slate-700 bg-[#121526] px-4 py-2 text-xs font-bold text-slate-200"
                    >
                      <Upload className="h-4 w-4 text-cyan-400" />
                      <span>رفع من المعرض / الملفات</span>
                    </button>
                  ) : (
                    <label className="flex min-h-[48px] cursor-pointer items-center justify-center gap-2 rounded-xl border border-slate-700 bg-[#121526] px-4 py-2 text-xs font-bold text-slate-200">
                      <Upload className="h-4 w-4 text-cyan-400" />
                      <span>رفع من المعرض / الملفات</span>
                      <input
                        type="file"
                        accept="image/*"
                        className="hidden"
                        onChange={(e) => handleFallbackUpload(e, 'gallery')}
                      />
                    </label>
                  )}
                </div>
              </div>
            ) : (
              <div className="space-y-3">
                <div className="relative overflow-hidden rounded-2xl border border-cyan-500/45 bg-[#05060C]">
                  <div className="relative flex h-80 w-full items-center justify-center">
                    <video ref={videoRef} playsInline muted className="h-full w-full object-cover" />

                    {/* Stage-specific Visual Guide Frame */}
                    <div className="pointer-events-none absolute inset-0 flex items-center justify-center p-4">
                      {phase === 'FACE_STAGE' && (
                        <div
                          className={`h-52 w-40 rounded-[46%] border-2 transition-colors ${
                            liveGuidance?.isReadyToCapture
                              ? 'border-emerald-400 shadow-[0_0_24px_rgba(16,185,129,0.5)]'
                              : 'border-cyan-400/80'
                          }`}
                        />
                      )}
                      {phase === 'BODY_STAGE' && (
                        <div
                          className={`h-72 w-44 rounded-3xl border-2 border-dashed transition-colors ${
                            liveGuidance?.isReadyToCapture
                              ? 'border-emerald-400'
                              : 'border-violet-400/80'
                          }`}
                        />
                      )}
                      {phase === 'HAIR_STAGE' && (
                        <div
                          className={`h-44 w-56 rounded-t-full rounded-b-3xl border-2 transition-colors ${
                            liveGuidance?.isReadyToCapture
                              ? 'border-emerald-400'
                              : 'border-cyan-400/80'
                          }`}
                        />
                      )}
                    </div>

                    {processingStage && (
                      <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 bg-black/75 px-4 text-center backdrop-blur-xs">
                        <Loader2 className="h-9 w-9 animate-spin text-cyan-400" />
                        <span className="text-sm font-bold text-white">{processingText}</span>
                        <button
                          type="button"
                          onClick={() => {
                            abortInFlightStage();
                            autoCapturePausedRef.current = true;
                            setAutoCapturePaused(true);
                            setStageError('تم إلغاء التحليل. يمكنك التقاط صورة يدويًا أو رفع صورة من جهازك.');
                          }}
                          className="rounded-xl border border-rose-500/50 bg-rose-500/20 px-4 py-1.5 text-xs font-bold text-rose-200 hover:bg-rose-500/30"
                        >
                          إلغاء التحليل
                        </button>
                      </div>
                    )}
                  </div>
                </div>

                {/* Big Clear Arabic Guidance Banner + Manual Capture & Upload Actions */}
                <div className="rounded-2xl border border-cyan-500/35 bg-[#080A12] p-4 text-center space-y-3">
                  <div className="text-base font-extrabold text-cyan-300">
                    {processingStage
                      ? processingText
                      : autoCapturePaused
                      ? 'تم إيقاف الالتقاط التلقائي مؤقتًا — التقط يدويًا أو استأنف أو ارفع صورة.'
                      : liveGuidance?.guidanceAr || 'جاري تجهيز المعاينة الحية...'}
                  </div>

                  <div className="flex flex-wrap justify-center gap-2">
                    <button
                      type="button"
                      disabled={processingStage || !cameraActive}
                      onClick={manualCaptureCurrentFrame}
                      className="inline-flex items-center gap-1.5 rounded-xl bg-gradient-to-l from-violet-600 to-cyan-500 px-3.5 py-2 text-xs font-bold text-white disabled:opacity-50"
                    >
                      <Camera className="h-3.5 w-3.5" />
                      <span>التقاط صورة الآن وتحليلها</span>
                    </button>

                    {autoCapturePaused && !processingStage && (
                      <button
                        type="button"
                        onClick={() => {
                          setStageError(null);
                          autoCapturePausedRef.current = false;
                          setAutoCapturePaused(false);
                          lastCaptureTimeRef.current = Date.now() - 2500;
                        }}
                        className="inline-flex items-center gap-1.5 rounded-xl border border-emerald-500/50 bg-emerald-500/15 px-3.5 py-2 text-xs font-bold text-emerald-200"
                      >
                        <Play className="h-3.5 w-3.5" />
                        <span>استئناف الالتقاط التلقائي</span>
                      </button>
                    )}

                    {isNativeCap ? (
                      <button
                        type="button"
                        onClick={() => handleNativeFallbackPick('camera')}
                        className="inline-flex cursor-pointer items-center gap-1.5 rounded-xl border border-cyan-500/40 bg-[#121526] px-3 py-2 text-xs font-bold text-cyan-200 hover:border-cyan-400"
                      >
                        <Camera className="h-3.5 w-3.5" />
                        <span>تصوير بكاميرا الهاتف</span>
                      </button>
                    ) : (
                      <label className="inline-flex cursor-pointer items-center gap-1.5 rounded-xl border border-cyan-500/40 bg-[#121526] px-3 py-2 text-xs font-bold text-cyan-200 hover:border-cyan-400">
                        <Camera className="h-3.5 w-3.5" />
                        <span>تصوير بكاميرا الهاتف</span>
                        <input
                          type="file"
                          accept="image/*"
                          capture="environment"
                          className="hidden"
                          onChange={(e) => handleFallbackUpload(e, 'camera')}
                        />
                      </label>
                    )}

                    {isNativeCap ? (
                      <button
                        type="button"
                        onClick={() => handleNativeFallbackPick('gallery')}
                        className="inline-flex cursor-pointer items-center gap-1.5 rounded-xl border border-slate-700 px-3 py-2 text-xs text-slate-200 hover:border-cyan-400"
                      >
                        <Upload className="h-3.5 w-3.5 text-cyan-400" />
                        <span>رفع من المعرض / الملفات</span>
                      </button>
                    ) : (
                      <label className="inline-flex cursor-pointer items-center gap-1.5 rounded-xl border border-slate-700 px-3 py-2 text-xs text-slate-200 hover:border-cyan-400">
                        <Upload className="h-3.5 w-3.5 text-cyan-400" />
                        <span>رفع من المعرض / الملفات</span>
                        <input
                          type="file"
                          accept="image/*"
                          className="hidden"
                          onChange={(e) => handleFallbackUpload(e, 'gallery')}
                        />
                      </label>
                    )}

                    <button
                      type="button"
                      onClick={() => {
                        abortInFlightStage();
                        autoCapturePausedRef.current = false;
                        setAutoCapturePaused(false);
                        lastCaptureTimeRef.current = Date.now();
                        setStageError(null);
                        setPhase(
                          phase === 'FACE_STAGE'
                            ? 'BODY_STAGE'
                            : phase === 'BODY_STAGE'
                            ? 'HAIR_STAGE'
                            : 'REVIEW_STAGE'
                        );
                      }}
                      className="rounded-xl border border-slate-700 px-3 py-2 text-xs text-slate-300 hover:text-white"
                    >
                      الانتقال للمرحلة التالية
                    </button>
                  </div>
                </div>
              </div>
            )}

            {fallbackPrepared && (
              <div className="rounded-xl border border-slate-800 bg-[#080A12] p-3 space-y-2 text-center">
                <img
                  src={fallbackPrepared.prep.previewUrl}
                  alt="معاينة صورة المرحلة"
                  className="mx-auto max-h-40 rounded-lg border border-cyan-500/30 object-contain"
                />
                <div className="text-[11px] text-slate-400 num-en">
                  {fallbackPrepared.prep.width}×{fallbackPrepared.prep.height}px •{' '}
                  {(fallbackPrepared.prep.compressedSizeBytes / 1024).toFixed(1)} KB
                  {fallbackPrepared.uploaded ? ' • محفوظة على الخادم' : ''}
                </div>
                {processingStage && (
                  <div className="text-xs font-bold text-cyan-300">{processingText}</div>
                )}
                {stageError && !processingStage && (
                  <button
                    type="button"
                    onClick={retryFallbackStage}
                    className="inline-flex items-center gap-1.5 rounded-lg bg-cyan-500 px-3 py-1.5 text-xs font-bold text-black"
                  >
                    <RefreshCw className="h-3.5 w-3.5" />
                    <span>إعادة المحاولة دون فقدان الصورة</span>
                  </button>
                )}
              </div>
            )}

            {cameraError && (
              <div className="rounded-xl border border-amber-500/40 bg-amber-500/10 p-3 text-xs text-amber-200">
                {cameraError}
              </div>
            )}
            {stageError && (
              <div className="flex items-start gap-2 rounded-xl border border-rose-500/40 bg-rose-500/10 p-3 text-xs text-rose-200">
                <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
                <span>{stageError}</span>
              </div>
            )}
          </div>

          {/* Live Stage Instructions & Progressive Status */}
          <div className="space-y-3 rounded-3xl border border-violet-500/25 bg-[#0C0E1A] p-5 lg:col-span-5">
            <h3 className="text-sm font-bold text-white">تعليمات المرحلة الحالية وحالة المناطق</h3>
            <div className="rounded-2xl border border-slate-800 bg-[#080A12] p-3.5 text-xs text-slate-300 space-y-1.5">
              {phase === 'FACE_STAGE' && (
                <>
                  <div>• اجعل وجهك في منتصف الإطار البيضاوي.</div>
                  <div>• تأكد من إضاءة واضحة ومتوازنة.</div>
                  <div>• انظر مباشرة إلى الكاميرا واثبت لحظة للالتقاط التلقائي.</div>
                </>
              )}
              {phase === 'BODY_STAGE' && (
                <>
                  <div>• ابتعد عن الهاتف حتى يظهر الرأس والقدمان بالكامل.</div>
                  <div>• قف بشكل مستقيم مع إظهار الذراعين والساقين قدر الإمكان.</div>
                  <div>• لا يستنتج سَبَق الوزن أو الدهون من الصورة وحدها.</div>
                </>
              )}
              {phase === 'HAIR_STAGE' && (
                <>
                  <div>• قرّب الرأس قليلًا وأظهر خط الشعر أو فروة الرأس بوضوح.</div>
                  <div>• أدر رأسك قليلًا عند الحاجة مع إضاءة جيدة.</div>
                  <div>• تُلتقط الصورة تلقائيًا من نفس جلسة الكاميرا.</div>
                </>
              )}
            </div>

            <div className="space-y-2">
              {completionRows.map((row) => {
                const rec = stageResults[row.key];
                return (
                  <div
                    key={row.key}
                    className="flex items-center justify-between rounded-xl border border-slate-800 bg-[#080A12] px-3 py-2 text-xs"
                  >
                    <span className="font-bold text-white">{row.label}</span>
                    {rec ? (
                      <span className="flex items-center gap-1 text-emerald-300">
                        <CheckCircle2 className="h-3.5 w-3.5" />
                        مكتمل
                      </span>
                    ) : (
                      <span className="text-slate-500">غير مكتمل</span>
                    )}
                  </div>
                );
              })}
            </div>
          </div>
        </div>
      )}

      {/* Stage 4: Review Stage */}
      {phase === 'REVIEW_STAGE' && (
        <div className="rounded-3xl border border-violet-500/25 bg-[#0C0E1A] p-5 space-y-4">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <h3 className="text-base font-bold text-white">المرحلة 4: مراجعة الفحص والنتائج المستقلة</h3>
              <p className="text-xs text-slate-400">
                يمكنك إعادة تصوير أي مرحلة غير مكتملة أو غير واضحة فقط، وتنزيل PDF مستقل لكل منطقة
              </p>
            </div>
            <div className="flex flex-wrap gap-2">
              <button
                onClick={() => setPhase('FACE_STAGE')}
                className="flex items-center gap-1 rounded-xl border border-slate-700 px-3 py-1.5 text-xs text-slate-200 hover:border-cyan-400"
              >
                <RefreshCw className="h-3.5 w-3.5" />
                <span>إعادة الوجه</span>
              </button>
              <button
                onClick={() => setPhase('BODY_STAGE')}
                className="flex items-center gap-1 rounded-xl border border-slate-700 px-3 py-1.5 text-xs text-slate-200 hover:border-cyan-400"
              >
                <RefreshCw className="h-3.5 w-3.5" />
                <span>إعادة الجسم</span>
              </button>
              <button
                onClick={() => setPhase('HAIR_STAGE')}
                className="flex items-center gap-1 rounded-xl border border-slate-700 px-3 py-1.5 text-xs text-slate-200 hover:border-cyan-400"
              >
                <RefreshCw className="h-3.5 w-3.5" />
                <span>إعادة الشعر</span>
              </button>
            </div>
          </div>

          <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
            {completionRows.map((row) => {
              const rec = stageResults[row.key];
              const fallbackPhoto =
                row.key === 'HAIR'
                  ? stagePhotos.hair
                  : row.key === 'BODY_POSTURE'
                  ? stagePhotos.body
                  : stagePhotos.face;
              const imgRaw = rec?.imageUrl || rec?.savedImageDataUrl || fallbackPhoto;
              const imgToShow = imgRaw ? resolveApiUrl(imgRaw) : undefined;

              return (
                <div
                  key={row.key}
                  className="rounded-2xl border border-violet-500/20 bg-[#101324] p-4 space-y-2.5"
                >
                  <div className="flex items-center justify-between">
                    <span className="text-sm font-bold text-white">{row.label}</span>
                    {rec ? (
                      <button
                        onClick={() => downloadStagePdf(rec)}
                        className="flex items-center gap-1 rounded-lg bg-gradient-to-l from-violet-600 to-cyan-500 px-2.5 py-1 text-[11px] font-bold text-white"
                      >
                        <FileDown className="h-3.5 w-3.5" />
                        <span>PDF مستقل</span>
                      </button>
                    ) : (
                      <span className="text-xs text-amber-300">غير مكتمل</span>
                    )}
                  </div>

                  {imgToShow && (
                    <img
                      src={imgToShow}
                      alt={`صورة مرجعية - ${row.label}`}
                      className="h-32 w-full rounded-xl border border-cyan-500/30 object-cover"
                    />
                  )}

                  {rec ? (
                    <div className="space-y-1.5 text-xs">
                      <p className="text-slate-200">{rec.summary}</p>
                      <p className="text-amber-200">
                        <strong>الملاحظات:</strong> {rec.potentialIssues.join(' · ') || 'لا توجد مؤشرات واضحة'}
                      </p>
                      <p className="text-emerald-200">
                        <strong>النصائح:</strong> {rec.recommendations.join(' · ')}
                      </p>
                    </div>
                  ) : (
                    <p className="text-xs text-slate-400">
                      لم يكتمل تحليل هذه المرحلة بعد. يمكنك إعادة تصويرها من الأزرار بالأعلى.
                    </p>
                  )}
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* Stage 5: SABQ BioFinger PPG Pulse & Respiratory */}
      {phase === 'BIOFINGER' && (
        <div className="rounded-3xl border border-violet-500/25 bg-[#0C0E1A] p-5 space-y-4">
          <div className="flex items-center gap-3">
            <Fingerprint className="h-6 w-6 text-cyan-400" />
            <div>
              <h3 className="text-base font-bold text-white">قياس النبض ومعدل التنفس الضوئي (PPG)</h3>
              <p className="text-xs text-slate-400">
                يستخدم تغيرات الضوء عبر الكاميرا فقط — لا يستخدم قارئ بصمة الهوية أبدًا ولا يعرض أرقامًا وهمية عند ضعف الإشارة
              </p>
            </div>
          </div>

          <div className="relative flex h-60 w-full items-center justify-center overflow-hidden rounded-2xl border border-cyan-500/35 bg-[#06070D]">
            <video
              ref={videoRef}
              playsInline
              muted
              className={`h-full w-full object-cover ${cameraActive ? 'block' : 'hidden'}`}
            />
            {!cameraActive && (
              <div className="px-6 text-center text-xs text-slate-300">
                <Activity className="mx-auto mb-2 h-10 w-10 text-cyan-400" />
                ضع إصبعك برفق فوق العدسة الخلفية في إضاءة واضحة، ثم اضغط بدء القياس.
              </div>
            )}
          </div>
          <canvas ref={ppgCanvasRef} className="hidden" />

          <div className="flex gap-3">
            <button
              onClick={() => startCamera(true)}
              className="min-h-[46px] flex-1 rounded-xl border border-violet-500/40 bg-[#141729] text-xs font-bold text-white"
            >
              تشغيل الكاميرا الخلفية
            </button>
            <button
              disabled={ppgRunning}
              onClick={runOpticalPpgMeasurement}
              className="min-h-[46px] flex-1 rounded-xl bg-gradient-to-l from-violet-600 to-cyan-500 text-xs font-bold text-white disabled:opacity-50"
            >
              {ppgRunning ? `جاري القياس ${ppgProgress}%` : 'بدء قياس النبض الضوئي'}
            </button>
          </div>

          {ppgResult && (
            <div
              className={`rounded-2xl border p-3.5 text-xs ${
                ppgResult.success
                  ? 'border-emerald-500/40 bg-emerald-500/10 text-emerald-200'
                  : 'border-amber-500/40 bg-amber-500/10 text-amber-200'
              }`}
            >
              {ppgResult.success ? (
                <div className="space-y-1">
                  <div>
                    النبض المقدر: <strong className="num-en">{ppgResult.bpm}</strong> bpm (تقدير غير تشخيصي)
                  </div>
                  {ppgResult.respiratoryEstimate && (
                    <div>
                      التنفس المقدر: <strong className="num-en">{ppgResult.respiratoryEstimate}</strong> نفس/دقيقة
                    </div>
                  )}
                </div>
              ) : (
                <div className="flex items-center gap-2 font-bold">
                  <XCircle className="h-4 w-4" />
                  <span>{ppgResult.messageAr}</span>
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
};
