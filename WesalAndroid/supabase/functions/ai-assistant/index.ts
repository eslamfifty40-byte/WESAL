// POST { idToken, messages: [{ role, text, media? }] } -> { reply }
//
// «مساعد وِصال» — the in-app AI assistant (round 8). The caller must be
// signed in to Firebase; the ID token is verified against Google's JWKS
// exactly like the phone functions (see ../_shared/phone.ts).
//
// The model is Google Gemini on the FREE AI Studio tier: the API key lives
// ONLY in the GEMINI_API_KEY function secret — never in the app, never in
// git. Gemini Flash is multimodal, so the last user message may carry ONE
// attachment (a photo the user wants explained, or a voice note the user
// recorded instead of typing) as base64 inline data.
//
// Abuse/cost guard: a per-user daily cap (ai_usage table, Africa/Cairo
// day). The free tier also has its own global daily quota — when Google
// answers 429 the client shows the same "come back tomorrow" message.

import {
  jsonError,
  jsonOk,
  readJsonBody,
  serviceClient,
  verifyFirebaseIdToken,
} from "../_shared/phone.ts";

const DAILY_LIMIT_PER_USER = 30;
const MAX_MESSAGES = 10;
const MAX_TEXT_CHARS = 4000;
const MAX_MEDIA_BASE64_CHARS = 5_600_000; // ~4 MB of binary

const ALLOWED_MEDIA_TYPES = new Set([
  "image/jpeg",
  "image/png",
  "image/webp",
  "audio/mp4",
  "audio/aac",
  "audio/mpeg",
  "audio/wav",
  "audio/ogg",
]);

const SYSTEM_PROMPT =
  `أنت «مساعد وِصال»، المساعد الذكي الرسمي داخل تطبيق وِصال للمراسلة الفورية. ` +
  `رد دائمًا بنفس لغة اللي بيكلمك: لو كتب بالمصري رد بالمصري البسيط الودود، ` +
  `ولو كتب بالعربية الفصحى رد بالفصحى، ولو بالإنجليزية رد بالإنجليزية. ` +
  `خليك مختصر ومفيد وعملي، واستخدم نقاط قصيرة لما الإجابة طويلة. ` +
  `لو حد بعت لك صورة، اوصفها وجواب على سؤاله عنها. لو بعت رسالة صوتية، ` +
  `افهم اللي اتقال ورد عليه كأنه مكتوب. ما تدّعيش إنك إنسان، ولو اتسألت ` +
  `انت مين قول إنك مساعد وِصال الذكي.`;

interface ChatMessage {
  role: "user" | "assistant";
  text: string;
  media?: { mimeType: string; dataBase64: string };
}

function cairoDay(): string {
  // YYYY-MM-DD in Africa/Cairo, regardless of the edge runtime's timezone.
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: "Africa/Cairo",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(new Date());
}

function parseMessages(raw: unknown): ChatMessage[] | null {
  if (!Array.isArray(raw) || raw.length === 0) return null;
  const items = raw.slice(-MAX_MESSAGES);
  const out: ChatMessage[] = [];
  for (let i = 0; i < items.length; i++) {
    const m = items[i] as Record<string, unknown>;
    const role = m?.role === "assistant" ? "assistant" : m?.role === "user" ? "user" : null;
    if (!role) return null;
    const text = String(m?.text ?? "").slice(0, MAX_TEXT_CHARS);
    const msg: ChatMessage = { role, text };
    // Attachments are honoured ONLY on the final message (the new question).
    if (i === items.length - 1 && m?.media && typeof m.media === "object") {
      const media = m.media as Record<string, unknown>;
      const mimeType = String(media.mimeType ?? "");
      const dataBase64 = String(media.dataBase64 ?? "");
      if (
        ALLOWED_MEDIA_TYPES.has(mimeType) &&
        dataBase64.length > 0 &&
        dataBase64.length <= MAX_MEDIA_BASE64_CHARS
      ) {
        msg.media = { mimeType, dataBase64 };
      }
    }
    if (!msg.text && !msg.media) return null;
    out.push(msg);
  }
  if (out[out.length - 1].role !== "user") return null;
  return out;
}

