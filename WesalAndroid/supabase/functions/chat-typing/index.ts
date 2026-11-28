// POST { idToken, chatId, on } -> { ok }
//
// Failover message plane (FAILOVER_DESIGN.md §4.3/§5.5): typing
// signals for Supabase-plane chats. The write is rpc_set_typing — an
// upsert with a 6-second expiry (or a delete when `on` is false); the
// client's TypingSignalGate still decides WHEN to call (transitions
// only), unchanged from the Firestore path. Reads ride msg-list's
// delta responses, so there is no typing read function.

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
  if (typeof body.on !== "boolean") {
    return jsonError(400, "invalid_body", "on must be a boolean");
  }

  const supabase = serviceClient();
  const gate = await requireMembership(supabase, String(chatId), uid);
  if (!gate.ok) return jsonError(gate.status, gate.error, gate.message);

  const { error } = await supabase.rpc("rpc_set_typing", {
    p_chat_id: String(chatId),
    p_user: uid,
    p_on: body.on,
  });
  if (error) {
    return jsonError(500, "store_unavailable", "Message store unavailable");
  }

  await bumpPlaneStats(supabase, "chat-typing");
  return jsonOk({});
});
