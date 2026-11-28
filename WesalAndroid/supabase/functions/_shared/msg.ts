// Shared helpers for the WESAL failover message-plane Edge Functions
// (FAILOVER_DESIGN.md §4.3). Stage-0 skeleton: the plane_stats metering
// increment, chat-mirror lookup + membership check, preview derivation
// and the basic payload-shape validators. Stage 2 (this file's current
// form) adds the full message validation + wire mapping used by
// msg-send / msg-list / msg-ack / msg-inbox / chat-typing.
//
// Conventions match _shared/phone.ts: Firebase uids and WESAL
// chat/message ids are text everywhere, and the error envelope is the
// shared { ok: false, error, message } shape from phone.ts.
//
// Wire shape decision (stage 2): the client sends the message in the
// exact field map it already builds for Firestore (camelCase keys —
// senderId, body in `text`, waveform in `waveform`, …) and
// validateAndMapMessage() below transliterates it into the snake_case
// rpc_send_message payload whose keys match the chat_messages columns
// one-for-one. msg-list returns the stored rows (snake_case) plus
// epoch-ms companions (serverTsMs / updatedAtMs) so the client never
// has to parse Postgres timestamp text.

import type { SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2.49.4";

// The client's chats-list preview rule: at most 80 characters of the
// message body (§4.3). rpc_send_message applies the same rule in SQL
// when the caller does not pass a preview.
export const PREVIEW_MAX_CHARS = 80;

// Message body ceiling, aligned with the client's own field rules.
export const MAX_MESSAGE_TEXT_CHARS = 4000;

// Ciphertext is base64-ish E2EE payload text; generous ceiling so a
// long encrypted message is never the thing that fails validation.
export const MAX_CIPHERTEXT_CHARS = 200_000;

// Type whitelist = the client's MessageType.raw values
// (domain/model/WesalModels.kt). Stage 2 routes TEXT only, but the
// store + validation already accept the full set so stage 3 (media /
// location sends) needs no function change.
export const MESSAGE_TYPES = [
  "TEXT",
  "IMAGE",
  "VIDEO",
  "VOICE",
  "AUDIO",
  "DOCUMENT",
  "LOCATION",
  "LIVE_LOCATION",
  "CALL_LOG",
  "SYSTEM",
] as const;

export const MESSAGE_STATUSES = ["SENT", "DELIVERED", "READ"] as const;

export const CHAT_KINDS = ["direct", "group", "support"] as const;

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

export type MembershipResult =
  | { ok: true; mirror: ChatMirror }
  | { ok: false; status: number; error: string; message: string };

/**
 * The gate every chat-scoped plane function runs after JWT
 * verification: the chat must be registered (409 chat_not_registered —
 * the client registers and retries once, §4.3) and the caller must be
 * in the mirror's participant list (403 not_a_participant).
 */
export async function requireMembership(
  supabase: SupabaseClient,
  chatId: string,
  uid: string,
): Promise<MembershipResult> {
  const mirror = await getChatMirror(supabase, chatId);
  if (!mirror) {
    return {
      ok: false,
      status: 409,
      error: "chat_not_registered",
      message: "Chat is not registered on the message plane",
    };
  }
  if (!isParticipant(mirror, uid)) {
    return {
      ok: false,
      status: 403,
      error: "not_a_participant",
      message: "Caller is not a participant of this chat",
    };
  }
  return { ok: true, mirror };
}

function asString(value: unknown, max = 4000): string | null {
  if (value === undefined || value === null) return "";
  if (typeof value !== "string" || value.length > max) return null;
  return value;
}

function asBool(value: unknown): boolean {
  return value === true;
}

function asNumber(value: unknown): number | null {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "string" && value.trim() !== "") {
    const n = Number(value);
    if (Number.isFinite(n)) return n;
  }
  return null;
}

