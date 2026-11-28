// POST { idToken, chatId, kind, participantIds } -> { ok, existed }
//
// Failover message plane (FAILOVER_DESIGN.md §4.3): registers a chat's
// membership mirror the first time the chat routes to the Supabase
// plane. The Edge Functions cannot read Firestore, so a participant
// registers the participant list once; the insert is first-write-wins
// (a plain INSERT — a duplicate primary key reports `existed` and the
// stored mirror is returned untouched, so a mirror can never be
// overwritten by a later registrant). The caller must include
// THEMSELVES in participantIds; if the stored mirror (registered by
// someone else first) does not contain the caller, the call is a 403 —
// the squat-detection surface named in §9.4.

import {
  jsonError,
  jsonOk,
  readJsonBody,
  serviceClient,
  verifyFirebaseIdToken,
} from "../_shared/phone.ts";
import {
  bumpPlaneStats,
  CHAT_KINDS,
  getChatMirror,
  isParticipant,
  isValidId,
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
  const kind = body.kind;
  if (!isValidId(chatId)) {
    return jsonError(400, "invalid_chat", "chatId invalid");
  }
  if (
    typeof kind !== "string" ||
    !(CHAT_KINDS as readonly string[]).includes(kind)
  ) {
    return jsonError(400, "invalid_chat", "kind must be direct, group or support");
  }
  const rawIds = body.participantIds;
  if (
    !Array.isArray(rawIds) || rawIds.length === 0 || rawIds.length > 500 ||
    !rawIds.every(isValidId)
  ) {
    return jsonError(400, "invalid_chat", "participantIds invalid");
  }
  const participantIds = [...new Set(rawIds as string[])];
  if (!participantIds.includes(uid)) {
    return jsonError(
      400,
      "invalid_chat",
      "participantIds must include the caller",
    );
  }

  const supabase = serviceClient();
  let existed = false;
  const { error: insertError } = await supabase
    .from("chat_mirrors")
    .insert({
      chat_id: chatId,
      kind,
      participant_ids: participantIds,
      created_by: uid,
    });
  if (insertError) {
    if (insertError.code === "23505") {
      existed = true; // First-write-wins: the stored mirror stands.
    } else {
      return jsonError(
        500,
        "store_unavailable",
        "Message store unavailable",
      );
    }
  }

  const mirror = await getChatMirror(supabase, String(chatId));
  if (!mirror) {
    return jsonError(500, "store_unavailable", "Message store unavailable");
  }
  if (!isParticipant(mirror, uid)) {
    return jsonError(
      403,
      "not_a_participant",
      "Caller is not a participant of this chat",
    );
  }

  await bumpPlaneStats(supabase, "msg-register-chat");
  return jsonOk({
    existed,
    participantCount: mirror.participant_ids.length,
  });
});
