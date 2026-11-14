import React, { useEffect, useState } from 'react';
import {
  Eye,
  FileDown,
  FileText,
  Loader2,
  Printer,
  Share2,
} from 'lucide-react';
import {
  generateSabqArabicPdfBlob,
  matchesLabForReport,
  matchesScanForReport,
} from '../utils/arabicPdfGenerator';
import { resolveApiUrl } from '../apiBase';
import type {
  HealthScanRecord,
  LabReading,
  ReportCategoryType,
  ReportRecord,
  RiskAssessmentResult,
  UserProfile,
} from '../types/sabq';

interface ReportsScreenProps {
  profile: UserProfile;
  scans: HealthScanRecord[];
  labs: LabReading[];
  riskAssessments: RiskAssessmentResult[];
  reports: ReportRecord[];
  onSaveReport: (report: ReportRecord) => void;
}

const REPORT_FILTER_TABS: Array<{ id: ReportCategoryType | 'ALL'; label: string }> = [
  { id: 'ALL', label: 'الكل' },
  { id: 'COMPREHENSIVE', label: 'التقرير الشامل' },
  { id: 'GLUCOSE', label: 'سكر الدم' },
  { id: 'BP', label: 'ضغط الدم' },
  { id: 'HBA1C', label: 'التراكمي HbA1c' },
  { id: 'CBC', label: 'صورة الدم CBC' },
  { id: 'THYROID', label: 'الغدة الدرقية' },
  { id: 'PULSE', label: 'القلب والنبض' },
  { id: 'RESPIRATORY_SPO2', label: 'التنفس والأكسجين' },
  { id: 'BMI', label: 'الوزن وBMI' },
  { id: 'BODY_COMPOSITION', label: 'الدهون والعضلات' },
  { id: 'LIVER_KIDNEY', label: 'الكبد والكلى' },
  { id: 'FACE_EYE_SKIN', label: 'الوجه والعين والبشرة' },
  { id: 'HAIR_SCALP', label: 'الشعر وفروة الرأس' },
  { id: 'FACE', label: 'لقطة الوجه' },
  { id: 'SKIN', label: 'لقطة البشرة' },
  { id: 'EYE', label: 'لقطة العين' },
  { id: 'HAIR', label: 'لقطة الشعر' },
  { id: 'BODY_POSTURE', label: 'الجسم والقوام' },
  { id: 'BIOFINGER_PPG', label: 'النبض الضوئي' },
];

