import * as admin from "firebase-admin";
import { onDocumentCreated, onDocumentUpdated } from "firebase-functions/v2/firestore";
import { onCall, HttpsError } from "firebase-functions/v2/https";

admin.initializeApp();
const db = admin.firestore();

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
  "chats/{chatId}/messages/{messageId}",
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
  "calls/{callId}",
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
  "calls/{callId}",
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
  "stories/{storyId}",
  async (event) => {
    const story = event.data?.data();
    if (!story) return;
    if (story.visibility === "nobody") return;

    const ownerUid: string = story.ownerUid || "";
    const ownerName: string = story.ownerName || "مستخدم WESAL";
    const chatsSnap = await db.collection("chats")
      .whereArrayContains("participantIds", ownerUid)
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
  "channels/{channelId}/posts/{postId}",
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
  "subscription_requests/{reqId}",
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
  "reports/{reportId}",
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
  "support_tickets/{ticketId}",
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
