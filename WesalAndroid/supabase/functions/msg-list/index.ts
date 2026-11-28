// POST { idToken, chatId, afterSeq?, beforeSeq?, watermarkMs?, limit?, ids? }
//   -> { ok, messages: [row…], typing: [{ userId }], hasMore }
//
// Failover message plane (FAILOVER_DESIGN.md §4.3/§5.3): the merged
// read source for one chat. Four modes:
//  - ids (ids[]): exactly the named rows — the outbox replay
//    existence check (§5.2 rule 5).
//  - delta (afterSeq): rows with seq > afterSeq PLUS rows the caller
//    already has whose updated_at advanced past watermarkMs (status
//    changes, edits, deletes land through this arm), seq ascending.
//  - page (beforeSeq): one page of older history, seq descending
//    from the cursor, returned ascending.
//  - initial (neither): the newest page, returned ascending.
// Rows are the stored chat_messages shape plus epoch-ms companions
// (serverTsMs / updatedAtMs, see _shared/msg.ts toClientRow). The
// chat's live typing rows ride the same response (§5.5) so there is
// no separate typing read call. Membership-checked like every plane
// function.

import {
  jsonError,
  jsonOk,
  readJsonBody,
  serviceClient,
  verifyFirebaseIdToken,
} from "../_shared/phone.ts";
import {
  bumpPlaneStats,
  isoFromMs,
  isValidId,
  requireMembership,
  toClientRow,
} from "../_shared/msg.ts";

const DEFAULT_PAGE_LIMIT = 60;
const DEFAULT_DELTA_LIMIT = 100;
const MAX_LIMIT = 200;

function clampLimit(value: unknown, fallback: number): number {
  const n = typeof value === "number" ? Math.trunc(value) : NaN;
  if (!Number.isFinite(n) || n <= 0) return fallback;
  return Math.min(n, MAX_LIMIT);
}

function asSeq(value: unknown): number | null {
  if (typeof value === "number" && Number.isFinite(value) && value >= 0) {
    return Math.trunc(value);
  }
  return null;
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

  const chatId = body.chatId;
  if (!isValidId(chatId)) {
    return jsonError(400, "invalid_chat", "chatId invalid");
  }

  const supabase = serviceClient();
  const gate = await requireMembership(supabase, String(chatId), uid);
  if (!gate.ok) return jsonError(gate.status, gate.error, gate.message);

  const afterSeq = asSeq(body.afterSeq);
  const beforeSeq = asSeq(body.beforeSeq);
  const watermarkMs = asSeq(body.watermarkMs) ?? 0;

  let query = supabase
    .from("chat_messages")
    .select("*")
    .eq("chat_id", String(chatId));

  let descending = false;
  if (Array.isArray(body.ids) && body.ids.length > 0) {
    const ids = body.ids.slice(0, 200);
    if (!ids.every(isValidId)) {
      return jsonError(400, "invalid_message", "ids invalid");
    }
    query = query.in("message_id", ids as string[])
      .order("seq", { ascending: true });
  } else if (afterSeq !== null) {
    if (watermarkMs > 0) {
      query = query.or(
        `seq.gt.${afterSeq},updated_at.gt.${isoFromMs(watermarkMs)}`,
      );
    } else {
      query = query.gt("seq", afterSeq);
    }
    query = query.order("seq", { ascending: true })
      .limit(clampLimit(body.limit, DEFAULT_DELTA_LIMIT));
  } else if (beforeSeq !== null) {
    descending = true;
    query = query.lt("seq", beforeSeq)
      .order("seq", { ascending: false })
      .limit(clampLimit(body.limit, DEFAULT_PAGE_LIMIT));
  } else {
    descending = true;
    query = query.order("seq", { ascending: false })
      .limit(clampLimit(body.limit, DEFAULT_PAGE_LIMIT));
  }

  const { data: rows, error } = await query;
  if (error) {
    return jsonError(500, "store_unavailable", "Message store unavailable");
  }
  const list = (rows ?? []) as Record<string, unknown>[];
  if (descending) list.reverse();
  const limit = clampLimit(
    body.limit,
    afterSeq !== null ? DEFAULT_DELTA_LIMIT : DEFAULT_PAGE_LIMIT,
  );

  const { data: typingRows } = await supabase
    .from("chat_typing")
    .select("user_id")
    .eq("chat_id", String(chatId))
    .gt("expires_at", new Date().toISOString());
  const typing = (typingRows ?? []).map((r) => ({
    userId: String((r as Record<string, unknown>).user_id || ""),
  })).filter((t) => t.userId !== "");

  await bumpPlaneStats(supabase, "msg-list");
  return jsonOk({
    messages: list.map(toClientRow),
    typing,
    hasMore: list.length >= limit,
  });
});
