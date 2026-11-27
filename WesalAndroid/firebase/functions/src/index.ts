import * as admin from "firebase-admin";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { onDocumentCreated, onDocumentUpdated } from "firebase-functions/v2/firestore";
import { onCall, HttpsError } from "firebase-functions/v2/https";
import { defineSecret } from "firebase-functions/params";
import { createHash, randomInt, timingSafeEqual } from "crypto";

admin.initializeApp();

// The Android app uses this NAMED Firestore database (AppDiModule.kt / firebase.json),
// NOT the (default) database. Every Firestore trigger below must pass it via the
// `database` option — without it, v2 triggers bind to the (default) database only and
// never fire for real app data (this is why killed-app callees got no call push and
// admins got no request alerts). Handler reads/writes must use the same database.
const FIRESTORE_DATABASE_ID = "ai-studio-livo-cd975dac-35e6-4dbb-864e-829e3b638424";
const db = getFirestore(admin.app(), FIRESTORE_DATABASE_ID);

const SUPER_ADMIN_EMAIL = "eslamfifty40@gmail.com";
const OWNER_NUMERIC_ID = "10001";

async function verifyOwnerSuperAdmin(uid: string, tokenEmail?: string): Promise<boolean> {
  if (!uid) return false;
  if ((tokenEmail || "").toLowerCase() !== SUPER_ADMIN_EMAIL.toLowerCase()) return false;
  const saDoc = await db.collection("super_admins").doc(uid).get();
  if (!saDoc.exists || saDoc.data()?.active === false) return false;
  const saNumeric = String(saDoc.data()?.numericId || "");
  if (saNumeric && saNumeric !== OWNER_NUMERIC_ID) return false;
  const userDoc = await db.collection("users").doc(uid).get();
  const userNumeric = String(userDoc.data()?.numericId || "");
  if (userNumeric && userNumeric !== OWNER_NUMERIC_ID) return false;
  return true;
}

export const onNewMessagePush = onDocumentCreated(
  { document: "chats/{chatId}/messages/{messageId}", database: FIRESTORE_DATABASE_ID },
  async (event) => {
    const msg = event.data?.data();
    if (!msg) return;

    const chatId = event.params.chatId;
    const chatSnap = await db.collection("chats").doc(chatId).get();
    const chat = chatSnap.data();
    if (!chat) return;

    const senderId: string = msg.senderId || "";
    const participants: string[] = chat.participantIds || [];
    const recipients = participants.filter((uid) => uid !== senderId);
    if (recipients.length === 0) return;

    const isGroup = Boolean(chat.isGroup);
    const title = isGroup ? (chat.groupName || "مجموعة WESAL") : (msg.senderName || "رسالة جديدة");
    const body = msg.isEncrypted ? "رسالة مشفرة جديدة" : (msg.text || "مرفق جديد");

    for (const uid of recipients) {
      const userSnap = await db.collection("users").doc(uid).get();
      const userData = userSnap.data();
      const blocked: string[] = userData?.blockedUsers || [];
      if (blocked.includes(senderId)) continue;
      const token = userData?.fcmToken;
      if (!token) continue;

      // Hidden-chat notification suppression is CLIENT-side only, by design:
      // the Android app keeps hidden chat IDs in per-account encrypted local storage and
      // WesalFirebaseMessagingService suppresses those notifications on-device.
      // A previous server branch read `users/{uid}.hiddenChatIds`, a field the app never
      // writes — and must not write: user docs are readable by any signed-in user under
      // the current rules, so storing the hidden list there would leak it. Branch removed.
      await admin.messaging().send({
        token,
        data: {
          type: isGroup ? "GROUP_MESSAGE" : "MESSAGE",
          messageId: event.params.messageId,
          chatId,
          title,
          body
        },
        android: {
          priority: "high"
        }
      });
    }
  }
);

export const onIncomingCallPush = onDocumentCreated(
  { document: "calls/{callId}", database: FIRESTORE_DATABASE_ID },
  async (event) => {
    const call = event.data?.data();
    if (!call || (call.status !== "CONNECTING" && call.status !== "RINGING")) return;

    const calleeSnap = await db.collection("users").doc(call.calleeId).get();
    const calleeData = calleeSnap.data();
    const blocked: string[] = calleeData?.blockedUsers || [];
    if (blocked.includes(call.callerId || "")) return;

    const token = calleeData?.fcmToken;
    if (!token) return;

    await admin.messaging().send({
      token,
      data: {
        type: "INCOMING_CALL",
        callId: event.params.callId,
        callerId: String(call.callerId || ""),
        callerName: call.callerName || "مستخدم WESAL",
        callerAvatar: call.callerAvatarUrl || "",
        callerVerified: String(Boolean(call.callerVerified)),
        isVideo: String(Boolean(call.isVideo))
      },
      android: {
        priority: "high"
      }
    });
  }
);

