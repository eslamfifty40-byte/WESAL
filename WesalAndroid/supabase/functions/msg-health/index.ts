// POST { idToken } -> { ok: true }
//
// Failover message-plane health probe (FAILOVER_DESIGN.md §4.3): the
// cheapest possible authenticated round-trip — Firebase-JWT verify
// only, NO table access — so the client's BackendHealthStore /
// MessagePlaneRouter can tell "the Supabase function layer is alive"
// apart from a dead backend, even when the database itself is at its
// monthly ceiling. Authentication follows the exact convention of the
// phone functions (see ../_shared/phone.ts): the Firebase ID token
// travels in the request body and is verified against Google's JWKS.

import {
  jsonError,
  jsonOk,
  readJsonBody,
  verifyFirebaseIdToken,
} from "../_shared/phone.ts";

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

  return jsonOk({});
});