function asStringArray(value: unknown, maxItems = 500): string[] | null {
  if (value === undefined || value === null) return [];
  if (!Array.isArray(value) || value.length > maxItems) return null;
  const out: string[] = [];
  for (const item of value) {
    if (typeof item !== "string" || item.length > 200) return null;
    out.push(item);
  }
  return out;
}

function asIntArray(value: unknown, maxItems = 1000): number[] | null {
  if (value === undefined || value === null) return [];
  if (!Array.isArray(value) || value.length > maxItems) return null;
  const out: number[] = [];
  for (const item of value) {
    const n = asNumber(item);
    if (n === null) return null;
    out.push(Math.trunc(n));
  }
  return out;
}

export type MessageValidation =
  | { ok: true; payload: Record<string, unknown> }
  | { ok: false; message: string };

/**
 * Validates the client's message field map (the Firestore shape) and
 * transliterates it into the rpc_send_message payload (snake_case,
 * keys = chat_messages columns). [callerUid] is the JWT-verified uid;
 * the message's senderId must equal it (§4.3). Returns the payload or
 * a human-readable validation failure (the caller maps it to
 * 400 invalid_message — the client never renders this text; its own
 * ErrorCopy owns user-facing copy).
 */
export function validateAndMapMessage(
  raw: unknown,
  callerUid: string,
): MessageValidation {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
    return { ok: false, message: "message must be an object" };
  }
  const m = raw as Record<string, unknown>;

  const messageId = m.messageId;
  const chatId = m.chatId;
  const senderId = m.senderId;
  if (!isValidId(messageId)) return { ok: false, message: "messageId invalid" };
  if (!isValidId(chatId)) return { ok: false, message: "chatId invalid" };
  if (!isValidId(senderId)) return { ok: false, message: "senderId invalid" };
  if (senderId !== callerUid) {
    return { ok: false, message: "senderId does not match the caller" };
  }

  const type = typeof m.type === "string" ? m.type : "TEXT";
  if (!(MESSAGE_TYPES as readonly string[]).includes(type)) {
    return { ok: false, message: "type not supported" };
  }

  const text = asString(m.text, MAX_MESSAGE_TEXT_CHARS);
  if (text === null) return { ok: false, message: "text too long or not a string" };
  const ciphertext = asString(m.ciphertext, MAX_CIPHERTEXT_CHARS);
  if (ciphertext === null) return { ok: false, message: "ciphertext too long" };
  const isEncrypted = asBool(m.isEncrypted);
  if (type === "TEXT" && !isEncrypted && text.trim() === "" && ciphertext === "") {
    return { ok: false, message: "empty text message" };
  }

  const clientTs = asNumber(m.clientTimestamp);
  if (clientTs === null || clientTs <= 0) {
    return { ok: false, message: "clientTimestamp invalid" };
  }

  const status = typeof m.status === "string" ? m.status : "SENT";
  if (!(MESSAGE_STATUSES as readonly string[]).includes(status)) {
    return { ok: false, message: "status not supported" };
  }

  const viewedBy = asStringArray(m.viewedBy);
  if (viewedBy === null) return { ok: false, message: "viewedBy invalid" };
  const deletedFor = asStringArray(m.deletedForUsers);
  if (deletedFor === null) return { ok: false, message: "deletedForUsers invalid" };
  const waveform = asIntArray(m.waveform);
  if (waveform === null) return { ok: false, message: "waveform invalid" };

  const wrappedKeys = (m.wrappedKeys && typeof m.wrappedKeys === "object" &&
      !Array.isArray(m.wrappedKeys))
    ? m.wrappedKeys as Record<string, unknown>
    : {};

  const str = (v: unknown, max = 2000): string | null => asString(v, max);
  const senderName = str(m.senderName, 300);
  const senderIconId = str(m.senderIconId, 300);
  const senderIconUrl = str(m.senderIconUrl, 4000);
  const senderVerificationType = str(m.senderVerificationType, 50) || "BLUE";
  const senderNeonColor = str(m.senderNeonColor, 100);
  const mediaUrl = str(m.mediaUrl, 4000);
  const mediaMimeType = str(m.mediaMimeType, 200);
  const mediaFileName = str(m.mediaFileName, 500);
  const locationAddress = str(m.locationAddress, 1000);
  if (
    senderName === null || senderIconId === null || senderIconUrl === null ||
    senderNeonColor === null || mediaUrl === null || mediaMimeType === null ||
    mediaFileName === null || locationAddress === null
  ) {
    return { ok: false, message: "a text field is too long or not a string" };
  }

  const nullableStr = (v: unknown): string | null => {
    if (v === undefined || v === null || v === "") return null;
    return typeof v === "string" && v.length <= 4000 ? v : null;
  };

  const glow = asNumber(m.senderNeonGlowIntensity);
  const mediaSize = asNumber(m.mediaSizeBytes);
  const mediaDuration = asNumber(m.mediaDurationMs);
  const liveLocationExpires = asNumber(m.liveLocationExpiresAt);
  const disappearingExpires = asNumber(m.disappearingExpiresAt);

  return {
    ok: true,
    payload: {
      message_id: messageId,
      chat_id: chatId,
      sender_id: senderId,
      sender_name: senderName,
      sender_icon_id: senderIconId,
      sender_icon_url: senderIconUrl,
      sender_verified: asBool(m.senderVerified),
      sender_verification_type: senderVerificationType,
      sender_neon_color: senderNeonColor,
      sender_neon_enabled: asBool(m.senderNeonEnabled),
      sender_neon_glow_intensity: glow === null ? 0.7 : glow,
      type,
      ciphertext,
      body_text: text,
      is_encrypted: isEncrypted,
      is_view_once: asBool(m.isViewOnce),
      viewed_by: viewedBy,
      media_url: mediaUrl,
      media_mime_type: mediaMimeType,
      media_file_name: mediaFileName,
      media_size_bytes: mediaSize === null ? 0 : Math.trunc(mediaSize),
      media_duration_ms: mediaDuration === null ? 0 : Math.trunc(mediaDuration),
      waveform_amplitudes: waveform,
      wrapped_keys: wrappedKeys,
      latitude: asNumber(m.latitude),
      longitude: asNumber(m.longitude),
      location_address: locationAddress,
      live_location_expires_at: liveLocationExpires === null
        ? 0
        : Math.trunc(liveLocationExpires),
      disappearing_expires_at: disappearingExpires === null
        ? 0
        : Math.trunc(disappearingExpires),
      reply_to_message_id: nullableStr(m.replyToMessageId),
      reply_to_preview: nullableStr(m.replyToPreview),
      forwarded_from_name: nullableStr(m.forwardedFromName),
      is_edited: asBool(m.isEdited),
      is_deleted_for_everyone: asBool(m.isDeletedForEveryone),
      deleted_for_users: deletedFor,
      status,
      client_ts: Math.trunc(clientTs),
      preview: derivePreview(text),
    },
  };
}

/**
 * Adds epoch-ms companions to a stored chat_messages row so the client
 * mapper never parses Postgres timestamp text (§4.3 transliteration).
 */
export function toClientRow(
  row: Record<string, unknown>,
): Record<string, unknown> {
  const serverTs = typeof row.server_ts === "string"
    ? Date.parse(row.server_ts)
    : NaN;
  const updatedAt = typeof row.updated_at === "string"
    ? Date.parse(row.updated_at)
    : NaN;
  return {
    ...row,
    serverTsMs: Number.isFinite(serverTs) ? serverTs : 0,
    updatedAtMs: Number.isFinite(updatedAt) ? updatedAt : 0,
  };
}

/** Epoch ms → ISO string for PostgREST timestamp comparisons. */
export function isoFromMs(ms: number): string {
  return new Date(ms).toISOString();
}
