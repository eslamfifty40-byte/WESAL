import type { HealthSectionId, UploadedImageRecord } from '../types/sabq';

const ALLOWED_SERVER_MIME_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp']);
const DECODABLE_EXTRA_IMAGE_MIMES = new Set([
  'image/jpg',
  'image/pjpeg',
  'image/heic',
  'image/heif',
  'image/avif',
  'image/bmp',
  'image/gif',
  'image/x-ms-bmp',
]);
const DECODABLE_IMAGE_EXTENSIONS = [
  '.jpg',
  '.jpeg',
  '.png',
  '.webp',
  '.heic',
  '.heif',
  '.avif',
  '.bmp',
  '.gif',
];
const MAX_RAW_FILE_BYTES = 20 * 1024 * 1024; // 20 MB limit before compression
const TARGET_MAX_COMPRESSED_BYTES = 2 * 1024 * 1024; // 2 MB target keeps mobile uploads fast & reliable
const REQUEST_TIMEOUT_MS = 45000;

export interface PreparedImagePreview {
  file: File;
  compressedBlob: Blob;
  previewUrl: string;
  mimeType: string;
  originalSizeBytes: number;
  compressedSizeBytes: number;
  width: number;
  height: number;
}

export function resolveImageMimeType(file: File): string {
  const rawType = (file.type || '').toLowerCase().trim();
  if (rawType === 'image/jpg' || rawType === 'image/pjpeg') return 'image/jpeg';
  if (ALLOWED_SERVER_MIME_TYPES.has(rawType)) return rawType;
  if (DECODABLE_EXTRA_IMAGE_MIMES.has(rawType) || rawType.startsWith('image/')) return rawType;

  const name = (file.name || '').toLowerCase();
  if (name.endsWith('.jpg') || name.endsWith('.jpeg')) return 'image/jpeg';
  if (name.endsWith('.png')) return 'image/png';
  if (name.endsWith('.webp')) return 'image/webp';
  if (DECODABLE_IMAGE_EXTENSIONS.some((ext) => name.endsWith(ext))) return 'image/jpeg';
  return rawType;
}

export function validateImageFile(file: File): string | null {
  if (file.size === 0) {
    return 'الملف المختار فارغ ولا يحتوي على بيانات صورة.';
  }
  if (file.size > MAX_RAW_FILE_BYTES) {
    return 'حجم الصورة يتجاوز الحد الأقصى المسموح به (20 ميجابايت).';
  }

  const rawType = (file.type || '').toLowerCase().trim();
  // Android/iOS photo pickers sometimes return empty MIME or application/octet-stream for gallery photos
  const isNeutralType = rawType === '' || rawType === 'application/octet-stream';
  if (!isNeutralType) {
    const mime = resolveImageMimeType(file);
    if (!mime.startsWith('image/')) {
      return 'صيغة الملف غير مدعومة. الرجاء اختيار صورة (JPEG أو PNG أو WebP).';
    }
  }
  return null;
}

function canvasToBlob(canvas: HTMLCanvasElement, mimeType: string, quality?: number): Promise<Blob | null> {
  return new Promise((resolve) => {
    try {
      canvas.toBlob((b) => resolve(b), mimeType, quality);
    } catch {
      resolve(null);
    }
  });
}

async function decodeFileToDrawable(
  file: File
): Promise<{ width: number; height: number; draw: (ctx: CanvasRenderingContext2D, w: number, h: number) => void }> {
  // Prefer createImageBitmap with EXIF orientation when available (prevents rotated mobile photos)
  if (typeof createImageBitmap === 'function') {
    try {
      const bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' } as any);
      if (bitmap.width > 0 && bitmap.height > 0) {
        return {
          width: bitmap.width,
          height: bitmap.height,
          draw: (ctx, w, h) => {
            ctx.drawImage(bitmap, 0, 0, w, h);
            bitmap.close?.();
          },
        };
      }
      bitmap.close?.();
    } catch {
      // fallback to HTMLImageElement below
    }
  }

  const objectUrl = URL.createObjectURL(file);
  try {
    const img = await new Promise<HTMLImageElement>((resolve, reject) => {
      const el = new Image();
      el.onload = () => resolve(el);
      el.onerror = () =>
        reject(new Error('تعذر قراءة الصورة المختارة — تأكد أن الملف صورة صالحة (JPEG / PNG / WebP).'));
      el.src = objectUrl;
    });
    const w = img.naturalWidth || img.width;
    const h = img.naturalHeight || img.height;
    if (w === 0 || h === 0) {
      throw new Error('أبعاد الصورة غير صالحة أو الملف تالف.');
    }
    return {
      width: w,
      height: h,
      draw: (ctx, tw, th) => {
        ctx.drawImage(img, 0, 0, tw, th);
      },
    };
  } finally {
    URL.revokeObjectURL(objectUrl);
  }
}