export const ReportsScreen: React.FC<ReportsScreenProps> = ({
  profile,
  scans,
  labs,
  riskAssessments,
  reports,
  onSaveReport,
}) => {
  const [filterType, setFilterType] = useState<ReportCategoryType | 'ALL'>('ALL');
  const [generating, setGenerating] = useState(false);
  const [previewOpen, setPreviewOpen] = useState(false);
  const [statusMessage, setStatusMessage] = useState<string | null>(null);
  const [latestPdfUrl, setLatestPdfUrl] = useState<string | null>(null);
  const [latestPdfBlob, setLatestPdfBlob] = useState<Blob | null>(null);
  const [latestFilename, setLatestFilename] = useState<string>('SABQ_Report.pdf');

  useEffect(() => {
    return () => {
      if (latestPdfUrl) URL.revokeObjectURL(latestPdfUrl);
    };
  }, [latestPdfUrl]);

  useEffect(() => {
    setPreviewOpen(false);
    setStatusMessage(null);
    setLatestPdfBlob(null);
    setLatestPdfUrl((prev) => {
      if (prev) URL.revokeObjectURL(prev);
      return null;
    });
  }, [profile.userId]);

  const buildPdfForType = async (
    reportType: ReportCategoryType,
    customScans?: HealthScanRecord[],
    customRisks?: RiskAssessmentResult[],
    customRefImg?: string,
    downloadImmediately = false
  ) => {
    setGenerating(true);
    setStatusMessage(null);
    try {
      const targetScans = customScans || scans;
      const targetRisks = customRisks || riskAssessments;
      const prevSame = reports.find((r) => r.reportType === reportType);
      const refImg =
        customRefImg ||
        targetScans.find(
          (s) => matchesScanForReport(s.scanType, reportType) && Boolean(s.imageUrl || s.savedImageDataUrl)
        )?.imageUrl ||
        targetScans.find(
          (s) => matchesScanForReport(s.scanType, reportType) && Boolean(s.savedImageDataUrl)
        )?.savedImageDataUrl;

      const { blob, filename, comparisonSummary } = await generateSabqArabicPdfBlob({
        profile,
        reportType,
        scans: targetScans,
        labs,
        riskAssessments: targetRisks,
        referenceImageDataUrl: refImg,
        previousReport: prevSame,
      });

      const url = URL.createObjectURL(blob);
      setLatestPdfUrl((prev) => {
        if (prev) URL.revokeObjectURL(prev);
        return url;
      });
      setLatestPdfBlob(blob);
      setLatestFilename(filename);

      if (!customScans) {
        const titleLabel =
          REPORT_FILTER_TABS.find((t) => t.id === reportType)?.label || 'التقرير';
        const matchedScans = scans.filter((s) => matchesScanForReport(s.scanType, reportType));
        const matchedLabs = labs.filter((l) => matchesLabForReport(l, reportType));
        onSaveReport({
          id: `rep_${reportType}_${Date.now()}`,
          userId: profile.userId,
          reportType,
          title:
            reportType === 'COMPREHENSIVE'
              ? `التقرير الشامل (${scans.length} فحوص، ${labs.length} تحاليل)`
              : `تقرير ${titleLabel} المستقل`,
          createdAt: new Date().toISOString(),
          scansCount: matchedScans.length,
          labsCount: matchedLabs.length,
          comparisonSummary,
          referenceImageUrl:
            refImg && (refImg.startsWith('/api/') || refImg.startsWith('http://') || refImg.startsWith('https://'))
              ? refImg
              : undefined,
          referenceImageDataUrl: refImg?.startsWith('data:') ? refImg : undefined,
          snapshotScans: matchedScans,
          snapshotLabs: matchedLabs,
          snapshotRisks: targetRisks,
        });
      }

      if (downloadImmediately) {
        const a = document.createElement('a');
        a.href = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        a.remove();
        setStatusMessage(`تم تنزيل التقرير (${filename}) بنجاح.`);
      } else {
        setPreviewOpen(true);
        setStatusMessage('تم إنشاء التقرير وحفظه في سجل المستخدم بنجاح.');
      }
    } catch (err: any) {
      setStatusMessage(err?.message || 'تعذر إنشاء ملف PDF، يرجى المحاولة مرة أخرى.');
    } finally {
      setGenerating(false);
    }
  };

  const handleSharePdf = async () => {
    if (!latestPdfBlob) {
      await buildPdfForType('COMPREHENSIVE', undefined, undefined, undefined, false);
      return;
    }
    const file = new File([latestPdfBlob], latestFilename, { type: 'application/pdf' });
    if (navigator.share && navigator.canShare?.({ files: [file] })) {
      try {
        await navigator.share({
          title: 'تقرير سَبَق الصحي',
          text: `تقرير سَبَق لـ ${profile.displayName}`,
          files: [file],
        });
        return;
      } catch {
        // fallback
      }
    }
    if (latestPdfUrl) {
      const a = document.createElement('a');
      a.href = latestPdfUrl;
      a.download = latestFilename;
      a.click();
      setStatusMessage('تم تنزيل ملف PDF للمشاركة اليدوية.');
    }
  };

  const handlePrint = () => {
    if (latestPdfUrl) {
      const iframe = document.createElement('iframe');
      iframe.style.display = 'none';
      iframe.src = latestPdfUrl;
      document.body.appendChild(iframe);
      iframe.onload = () => {
        try {
          iframe.contentWindow?.focus();
          iframe.contentWindow?.print();
        } catch {
          window.print();
        }
        window.setTimeout(() => iframe.remove(), 60000);
      };
    } else {
      window.print();
    }
  };

  const visibleReports =
    filterType === 'ALL' ? reports : reports.filter((r) => r.reportType === filterType);

  return (
    <div className="space-y-6 pb-8">
      <div className="rounded-3xl border border-violet-500/25 bg-[#0C0E1A] p-5 space-y-4">
        <div className="flex items-center gap-3">
          <span className="flex h-11 w-11 items-center justify-center rounded-2xl bg-cyan-500/15 text-cyan-300">
            <FileText className="h-6 w-6" />
          </span>
          <div>
            <h2 className="text-lg font-bold text-white">التقارير المستقلة الـ 12 والتقرير الشامل (PDF عربي RTL)</h2>
            <p className="text-xs text-slate-400">
              يشمل الشعار والجداول والصور المرجعية وأرقام 0-9 الإنجليزية مع اتصال الحروف العربية
            </p>
          </div>
        </div>

        <div className="flex flex-wrap gap-1.5">
          {REPORT_FILTER_TABS.map((t) => (
            <button
              key={t.id}
              onClick={() => setFilterType(t.id)}
              className={`min-h-[36px] rounded-xl px-3 py-1 text-xs font-bold ${
                filterType === t.id
                  ? 'bg-gradient-to-l from-violet-600 to-cyan-500 text-white'
                  : 'border border-slate-800 bg-[#121526] text-slate-300'
              }`}
            >
              {t.label}
            </button>
          ))}
        </div>

        <div className="flex flex-wrap items-center justify-between gap-2 rounded-2xl border border-slate-800 bg-[#080A12] px-3.5 py-2 text-xs text-slate-300">
          <span>
            القسم المحدد:{' '}
            <strong className="text-cyan-300">
              {REPORT_FILTER_TABS.find((t) => t.id === filterType)?.label}
            </strong>
          </span>
          <span className="num-en text-[11px] text-slate-400">
            الفحوص المطابقة:{' '}
            {
              scans.filter((s) =>
                matchesScanForReport(s.scanType, filterType === 'ALL' ? 'COMPREHENSIVE' : filterType)
              ).length
            }{' '}
            · التحاليل المطابقة:{' '}
            {
              labs.filter((l) =>
                matchesLabForReport(l, filterType === 'ALL' ? 'COMPREHENSIVE' : filterType)
              ).length
            }
          </span>
        </div>

        <div className="grid grid-cols-2 gap-2.5 sm:grid-cols-5">
          <button
            disabled={generating}
            onClick={() =>
              buildPdfForType(
                filterType === 'ALL' ? 'COMPREHENSIVE' : filterType,
                undefined,
                undefined,
                undefined,
                false
              )
            }
            className="flex min-h-[44px] items-center justify-center gap-2 rounded-xl border border-violet-500/40 bg-[#141729] px-3 py-2 text-xs font-bold text-white hover:border-cyan-400"
          >
            {generating ? <Loader2 className="h-4 w-4 animate-spin" /> : <FileText className="h-4 w-4 text-cyan-400" />}
            <span>إنشاء التقرير</span>
          </button>

          <button
            onClick={() =>
              latestPdfUrl
                ? setPreviewOpen(true)
                : buildPdfForType(
                    filterType === 'ALL' ? 'COMPREHENSIVE' : filterType,
                    undefined,
                    undefined,
                    undefined,
                    false
                  )
            }
            className="flex min-h-[44px] items-center justify-center gap-2 rounded-xl border border-slate-700 bg-[#141729] px-3 py-2 text-xs font-bold text-white hover:border-cyan-400"
          >
            <Eye className="h-4 w-4 text-violet-400" />
            <span>عرض التقرير</span>
          </button>

          <button
            disabled={generating}
            onClick={() =>
              buildPdfForType(
                filterType === 'ALL' ? 'COMPREHENSIVE' : filterType,
                undefined,
                undefined,
                undefined,
                true
              )
            }
            className="flex min-h-[44px] items-center justify-center gap-2 rounded-xl bg-gradient-to-l from-violet-600 to-cyan-500 px-3 py-2 text-xs font-bold text-white"
          >
            <FileDown className="h-4 w-4" />
            <span>تحميل PDF</span>
          </button>

          <button
            onClick={handleSharePdf}
            className="flex min-h-[44px] items-center justify-center gap-2 rounded-xl border border-slate-700 bg-[#141729] px-3 py-2 text-xs font-bold text-white hover:border-cyan-400"
          >
            <Share2 className="h-4 w-4 text-emerald-400" />
            <span>مشاركة</span>
          </button>

          <button
            onClick={handlePrint}
            className="flex min-h-[44px] items-center justify-center gap-2 rounded-xl border border-slate-700 bg-[#141729] px-3 py-2 text-xs font-bold text-white hover:border-cyan-400"
          >
            <Printer className="h-4 w-4 text-amber-300" />
            <span>طباعة</span>
          </button>
        </div>

        {statusMessage && (
          <div className="rounded-xl border border-emerald-500/35 bg-emerald-500/10 p-3 text-xs text-emerald-200">
            {statusMessage}
          </div>
        )}
      </div>

      {previewOpen && latestPdfUrl && (
        <div className="rounded-3xl border border-cyan-500/40 bg-[#0C0E1A] p-4 space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-bold text-cyan-300">معاينة ملف PDF العربي RTL الفعلي</h3>
            <button
              onClick={() => setPreviewOpen(false)}
              className="rounded-lg border border-slate-700 px-3 py-1 text-xs text-slate-300"
            >
              إغلاق المعاينة
            </button>
          </div>
          <iframe
            src={latestPdfUrl}
            title="معاينة التقرير الصحي"
            className="h-[540px] w-full rounded-2xl border border-slate-800 bg-black"
          />
        </div>
      )}

      <div className="rounded-3xl border border-violet-500/20 bg-[#0C0E1A] p-5 space-y-3">
        <h3 className="text-base font-bold text-white">سجل التقارير المستقلة ({visibleReports.length})</h3>
        {visibleReports.length === 0 ? (
          <div className="rounded-2xl border border-slate-800 bg-[#080A12] p-6 text-center text-xs text-slate-400">
            لا توجد تقارير محفوظة في هذا القسم بعد.
          </div>
        ) : (
          <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
            {visibleReports.map((rep) => {
              const refImg =
                rep.referenceImageUrl ||
                rep.referenceImageDataUrl ||
                rep.snapshotScans.find((s) => s.imageUrl || s.savedImageDataUrl)?.imageUrl ||
                rep.snapshotScans.find((s) => s.savedImageDataUrl)?.savedImageDataUrl;
              return (
                <div
                  key={rep.id}
                  className="flex flex-col justify-between gap-3 rounded-2xl border border-slate-800 bg-[#080A12] p-4 text-xs"
                >
                  <div className="flex items-start gap-3">
                    {refImg && (
                      <img
                        src={resolveApiUrl(refImg)}
                        alt={`صورة مرجعية - ${rep.title}`}
                        className="h-16 w-20 shrink-0 rounded-xl border border-cyan-500/40 object-cover"
                      />
                    )}
                    <div className="space-y-1">
                      <div className="text-sm font-bold text-white">{rep.title}</div>
                      <div className="text-slate-400">{rep.comparisonSummary}</div>
                      <div className="num-en text-[11px] text-slate-500">
                        {new Date(rep.createdAt).toLocaleString('en-GB')}
                      </div>
                    </div>
                  </div>
                  <div className="flex gap-2">
                    <button
                      onClick={() =>
                        buildPdfForType(
                          rep.reportType,
                          rep.snapshotScans,
                          rep.snapshotRisks,
                          refImg,
                          false
                        )
                      }
                      className="flex-1 rounded-xl border border-slate-700 px-3 py-1.5 font-bold text-slate-200 hover:border-cyan-400"
                    >
                      عرض التقرير
                    </button>
                    <button
                      onClick={() =>
                        buildPdfForType(
                          rep.reportType,
                          rep.snapshotScans,
                          rep.snapshotRisks,
                          refImg,
                          true
                        )
                      }
                      className="flex-1 rounded-xl bg-gradient-to-l from-violet-600 to-cyan-500 px-3 py-1.5 font-bold text-white"
                    >
                      تحميل PDF
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
};