export const onCallStateChanged = onDocumentUpdated(
  { document: "calls/{callId}", database: FIRESTORE_DATABASE_ID },
  async (event) => {
    const before = event.data?.before.data();
    const after = event.data?.after.data();
    if (!before || !after) return;

    if (before.status !== "MISSED" && after.status === "MISSED" && !after.endedAt) {
      await event.data?.after.ref.update({ endedAt: Date.now() });
    }
  }
);

export const onNewStoryPush = onDocumentCreated(
  { document: "stories/{storyId}", database: FIRESTORE_DATABASE_ID },
  async (event) => {
    const story = event.data?.data();
    if (!story) return;
    if (story.visibility === "nobody") return;

    const ownerUid: string = story.ownerUid || "";
    const ownerName: string = story.ownerName || "مستخدم WESAL";
    const chatsSnap = await db.collection("chats")
      .where("participantIds", "array-contains", ownerUid)
      .limit(50)
      .get();

    const recipientSet = new Set<string>();
    for (const chatDoc of chatsSnap.docs) {
      const participants: string[] = chatDoc.data().participantIds || [];
      for (const p of participants) {
        if (p !== ownerUid) recipientSet.add(p);
      }
    }

    const tokens: string[] = [];
    for (const uid of recipientSet) {
      const userSnap = await db.collection("users").doc(uid).get();
      const userData = userSnap.data();
      const blocked: string[] = userData?.blockedUsers || [];
      if (blocked.includes(ownerUid)) continue;
      if (userData?.fcmToken) tokens.push(userData.fcmToken);
    }

    if (tokens.length === 0) return;

    await admin.messaging().sendEachForMulticast({
      tokens,
      data: {
        type: "STORY",
        messageId: `story_${event.params.storyId}`,
        title: "حالة جديدة على WESAL",
        body: `نشر ${ownerName} حالة جديدة`
      },
      android: {
        priority: "normal"
      }
    });
  }
);

export const onNewChannelPostPush = onDocumentCreated(
  { document: "channels/{channelId}/posts/{postId}", database: FIRESTORE_DATABASE_ID },
  async (event) => {
    const post = event.data?.data();
    if (!post) return;

    const channelId = event.params.channelId;
    const channelSnap = await db.collection("channels").doc(channelId).get();
    const channel = channelSnap.data();
    if (!channel || channel.isSuspended === true) return;

    const authorUid: string = post.authorUid || "";
    const subscribers: string[] = (channel.subscriberIds || []).filter(
      (uid: string) => uid !== authorUid
    );
    if (subscribers.length === 0) return;

    const channelTitle: string = channel.title || "قناة WESAL";
    const body: string = post.text ? String(post.text).slice(0, 120) : "منشور جديد في القناة";

    const tokens: string[] = [];
    for (const uid of subscribers.slice(0, 450)) {
      const userSnap = await db.collection("users").doc(uid).get();
      const token = userSnap.data()?.fcmToken;
      if (token) tokens.push(token);
    }

    if (tokens.length === 0) return;

    await admin.messaging().sendEachForMulticast({
      tokens,
      data: {
        type: "CHANNEL_POST",
        channelId,
        postId: event.params.postId,
        messageId: `channel_${event.params.postId}`,
        title: channelTitle,
        body
      },
      android: {
        priority: "normal"
      }
    });
  }
);

export const inspectFirstAdminCandidate = onCall(async (request) => {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "Authentication required");
  }
  const callerEmail = (request.auth.token.email || "").toLowerCase();
  if (callerEmail !== SUPER_ADMIN_EMAIL.toLowerCase()) {
    throw new HttpsError("permission-denied", "Only the primary owner email can inspect the admin candidate");
  }

  const userRecord = await admin.auth().getUserByEmail(SUPER_ADMIN_EMAIL);
  return {
    uid: userRecord.uid,
    email: userRecord.email || SUPER_ADMIN_EMAIL,
    emailVerified: userRecord.emailVerified,
    ownerNumericId: OWNER_NUMERIC_ID
  };
});

