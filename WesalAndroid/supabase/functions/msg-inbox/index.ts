// POST { idToken } -> { ok, rows: [inboxRow…] }
//
// Failover message plane (FAILOVER_DESIGN.md §4.3/§5.4/§6): all of the
// caller's chat_inbox rows — one per chat that has Supabase-plane
// traffic. This single call is both the chats-list merge source
// (InboxMergeLogic) and the listener service's notification poll:
// the row denormalizes the last message's preview + sender name at
// send time, so a notification needs no profile fetch (important
// when Firestore, the profile store, is the dead backend). Rows are
// camelCased here because the summary shape is a client contract,
// not a table transliteration.

import {
  jsonError,
  jsonOk,
  readJsonBody,
  serviceClient,
  verifyFirebaseIdToken,
} from "../_shared/phone.ts";
import { bumpPlaneStats } from "../_shared/msg.ts";

function tsMs(value: unknown): number {
  if (typeof value !== "string") return 0;
  const ms = Date.parse(value);
  return Number.isFinite(ms) ? ms : 0;
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") {
    return jsonError(405, "method_not_allowed", "POST only");
  }
  const body = await readJsonBody(req);
  if (!body) return jsonError(400, "invalid_body", "JSON body required");

  const uid = await verifyFirebaseIdToken(String(body.idToken || ""));
  if (!uid) {
    return jsonError(
      401,
      "unauthenticated",
      "Firebase ID token missing or invalid",
    );
  }

  const supabase = serviceClient();
  const { data, error } = await supabase
    .from("chat_inbox")
    .select("*")
    .eq("user_id", uid)
    .order("updated_at", { ascending: false });
  if (error) {
    return jsonError(500, "store_unavailable", "Message store unavailable");
  }

  const rows = (data ?? []).map((r) => {
    const row = r as Record<string, unknown>;
    return {
      chatId: String(row.chat_id || ""),
      lastMessageId: row.last_message_id ? String(row.last_message_id) : null,
      lastMessagePreview: String(row.last_message_preview || ""),
      lastMessageSenderId: String(row.last_message_sender_id || ""),
      lastMessageSenderName: String(row.last_message_sender_name || ""),
      lastMessageStatus: String(row.last_message_status || "SENT"),
      lastMessageServerTsMs: tsMs(row.last_message_server_ts),
      lastMessageClientTs: typeof row.last_message_client_ts === "number"
        ? row.last_message_client_ts
        : 0,
      unreadCount: typeof row.unread_count === "number" ? row.unread_count : 0,
      updatedAtMs: tsMs(row.updated_at),
    };
  });

  await bumpPlaneStats(supabase, "msg-inbox");
  return jsonOk({ rows });
});
