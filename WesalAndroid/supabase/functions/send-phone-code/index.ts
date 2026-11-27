// POST { phone, idToken } -> { ok: true, channel, expiresInSeconds }
//
// Supabase port of the Firebase `sendPhoneCode` callable. The caller must be
// signed in to Firebase (any provider — the mandatory verification gate runs
// after sign-in); the Firebase ID token is verified against Google's JWKS
// and its uid is recorded on the verification row so verify-phone-code can
// reject a code that was requested from a different session.
//
// Zavu is TRANSPORT ONLY (same assumption as the Firebase version):
// POST https://api.zavu.dev/v1/messages with a Bearer key held in the
// ZAVU_API_KEY function secret, body { to, channel, text }.
//
// Channel order (owner decision 2026-11-27, after live device testing):
// WhatsApp FIRST, SMS as fallback. Rationale, all measured the same day:
// (a) Egyptian carriers filter/delay international A2P SMS — of 3 codes
// sent to the owner's numbers, 2 sat at "accepted by carrier" unconfirmed
// while WhatsApp has no such grey-route problem; (b) Zavu charges $0.2799
// per Egypt SMS, so the free plan's $2 monthly credit buys ~7 SMS total,
// versus 2,000 free WhatsApp/app messages a month. The WESAL WhatsApp
// sender (registered 2026-11-27, WABA-linked) is selected explicitly via
// the Zavu-Sender header; the SMS attempt keeps the default SMS sender.

import {
  jsonError,
  jsonOk,
  normalizePhoneE164,
  PHONE_CODE_MAX_SENDS_PER_WINDOW,
  PHONE_CODE_RESEND_MIN_MS,
  PHONE_CODE_TTL_MS,
  PHONE_CODE_WINDOW_MS,
  readJsonBody,
  serviceClient,
  sha256Hex,
  verifyFirebaseIdToken,
} from "../_shared/phone.ts";

// The WESAL WhatsApp sender in Zavu (an identifier, not a credential —
// the API key stays in the ZAVU_API_KEY secret).
const ZAVU_WHATSAPP_SENDER_ID = "kd73bkrwc88xf1w7r5q3myfp918k00jt";

async function sendCodeViaZavu(
  phone: string,
  code: string,
): Promise<string | null> {
  const apiKey = Deno.env.get("ZAVU_API_KEY") ?? "";
  if (!apiKey) return null;
  const text =
    `كود التحقق بتاعك في وصال: ${code} — صالح ١٠ دقايق. ما تشاركهوش مع حد.`;
  const attempts: { channel: string; senderId?: string }[] = [
    { channel: "whatsapp", senderId: ZAVU_WHATSAPP_SENDER_ID },
    { channel: "sms_oneway" },
  ];
  for (const attempt of attempts) {
    try {
      const headers: Record<string, string> = {
        Authorization: `Bearer ${apiKey}`,
        "Content-Type": "application/json",
      };
      if (attempt.senderId) headers["Zavu-Sender"] = attempt.senderId;
      const res = await fetch("https://api.zavu.dev/v1/messages", {
        method: "POST",
        headers,
        body: JSON.stringify({ to: phone, channel: attempt.channel, text }),
      });
      if (res.ok) return attempt.channel;
      await res.text().catch(() => "");
    } catch {
      // Fall through to the next channel.
    }
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
    return jsonError(401, "unauthenticated", "Firebase ID token missing or invalid");
  }

  const phone = normalizePhoneE164(String(body.phone || ""));
  if (!phone) {
    return jsonError(
      400,
      "invalid_phone",
      "رقم الهاتف غير صحيح — اكتبه بالصيغة الدولية",
    );
  }
  const phoneHash = await sha256Hex(phone);
  const supabase = serviceClient();
  const now = Date.now();

  const { data: existing, error: readError } = await supabase
    .from("phone_verifications")
    .select("*")
    .eq("phone_hash", phoneHash)
    .maybeSingle();
  if (readError) {
    return jsonError(500, "server_error", "خطأ في الخادم — حاول مرة أخرى");
  }

  const lastSentAt = existing ? Date.parse(existing.last_sent_at) : 0;
  if (lastSentAt > 0 && now - lastSentAt < PHONE_CODE_RESEND_MIN_MS) {
    return jsonError(
      429,
      "rate_limited_resend",
      "استنى دقيقة قبل ما تطلب كود جديد",
    );
  }
  let windowStart = existing ? Date.parse(existing.send_window_start) : 0;
  let sendCount = existing ? Number(existing.send_count || 0) : 0;
  if (now - windowStart >= PHONE_CODE_WINDOW_MS) {
    windowStart = now;
    sendCount = 0;
  }
  if (sendCount >= PHONE_CODE_MAX_SENDS_PER_WINDOW) {
    return jsonError(
      429,
      "rate_limited_hourly",
      "وصلت للحد الأقصى لطلب الأكواد للرقم ده — حاول مرة أخرى بعد شوية",
    );
  }

  // CSPRNG 6-digit code, zero-padded (mirrors randomInt(0, 1_000_000)).
  const buf = new Uint32Array(1);
  crypto.getRandomValues(buf);
  const code = (buf[0] % 1_000_000).toString().padStart(6, "0");
  // The row is written BEFORE delivery on purpose (same as the Firebase
  // version): the rate limits above are enforced from it, so a delivery
  // error can never be retried into a spam loop.
  const { error: writeError } = await supabase
    .from("phone_verifications")
    .upsert({
      phone_hash: phoneHash,
      code_hash: await sha256Hex(code + phoneHash),
      firebase_uid: uid,
      expires_at: new Date(now + PHONE_CODE_TTL_MS).toISOString(),
      attempts: 0,
      last_sent_at: new Date(now).toISOString(),
      send_count: sendCount + 1,
      send_window_start: new Date(windowStart).toISOString(),
    });
  if (writeError) {
    return jsonError(500, "server_error", "خطأ في الخادم — حاول مرة أخرى");
  }

  const channel = await sendCodeViaZavu(phone, code);
  if (!channel) {
    return jsonError(
      503,
      "delivery_failed",
      "تعذر إرسال الكود دلوقتي — حاول مرة أخرى بعد شوية",
    );
  }
  return jsonOk({ channel, expiresInSeconds: PHONE_CODE_TTL_MS / 1000 });
});