export const setAccountVerification = onCall(async (request) => {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "Authentication required");
  }

  const callerUid = request.auth.uid;
  const callerEmail = String(request.auth.token.email || "");
  const isAuthorized = await verifyOwnerSuperAdmin(callerUid, callerEmail);
  if (!isAuthorized) {
    throw new HttpsError("permission-denied", "Only Owner Super Admin (10001) can manage verification seals");
  }

  const allowedSeals = new Set([
    "BLUE", "BLACK", "GREEN", "GOLD", "SILVER", "RUBY", "PURPLE", "ORANGE",
    "ROSE_GOLD", "DIAMOND", "PLATINUM", "PEARL", "JADE", "BRONZE", "COPPER",
    "AMBER", "CORAL", "MAGENTA", "VIOLET", "INDIGO", "CYAN", "TEAL", "MINT",
    "LIME", "YELLOW", "CRIMSON", "SKY", "OBSIDIAN", "ROYAL", "ADMIN_CROWN"
  ]);
  const targetUid = String(request.data?.targetUid || "").trim();
  const verified = Boolean(request.data?.verified);
  const verificationTypeRaw = String(request.data?.verificationType || "BLUE").toUpperCase();
  const verificationType = allowedSeals.has(verificationTypeRaw) ? verificationTypeRaw : "BLUE";
  const expiresAt = Number(request.data?.expiresAt || 0);
  const durationLabel = String(request.data?.durationLabel || (expiresAt === 0 ? "دائم" : "مؤقت")).trim();
  const note = String(request.data?.note || "").trim();
  if (!targetUid) {
    throw new HttpsError("invalid-argument", "targetUid is required");
  }

  const targetRef = db.collection("users").doc(targetUid);
  const targetSnap = await targetRef.get();
  if (!targetSnap.exists) {
    throw new HttpsError("not-found", "Target user profile not found");
  }
  const targetData = targetSnap.data() || {};
  const now = Date.now();

  await targetRef.update({
    isVerified: verified,
    verificationType,
    verificationExpiresAt: verified ? expiresAt : 0,
    verifiedByUid: verified ? callerUid : "",
    verifiedAt: verified ? now : 0,
    verificationNote: note
  });

  const logRef = db.collection("verification_audit_logs").doc();
  await logRef.set({
    logId: logRef.id,
    operationId: logRef.id,
    targetUid,
    targetNumericId: targetData.numericId || "",
    targetPublicId: targetData.publicId || "",
    targetName: targetData.displayName || "",
    action: verified ? "GRANTED" : "REVOKED",
    verificationType,
    durationLabel,
    expiresAt: verified ? expiresAt : 0,
    adminUid: callerUid,
    adminEmail: callerEmail || SUPER_ADMIN_EMAIL,
    note,
    timestamp: now
  });

  return { ok: true, targetUid, isVerified: verified, verificationType };
});

async function notifyAdmins(
  payload: Record<string, string>,
  permissionKey?: string
): Promise<void> {
  const adminUids = new Set<string>([
    "fxKvKnbnksPqZy07rQjTzxIKzzP2",
    "D6Z812za9ZNUW0kAt7SaFD3a9UN2"
  ]);
  const saSnap = await db.collection("super_admins").get();
  saSnap.docs.forEach((doc) => {
    if (doc.data()?.active !== false) adminUids.add(doc.id);
  });
  const modSnap = await db.collection("moderators").where("active", "==", true).get();
  modSnap.docs.forEach((doc) => {
    const data = doc.data() || {};
    if (!permissionKey || data[permissionKey] === true) {
      adminUids.add(doc.id);
    }
  });

  const tokens: string[] = [];
  for (const uid of adminUids) {
    const uSnap = await db.collection("users").doc(uid).get();
    const token = uSnap.data()?.fcmToken;
    if (token) tokens.push(token);
  }
  if (tokens.length === 0) return;

  await admin.messaging().sendEachForMulticast({
    tokens,
    data: payload,
    android: { priority: "high" }
  });
}

export const onSubscriptionRequestCreated = onDocumentCreated(
  { document: "subscription_requests/{reqId}", database: FIRESTORE_DATABASE_ID },
  async (event) => {
    const req = event.data?.data();
    if (!req) return;
    await notifyAdmins(
      {
        type: "ADMIN_SUBSCRIPTION",
        messageId: `sub_${event.params.reqId}`,
        requestId: event.params.reqId,
        title: "طلب توثيق جديد — WESAL",
        body: `${req.userName || "مستخدم"} (${req.userNumericId || ""}) طلب شارة ${req.desiredSeal || ""}`
      },
      "canManageVerification"
    );
  }
);

