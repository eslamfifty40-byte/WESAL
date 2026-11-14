export interface DeviceHardwareCapabilities {
  hasCamera: boolean;
  hasMicrophone: boolean;
  hasWebBluetooth: boolean;
  hasBiometricPlatformAuth: boolean;
  hasSpeechSynthesis: boolean;
  hasMediaSession: boolean;
  summaryNotesAr: string[];
}

export interface PpgSampleResult {
  success: boolean;
  bpm: number | null;
  respiratoryEstimate: number | null;
  signalQuality: number; // 0..1
  redMean: number;
  messageAr: string;
}

export async function inspectDeviceCapabilities(): Promise<DeviceHardwareCapabilities> {
  const hasCamera = Boolean(navigator.mediaDevices?.getUserMedia);
  const hasMicrophone = Boolean(navigator.mediaDevices?.getUserMedia);
  const hasWebBluetooth = Boolean((navigator as any).bluetooth);
  const hasSpeechSynthesis = 'speechSynthesis' in window;
  const hasMediaSession = 'mediaSession' in navigator;

  let hasBiometricPlatformAuth = false;
  try {
    if (window.PublicKeyCredential?.isUserVerifyingPlatformAuthenticatorAvailable) {
      hasBiometricPlatformAuth =
        await window.PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable();
    }
  } catch {
    hasBiometricPlatformAuth = false;
  }

  const summaryNotesAr: string[] = [
    'قارئ بصمة الهوية في الهاتف مخصص للأمان والمصادقة فقط، وليس مستشعرًا لتحليل الدم أو السكر أو الضغط.',
  ];

  if (hasCamera) {
    summaryNotesAr.push(
      'الكاميرا متاحة للتحليل البصري ومحاولات التصوير الضوئي للنبض (PPG) كتقدير غير تشخيصي.'
    );
  } else {
    summaryNotesAr.push('الكاميرا غير متاحة أو غير مدعومة في هذا المتصفح.');
  }

  if (hasWebBluetooth) {
    summaryNotesAr.push('واجهة Bluetooth متاحة للاقتران بالأجهزة المدعومة عند منح الإذن.');
  } else {
    summaryNotesAr.push(
      'واجهة Web Bluetooth غير مدعومة في هذا المتصفح أو الإطار؛ تتطلب الساعات المتوافقة تطبيق Android الأصلي.'
    );
  }

  return {
    hasCamera,
    hasMicrophone,
    hasWebBluetooth,
    hasBiometricPlatformAuth,
    hasSpeechSynthesis,
    hasMediaSession,
    summaryNotesAr,
  };
}

/**
 * Computes pulse BPM strictly from real sampled red-channel optical intensities.
 * Returns failure message if signal is noisy, flat, or finger not covering lens.
 */
export function analyzePpgSamples(
  redMeans: number[],
  timestampsMs: number[]
): PpgSampleResult {
  if (redMeans.length < 90 || redMeans.length !== timestampsMs.length) {
    return {
      success: false,
      bpm: null,
      respiratoryEstimate: null,
      signalQuality: 0,
      redMean: 0,
      messageAr: 'تعذر إجراء القياس، يرجى المحاولة مرة أخرى.',
    };
  }

  const durationSec = (timestampsMs[timestampsMs.length - 1] - timestampsMs[0]) / 1000;
  if (durationSec < 4.5) {
    return {
      success: false,
      bpm: null,
      respiratoryEstimate: null,
      signalQuality: 0,
      redMean: 0,
      messageAr: 'تعذر إجراء القياس، يرجى المحاولة مرة أخرى.',
    };
  }

  const mean = redMeans.reduce((a, b) => a + b, 0) / redMeans.length;
  const variance =
    redMeans.reduce((acc, val) => acc + (val - mean) ** 2, 0) / redMeans.length;
  const stdDev = Math.sqrt(variance);

  // If image is too dark or completely static/flat (no pulsatile change), fail honestly
  if (mean < 45 || stdDev < 0.08) {
    return {
      success: false,
      bpm: null,
      respiratoryEstimate: null,
      signalQuality: 0.12,
      redMean: mean,
      messageAr: 'تعذر إجراء القياس، يرجى المحاولة مرة أخرى.',
    };
  }

  // Detrend with local moving average
  const win = 5;
  const detrended = redMeans.map((v, i) => {
    const start = Math.max(0, i - win);
    const end = Math.min(redMeans.length - 1, i + win);
    let sum = 0;
    for (let j = start; j <= end; j++) sum += redMeans[j];
    return v - sum / (end - start + 1);
  });

  const peaks: number[] = [];
  const minPeakDistanceMs = 340; // max ~176 bpm
  for (let i = 2; i < detrended.length - 2; i++) {
    const c = detrended[i];
    if (
      c > 0.02 &&
      c > detrended[i - 1] &&
      c > detrended[i + 1] &&
      c >= detrended[i - 2] &&
      c >= detrended[i + 2]
    ) {
      const t = timestampsMs[i];
      if (peaks.length === 0 || t - peaks[peaks.length - 1] >= minPeakDistanceMs) {
        peaks.push(t);
      }
    }
  }

  if (peaks.length < 4) {
    return {
      success: false,
      bpm: null,
      respiratoryEstimate: null,
      signalQuality: 0.25,
      redMean: mean,
      messageAr: 'تعذر إجراء القياس، يرجى المحاولة مرة أخرى.',
    };
  }

  const intervals: number[] = [];
  for (let i = 1; i < peaks.length; i++) {
    intervals.push(peaks[i] - peaks[i - 1]);
  }
  const avgInterval = intervals.reduce((a, b) => a + b, 0) / intervals.length;
  const jitter =
    intervals.reduce((acc, val) => acc + Math.abs(val - avgInterval), 0) /
    intervals.length /
    avgInterval;

  const bpm = Math.round(60000 / avgInterval);
  if (bpm < 42 || bpm > 175 || jitter > 0.36) {
    return {
      success: false,
      bpm: null,
      respiratoryEstimate: null,
      signalQuality: Math.max(0.1, Number((1 - jitter).toFixed(2))),
      redMean: mean,
      messageAr: 'تعذر إجراء القياس، يرجى المحاولة مرة أخرى.',
    };
  }

  const quality = Math.min(0.95, Math.max(0.45, Number((1 - jitter).toFixed(2))));
  // Respiratory estimation only if duration >= 9s and quality >= 0.6
  const respiratoryEstimate =
    durationSec >= 9 && quality >= 0.6 ? Math.round(bpm / 4.5) : null;

  return {
    success: true,
    bpm,
    respiratoryEstimate,
    signalQuality: quality,
    redMean: Number(mean.toFixed(1)),
    messageAr: 'تم استخلاص موجة ضوئية متماسكة (تقدير غير تشخيصي)',
  };
}
