import { Capacitor } from '@capacitor/core';

export const DEFAULT_PRODUCTION_API_ORIGIN = 'https://promising-phantasmata-jv0ns.web.app';

export function isCapacitorNativePlatform(): boolean {
  try {
    if (Capacitor.isNativePlatform()) return true;
  } catch {
    // ignore
  }
  const prot = typeof window !== 'undefined' ? window.location.protocol : '';
  return prot === 'capacitor:' || prot === 'ionic:';
}

export function getApiBaseOrigin(): string {
  const configured = String(import.meta.env.VITE_API_BASE_URL || '').trim().replace(/\/+$/, '');
  if (isCapacitorNativePlatform()) {
    return configured || DEFAULT_PRODUCTION_API_ORIGIN;
  }
  // When Vite explicitly provides VITE_API_BASE_URL outside standard browser origin, allow it;
  // on Firebase Hosting or local Vite/Express port 3000, keep same-origin relative '/api/...'.
  return '';
}

export function resolveApiUrl(pathOrUrl: string): string {
  const trimmed = String(pathOrUrl || '').trim();
  if (!trimmed) return trimmed;
  if (
    trimmed.startsWith('http://') ||
    trimmed.startsWith('https://') ||
    trimmed.startsWith('data:') ||
    trimmed.startsWith('blob:')
  ) {
    return trimmed;
  }
  const base = getApiBaseOrigin();
  if (!base) return trimmed;
  return trimmed.startsWith('/') ? `${base}${trimmed}` : `${base}/${trimmed}`;
}