export const onReportCreated = onDocumentCreated(
  { document: "reports/{reportId}", database: FIRESTORE_DATABASE_ID },
  async (event) => {
    const rep = event.data?.data();
    if (!rep) return;
    await notifyAdmins(
      {
        type: "ADMIN_REPORT",
        messageId: `rep_${event.params.reportId}`,
        reportId: event.params.reportId,
        title: "بلاغ جديد — WESAL",
        body: `بلاغ ضد ${rep.targetName || rep.targetUid || "حساب"}: ${String(rep.reason || "").slice(0, 80)}`
      },
      "canManageReports"
    );
  }
);

export const onSupportTicketCreated = onDocumentCreated(
  { document: "support_tickets/{ticketId}", database: FIRESTORE_DATABASE_ID },
  async (event) => {
    const ticket = event.data?.data();
    if (!ticket) return;
    await notifyAdmins({
      type: "ADMIN_SUPPORT",
      messageId: `sup_${event.params.ticketId}`,
      ticketId: event.params.ticketId,
      title: "تذكرة دعم جديدة — WESAL",
      body: `${ticket.userName || "مستخدم"}: ${String(ticket.subject || ticket.message || "").slice(0, 80)}`
    });
  }
);

// ---------------------------------------------------------------------------
// Spec §10.3 (round 4): server-side ban enforcement — custom claims + token
// revocation. The Firestore ban fields remain the display source of truth
// (the client writes them first and never depends on this callable); this
// function makes the ban bite at the Auth layer: the stamped `banned` claim
// is consulted by the rules gate (isCurrentlyBanned), and
// revokeRefreshTokens() kills the target's existing sessions on refresh.
// ---------------------------------------------------------------------------

const SUPER_ADMIN_UIDS = ["fxKvKnbnksPqZy07rQjTzxIKzzP2", "D6Z812za9ZNUW0kAt7SaFD3a9UN2"];
const SUPER_ADMIN_EMAILS = ["eslamfifty40@gmail.com", "tbtbttbbt7@gmail.com"];
const PROTECTED_ADMIN_NUMERIC_IDS = ["10001", "10002"];

// Mirrors the rules-level isSuperAdmin(): registry doc, known owner uids, or
// an owner email on the caller's token.
async function isRulesSuperAdmin(uid: string, tokenEmail?: string): Promise<boolean> {
  if (!uid) return false;
  if (SUPER_ADMIN_UIDS.includes(uid)) return true;
  const email = (tokenEmail || "").toLowerCase();
  if (email && SUPER_ADMIN_EMAILS.includes(email)) return true;
  const saDoc = await db.collection("super_admins").doc(uid).get();
  return saDoc.exists;
}

// Mirrors the client ban permission (UserRepositoryImpl.banUser): full super
// admin, or an active moderator holding canManageUsersModeration — the same
// field the rules' hasModPermission() reads on moderators/{uid}.
async function verifyCanModerateUsers(uid: string, tokenEmail?: string): Promise<boolean> {
  if (!uid) return false;
  if (await isRulesSuperAdmin(uid, tokenEmail)) return true;
  const modDoc = await db.collection("moderators").doc(uid).get();
  if (!modDoc.exists) return false;
  const data = modDoc.data() || {};
  return data.active === true && data.canManageUsersModeration === true;
}

export const setUserBanState = onCall(async (request) => {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "Authentication required");
  }
  const callerUid = request.auth.uid;
  const callerEmail = String(request.auth.token.email || "");
  const isAuthorized = await verifyCanModerateUsers(callerUid, callerEmail);
  if (!isAuthorized) {
    throw new HttpsError("permission-denied", "Only super admins or moderators with canManageUsersModeration can change ban state");
  }

  const targetUid = String(request.data?.targetUid || "").trim();
  const banned = Boolean(request.data?.banned);
  if (!targetUid) {
    throw new HttpsError("invalid-argument", "targetUid is required");
  }

  const targetSnap = await db.collection("users").doc(targetUid).get();
  if (!targetSnap.exists) {
    throw new HttpsError("not-found", "Target user profile not found");
  }
  const targetData = targetSnap.data() || {};

  const targetRecord = await admin.auth().getUser(targetUid).catch(() => null);
  if (!targetRecord) {
    throw new HttpsError("not-found", "Target auth user not found");
  }

  if (banned) {
    // Same protected-admin guard the client enforces before writing the ban.
    const targetNumeric = String(targetData.numericId ?? "");
    const targetEmail = (targetRecord.email || "").toLowerCase();
    if (PROTECTED_ADMIN_NUMERIC_IDS.includes(targetNumeric) || SUPER_ADMIN_EMAILS.includes(targetEmail)) {
      throw new HttpsError("permission-denied", "Protected admin accounts cannot be banned");
    }
    const banType = String(request.data?.banType || "PERMANENT").toUpperCase() === "TEMPORARY" ? "TEMPORARY" : "PERMANENT";
    const banExpiresAt = Number(request.data?.banExpiresAt || 0);
    const claims: Record<string, any> = { ...(targetRecord.customClaims || {}) };
    claims.banned = true;
    claims.banType = banType;
    claims.banExpiresAt = banExpiresAt;
    await admin.auth().setCustomUserClaims(targetUid, claims);
    await admin.auth().revokeRefreshTokens(targetUid);
    return { ok: true, targetUid, banned: true };
  }

  // Unban: strip only the ban claims, preserving any others.
  const claims: Record<string, any> = { ...(targetRecord.customClaims || {}) };
  delete claims.banned;
  delete claims.banType;
  delete claims.banExpiresAt;
  await admin.auth().setCustomUserClaims(targetUid, claims);
  return { ok: true, targetUid, banned: false };
});