/**
 * Safely decodes and scales large images (e.g., 4000x3000 gallery photos) down to max 1280px
 * while preserving diagnostic visual clarity (pores, wrinkles, scalp, hairline) and normalizing
 * mobile photo formats to JPEG / PNG / WebP before multipart upload.
 */
export async function prepareAndCompressImage(file: File): Promise<PreparedImagePreview> {
  const err = validateImageFile(file);
  if (err) throw new Error(err);

  const resolvedMime = resolveImageMimeType(file);
  const decoded = await decodeFileToDrawable(file);

  const maxDim = 1280;
  let targetW = decoded.width;
  let targetH = decoded.height;
  if (targetW > maxDim || targetH > maxDim) {
    if (targetW >= targetH) {
      targetH = Math.max(1, Math.round((targetH * maxDim) / targetW));
      targetW = maxDim;
    } else {
      targetW = Math.max(1, Math.round((targetW * maxDim) / targetH));
      targetH = maxDim;
    }
  }

  const canvas = document.createElement('canvas');
  canvas.width = Math.max(1, targetW);
  canvas.height = Math.max(1, targetH);
  const ctx = canvas.getContext('2d');
  if (!ctx) throw new Error('تعذر تهيئة معالج الصور في المتصفح.');
  decoded.draw(ctx, canvas.width, canvas.height);

  // Server accepts image/jpeg, image/png, image/webp; convert any extra browser-decoded format (e.g. HEIC) to JPEG
  let outMime = ALLOWED_SERVER_MIME_TYPES.has(resolvedMime) ? resolvedMime : 'image/jpeg';
  let compressedBlob = await canvasToBlob(
    canvas,
    outMime,
    outMime === 'image/png' ? undefined : 0.85
  );

  // Fallback if browser doesn't support WebP encoding in canvas.toBlob
  if (!compressedBlob || (outMime === 'image/webp' && compressedBlob.type !== 'image/webp')) {
    outMime = 'image/jpeg';
    compressedBlob = await canvasToBlob(canvas, outMime, 0.85);
  }

  // Keep compressed transfer size compact for reliable mobile uploads
  if (compressedBlob && compressedBlob.size > TARGET_MAX_COMPRESSED_BYTES) {
    const jpegFallback = await canvasToBlob(canvas, 'image/jpeg', 0.80);
    if (jpegFallback && jpegFallback.size < compressedBlob.size) {
      compressedBlob = jpegFallback;
      outMime = 'image/jpeg';
    }
  }

  if (!compressedBlob || compressedBlob.size === 0) {
    throw new Error('فشل ضغط الصورة قبل الرفع.');
  }

  const previewUrl = URL.createObjectURL(compressedBlob);
  return {
    file,
    compressedBlob,
    previewUrl,
    mimeType: outMime,
    originalSizeBytes: file.size,
    compressedSizeBytes: compressedBlob.size,
    width: canvas.width,
    height: canvas.height,
  };
}

export function blobToDataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(new Error('تعذر تحويل الصورة للقراءة.'));
    reader.readAsDataURL(blob);
  });
}

export async function uploadImageMultipart(params: {
  userId: string;
  sectionId: HealthSectionId | 'SMART_SCAN';
  sessionId?: string;
  sourceOrigin: 'camera' | 'gallery' | 'file';
  prepared: PreparedImagePreview;
  signal?: AbortSignal;
}): Promise<UploadedImageRecord> {
  const { apiUploadMultipart } = await import('../services/apiClient');
  const form = new FormData();
  const ext =
    params.prepared.mimeType === 'image/png'
      ? 'png'
      : params.prepared.mimeType === 'image/webp'
      ? 'webp'
      : 'jpg';
  form.append('image', params.prepared.compressedBlob, `sabq_${params.sectionId}_${Date.now()}.${ext}`);
  form.append('sectionId', params.sectionId);
  form.append('sourceOrigin', params.sourceOrigin);
  if (params.sessionId) form.append('sessionId', params.sessionId);

  const data = await apiUploadMultipart<{ ok?: boolean; image?: UploadedImageRecord; error?: string }>(
    `/api/uploads/${encodeURIComponent(params.userId)}`,
    form,
    { signal: params.signal },
    REQUEST_TIMEOUT_MS
  );

  if (!data?.ok || !data?.image) {
    throw new Error(data?.error || 'فشل رفع الصورة إلى الخادم.');
  }
  return data.image;
}
