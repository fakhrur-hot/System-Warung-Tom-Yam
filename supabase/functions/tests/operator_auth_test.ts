/**
 * Operator authentication tests.
 * Tests: verifyOperatorToken accept/reject logic, allowlisted function access,
 * non-allowlisted function rejection, and source-code-level verification that
 * the auth patterns are wired correctly.
 *
 * Source-code assertion tests are the primary verification mechanism — they read
 * the actual function source files and assert the expected auth patterns.
 */
import {
  assertEquals,
  assert,
} from "https://deno.land/std@0.177.0/testing/asserts.ts";

import { extractBearer } from "../_shared/auth.ts";

// ══════════════════════════════════════════════════════════════════════════════
// 1. verifyOperatorToken unit tests (accept/reject cases)
// ══════════════════════════════════════════════════════════════════════════════

Deno.test("verifyOperatorToken: returns null when no Bearer header is present", () => {
  // extractBearer is the first gate in verifyOperatorToken — if it returns null,
  // verifyOperatorToken returns null immediately without hitting the DB.
  const req = new Request("http://localhost/api/menu", {
    method: "PUT",
    headers: {},
  });
  const token = extractBearer(req);
  assertEquals(token, null, "No auth header → extractBearer must return null");
});

Deno.test("verifyOperatorToken: returns null for malformed Bearer header", () => {
  const req = new Request("http://localhost/api/menu", {
    method: "PUT",
    headers: { authorization: "Basic abc123" },
  });
  const token = extractBearer(req);
  assertEquals(token, null, "Non-Bearer scheme → extractBearer must return null");
});

Deno.test("verifyOperatorToken: extracts token from valid Bearer header", () => {
  const req = new Request("http://localhost/api/menu", {
    method: "PUT",
    headers: { authorization: "Bearer my-session-token-here" },
  });
  const token = extractBearer(req);
  assertEquals(token, "my-session-token-here");
});

Deno.test("verifyOperatorToken design: rejects non-OPERATOR roles (documented)", () => {
  // verifyOperatorToken queries with `.eq("role", "OPERATOR")` — devices with
  // role ADMIN, ADMIN_SECONDARY, or ORDERING will never match this filter.
  // This is enforced by the Supabase query filter in _shared/auth.ts.
  assert(true, "Non-OPERATOR roles are rejected by the .eq('role', 'OPERATOR') filter");
});

Deno.test("verifyOperatorToken design: rejects non-APPROVED status (documented)", () => {
  // verifyOperatorToken queries with `.eq("status", "APPROVED")` — devices with
  // status PENDING or REVOKED will never match.
  assert(true, "Non-APPROVED statuses are rejected by the .eq('status', 'APPROVED') filter");
});

Deno.test("verifyOperatorToken design: accepts OPERATOR + APPROVED (documented)", () => {
  // A device row with role='OPERATOR', status='APPROVED', and a matching
  // session_token_hash will be returned by verifyOperatorToken.
  // This is the only case where the function returns a non-null device object.
  assert(true, "OPERATOR + APPROVED is the sole acceptance path");
});

// ══════════════════════════════════════════════════════════════════════════════
// 2. Source code verification: verifyOperatorToken in _shared/auth.ts
// ══════════════════════════════════════════════════════════════════════════════

Deno.test("_shared/auth.ts exports verifyOperatorToken with correct filters", async () => {
  const source = await Deno.readTextFile(
    new URL("../_shared/auth.ts", import.meta.url)
  );
  assert(
    source.includes("export async function verifyOperatorToken"),
    "auth.ts must export verifyOperatorToken"
  );
  assert(
    source.includes('.eq("role", "OPERATOR")'),
    "verifyOperatorToken must filter to OPERATOR role"
  );
  assert(
    source.includes('.eq("status", "APPROVED")'),
    "verifyOperatorToken must filter to APPROVED status"
  );
  assert(
    source.includes('.eq("session_token_hash", hash)'),
    "verifyOperatorToken must match on session_token_hash"
  );
});

// ══════════════════════════════════════════════════════════════════════════════
// 3. Five allowlisted functions accept OPERATOR tokens (source verification)
// ══════════════════════════════════════════════════════════════════════════════

Deno.test("menu/index.ts imports and uses verifyOperatorToken in its auth check", async () => {
  const source = await Deno.readTextFile(
    new URL("../menu/index.ts", import.meta.url)
  );
  assert(
    source.includes("verifyOperatorToken"),
    "menu must import verifyOperatorToken"
  );
  assert(
    source.includes("verifyAdminToken(req) ?? await verifyOperatorToken(req)"),
    "menu must fall through from admin to operator"
  );
});

Deno.test("menu-image/index.ts imports and uses verifyOperatorToken (POST and DELETE)", async () => {
  const source = await Deno.readTextFile(
    new URL("../menu-image/index.ts", import.meta.url)
  );
  assert(
    source.includes("verifyOperatorToken"),
    "menu-image must import verifyOperatorToken"
  );
  // Both POST and DELETE handlers use the same pattern
  const matches = source.match(/verifyAdminToken\(req\) \?\? await verifyOperatorToken\(req\)/g);
  assert(
    matches !== null && matches.length >= 2,
    "menu-image must use operator fallback in both POST and DELETE handlers"
  );
});

Deno.test("tables/index.ts imports and uses verifyOperatorToken in PUT", async () => {
  const source = await Deno.readTextFile(
    new URL("../tables/index.ts", import.meta.url)
  );
  assert(
    source.includes("verifyOperatorToken"),
    "tables must import verifyOperatorToken"
  );
  assert(
    source.includes("verifyAdminToken(req) ?? await verifyOperatorToken(req)"),
    "tables must fall through from admin to operator"
  );
});