// ---------------------------------------------------------------------------
// Spec §12.5 (round 4): group-add privacy enforced server-side. Rules cannot
// iterate a memberIds diff checking each candidate's groupAddPrivacy, so
// membership additions go through this callable: it re-checks every candidate
// exactly like the client's filterAllowedGroupMembers() (EVERYONE / CONTACTS /
// NOBODY, where CONTACTS means an ACCEPTED contact request exists — plus the
// same super-admin / owner exceptions the client's areContactsAccepted()
// applies) and performs the write with the Admin SDK only for the candidates
// that are allowed. The per-candidate verdict is returned so the UI keeps its
// existing Arabic error reporting.
// ---------------------------------------------------------------------------

async function hasAcceptedContactRequest(uidA: string, uidB: string): Promise<boolean> {
  const candidateDocIds = [`${uidA}_${uidB}`, `${uidB}_${uidA}`];
  for (const docId of candidateDocIds) {
    const snap = await db.collection("contact_requests").doc(docId).get();
    if (snap.exists && snap.data()?.status === "ACCEPTED") return true;
  }
  return false;
}

export const changeGroupMembership = onCall(async (request) => {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "Authentication required");
  }
  const callerUid = request.auth.uid;
  const callerEmail = String(request.auth.token.email || "");
  const groupId = String(request.data?.groupId || "").trim();
  const rawMemberIds: unknown[] = Array.isArray(request.data?.memberIds) ? request.data.memberIds : [];
  const candidateIds = Array.from(new Set(
    rawMemberIds
      .map((value) => String(value || "").trim())
      .filter((value) => value.length > 0 && value !== callerUid)
  ));
  if (!groupId) {
    throw new HttpsError("invalid-argument", "groupId is required");
  }
  if (candidateIds.length === 0) {
    throw new HttpsError("invalid-argument", "memberIds is required");
  }

  const groupRef = db.collection("groups").doc(groupId);
  const groupSnap = await groupRef.get();
  if (!groupSnap.exists) {
    throw new HttpsError("not-found", "Group not found");
  }
  const group = groupSnap.data() || {};
  const adminIds: string[] = Array.isArray(group.adminIds) ? group.adminIds : [];
  if (group.creatorId !== callerUid && !adminIds.includes(callerUid)) {
    throw new HttpsError("permission-denied", "Only a group admin can add members");
  }
  const currentMemberIds: string[] = Array.isArray(group.memberIds) ? group.memberIds : [];

  const callerIsSuperAdmin = await isRulesSuperAdmin(callerUid, callerEmail);

  const added: string[] = [];
  const rejected: { uid: string; name: string; reason: string }[] = [];
  for (const targetUid of candidateIds) {
    if (currentMemberIds.includes(targetUid)) {
      // Already a member: idempotent success, matching the client's arrayUnion.
      added.push(targetUid);
      continue;
    }
    const userSnap = await db.collection("users").doc(targetUid).get();
    if (!userSnap.exists) {
      rejected.push({ uid: targetUid, name: targetUid, reason: "NOT_FOUND" });
      continue;
    }
    const userData = userSnap.data() || {};
    const label = String(userData.displayName || "").trim() ||
      (userData.publicId ? `@${userData.publicId}` : targetUid);
    const privacy = String(userData.groupAddPrivacy || "EVERYONE").toUpperCase();
    let allowed = false;
    if (privacy === "EVERYONE") {
      allowed = true;
    } else if (privacy === "CONTACTS") {
      allowed = callerIsSuperAdmin ||
        String(userData.numericId ?? "") === OWNER_NUMERIC_ID ||
        (await hasAcceptedContactRequest(callerUid, targetUid));
    }
    if (allowed) {
      added.push(targetUid);
    } else {
      rejected.push({ uid: targetUid, name: label, reason: privacy === "NOBODY" ? "NOBODY" : "NOT_CONTACTS" });
    }
  }

  const newlyAdded = added.filter((uid) => !currentMemberIds.includes(uid));
  if (newlyAdded.length > 0) {
    await groupRef.update({
      memberIds: FieldValue.arrayUnion(...newlyAdded)
    });
    // Keep the linked group chat in sync, exactly like the client write did.
    const chatId = String(group.chatId || "");
    if (chatId) {
      const chatUpdates: Record<string, any> = {
        participantIds: FieldValue.arrayUnion(...newlyAdded)
      };
      for (const uid of newlyAdded) {
        chatUpdates[`unreadCounts.${uid}`] = 0;
      }
      await db.collection("chats").doc(chatId).update(chatUpdates).catch(() => undefined);
    }
  }

  return { ok: true, groupId, added, rejected };
});

