import type { UserProfile, UserSettings, UserWorkspaceData } from '../types/sabq';
import { apiFetchJson } from './apiClient';
import {
  auth,
  loadWorkspaceFromFirestore,
  syncWorkspaceToFirestore,
} from '../firebase';

const ACCOUNTS_LIST_KEY = 'sabq_accounts_registry_v1';
const ACTIVE_USER_KEY = 'sabq_active_user_id_v1';
const USER_STORAGE_PREFIX = 'sabq_workspace_v2_';
const SESSION_STATE_PREFIX = 'sabq_scan_session_v1_';

export const DEFAULT_SETTINGS: UserSettings = {
  voiceEnabled: true,
  voiceVolume: 0.85,
  darkMode: true,
  fontScale: 'md',
  notificationsEnabled: true,
  quietHoursEnabled: true,
  quietStartHour: 22,
  quietEndHour: 7,
  cameraAllowed: true,
  micAllowed: true,
  biometricLockEnabled: false,
};

export function createEmptyWorkspace(userId: string, displayName: string): UserWorkspaceData {
  return {
    profile: {
      userId,
      displayName,
      createdAt: new Date().toISOString(),
    },
    settings: { ...DEFAULT_SETTINGS },
    sessions: [],
    images: [],
    memories: [],
    chats: [],
    scans: [],
    labs: [],
    riskAssessments: [],
    reports: [],
  };
}

export function getSavedActiveUserId(fallbackId: string): string {
  try {
    return localStorage.getItem(ACTIVE_USER_KEY) || fallbackId;
  } catch {
    return fallbackId;
  }
}

export function saveActiveUserId(userId: string): void {
  try {
    localStorage.setItem(ACTIVE_USER_KEY, userId);
  } catch {
    // ignore storage restriction
  }
}

export function listUserAccounts(): UserProfile[] {
  try {
    const raw = localStorage.getItem(ACCOUNTS_LIST_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as UserProfile[];
      if (Array.isArray(parsed) && parsed.length > 0) return parsed;
    }
  } catch {
    // ignore parse issues
  }
  const initial: UserProfile[] = [
    {
      userId: 'user_primary',
      displayName: 'أحمد (الحساب الرئيسي)',
      createdAt: new Date().toISOString(),
    },
  ];
  try {
    localStorage.setItem(ACCOUNTS_LIST_KEY, JSON.stringify(initial));
  } catch {
    // ignore
  }
  return initial;
}

export function saveUserAccounts(accounts: UserProfile[]) {
  try {
    localStorage.setItem(ACCOUNTS_LIST_KEY, JSON.stringify(accounts));
  } catch {
    // ignore
  }
}

function isRemoteOrApiUrl(url?: string): boolean {
  if (!url) return false;
  return url.startsWith('/api/uploads/') || url.startsWith('https://') || url.startsWith('http://');
}

/**
 * Strips heavy inline base64 strings when an external/API imageUrl is already stored,
 * preventing localStorage QuotaExceededError and Firestore 1MB document crashes.
 */
export function compactWorkspaceForStorage(data: UserWorkspaceData): UserWorkspaceData {
  const shrinkUrl = (url?: string, dataUrl?: string): string | undefined => {
    if (isRemoteOrApiUrl(url)) return undefined;
    if (dataUrl && dataUrl.length > 90000) return undefined;
    return dataUrl;
  };

  return {
    ...data,
    sessions: (data.sessions || []).slice(0, 60),
    images: (data.images || []).slice(0, 60),
    memories: (data.memories || []).slice(0, 150),
    labs: (data.labs || []).slice(0, 150),
    riskAssessments: (data.riskAssessments || []).slice(0, 20),
    scans: (data.scans || []).slice(0, 80).map((s) => ({
      ...s,
      savedImageDataUrl: shrinkUrl(s.imageUrl, s.savedImageDataUrl),
    })),
    reports: (data.reports || []).slice(0, 80).map((r) => ({
      ...r,
      referenceImageDataUrl: shrinkUrl(r.referenceImageUrl, r.referenceImageDataUrl),
      snapshotScans: (r.snapshotScans || []).map((s) => ({
        ...s,
        savedImageDataUrl: shrinkUrl(s.imageUrl, s.savedImageDataUrl),
      })),
    })),
    chats: (data.chats || []).slice(-80).map((c) => ({
      ...c,
      imagePreview: c.imagePreview && c.imagePreview.length > 90000 ? undefined : c.imagePreview,
    })),
  };
}

function safeSetLocalItem(key: string, value: string): void {
  try {
    localStorage.setItem(key, value);
  } catch {
    // Never throw QuotaExceededError into React state updaters
  }
}

const PENDING_SYNC_PREFIX = 'sabq_pending_sync_v1_';

function markPendingSync(userId: string, pending: boolean): void {
  try {
    if (pending) localStorage.setItem(`${PENDING_SYNC_PREFIX}${userId}`, '1');
    else localStorage.removeItem(`${PENDING_SYNC_PREFIX}${userId}`);
  } catch {
    // ignore
  }
}

