import { jsPDF } from 'jspdf';
import {
  OFFICIAL_SABQ_ICON,
  type HealthScanRecord,
  type LabReading,
  type ReportCategoryType,
  type ReportRecord,
  type RiskAssessmentResult,
  type UserProfile,
} from '../types/sabq';

function wrapArabicLines(
  ctx: CanvasRenderingContext2D,
  text: string,
  maxWidth: number
): string[] {
  const paragraphs = text.split('\n');
  const lines: string[] = [];
  for (const para of paragraphs) {
    const words = para.trim().split(/\s+/);
    if (words.length === 0 || (words.length === 1 && words[0] === '')) {
      lines.push('');
      continue;
    }
    let current = '';
    for (const word of words) {
      const candidate = current ? `${current} ${word}` : word;
      if (ctx.measureText(candidate).width <= maxWidth) {
        current = candidate;
      } else {
        if (current) lines.push(current);
        current = word;
      }
    }
    if (current) lines.push(current);
  }
  return lines;
}

async function loadImageElement(src: string): Promise<HTMLImageElement | null> {
  const trimmed = String(src || '').trim();
  if (!trimmed) return null;

  let objectUrlToRevoke: string | null = null;
  let resolvedSrc = trimmed;

  // Fetch /api/... or external https:// URLs via apiFetchBlob so CORS never taints canvas
  if (
    !trimmed.startsWith('data:') &&
    !trimmed.startsWith('blob:') &&
    !trimmed.startsWith('/assets/')
  ) {
    try {
      const { apiFetchBlob } = await import('../services/apiClient');
      const { blob } = await apiFetchBlob(trimmed, { method: 'GET' }, 12000);
      if (blob && blob.size > 0) {
        objectUrlToRevoke = URL.createObjectURL(blob);
        resolvedSrc = objectUrlToRevoke;
      }
    } catch {
      return null;
    }
  }

  return new Promise((resolve) => {
    const img = new Image();
    img.crossOrigin = 'anonymous';
    const timer = window.setTimeout(() => {
      if (objectUrlToRevoke) URL.revokeObjectURL(objectUrlToRevoke);
      resolve(null);
    }, 6000);
    img.onload = () => {
      window.clearTimeout(timer);
      if (objectUrlToRevoke) URL.revokeObjectURL(objectUrlToRevoke);
      resolve(img);
    };
    img.onerror = () => {
      window.clearTimeout(timer);
      if (objectUrlToRevoke) URL.revokeObjectURL(objectUrlToRevoke);
      resolve(null);
    };
    img.src = resolvedSrc;
  });
}

export function matchesScanForReport(scanType: string, reportType: ReportCategoryType): boolean {
  if (reportType === 'COMPREHENSIVE') return true;
  if (scanType === reportType) return true;
  if (reportType === 'FACE_EYE_SKIN') return ['FACE', 'EYE', 'SKIN', 'FACE_EYE_SKIN'].includes(scanType);
  if (reportType === 'HAIR_SCALP') return ['HAIR', 'HAIR_SCALP'].includes(scanType);
  if (reportType === 'BMI' || reportType === 'BODY_BMI') return ['BMI', 'BODY_BMI'].includes(scanType);
  if (reportType === 'PULSE' || reportType === 'BIOFINGER_PPG') return ['PULSE', 'BIOFINGER_PPG'].includes(scanType);
  if (reportType === 'RESPIRATORY_SPO2' || reportType === 'RESPIRATORY') {
    return ['RESPIRATORY', 'RESPIRATORY_SPO2'].includes(scanType);
  }
  return false;
}

export function matchesLabForReport(
  lab: Pick<LabReading, 'sectionId' | 'testCode'>,
  reportType: ReportCategoryType
): boolean {
  if (reportType === 'COMPREHENSIVE' || reportType === 'LABS') return true;
  if (lab.sectionId === reportType || lab.testCode === reportType) return true;
  if (reportType === 'BIOFINGER_PPG' && lab.sectionId === 'PULSE') return true;
  if (reportType === 'RESPIRATORY' && lab.sectionId === 'RESPIRATORY_SPO2') return true;
  if (reportType === 'BODY_BMI' && lab.sectionId === 'BMI') return true;
  if (reportType === 'DIABETES_RISK' && ['GLUCOSE', 'HBA1C'].includes(lab.sectionId)) return true;
  if (reportType === 'BP_RISK' && lab.sectionId === 'BP') return true;
  if (reportType === 'HBA1C_RISK' && lab.sectionId === 'HBA1C') return true;
  if (reportType === 'ANEMIA_CBC_RISK' && lab.sectionId === 'CBC') return true;
  if (reportType === 'THYROID_RISK' && lab.sectionId === 'THYROID') return true;
  if (reportType === 'HEART_RISK' && lab.sectionId === 'PULSE') return true;
  if (reportType === 'LIVER_KIDNEY_RISK' && lab.sectionId === 'LIVER_KIDNEY') return true;
  return false;
}

