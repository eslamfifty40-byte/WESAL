import { Camera, CameraResultType, CameraSource } from '@capacitor/camera';
import { isCapacitorNativePlatform } from '../apiBase';

export { isCapacitorNativePlatform };

async function dataUrlToFile(dataUrl: string, filename: string): Promise<File> {
  const res = await fetch(dataUrl);
  const blob = await res.blob();
  const mime = blob.type || 'image/jpeg';
  return new File([blob], filename, { type: mime });
}

/**
 * On Capacitor Android native, opens native Camera or Photo Picker via @capacitor/camera
 * and returns a standard File object ready for compression & multipart upload.
 * Returns null if not native (caller uses web input/camera) or if user cancels.
 */
export async function pickImageWithCapacitorNative(
  source: 'camera' | 'gallery'
): Promise<{ file: File } | { cancelled: true } | null> {
  if (!isCapacitorNativePlatform()) {
    return null;
  }

  try {
    const perms = await Camera.requestPermissions({
      permissions: source === 'camera' ? ['camera'] : ['photos'],
    });
    if (source === 'camera' && perms.camera === 'denied') {
      throw new Error('تم رفض إذن الكاميرا في أندرويد — فعّله من إعدادات التطبيق.');
    }

    const photo = await Camera.getPhoto({
      quality: 90,
      allowEditing: false,
      resultType: CameraResultType.DataUrl,
      source: source === 'camera' ? CameraSource.Camera : CameraSource.Photos,
      correctOrientation: true,
    });

    if (!photo.dataUrl) {
      return { cancelled: true };
    }

    const ext = photo.format === 'png' ? 'png' : 'jpg';
    const file = await dataUrlToFile(photo.dataUrl, `sabq_cap_${Date.now()}.${ext}`);
    return { file };
  } catch (err: any) {
    const msg = String(err?.message || '').toLowerCase();
    if (msg.includes('cancel') || msg.includes('user cancelled')) {
      return { cancelled: true };
    }
    throw err;
  }
}
