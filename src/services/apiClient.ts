import { resolveApiUrl } from '../apiBase';
import { getFirebaseIdToken } from '../firebase';

export class ApiClientError extends Error {
  status: number;
  isHtmlResponse: boolean;
  isTimeout: boolean;
  isNetworkError: boolean;

  constructor(
    message: string,
    opts?: {
      status?: number;
      isHtmlResponse?: boolean;
      isTimeout?: boolean;
      isNetworkError?: boolean;
    }
  ) {
    super(message);
    this.name = 'ApiClientError';
    this.status = opts?.status ?? 0;
    this.isHtmlResponse = Boolean(opts?.isHtmlResponse);
    this.isTimeout = Boolean(opts?.isTimeout);
    this.isNetworkError = Boolean(opts?.isNetworkError);
  }
}

function looksLikeHtml(text: string, contentType: string): boolean {
  if (contentType.includes('text/html') || contentType.includes('application/xhtml+xml')) {
    return true;
  }
  const sample = text.slice(0, 256).trim().toLowerCase();
  return sample.startsWith('<!doctype') || sample.startsWith('<html') || sample.startsWith('<head');
}

async function performRawFetch(
  pathOrUrl: string,
  init: RequestInit = {},
  timeoutMs = 45000
): Promise<Response> {
  const controller = new AbortController();
  const externalSignal = init.signal;
  if (externalSignal) {
    if (externalSignal.aborted) {
      throw new ApiClientError('تم إلغاء الطلب.');
    }
    externalSignal.addEventListener('abort', () => controller.abort(), { once: true });
  }

  const headers = new Headers(init.headers || {});
  const idToken = await getFirebaseIdToken();
  if (idToken && !headers.has('Authorization')) {
    headers.set('Authorization', `Bearer ${idToken}`);
  }

  const timer = window.setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(resolveApiUrl(pathOrUrl), {
      ...init,
      headers,
      signal: controller.signal,
    });
  } catch (err: any) {
    if (externalSignal?.aborted) {
      throw new ApiClientError('تم إلغاء العملية بناءً على طلبك.');
    }
    if (err?.name === 'AbortError') {
      throw new ApiClientError(
        'انتهت مهلة الاتصال بالخادم — يرجى التحقق من الإنترنت وإعادة المحاولة.',
        { isTimeout: true }
      );
    }
    throw new ApiClientError(
      'تعذر الاتصال بالخادم — تحقق من اتصال الإنترنت وحاول مرة أخرى.',
      { isNetworkError: true }
    );
  } finally {
    window.clearTimeout(timer);
  }
}

export async function apiFetchJson<T = any>(
  pathOrUrl: string,
  init: RequestInit = {},
  timeoutMs = 45000
): Promise<T> {
  const headers = new Headers(init.headers || {});
  if (!headers.has('Accept')) {
    headers.set('Accept', 'application/json');
  }
  if (init.body && typeof init.body === 'string' && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }

  const res = await performRawFetch(pathOrUrl, { ...init, headers }, timeoutMs);
  const contentType = (res.headers.get('content-type') || '').toLowerCase();
  const rawText = await res.text();

  if (looksLikeHtml(rawText, contentType)) {
    throw new ApiClientError(
      'استلم التطبيق صفحة HTML بدلاً من استجابة الخادم (JSON) — يرجى التحقق من توجيه مسارات API على Firebase Hosting.',
      { status: res.status || 502, isHtmlResponse: true }
    );
  }

  let parsed: any = {};
  if (rawText.trim()) {
    try {
      parsed = JSON.parse(rawText);
    } catch {
      throw new ApiClientError(
        res.ok
          ? 'استجابة الخادم ليست بصيغة JSON صالحة.'
          : `فشل طلب الخادم (رمز الحالة ${res.status}).`,
        { status: res.status }
      );
    }
  }

  if (!res.ok) {
    const serverMsg =
      typeof parsed?.error === 'string' && parsed.error.trim()
        ? parsed.error
        : typeof parsed?.message === 'string' && parsed.message.trim()
        ? parsed.message
        : `تعذر إتمام الطلب (رمز الحالة ${res.status}).`;
    throw new ApiClientError(serverMsg, { status: res.status });
  }

  return parsed as T;
}

export async function apiUploadMultipart<T = any>(
  pathOrUrl: string,
  formData: FormData,
  init: Omit<RequestInit, 'body' | 'method'> = {},
  timeoutMs = 45000
): Promise<T> {
  // Explicitly strip any Content-Type so the browser inserts the multipart boundary cleanly
  const headers = new Headers(init.headers || {});
  headers.delete('Content-Type');
  if (!headers.has('Accept')) {
    headers.set('Accept', 'application/json');
  }

  const res = await performRawFetch(
    pathOrUrl,
    {
      ...init,
      method: 'POST',
      headers,
      body: formData,
    },
    timeoutMs
  );

  const contentType = (res.headers.get('content-type') || '').toLowerCase();
  const rawText = await res.text();

  if (looksLikeHtml(rawText, contentType)) {
    throw new ApiClientError(
      'أعاد الخادم صفحة HTML بدلاً من تأكيد رفع الصورة — يرجى التحقق من إعداد /api/uploads.',
      { status: res.status || 502, isHtmlResponse: true }
    );
  }

  let parsed: any = {};
  if (rawText.trim()) {
    try {
      parsed = JSON.parse(rawText);
    } catch {
      throw new ApiClientError(`استجابة رفع غير صالحة من الخادم (${res.status}).`, {
        status: res.status,
      });
    }
  }

  if (!res.ok) {
    throw new ApiClientError(
      parsed?.error || `فشل رفع الملف إلى الخادم (${res.status}).`,
      { status: res.status }
    );
  }

  return parsed as T;
}

export async function apiFetchBlob(
  pathOrUrl: string,
  init: RequestInit = {},
  timeoutMs = 45000
): Promise<{ blob: Blob; contentType: string; filename?: string }> {
  const res = await performRawFetch(pathOrUrl, init, timeoutMs);
  const contentType = (res.headers.get('content-type') || '').toLowerCase();

  if (!res.ok || contentType.includes('text/html') || contentType.includes('application/json')) {
    const rawText = await res.text().catch(() => '');
    if (looksLikeHtml(rawText, contentType)) {
      throw new ApiClientError(
        'أعاد الخادم صفحة HTML بدلاً من الملف المطلوب — تحقق من توجيه مسارات API.',
        { status: res.status || 502, isHtmlResponse: true }
      );
    }
    try {
      const json = JSON.parse(rawText);
      if (json?.error) {
        throw new ApiClientError(json.error, { status: res.status });
      }
    } catch (e) {
      if (e instanceof ApiClientError) throw e;
    }
    throw new ApiClientError(`تعذر تنزيل الملف من الخادم (رمز الحالة ${res.status}).`, {
      status: res.status,
    });
  }

  const blob = await res.blob();
  const disp = res.headers.get('content-disposition') || '';
  const match = /filename\*?=(?:UTF-8'')?["']?([^"';]+)["']?/i.exec(disp);
  return {
    blob,
    contentType: contentType || blob.type || 'application/octet-stream',
    filename: match?.[1] ? decodeURIComponent(match[1]) : undefined,
  };
}