export function matchesRiskForReport(
  risk: Pick<RiskAssessmentResult, 'category' | 'sectionId'>,
  reportType: ReportCategoryType
): boolean {
  if (reportType === 'COMPREHENSIVE') return true;
  if (risk.category === reportType || risk.sectionId === reportType) return true;
  if (reportType === 'HBA1C' && risk.category === 'DIABETES_RISK') return true;
  return false;
}

export const REPORT_TITLES: Record<ReportCategoryType, string> = {
  GLUCOSE: 'تقرير السكر وسكر الدم المستقل',
  BP: 'تقرير ضغط الدم المستقل',
  HBA1C: 'تقرير السكر التراكمي HbA1c المستقل',
  CBC: 'تقرير صورة الدم والأنيميا CBC المستقل',
  THYROID: 'تقرير الغدة الدرقية المستقل',
  PULSE: 'تقرير القلب والنبض المستقل',
  RESPIRATORY_SPO2: 'تقرير التنفس والأكسجين المستقل',
  BMI: 'تقرير الوزن ومؤشر كتلة الجسم (BMI)',
  BODY_COMPOSITION: 'تقرير الدهون وتركيب الجسم المستقل',
  LIVER_KIDNEY: 'تقرير وظائف الكبد والكلى المستقل',
  FACE_EYE_SKIN: 'تقرير الوجه والعين والبشرة المستقل',
  HAIR_SCALP: 'تقرير الشعر وفروة الرأس المستقل',
  FACE: 'تقرير تحليل الوجه المستقل',
  SKIN: 'تقرير تحليل البشرة المستقل',
  EYE: 'تقرير تحليل العين المستقل',
  HAIR: 'تقرير تحليل الشعر وفروة الرأس المستقل',
  BODY_POSTURE: 'تقرير الجسم ووضعية الوقوف المستقل',
  BIOFINGER_PPG: 'تقرير النبض الضوئي (SABQ BioFinger)',
  RESPIRATORY: 'تقرير مؤشر التنفس المستقل',
  DIABETES_RISK: 'تقرير عوامل خطورة السكر والتراكمي',
  BP_RISK: 'تقرير عوامل خطورة ضغط الدم',
  THYROID_BLOOD_RISK: 'تقرير عوامل خطورة الغدة والدم',
  HBA1C_RISK: 'تقرير خطورة السكر التراكمي',
  ANEMIA_CBC_RISK: 'تقرير خطورة الأنيميا وصورة الدم',
  THYROID_RISK: 'تقرير خطورة الغدة الدرقية',
  HEART_RISK: 'تقرير عوامل الخطورة القلبية',
  LIVER_KIDNEY_RISK: 'تقرير وظائف الكبد والكلى',
  GENERAL_HEALTH: 'تقرير الصحة العامة التقديري',
  BODY_BMI: 'تقرير الوزن ومؤشر كتلة الجسم (BMI)',
  LABS: 'تقرير المؤشرات والتحاليل الطبية الموثقة',
  COMPREHENSIVE: 'التقرير الصحي الشامل — سَبَق SABQ AI',
};