// ---------------------------------------------------------------------------
// Phone registration + mandatory phone verification (round 4, phone phase).
//
// Owner rules (2026-11-27): Gmail/email sign-in stays; phone registration is
// ADDED alongside it. Contacts discoverability requires a VERIFIED phone —
// only phoneVerified accounts carry a phoneHash. Verification is a mandatory
// gate for EVERY account (admins included): after any sign-in, an unverified
// account must complete this flow once before the app opens.
//
// Zavu is TRANSPORT ONLY: it delivers the message (POST /v1/messages with a
// Bearer key held in the ZAVU_API_KEY secret — never in code). WESAL itself
// generates, hashes, expires and verifies the codes, in the server-only
// `phone_verifications` collection (rules: allow read, write: if false),
// keyed by phoneHash = sha256(canonical E.164) — the same hash space the
// client uses for contacts discovery (PhoneUtils.sha256Hash).
// ---------------------------------------------------------------------------

const zavuApiKey = defineSecret("ZAVU_API_KEY");

const PHONE_CODE_TTL_MS = 10 * 60_000;
const PHONE_CODE_RESEND_MIN_MS = 60_000;
const PHONE_CODE_WINDOW_MS = 60 * 60_000;
const PHONE_CODE_MAX_SENDS_PER_WINDOW = 5;
const PHONE_CODE_MAX_ATTEMPTS = 5;

function sha256Hex(value: string): string {
  return createHash("sha256").update(value, "utf8").digest("hex");
}

function toAsciiDigits(input: string): string {
  return String(input || "").replace(/[\u0660-\u0669]/g, (c) =>
    String(c.charCodeAt(0) - 0x0660)
  ).replace(/[\u06F0-\u06F9]/g, (c) =>
    String(c.charCodeAt(0) - 0x06F0)
  );
}

/**
 * Canonical E.164 normalization mirroring the client's PhoneUtils.normalizeToE164
 * with the Egypt (+20) default: accepts "+…", "00…", trunk-0 local numbers and
 * bare national numbers. Egyptian numbers are validated like the client
 * (10 national digits starting 10/11/12/15); anything else must fit the
 * generic E.164 length bounds (7–15 digits).
 */
function normalizePhoneE164Server(raw: string): string | null {
  const cleaned = toAsciiDigits(raw).replace(/[\s\-().]/g, "");
  if (!cleaned) return null;
  const digits = cleaned.replace(/\D/g, "");
  if (!digits) return null;
  let e164: string;
  if (cleaned.startsWith("+")) {
    e164 = "+" + digits;
  } else if (cleaned.startsWith("00")) {
    e164 = "+" + digits.slice(2);
  } else if (cleaned.startsWith("0")) {
    e164 = "+20" + digits.slice(1);
  } else if (cleaned.startsWith("20") && digits.length === 12) {
    e164 = "+" + digits;
  } else {
    e164 = "+20" + digits;
  }
  const totalDigits = e164.length - 1;
  if (totalDigits < 7 || totalDigits > 15) return null;
  if (e164.startsWith("+20")) {
    const national = e164.slice(3);
    if (!/^1[0125][0-9]{8}$/.test(national)) return null;
  }
  return e164;
}

function hashesEqual(a: string, b: string): boolean {
  const ba = Buffer.from(a, "utf8");
  const bb = Buffer.from(b, "utf8");
  return ba.length === bb.length && timingSafeEqual(ba, bb);
}