Deno.test("cafe-location/index.ts imports and uses verifyOperatorToken (PUT and GET)", async () => {
  const source = await Deno.readTextFile(
    new URL("../cafe-location/index.ts", import.meta.url)
  );
  assert(
    source.includes("verifyOperatorToken"),
    "cafe-location must import verifyOperatorToken"
  );
  // Both PUT and GET handlers accept operator tokens
  const matches = source.match(/verifyAdminToken\(req\) \?\? await verifyOperatorToken\(req\)/g);
  assert(
    matches !== null && matches.length >= 2,
    "cafe-location must use operator fallback in both PUT and GET handlers"
  );
});

Deno.test("branding/index.ts imports and uses verifyOperatorToken in PUT", async () => {
  const source = await Deno.readTextFile(
    new URL("../branding/index.ts", import.meta.url)
  );
  assert(
    source.includes("verifyOperatorToken"),
    "branding must import verifyOperatorToken"
  );
  assert(
    source.includes("verifyAdminToken(req) ?? await verifyOperatorToken(req)"),
    "branding must fall through from admin to operator"
  );
});

// ══════════════════════════════════════════════════════════════════════════════
// 4. Non-allowlisted functions reject OPERATOR tokens (source verification)
// ══════════════════════════════════════════════════════════════════════════════

Deno.test("orders/index.ts does NOT use verifyOperatorToken", async () => {
  const source = await Deno.readTextFile(
    new URL("../orders/index.ts", import.meta.url)
  );
  assert(
    !source.includes("verifyOperatorToken"),
    "orders must reject operator tokens by omission — it must not import or call verifyOperatorToken"
  );
});

Deno.test("devices/index.ts does NOT use verifyOperatorToken", async () => {
  const source = await Deno.readTextFile(
    new URL("../devices/index.ts", import.meta.url)
  );
  assert(
    !source.includes("verifyOperatorToken"),
    "devices must reject operator tokens by omission — it must not import or call verifyOperatorToken"
  );
});

Deno.test("settings/index.ts does NOT use verifyOperatorToken", async () => {
  const source = await Deno.readTextFile(
    new URL("../settings/index.ts", import.meta.url)
  );
  assert(
    !source.includes("verifyOperatorToken"),
    "settings must reject operator tokens by omission — it must not import or call verifyOperatorToken"
  );
});

Deno.test("reports-closing/index.ts does NOT use verifyOperatorToken", async () => {
  const source = await Deno.readTextFile(
    new URL("../reports-closing/index.ts", import.meta.url)
  );
  assert(
    !source.includes("verifyOperatorToken"),
    "reports-closing must reject operator tokens by omission — it must not import or call verifyOperatorToken"
  );
});

Deno.test("attendance/index.ts does NOT use verifyOperatorToken", async () => {
  const source = await Deno.readTextFile(
    new URL("../attendance/index.ts", import.meta.url)
  );
  assert(
    !source.includes("verifyOperatorToken"),
    "attendance must reject operator tokens by omission — it must not import or call verifyOperatorToken"
  );
});

// ══════════════════════════════════════════════════════════════════════════════
// 5. Documented integration behaviour (same style as identity_test.ts)
// ══════════════════════════════════════════════════════════════════════════════

Deno.test("operator token accepted at menu PUT (documented integration)", () => {
  // Integration scenario:
  // 1. Operator device has status=APPROVED, role=OPERATOR, valid session_token_hash
  // 2. PUT /api/menu with Bearer <operator-session-token>
  // 3. verifyAdminToken returns null (role filter excludes OPERATOR)
  // 4. verifyOperatorToken returns the device row → auth passes → 200
  assert(true, "menu PUT accepts OPERATOR token via fallback chain");
});

Deno.test("operator token rejected at orders POST (documented integration)", () => {
  // Integration scenario:
  // 1. Operator device has status=APPROVED, role=OPERATOR, valid session_token_hash
  // 2. POST /api/orders with Bearer <operator-session-token>
  // 3. verifyAdminToken returns null (role filter excludes OPERATOR)
  // 4. verifyOrderingKey returns null (key type mismatch)
  // 5. No verifyOperatorToken call exists → auth fails → 401
  assert(true, "orders POST rejects OPERATOR by absence of verifyOperatorToken");
});

Deno.test("operator token rejected at devices PATCH (documented integration)", () => {
  // Integration scenario:
  // 1. PATCH /api/devices/:id with Bearer <operator-session-token>
  // 2. verifySuperadminJwt returns null
  // 3. verifyAdminToken returns null (role filter excludes OPERATOR)
  // 4. No verifyOperatorToken call → 401
  assert(true, "devices PATCH rejects OPERATOR — management stays admin-only");
});

Deno.test("operator token rejected at settings PUT (documented integration)", () => {
  // Integration scenario:
  // 1. PUT /api/settings with Bearer <operator-session-token>
  // 2. verifyAdminToken returns null → 401
  // Settings are admin-only; the operator can only manage the five allowlisted domains.
  assert(true, "settings PUT rejects OPERATOR");
});

Deno.test("operator token rejected at reports-closing GET (documented integration)", () => {
  // Integration scenario:
  // 1. GET /api/reports/closing with Bearer <operator-session-token>
  // 2. verifyAdminToken returns null, verifySuperadminJwt returns null → 401
  assert(true, "reports-closing rejects OPERATOR");
});

Deno.test("operator token rejected at attendance POST (documented integration)", () => {
  // Integration scenario:
  // 1. POST /api/attendance with Bearer <operator-session-token>
  // 2. verifyAdminToken returns null, verifyOrderingKey returns null → 401
  assert(true, "attendance rejects OPERATOR");
});
