import * as admin from "firebase-admin";
import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { onCall, HttpsError } from "firebase-functions/v2/https";

admin.initializeApp();
const db = admin.firestore();

const SUPER_ADMIN_EMAIL = "eslamfifty40@gmail.com";

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

    const tokens: string[] = [];
    for (const uid of recipients) {
      const userSnap = await db.collection("users").doc(uid).get();
      const userData = userSnap.data();
      const blocked: string[] = userData?.blockedUsers || [];
      if (blocked.includes(senderId)) continue;
      const token = userData?.fcmToken;
      if (token) tokens.push(token);
    }

    if (tokens.length === 0) return;

    await admin.messaging().sendEachForMulticast({
      tokens,
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
);

export const onIncomingCallPush = onDocumentCreated(
  "calls/{callId}",
  async (event) => {
    const call = event.data?.data();
    if (!call || call.status !== "RINGING") return;

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
        callerName: call.callerName || "مستخدم WESAL",
        callerAvatar: call.callerAvatarUrl || "",
        isVideo: String(Boolean(call.isVideo))
      },
      android: {
        priority: "high"
      }
    });
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
    if (!channel) return;

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
    emailVerified: userRecord.emailVerified
  };
});

export const setAccountVerification = onCall(async (request) => {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "Authentication required");
  }

  const callerUid = request.auth.uid;
  const callerEmail = (request.auth.token.email || "").toLowerCase();
  const superAdminDoc = await db.collection("super_admins").doc(callerUid).get();
  const isAuthorized = superAdminDoc.exists || callerEmail === SUPER_ADMIN_EMAIL.toLowerCase();
  if (!isAuthorized) {
    throw new HttpsError("permission-denied", "Only the Super Admin can manage verification seals");
  }

  const targetUid = String(request.data?.targetUid || "").trim();
  const verified = Boolean(request.data?.verified);
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
    verifiedByUid: verified ? callerUid : "",
    verifiedAt: verified ? now : 0,
    verificationNote: note
  });

  const logRef = db.collection("verification_audit_logs").doc();
  await logRef.set({
    logId: logRef.id,
    targetUid,
    targetPublicId: targetData.publicId || "",
    targetName: targetData.displayName || "",
    action: verified ? "GRANTED" : "REVOKED",
    adminUid: callerUid,
    adminEmail: callerEmail || SUPER_ADMIN_EMAIL,
    note,
    timestamp: now
  });

  return { ok: true, targetUid, isVerified: verified };
});
