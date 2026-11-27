// POST { phone, code, idToken } -> { ok: true, phoneHash }
//
// Supabase port of the Firebase `verifyPhoneCodeForCurrentUser` callable:
// validates the code against phone_verifications, enforces the
// phone-uniqueness semantics of verified_phones (a number bound to a
// DIFFERENT Firebase uid is rejected with 409 phone_in_use BEFORE the code
// is consumed), then binds phone_hash -> uid in verified_phones. The client
// reads verified_phones (anon SELECT) for the mandatory gate and for
// contacts discovery.

import {
  jsonError,
  jsonOk,
  normalizePhoneE164,
  PHONE_CODE_MAX_ATTEMPTS,
  readJsonBody,
  serviceClient,
  sha256Hex,
  toAsciiDigits,
  verifyFirebaseIdToken,
} from "../_shared/phone.ts";

function hashesEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
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
  const code = toAsciiDigits(String(body.code || "")).replace(/\D/g, "");
  if (!/^[0-9]{6}$/.test(code)) {
    return jsonError(400, "invalid_code_format", "الكود لازم يكون ٦ أرقام");
  }

  const phoneHash = await sha256Hex(phone);
  const supabase = serviceClient();

  const { data: row, error: readError } = await supabase
    .from("phone_verifications")
    .select("*")
    .eq("phone_hash", phoneHash)
    .maybeSingle();
  if (readError) {
    return jsonError(500, "server_error", "خطأ في الخادم — حاول مرة أخرى");
  }
  if (!row) {
    return jsonError(
      404,
      "code_not_found",
      "مفيش كود تحقق للرقم ده — اطلب كود جديد",
    );
  }
  // The code must have been requested from this same account's session
  // (mirrors the uid check in verifyPhoneCodeForCurrentUser).
  if (row.firebase_uid && row.firebase_uid !== uid) {
    return jsonError(
      403,
      "session_mismatch",
      "الكود ده اتبعت من جلسة تانية — اطلب كود جديد من هنا",
    );
  }
  if (Date.parse(row.expires_at) < Date.now()) {
    return jsonError(
      400,
      "code_expired",
      "الكود انتهت صلاحيته — اطلب كود جديد",
    );
  }
  const attempts = Number(row.attempts || 0);
  if (attempts >= PHONE_CODE_MAX_ATTEMPTS) {
    await supabase.from("phone_verifications").delete().eq(
      "phone_hash",
      phoneHash,
    );
    return jsonError(
      400,
      "too_many_attempts",
      "محاولات كتير غلط — اطلب كود جديد وحاول تاني",
    );
  }

  const actual = await sha256Hex(code + phoneHash);
  if (!hashesEqual(actual, String(row.code_hash || ""))) {
    const nextAttempts = attempts + 1;
    if (nextAttempts >= PHONE_CODE_MAX_ATTEMPTS) {
      await supabase.from("phone_verifications").delete().eq(
        "phone_hash",
        phoneHash,
      );
      return jsonError(
        400,
        "too_many_attempts",
        "محاولات كتير غلط — اطلب كود جديد وحاول تاني",
      );
    }
    await supabase.from("phone_verifications").update({
      attempts: nextAttempts,
    }).eq("phone_hash", phoneHash);
    return jsonError(400, "invalid_code", "الكود غير صحيح — راجعه وحاول تاني");
  }

  // Ownership lock, checked BEFORE the code is consumed (mirrors the
  // phone_uniqueness check in the Firebase version): a number already
  // linked to a different account changes nothing.
  const { data: binding, error: bindingError } = await supabase
    .from("verified_phones")
    .select("*")
    .eq("phone_hash", phoneHash)
    .maybeSingle();
  if (bindingError) {
    return jsonError(500, "server_error", "خطأ في الخادم — حاول مرة أخرى");
  }
  if (binding && binding.firebase_uid !== uid) {
    return jsonError(409, "phone_in_use", "الرقم ده مربوط بحساب تاني");
  }

  // One uid holds one number in this registry: if this account previously
  // verified a different number, move the binding to the new number.
  await supabase.from("verified_phones").delete().eq("firebase_uid", uid)
    .neq("phone_hash", phoneHash);

  const { error: upsertError } = await supabase
    .from("verified_phones")
    .upsert({
      phone_hash: phoneHash,
      firebase_uid: uid,
      phone_e164: phone,
      verified_at: new Date().toISOString(),
    });
  if (upsertError) {
    return jsonError(500, "server_error", "خطأ في الخادم — حاول مرة أخرى");
  }

  await supabase.from("phone_verifications").delete().eq(
    "phone_hash",
    phoneHash,
  );
  return jsonOk({ phoneHash });
});
