// Shared helpers for the WESAL phone-verification Edge Functions.
//
// These mirror the Firebase implementation in
// firebase/functions/src/index.ts (sendPhoneCode / checkPhoneCode /
// verifyPhoneCodeForCurrentUser) convention-for-convention:
//   phoneHash = sha256 hex of the canonical E.164 number
//               (same hash space as the client's PhoneUtils.sha256Hash)
//   codeHash  = sha256 hex of (code + phoneHash)
//   6-digit codes, 10-minute TTL, 60s resend interval,
//   max 5 sends per phone per hour, max 5 verify attempts.

import { createRemoteJWKSet, jwtVerify } from "https://esm.sh/jose@5.9.6";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.49.4";

export const FIREBASE_PROJECT_ID = "idyllic-axon-mw1rw";

export const PHONE_CODE_TTL_MS = 10 * 60_000;
export const PHONE_CODE_RESEND_MIN_MS = 60_000;
export const PHONE_CODE_WINDOW_MS = 60 * 60_000;
export const PHONE_CODE_MAX_SENDS_PER_WINDOW = 5;
export const PHONE_CODE_MAX_ATTEMPTS = 5;

const JWKS = createRemoteJWKSet(
  new URL(
    "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com",
  ),
);

/** Verifies a Firebase ID token (RS256, Google JWKS). Returns the uid (sub). */
export async function verifyFirebaseIdToken(
  idToken: string,
): Promise<string | null> {
  if (!idToken) return null;
  try {
    const { payload } = await jwtVerify(idToken, JWKS, {
      issuer: `https://securetoken.google.com/${FIREBASE_PROJECT_ID}`,
      audience: FIREBASE_PROJECT_ID,
      algorithms: ["RS256"],
    });
    return typeof payload.sub === "string" && payload.sub ? payload.sub : null;
  } catch {
    return null;
  }
}

export function serviceClient() {
  return createClient(
    Deno.env.get("SUPABASE_URL") ?? "",
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "",
    { auth: { persistSession: false } },
  );
}

export async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(value),
  );
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

export function toAsciiDigits(input: string): string {
  return String(input || "")
    .replace(/[٠-٩]/g, (c) => String(c.charCodeAt(0) - 0x0660))
    .replace(/[۰-۹]/g, (c) => String(c.charCodeAt(0) - 0x06F0));
}

/**
 * Canonical E.164 normalization mirroring the client's
 * PhoneUtils.normalizeToE164 with the Egypt (+20) default — identical logic
 * to normalizePhoneE164Server in the Firebase functions.
 */
export function normalizePhoneE164(raw: string): string | null {
  const cleaned = toAsciiDigits(raw).replace(/[\s\-().]/g, "");
  if (!cleaned) return null;
  const digits = cleaned.replace(/\D/g, "");
  if (!digits) return null;
  let e164: string;
  if (cleaned.startsWith("+")) {
    e164 = "+" + digits;
  } else if (cleaned.startsWith("00")) {
    e164 = "+" + digits.slice(2);
  } else if (cleaned.startsWith("0")) {
    e164 = "+20" + digits.slice(1);
  } else if (cleaned.startsWith("20") && digits.length === 12) {
    e164 = "+" + digits;
  } else {
    e164 = "+20" + digits;
  }
  const totalDigits = e164.length - 1;
  if (totalDigits < 7 || totalDigits > 15) return null;
  if (e164.startsWith("+20")) {
    const national = e164.slice(3);
    if (!/^1[0125][0-9]{8}$/.test(national)) return null;
  }
  return e164;
}

export function jsonError(
  status: number,
  error: string,
  message: string,
): Response {
  return new Response(JSON.stringify({ ok: false, error, message }), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

export function jsonOk(body: Record<string, unknown>): Response {
  return new Response(JSON.stringify({ ok: true, ...body }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

export async function readJsonBody(
  req: Request,
): Promise<Record<string, unknown> | null> {
  try {
    const body = await req.json();
    return body && typeof body === "object" ? body : null;
  } catch {
    return null;
  }
}