// Node 20 ships a global fetch; typed loosely so the build does not depend on
// DOM/undici lib types being present in tsconfig.
const fetchFn: (
  url: string,
  init: Record<string, unknown>
) => Promise<{ ok: boolean; status: number }> =
  (globalThis as unknown as { fetch: never }).fetch;

/**
 * Delivers the OTP through Zavu. ASSUMPTION (see FIXLOG_R4_PHONE): the v1
 * messages API accepts a JSON body { to, channel, text } where channel is
 * "whatsapp" or "sms", authenticated with `Authorization: Bearer <key>`.
 * WhatsApp is tried first (cheaper/free-tier friendly); any non-2xx or
 * network failure falls back to SMS. Returns the channel that accepted.
 */
async function sendCodeViaZavu(phone: string, code: string): Promise<string> {
  const apiKey = zavuApiKey.value();
  if (!apiKey) {
    throw new HttpsError("failed-precondition", "خدمة إرسال الأكواد غير مهيأة على الخادم");
  }
  const text = `كود التحقق بتاعك في وصال: ${code} — صالح ١٠ دقايق. ما تشاركهوش مع حد.`;
  for (const channel of ["whatsapp", "sms"]) {
    try {
      const res = await fetchFn("https://api.zavu.dev/v1/messages", {
        method: "POST",
        headers: {
          Authorization: `Bearer ${apiKey}`,
          "Content-Type": "application/json"
        },
        body: JSON.stringify({ to: phone, channel, text })
      });
      if (res.ok) return channel;
    } catch (e) {
      // Fall through to the next channel.
    }
  }
  throw new HttpsError("unavailable", "تعذر إرسال الكود دلوقتي — حاول مرة أخرى بعد شوية");
}

export const sendPhoneCode = onCall({ secrets: [zavuApiKey] }, async (request) => {
  const phone = normalizePhoneE164Server(String(request.data?.phone || ""));
  if (!phone) {
    throw new HttpsError("invalid-argument", "رقم الهاتف غير صحيح — اكتبه بالصيغة الدولية");
  }
  const phoneHash = sha256Hex(phone);
  const ref = db.collection("phone_verifications").doc(phoneHash);
  const now = Date.now();

  const snap = await ref.get();
  const existing = snap.exists ? (snap.data() || {}) : {};

  const lastSentAt = Number(existing.sentAt || 0);
  if (lastSentAt > 0 && now - lastSentAt < PHONE_CODE_RESEND_MIN_MS) {
    throw new HttpsError("resource-exhausted", "استنى دقيقة قبل ما تطلب كود جديد");
  }
  let windowStart = Number(existing.sendWindowStart || 0);
  let sendCount = Number(existing.sendCount || 0);
  if (now - windowStart >= PHONE_CODE_WINDOW_MS) {
    windowStart = now;
    sendCount = 0;
  }
  if (sendCount >= PHONE_CODE_MAX_SENDS_PER_WINDOW) {
    throw new HttpsError(
      "resource-exhausted",
      "وصلت للحد الأقصى لطلب الأكواد للرقم ده — حاول مرة أخرى بعد شوية"
    );
  }

  const code = randomInt(0, 1_000_000).toString().padStart(6, "0");
  const data: Record<string, unknown> = {
    phone,
    codeHash: sha256Hex(code + phoneHash),
    expiresAt: now + PHONE_CODE_TTL_MS,
    attempts: 0,
    sentAt: now,
    sendCount: sendCount + 1,
    sendWindowStart: windowStart,
    used: false
  };
  // When the caller is signed in (the mandatory-gate / linking flow), record
  // which account asked for the code so verifyPhoneCodeForCurrentUser can
  // reject a code that was requested from a different session.
  if (request.auth?.uid) {
    data.uid = request.auth.uid;
  }
  // The doc is written BEFORE delivery on purpose: the rate limits above are
  // enforced from it, so a delivery error can never be retried into a spam
  // loop — the user waits out the 60s resend interval instead.
  await ref.set(data);

  const channel = await sendCodeViaZavu(phone, code);
  return { ok: true, channel, expiresInSeconds: PHONE_CODE_TTL_MS / 1000 };
});

/**
 * Validates a submitted code against phone_verifications/{phoneHash} WITHOUT
 * consuming it: expiry, single-use and the attempt budget are enforced here,
 * and a wrong code increments `attempts`. On success the caller decides when
 * to mark the doc used (verifyPhoneCodeForCurrentUser checks the
 * phone_uniqueness lock first, so a blocked link does not burn the code).
 */