// Model fallback chain, verified live 2026-11-27 against the owner's key:
// gemini-3.5-flash answers; gemini-3.8-flash / gemini-flash-latest can be
// transiently 503 (high demand); the retired 2.x models 404 for new users.
// The chain tries each in order on 404/503/429 and reports quota only when
// every model says 429.
const MODELS = ["gemini-3.5-flash", "gemini-3.8-flash", "gemini-flash-latest"];

async function askGemini(
  apiKey: string,
  messages: ChatMessage[],
): Promise<{ reply: string } | { quota: true } | null> {
  const contents = messages.map((m, idx) => {
    const parts: Record<string, unknown>[] = [];
    if (idx === messages.length - 1 && m.media) {
      parts.push({
        inline_data: { mime_type: m.media.mimeType, data: m.media.dataBase64 },
      });
    }
    parts.push({ text: m.text || "اشرح لي ده" });
    return { role: m.role === "assistant" ? "model" : "user", parts };
  });
  const payload = JSON.stringify({
    system_instruction: { parts: [{ text: SYSTEM_PROMPT }] },
    contents,
    generationConfig: { maxOutputTokens: 1024, temperature: 0.7 },
  });

  let sawQuota = false;
  for (const model of MODELS) {
    try {
      const res = await fetch(
        `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`,
        {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "x-goog-api-key": apiKey,
          },
          body: payload,
        },
      );
      if (res.status === 429) {
        sawQuota = true;
        await res.text().catch(() => "");
        continue;
      }
      if (res.status === 404 || res.status === 503) {
        await res.text().catch(() => "");
        continue;
      }
      if (!res.ok) {
        await res.text().catch(() => "");
        return null;
      }
      const data = await res.json();
      const parts = data?.candidates?.[0]?.content?.parts;
      // Skip reasoning ("thought") parts — only the visible answer counts.
      const reply = Array.isArray(parts)
        ? parts
          .filter((p) => !p?.thought)
          .map((p) => String(p?.text ?? ""))
          .join("")
          .trim()
        : "";
      if (reply) return { reply };
    } catch {
      // Network hiccup on this model — try the next one.
    }
  }
  return sawQuota ? { quota: true } : null;
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

  const messages = parseMessages(body.messages);
  if (!messages) {
    return jsonError(400, "invalid_messages", "رسالة غير صحيحة");
  }

  const apiKey = Deno.env.get("GEMINI_API_KEY") ?? "";
  if (!apiKey) {
    return jsonError(503, "not_configured", "المساعد مش جاهز دلوقتي — حاول بعد شوية");
  }

  const supabase = serviceClient();
  const day = cairoDay();
  const { data: usage, error: usageError } = await supabase
    .from("ai_usage")
    .select("count")
    .eq("firebase_uid", uid)
    .eq("day", day)
    .maybeSingle();
  if (usageError) {
    return jsonError(500, "server_error", "خطأ في الخادم — حاول مرة أخرى");
  }
  const used = usage ? Number(usage.count || 0) : 0;
  if (used >= DAILY_LIMIT_PER_USER) {
    return jsonError(
      429,
      "daily_limit",
      "وصلت لحد الأسئلة المجانية النهارده — ارجع لي بكرة",
    );
  }

  const result = await askGemini(apiKey, messages);
  if (!result) {
    return jsonError(503, "ai_failed", "المساعد مش قادر يرد دلوقتي — جرّب تاني");
  }
  if ("quota" in result) {
    return jsonError(
      429,
      "daily_limit",
      "وصلت لحد الأسئلة المجانية النهارده — ارجع لي بكرة",
    );
  }

  // Count only successful answers (a failed call must not burn the user's day).
  await supabase
    .from("ai_usage")
    .upsert({ firebase_uid: uid, day, count: used + 1 });

  return jsonOk({ reply: result.reply });
});