export async function generateSabqArabicPdfBlob(params: {
  profile: UserProfile;
  reportType: ReportCategoryType;
  scans: HealthScanRecord[];
  labs: LabReading[];
  riskAssessments?: RiskAssessmentResult[];
  referenceImageDataUrl?: string;
  previousReport?: ReportRecord;
}): Promise<{ blob: Blob; filename: string; comparisonSummary: string }> {
  if (document.fonts?.ready) {
    await document.fonts.ready;
  }

  const pageWidthPx = 1240;
  const pageHeightPx = 1754;
  const margin = 76;
  const contentWidth = pageWidthPx - margin * 2;
  const pages: HTMLCanvasElement[] = [];

  const logoImg = await loadImageElement(OFFICIAL_SABQ_ICON);

  const createPage = (pageNum: number) => {
    const canvas = document.createElement('canvas');
    canvas.width = pageWidthPx;
    canvas.height = pageHeightPx;
    const ctx = canvas.getContext('2d')!;
    ctx.direction = 'rtl';
    ctx.textAlign = 'right';

    ctx.fillStyle = '#070912';
    ctx.fillRect(0, 0, pageWidthPx, pageHeightPx);

    const grad = ctx.createLinearGradient(margin, 0, pageWidthPx - margin, 0);
    grad.addColorStop(0, '#00D4FF');
    grad.addColorStop(1, '#8B5CF6');
    ctx.fillStyle = grad;
    ctx.fillRect(0, 0, pageWidthPx, 10);

    ctx.fillStyle = '#64748B';
    ctx.font = '400 20px "Cairo", "Tajawal", sans-serif';
    ctx.textAlign = 'left';
    ctx.fillText(`SABQ AI  |  Page ${pageNum}`, margin, pageHeightPx - 32);
    ctx.textAlign = 'right';
    ctx.fillText(
      'تنبيه: التقديرات البصرية وتقييم عوامل الخطورة لا تغني عن التشخيص الطبي المخبري.',
      pageWidthPx - margin,
      pageHeightPx - 32
    );

    pages.push(canvas);
    return { canvas, ctx };
  };

  let { ctx } = createPage(1);
  let y = 70;

  const ensureSpace = (needed: number) => {
    if (y + needed > pageHeightPx - 95) {
      const next = createPage(pages.length + 1);
      ctx = next.ctx;
      y = 78;
    }
  };

  const drawTextBlock = (
    text: string,
    font: string,
    color: string,
    lineHeight: number,
    spacingAfter = 12
  ) => {
    ctx.font = font;
    ctx.fillStyle = color;
    ctx.direction = 'rtl';
    ctx.textAlign = 'right';
    const lines = wrapArabicLines(ctx, text, contentWidth);
    ensureSpace(lines.length * lineHeight + spacingAfter);
    for (const line of lines) {
      ctx.fillText(line, pageWidthPx - margin, y);
      y += lineHeight;
    }
    y += spacingAfter;
  };

  const drawSectionHeader = (title: string) => {
    ensureSpace(64);
    ctx.fillStyle = 'rgba(139, 92, 246, 0.18)';
    ctx.fillRect(margin, y - 34, contentWidth, 44);
    ctx.fillStyle = '#00D4FF';
    ctx.fillRect(pageWidthPx - margin - 6, y - 34, 6, 44);
    ctx.font = '700 24px "Cairo", "Tajawal", sans-serif';
    ctx.fillStyle = '#F8FAFC';
    ctx.textAlign = 'right';
    ctx.fillText(title, pageWidthPx - margin - 20, y - 4);
    y += 28;
  };

  const drawTable = (headers: [string, string, string], rows: Array<[string, string, string]>) => {
    const colWidths = [Math.round(contentWidth * 0.34), Math.round(contentWidth * 0.36), Math.round(contentWidth * 0.30)];
    const rowH = 44;
    ensureSpace((rows.length + 1) * rowH + 20);

    // Header Row
    ctx.fillStyle = '#14182D';
    ctx.fillRect(margin, y - 30, contentWidth, rowH);
    ctx.strokeStyle = 'rgba(0, 212, 255, 0.35)';
    ctx.lineWidth = 1.5;
    ctx.strokeRect(margin, y - 30, contentWidth, rowH);

    ctx.font = '700 20px "Cairo", sans-serif';
    ctx.fillStyle = '#00D4FF';
    ctx.textAlign = 'right';
    let rx = pageWidthPx - margin - 14;
    headers.forEach((h, i) => {
      ctx.fillText(h, rx, y - 2);
      rx -= colWidths[i];
    });
    y += rowH;

    // Data Rows
    ctx.font = '400 19px "Cairo", sans-serif';
    rows.forEach((row, idx) => {
      ensureSpace(rowH + 10);
      ctx.fillStyle = idx % 2 === 0 ? '#0B0E1A' : '#0E1222';
      ctx.fillRect(margin, y - 30, contentWidth, rowH);
      ctx.strokeStyle = 'rgba(139, 92, 246, 0.25)';
      ctx.strokeRect(margin, y - 30, contentWidth, rowH);

      let cx = pageWidthPx - margin - 14;
      row.forEach((cell, i) => {
        ctx.fillStyle = i === 1 ? '#6EE7B7' : '#E2E8F0';
        const trimmed = cell.length > 32 ? `${cell.slice(0, 31)}…` : cell;
        ctx.fillText(trimmed, cx, y - 2);
        cx -= colWidths[i];
      });
      y += rowH;
    });
    y += 14;
  };

  // Header with Official SABQ Logo
  if (logoImg) {
    ctx.drawImage(logoImg, pageWidthPx - margin - 86, y - 22, 86, 86);
  }
  const nowStr = new Date().toLocaleString('en-GB', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });

  const reportTitle = REPORT_TITLES[params.reportType];
  ctx.font = '800 32px "Cairo", sans-serif';
  ctx.fillStyle = '#00D4FF';
  ctx.textAlign = 'right';
  ctx.fillText(`سَبَق — ${reportTitle}`, pageWidthPx - margin - 104, y + 12);

  ctx.font = '600 22px "Cairo", sans-serif';
  ctx.fillStyle = '#E2E8F0';
  ctx.fillText(`اسم المستخدم: ${params.profile.displayName}`, pageWidthPx - margin - 104, y + 48);
  ctx.fillText(`تاريخ ووقت الفحص: ${nowStr}`, pageWidthPx - margin - 104, y + 80);
  y += 126;

  // Reference Images (embed relevant photos, e.g., Hair, Face, Body from imageUrl or dataUrl)
  const refImages = Array.from(
    new Set(
      [
        params.referenceImageDataUrl,
        ...params.scans
          .filter(
            (s) =>
              Boolean(s.imageUrl || s.savedImageDataUrl) &&
              matchesScanForReport(s.scanType, params.reportType)
          )
          .map((s) => s.imageUrl || s.savedImageDataUrl),
      ].filter(Boolean) as string[]
    )
  ).slice(0, 3);

  if (refImages.length > 0) {
    drawSectionHeader('الصور المرجعية المرتبطة بجلسة الفحص');
    const thumbW = 310;
    const thumbH = 220;
    ensureSpace(thumbH + 30);
    let drawX = pageWidthPx - margin - thumbW;
    for (const src of refImages) {
      const imgEl = await loadImageElement(src);
      if (imgEl) {
        ctx.strokeStyle = '#00D4FF';
        ctx.lineWidth = 2.5;
        ctx.strokeRect(drawX - 2, y - 2, thumbW + 4, thumbH + 4);
        ctx.drawImage(imgEl, drawX, y, thumbW, thumbH);
        drawX -= thumbW + 24;
      }
    }
    y += thumbH + 26;
  }

  // Filter scans for this report
  const filteredScans = params.scans.filter((s) => matchesScanForReport(s.scanType, params.reportType));

  if (filteredScans.length > 0) {
    drawSectionHeader('جدول النتائج والموثوقية والمصادر');
    drawTable(
      ['الفحص / المنطقة', 'النتيجة / التقدير', 'المصدر والتاريخ'],
      filteredScans.slice(0, 8).map((s) => [
        s.scanTitleAr,
        s.measuredMetric
          ? `${s.measuredMetric.value} ${s.measuredMetric.unit}`
          : `${s.severityLevel || 'تقدير غير تشخيصي'}`,
        new Date(s.timestamp).toLocaleDateString('en-GB'),
      ])
    );

    drawSectionHeader('التفاصيل والشرح المبسط والحدود والتوصيات');
    for (const scan of filteredScans) {
      const conf =
        scan.confidenceScore !== null
          ? `${Math.round(scan.confidenceScore * 100)}%`
          : 'قياس مباشر';
      const kind = scan.isNonDiagnosticEstimate ? 'تقدير غير تشخيصي' : 'قياس مباشر';
      drawTextBlock(
        `• القسم: ${scan.scanTitleAr} (${kind} | الموثوقية: ${conf})\n` +
          `• الشرح المبسط: ${scan.summary}\n` +
          `• المشكلات المحتملة: ${scan.potentialIssues.join('، ') || 'لا توجد مؤشرات ظاهرة'}\n` +
          `• حدود التحليل: ${scan.limitations || 'التحليل البصري يتأثر بالإضاءة والزاوية ولا يشخص الأمراض.'}\n` +
          `• التوصيات والحلول: ${scan.recommendations.join('، ') || 'المتابعة الدورية'}\n` +
          `• متى تجب مراجعة الطبيب: ${scan.doctorConsultationCriteria}`,
        '400 20px "Cairo", sans-serif',
        '#E2E8F0',
        31,
        16
      );
    }
  }

  // Risk Assessments included for matching section or Comprehensive
  const riskList = (params.riskAssessments || []).filter((r) => matchesRiskForReport(r, params.reportType));
  if (riskList.length > 0) {
    drawSectionHeader('تقييم عوامل الخطورة الشخصية (تقييم غير تشخيصي)');
    drawTable(
      ['المجال الصحي', 'مستوى الخطورة', 'المصدر'],
      riskList.map((r) => [r.titleAr, r.riskLevelAr, r.resultClassification || 'تقييم خطورة'])
    );
    for (const r of riskList) {
      drawTextBlock(
        `• ${r.titleAr} — مستوى الخطورة: ${r.riskLevelAr}\n` +
          `• البيانات المستخدمة: ${(r.usedData || []).join('، ') || 'غير كافية'}\n` +
          `• عوامل رفعت الخطورة: ${(r.elevatingFactors || []).join('، ') || 'لا يوجد'}\n` +
          `• المعلومات الناقصة: ${(r.missingInformation || []).join('، ') || 'مكتمل'}\n` +
          `• الشرح والخطوة التالية: ${r.summary} (${r.nextStepAr})\n` +
          `• متى تراجع الطبيب: ${r.doctorConsultationCriteria}`,
        '400 20px "Cairo", sans-serif',
        '#E2E8F0',
        31,
        14
      );
    }
  }

  // Verified Clinical Labs Table
  const filteredLabs = params.labs.filter((l) => matchesLabForReport(l, params.reportType));
  if (filteredLabs.length > 0 || params.reportType === 'LABS' || params.reportType === 'COMPREHENSIVE') {
    drawSectionHeader('القراءات المخبرية والأجهزة المعتمدة');
    if (filteredLabs.length === 0) {
      drawTextBlock(
        'لم تُسجل قراءات مخبرية أو قياسات موثقة لهذا القسم بعد (يمنع التطبيق أي قراءات تخمينية).',
        '400 20px "Cairo", sans-serif',
        '#94A3B8',
        30,
        14
      );
    } else {
      drawTable(
        ['التحليل / المؤشر', 'القيمة المقاسة', 'المصدر'],
        filteredLabs.slice(0, 12).map((l) => [
          l.testNameAr,
          `${l.measuredValue} ${l.unit}`,
          l.source,
        ])
      );
      for (const lab of filteredLabs.slice(0, 8)) {
        if (lab.explanationAr) {
          drawTextBlock(
            `• ${lab.testNameAr} (${lab.measuredValue} ${lab.unit}) — ${new Date(lab.recordedAt).toLocaleDateString('en-GB')}: ${lab.explanationAr}`,
            '400 19px "Cairo", sans-serif',
            '#CBD5E1',
            28,
            8
          );
        }
      }
    }
  }

  const comparisonSummary = params.previousReport
    ? `مقارنة بالتقرير السابق (${params.previousReport.title}) بتاريخ ${new Date(
        params.previousReport.createdAt
      ).toLocaleDateString('en-GB')}: الفحوص الحالية ${filteredScans.length} (سابقًا ${
        params.previousReport.scansCount
      }) والتحاليل الحالية ${filteredLabs.length} (سابقًا ${params.previousReport.labsCount}).`
    : 'هذا التقرير هو المرجع الأساسي الأول لهذا القسم للمقارنة المستقبلية.';

  drawSectionHeader('المقارنة بالفحص السابق');
  drawTextBlock(comparisonSummary, '400 20px "Cairo", sans-serif', '#E2E8F0', 30, 14);

  const pdf = new jsPDF({ orientation: 'portrait', unit: 'pt', format: 'a4' });
  pages.forEach((canvas, idx) => {
    if (idx > 0) pdf.addPage();
    pdf.addImage(canvas.toDataURL('image/jpeg', 0.92), 'JPEG', 0, 0, 595.28, 841.89);
  });

  const blob = pdf.output('blob');
  const safeUser = params.profile.userId.replace(/[^a-zA-Z0-9_-]/g, '');
  const filename = `SABQ_${params.reportType}_${safeUser}_${Date.now()}.pdf`;
  return { blob, filename, comparisonSummary };
}
