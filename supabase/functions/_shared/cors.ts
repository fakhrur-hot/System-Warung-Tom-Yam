/**
 * CORS headers for Supabase Edge Functions, and the single place the café's public origin is read.
 *
 * ### Why this file fails loudly
 *
 * `WEBSITE_ORIGIN` names the café's Cloudflare Pages site. It used to fall back to a placeholder
 * (`https://your-site.pages.dev`) when unset, which is the worst of both worlds: the deployment looks
 * healthy, every function returns 200, and the *browser* refuses the response later with a generic
 * CORS message naming neither this variable nor this project. Worse, on a café-specific branch that
 * placeholder was edited to a real domain — so a misconfigured deployment could serve **another
 * café's** origin, allowing the wrong site and blocking the right one.
 *
 * An unset origin now omits `Access-Control-Allow-Origin` entirely and logs a diagnostic naming the
 * variable. Omitting is deliberately louder than guessing: a browser reports a *missing* header
 * rather than a mismatched one, which points at configuration instead of at the caller's code.
 *
 * ### Why it does not throw
 *
 * The Android app sends no `Origin` and ignores CORS. Throwing here would take a café's tills offline
 * over a variable only the website needs, turning a web-only misconfiguration into a total outage.
 * Endpoints that genuinely cannot work without the origin — the ones that mint links *into* the site
 * — call [requireWebsiteOrigin] and fail individually with a named error.
 */

/** The café's public site origin, trailing slashes removed, or `null` when unset. */
export function websiteOrigin(): string | null {
  const raw = Deno.env.get("WEBSITE_ORIGIN");
  if (!raw || raw.trim() === "") return null;
  // An Access-Control-Allow-Origin value with a trailing slash matches nothing — a browser's Origin
  // header never carries one. Normalising here means a deployment cannot fail on a stray character.
  return raw.trim().replace(/\/+$/, "");
}

function buildCorsHeaders(): Record<string, string> {
  const headers: Record<string, string> = {
    "Access-Control-Allow-Headers":
      "authorization, x-client-info, apikey, content-type, x-browser-id",
    "Access-Control-Allow-Methods": "GET, POST, PATCH, PUT, DELETE, OPTIONS",
  };

  const origin = websiteOrigin();
  if (origin === null) {
    console.error(
      "[cors] WEBSITE_ORIGIN is not set on this deployment. Access-Control-Allow-Origin will be " +
        "omitted, so every browser request from the café's site will be refused. Set it to the " +
        "site's origin, e.g. https://your-cafe.pages.dev (no trailing slash). The Android app is " +
        "unaffected — it does not use CORS.",
    );
    return headers;
  }

  headers["Access-Control-Allow-Origin"] = origin;
  return headers;
}

export const corsHeaders = buildCorsHeaders();

/**
 * The site origin for endpoints that *mint links into it* — the owner-recovery QR and the invite QR.
 *
 * These cannot degrade gracefully. A QR built on a placeholder origin encodes a URL to a site that
 * does not exist, and it fails at the worst possible moment: in a café, with a phone already
 * scanning it. Failing here gives a named error at the point of configuration instead.
 *
 * @throws Error when `WEBSITE_ORIGIN` is unset — callers map it to a 500 with a code.
 */
export function requireWebsiteOrigin(): string {
  const origin = websiteOrigin();
  if (origin === null) {
    throw new Error(
      "WEBSITE_ORIGIN is not set on this deployment, so a link into the café's site cannot be " +
        "built. Set it to the site's origin, e.g. https://your-cafe.pages.dev (no trailing slash).",
    );
  }
  return origin;
}

/**
 * Returns a preflight (OPTIONS) response with CORS headers.
 */
export function handleCors(req: Request): Response | null {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }
  return null;
}
