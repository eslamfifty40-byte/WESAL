// POST { idToken, chatId, messageIds: […], status } -> { ok }
//
// Failover message plane (FAILOVER_DESIGN.md §4.3/§5.4): batched
// receipt acks for Supabase-homed messages, flushed by the client at
// the r14 cadences (ReadReceiptBatcher / delivered-stamp throttle) —
// never per message. The write is rpc_ack_messages: status advances
// monotonically (SENT -> DELIVERED -> READ, never backwards, only on
// other people's messages) and a READ ack zeroes the reader's inbox
// unread_count for the chat in the same transaction.

import {
  jsonError,
  jsonOk,
  readJsonBody,
  serviceClient,
  verifyFirebaseIdToken,
} from "../_shared/phone.ts";
import {
  bumpPlaneStats,
  isValidId,
  requireMembership,
} from "../_shared/msg.ts";

const MAX_ACK_IDS = 200;

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

  const chatId = body.chatId;
  if (!isValidId(chatId)) {
    return jsonError(400, "invalid_chat", "chatId invalid");
  }
  const status = body.status;
  if (status !== "DELIVERED" && status !== "READ") {
    return jsonError(400, "invalid_status", "status must be DELIVERED or READ");
  }
  const rawIds = body.messageIds;
  if (
    !Array.isArray(rawIds) || rawIds.length === 0 ||
    rawIds.length > MAX_ACK_IDS || !rawIds.every(isValidId)
  ) {
    return jsonError(400, "invalid_message", "messageIds invalid");
  }
  const messageIds = [...new Set(rawIds as string[])];

  const supabase = serviceClient();
  const gate = await requireMembership(supabase, String(chatId), uid);
  if (!gate.ok) return jsonError(gate.status, gate.error, gate.message);

  const { error } = await supabase.rpc("rpc_ack_messages", {
    p_chat_id: String(chatId),
    p_reader: uid,
    p_ids: messageIds,
    p_status: status,
  });
  if (error) {
    return jsonError(500, "store_unavailable", "Message store unavailable");
  }

  await bumpPlaneStats(supabase, "msg-ack");
  return jsonOk({});
});
