// POST { idToken, message: { …Firestore field map… } }
//   -> { ok, landed, serverTs, serverTsMs, seq }
//
// Failover message plane (FAILOVER_DESIGN.md §4.3): sends one message
// to the Supabase store. The caller must be a participant of the
// chat's mirror and the message's senderId must be the caller
// (validateAndMapMessage enforces both shape and sender). The write
// itself is the atomic rpc_send_message: the row insert (PK =
// the client's outbox UUID, on-conflict-do-nothing) and every
// participant's chat_inbox summary commit together, so a cross-store
// retry with the same id can neither duplicate the message nor
// double-count unread (§5.2 rule 3). Also performs the
// disappearing-message sweep for this chat opportunistically
// (piggybacked hygiene, §4.3).

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
  validateAndMapMessage,
} from "../_shared/msg.ts";

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

  const validation = validateAndMapMessage(body.message, uid);
  if (!validation.ok) {
    return jsonError(400, "invalid_message", validation.message);
  }
  const payload = validation.payload;
  const chatId = String(payload.chat_id);

  const supabase = serviceClient();
  const gate = await requireMembership(supabase, chatId, uid);
  if (!gate.ok) return jsonError(gate.status, gate.error, gate.message);

  const { data, error } = await supabase.rpc("rpc_send_message", {
    payload,
  });
  if (error || !data) {
    return jsonError(500, "store_unavailable", "Message store unavailable");
  }

  // Disappearing-message sweep for this chat (best-effort hygiene —
  // a failed sweep never fails the send).
  try {
    await supabase
      .from("chat_messages")
      .delete()
      .eq("chat_id", chatId)
      .gt("disappearing_expires_at", 0)
      .lt("disappearing_expires_at", Date.now());
  } catch {
    // Hygiene only.
  }

  await bumpPlaneStats(supabase, "msg-send");
  const result = data as Record<string, unknown>;
  const serverTs = typeof result.server_ts === "string"
    ? result.server_ts
    : "";
  const serverTsMs = serverTs ? Date.parse(serverTs) : 0;
  return jsonOk({
    landed: result.landed === true,
    serverTs,
    serverTsMs: Number.isFinite(serverTsMs) ? serverTsMs : 0,
    seq: typeof result.seq === "number" ? result.seq : 0,
  });
});