export async function loadUserWorkspace(
  userId: string,
  displayName: string
): Promise<UserWorkspaceData> {
  const localKey = `${USER_STORAGE_PREFIX}${userId}`;
  let localCandidate: UserWorkspaceData | null = null;

  try {
    const localRaw = localStorage.getItem(localKey);
    if (localRaw) {
      const parsed = JSON.parse(localRaw) as Partial<UserWorkspaceData>;
      localCandidate = { ...createEmptyWorkspace(userId, displayName), ...parsed };
    }
  } catch {
    // ignore corrupt JSON
  }

  const hasPendingOffline = (() => {
    try {
      return localStorage.getItem(`${PENDING_SYNC_PREFIX}${userId}`) === '1';
    } catch {
      return false;
    }
  })();

  if (hasPendingOffline && localCandidate) {
    await persistUserWorkspace(localCandidate);
    return localCandidate;
  }

  // If authenticated Firebase UID matches this workspace, load directly from Firestore first
  if (auth.currentUser?.uid === userId && auth.currentUser?.emailVerified) {
    try {
      const firestoreData = await loadWorkspaceFromFirestore(userId);
      if (firestoreData) {
        const merged = {
          ...createEmptyWorkspace(userId, displayName),
          ...firestoreData,
        } as UserWorkspaceData;
        safeSetLocalItem(localKey, JSON.stringify(compactWorkspaceForStorage(merged)));
        return merged;
      }
    } catch {
      // Fall through to /api/user-data fallback cleanly
    }
  }

  // Always check unified API server so server-uploaded images/records sync accurately
  try {
    const json = await apiFetchJson<{ data?: Partial<UserWorkspaceData> | null }>(
      `/api/user-data/${encodeURIComponent(userId)}`,
      { method: 'GET' },
      8000
    );
    if (json?.data) {
      const merged = { ...createEmptyWorkspace(userId, displayName), ...json.data } as UserWorkspaceData;
      safeSetLocalItem(localKey, JSON.stringify(compactWorkspaceForStorage(merged)));
      return merged;
    }
  } catch {
    // offline resilient fallback
  }

  if (localCandidate) return localCandidate;

  const fresh = createEmptyWorkspace(userId, displayName);
  safeSetLocalItem(localKey, JSON.stringify(fresh));
  return fresh;
}

export async function persistUserWorkspace(data: UserWorkspaceData): Promise<boolean> {
  const userId = data.profile.userId;
  const localKey = `${USER_STORAGE_PREFIX}${userId}`;
  const compacted = compactWorkspaceForStorage(data);
  safeSetLocalItem(localKey, JSON.stringify(compacted));

  let synced = false;
  if (auth.currentUser?.uid === userId && auth.currentUser?.emailVerified) {
    try {
      synced = await syncWorkspaceToFirestore(userId, compacted);
    } catch {
      // continue to API sync
    }
  }

  try {
    await apiFetchJson(
      `/api/user-data/${encodeURIComponent(userId)}`,
      {
        method: 'PUT',
        body: JSON.stringify(compacted),
      },
      12000
    );
    markPendingSync(userId, false);
    return true;
  } catch {
    markPendingSync(userId, !synced);
    return synced;
  }
}

export async function flushPendingWorkspaceSync(userId: string): Promise<boolean> {
  try {
    if (localStorage.getItem(`${PENDING_SYNC_PREFIX}${userId}`) !== '1') return true;
    const raw = localStorage.getItem(`${USER_STORAGE_PREFIX}${userId}`);
    if (!raw) {
      markPendingSync(userId, false);
      return true;
    }
    const parsed = JSON.parse(raw) as UserWorkspaceData;
    return await persistUserWorkspace(parsed);
  } catch {
    return false;
  }
}

export async function deleteUserWorkspace(userId: string): Promise<void> {
  const localKey = `${USER_STORAGE_PREFIX}${userId}`;
  try {
    localStorage.removeItem(localKey);
    localStorage.removeItem(`${SESSION_STATE_PREFIX}${userId}`);
    markPendingSync(userId, false);
  } catch {
    // ignore
  }
  try {
    await apiFetchJson(
      `/api/user-data/${encodeURIComponent(userId)}`,
      { method: 'DELETE' },
      8000
    );
  } catch {
    // ignore offline
  }
}

export function saveScanSessionDraft(userId: string, draft: unknown): void {
  safeSetLocalItem(`${SESSION_STATE_PREFIX}${userId}`, JSON.stringify(draft));
}

export function loadScanSessionDraft<T>(userId: string): T | null {
  try {
    const raw = localStorage.getItem(`${SESSION_STATE_PREFIX}${userId}`);
    return raw ? (JSON.parse(raw) as T) : null;
  } catch {
    return null;
  }
}

export function clearScanSessionDraft(userId: string): void {
  try {
    localStorage.removeItem(`${SESSION_STATE_PREFIX}${userId}`);
  } catch {
    // ignore
  }
}