async function checkPhoneCode(
  phoneHash: string,
  rawCode: string
): Promise<{ ref: FirebaseFirestore.DocumentReference; data: FirebaseFirestore.DocumentData }> {
  const code = toAsciiDigits(rawCode).replace(/\D/g, "");
  if (!/^[0-9]{6}$/.test(code)) {
    throw new HttpsError("invalid-argument", "الكود لازم يكون ٦ أرقام");
  }
  const ref = db.collection("phone_verifications").doc(phoneHash);
  const snap = await ref.get();
  if (!snap.exists) {
    throw new HttpsError("not-found", "مفيش كود تحقق للرقم ده — اطلب كود جديد");
  }
  const data = snap.data() || {};
  if (data.used === true) {
    throw new HttpsError("failed-precondition", "الكود ده اتستخدم قبل كده — اطلب كود جديد");
  }
  if (Number(data.expiresAt || 0) < Date.now()) {
    throw new HttpsError("failed-precondition", "الكود انتهت صلاحيته — اطلب كود جديد");
  }
  const attempts = Number(data.attempts || 0);
  if (attempts >= PHONE_CODE_MAX_ATTEMPTS) {
    throw new HttpsError("failed-precondition", "محاولات كتير غلط — اطلب كود جديد وحاول تاني");
  }
  const actual = sha256Hex(code + phoneHash);
  if (!hashesEqual(actual, String(data.codeHash || ""))) {
    await ref.update({ attempts: attempts + 1 });
    throw new HttpsError("invalid-argument", "الكود غير صحيح — راجعه وحاول تاني");
  }
  return { ref, data };
}

export const verifyPhoneCode = onCall(async (request) => {
  const phone = normalizePhoneE164Server(String(request.data?.phone || ""));
  if (!phone) {
    throw new HttpsError("invalid-argument", "رقم الهاتف غير صحيح — اكتبه بالصيغة الدولية");
  }
  const phoneHash = sha256Hex(phone);
  const { ref } = await checkPhoneCode(phoneHash, String(request.data?.code || ""));
  await ref.update({ used: true, usedAt: Date.now() });

  const existing = await admin.auth().getUserByPhoneNumber(phone).catch((e: unknown) => {
    if ((e as { code?: string })?.code === "auth/user-not-found") return null;
    throw e;
  });
  let uid: string;
  let isNewUser = false;
  if (existing) {
    uid = existing.uid;
  } else {
    const created = await admin.auth().createUser({ phoneNumber: phone });
    uid = created.uid;
    isNewUser = true;
  }
  const customToken = await admin.auth().createCustomToken(uid);
  return { customToken, isNewUser, phone };
});

export const verifyPhoneCodeForCurrentUser = onCall(async (request) => {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "Authentication required");
  }
  const uid = request.auth.uid;
  // The client passes back exactly the number it sent the code to while
  // signed in (sendPhoneCode recorded this uid on the verification doc).
  const phone = normalizePhoneE164Server(String(request.data?.phone || ""));
  if (!phone) {
    throw new HttpsError("invalid-argument", "رقم الهاتف غير صحيح — اكتبه بالصيغة الدولية");
  }
  const phoneHash = sha256Hex(phone);
  const { ref, data } = await checkPhoneCode(phoneHash, String(request.data?.code || ""));
  if (data.uid && String(data.uid) !== uid) {
    throw new HttpsError("permission-denied", "الكود ده اتبعت من جلسة تانية — اطلب كود جديد من هنا");
  }

  // Ownership lock, checked BEFORE the code is consumed or anything is
  // stamped: a number already linked to a different account changes nothing.
  const lockRef = db.collection("phone_uniqueness").doc(phoneHash);
  const lockSnap = await lockRef.get();
  const lockOwner = lockSnap.exists ? String(lockSnap.data()?.uid || "") : "";
  if (lockOwner && lockOwner !== uid) {
    throw new HttpsError("already-exists", "الرقم ده مربوط بحساب تاني");
  }

  await ref.update({ used: true, usedAt: Date.now() });

  const userRef = db.collection("users").doc(uid);
  const batch = db.batch();
  batch.set(
    userRef,
    { phoneNumber: phone, isPhoneVerified: true, phoneHash },
    { merge: true }
  );
  batch.set(
    userRef.collection("private").doc("info"),
    { phoneNumber: phone, isPhoneVerified: true, updatedAt: Date.now() },
    { merge: true }
  );
  batch.set(lockRef, { uid, updatedAt: Date.now() }, { merge: true });
  await batch.commit();

  return { ok: true, phone };
});
