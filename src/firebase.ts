import { initializeApp } from 'firebase/app';
import {
  getAuth,
  GoogleAuthProvider,
  onAuthStateChanged,
  signInWithPopup,
  signOut,
  type User,
} from 'firebase/auth';
import {
  doc,
  getDoc,
  getDocFromServer,
  getFirestore,
  serverTimestamp,
  writeBatch,
  type Timestamp,
} from 'firebase/firestore';
import { getDownloadURL, getStorage, ref, uploadBytes } from 'firebase/storage';
import firebaseConfig from '../firebase-applet-config.json';
import type { UserWorkspaceData } from './types/sabq';

const app = initializeApp(firebaseConfig);
export const auth = getAuth(app);
export const db = getFirestore(app, firebaseConfig.firestoreDatabaseId);
export const storage = getStorage(app);

export enum OperationType {
  CREATE = 'create',
  UPDATE = 'update',
  DELETE = 'delete',
  LIST = 'list',
  GET = 'get',
  WRITE = 'write',
}

export interface FirestoreErrorInfo {
  error: string;
  operationType: OperationType;
  path: string | null;
  authInfo: {
    userId?: string | null;
    email?: string | null;
    emailVerified?: boolean | null;
    isAnonymous?: boolean | null;
    tenantId?: string | null;
    providerInfo?: {
      providerId?: string | null;
      email?: string | null;
    }[];
  };
}

export function handleFirestoreError(
  error: unknown,
  operationType: OperationType,
  path: string | null
): never {
  const errInfo: FirestoreErrorInfo = {
    error: error instanceof Error ? error.message : String(error),
    authInfo: {
      userId: auth.currentUser?.uid,
      email: auth.currentUser?.email,
      emailVerified: auth.currentUser?.emailVerified,
      isAnonymous: auth.currentUser?.isAnonymous,
      tenantId: auth.currentUser?.tenantId,
      providerInfo:
        auth.currentUser?.providerData?.map((provider) => ({
          providerId: provider.providerId,
          email: provider.email,
        })) || [],
    },
    operationType,
    path,
  };
  console.error('Firestore Error: ', JSON.stringify(errInfo));
  throw new Error(JSON.stringify(errInfo));
}

async function testConnection() {
  try {
    await getDocFromServer(doc(db, 'test', 'connection'));
  } catch (error) {
    if (error instanceof Error && error.message.includes('the client is offline')) {
      console.error('Please check your Firebase configuration.');
    }
  }
}

void testConnection();

export function subscribeFirebaseAuth(callback: (user: User | null) => void): () => void {
  return onAuthStateChanged(auth, callback);
}

export async function signInWithGoogle(): Promise<User> {
  const provider = new GoogleAuthProvider();
  provider.setCustomParameters({ prompt: 'select_account' });
  const cred = await signInWithPopup(auth, provider);
  return cred.user;
}

export async function signOutFirebase(): Promise<void> {
  await signOut(auth);
}

export async function getFirebaseIdToken(): Promise<string | null> {
  const u = auth.currentUser;
  if (!u) return null;
  try {
    return await u.getIdToken();
  } catch {
    return null;
  }
}

export async function uploadImageToFirebaseStorage(params: {
  uid: string;
  imageId: string;
  filename: string;
  blob: Blob;
  mimeType: string;
}): Promise<{ storagePath: string; downloadUrl: string } | null> {
  if (!auth.currentUser || auth.currentUser.uid !== params.uid || !auth.currentUser.emailVerified) {
    return null;
  }
  const storagePath = `users/${params.uid}/uploads/${params.filename}`;
  const storageRef = ref(storage, storagePath);
  await uploadBytes(storageRef, params.blob, {
    contentType: params.mimeType,
    customMetadata: {
      userId: params.uid,
      imageId: params.imageId,
    },
  });
  const downloadUrl = await getDownloadURL(storageRef);
  return { storagePath, downloadUrl };
}

export async function loadWorkspaceFromFirestore(uid: string): Promise<Partial<UserWorkspaceData> | null> {
  if (!auth.currentUser || auth.currentUser.uid !== uid || !auth.currentUser.emailVerified) {
    return null;
  }
  const statePath = `users/${uid}/workspace/state`;
  try {
    const snap = await getDoc(doc(db, 'users', uid, 'workspace', 'state'));
    if (!snap.exists()) return null;
    const raw = snap.data() as Record<string, any>;
    return {
      profile: raw.profile,
      settings: raw.settings,
      sessions: raw.sessions || [],
      images: raw.images || [],
      memories: raw.memories || [],
      chats: raw.chats || [],
      scans: raw.scans || [],
      labs: raw.labs || [],
      riskAssessments: raw.riskAssessments || [],
      reports: raw.reports || [],
    };
  } catch (error) {
    handleFirestoreError(error, OperationType.GET, statePath);
  }
}

export async function syncWorkspaceToFirestore(
  uid: string,
  workspace: UserWorkspaceData
): Promise<boolean> {
  if (!auth.currentUser || auth.currentUser.uid !== uid || !auth.currentUser.emailVerified) {
    return false;
  }
  const userPath = `users/${uid}`;
  const statePath = `users/${uid}/workspace/state`;
  try {
    const userRef = doc(db, 'users', uid);
    const stateRef = doc(db, 'users', uid, 'workspace', 'state');
    const existingUser = await getDoc(userRef);
    const existingState = await getDoc(stateRef);

    const existingCreatedAt: Timestamp | undefined = existingUser.exists()
      ? (existingUser.data()?.createdAt as Timestamp | undefined)
      : undefined;
    const existingStateCreatedAt: Timestamp | undefined = existingState.exists()
      ? (existingState.data()?.createdAt as Timestamp | undefined)
      : undefined;

    const displayName = (workspace.profile.displayName || auth.currentUser.displayName || 'المستخدم').slice(0, 120);
    const batch = writeBatch(db);

    if (!existingUser.exists()) {
      batch.set(userRef, {
        userId: uid,
        displayName,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp(),
      });
    } else {
      batch.update(userRef, {
        displayName,
        updatedAt: serverTimestamp(),
      });
    }

    const payload = {
      userId: uid,
      profile: {
        ...workspace.profile,
        userId: uid,
        displayName,
      },
      settings: workspace.settings,
      sessions: (workspace.sessions || []).slice(0, 60),
      images: (workspace.images || []).slice(0, 60),
      memories: (workspace.memories || []).slice(0, 150),
      chats: (workspace.chats || []).slice(-80),
      scans: (workspace.scans || []).slice(0, 80),
      labs: (workspace.labs || []).slice(0, 150),
      riskAssessments: (workspace.riskAssessments || []).slice(0, 20),
      reports: (workspace.reports || []).slice(0, 80),
      updatedAt: serverTimestamp(),
    };

    if (!existingState.exists()) {
      batch.set(stateRef, {
        ...payload,
        createdAt: serverTimestamp(),
      });
    } else {
      batch.update(stateRef, {
        ...payload,
        createdAt: existingStateCreatedAt || existingCreatedAt || serverTimestamp(),
      });
    }

    await batch.commit();
    return true;
  } catch (error) {
    handleFirestoreError(error, OperationType.WRITE, statePath);
  }
}
