// Shared helpers for the WESAL failover message-plane Edge Functions
// (FAILOVER_DESIGN.md §4.3). Stage-0 skeleton: the plane_stats metering
// increment, chat-mirror lookup + membership check, preview derivation
// and the basic payload-shape validators. The Stage-2 functions
// (msg-send / msg-list / msg-ack / msg-inbox / chat-typing) build on
// these; the full message validation (type whitelist = the client's
// MessageType.raw values, per-type field rules) lands with msg-send.
//
// Conventions match _shared/phone.ts: Firebase uids and WESAL
// chat/message ids are text everywhere, and the error envelope is the
// shared { ok: false, error, message } shape from phone.ts.

import type { SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2.49.4";

// The client's chats-list preview rule: at most 80 characters of the
// message body (§4.3). rpc_send_message applies the same rule in SQL
// when the caller does not pass a preview.
export const PREVIEW_MAX_CHARS = 80;

// Stage-0 ceiling for a message body; Stage 2 aligns this with the
// client's own field rules before msg-send ships.
export const MAX_MESSAGE_TEXT_CHARS = 4000;

export function derivePreview(bodyText: string): string {
  return String(bodyText || "").slice(0, PREVIEW_MAX_CHARS);
}

/** WESAL ids (Firebase uids, chat ids, outbox UUIDs) are plain text. */
export function isValidId(value: unknown): boolean {
  return typeof value === "string" && value.length > 0 && value.length <= 200;
}

function cairoDay(): string {
  // YYYY-MM-DD in Africa/Cairo, regardless of the edge runtime's
  // timezone (same helper as the ai-assistant function).
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: "Africa/Cairo",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(new Date());
}

/**
 * Self-metering (§9.2): increments today's plane_stats counter for a
 * function. Fire-and-forget and best-effort by design — a metering
 * failure must never fail the metered call, so every error is
 * swallowed. Callers do NOT await this for correctness.
 */
export async function bumpPlaneStats(
  supabase: SupabaseClient,
  fnName: string,
): Promise<void> {
  try {
    const day = cairoDay();
    const { data } = await supabase
      .from("plane_stats")
      .select("calls")
      .eq("day", day)
      .eq("fn_name", fnName)
      .maybeSingle();
    const calls = data ? Number(data.calls || 0) : 0;
    await supabase
      .from("plane_stats")
      .upsert({ day, fn_name: fnName, calls: calls + 1 });
  } catch {
    // Metering is advisory only.
  }
}

export interface ChatMirror {
  chat_id: string;
  kind: string;
  participant_ids: string[];
  created_by: string;
}

/**
 * Loads a chat's membership mirror, or null when the chat was never
 * registered on the plane (the caller maps that to the
 * chat_not_registered error, §4.3).
 */
export async function getChatMirror(
  supabase: SupabaseClient,
  chatId: string,
): Promise<ChatMirror | null> {
  const { data, error } = await supabase
    .from("chat_mirrors")
    .select("chat_id, kind, participant_ids, created_by")
    .eq("chat_id", chatId)
    .maybeSingle();
  if (error || !data) return null;
  return data as ChatMirror;
}

/** The membership check every plane function runs (§4.3). */
export function isParticipant(mirror: ChatMirror, uid: string): boolean {
  return Array.isArray(mirror.participant_ids) &&
    mirror.participant_ids.includes(uid);
}
