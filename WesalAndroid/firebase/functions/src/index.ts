import * as admin from "firebase-admin";
import { onDocumentCreated } from "firebase-functions/v2/firestore";

admin.initializeApp();
const db = admin.firestore();

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
    const title = isGroup ? (chat.groupName || "مجموعة وصال") : (msg.senderName || "رسالة جديدة");
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
        callerName: call.callerName || "مستخدم وصال",
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
    const ownerName: string = story.ownerName || "مستخدم وصال";
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
        title: "حالة جديدة على وصال",
        body: `نشر ${ownerName} حالة جديدة`
      },
      android: {
        priority: "normal"
      }
    });
  }
);
