/**
 * CORS headers for Supabase Edge Functions.
 * Allows cross-origin requests from the Cloudflare Pages frontend.
 */

export const corsHeaders = {
  "Access-Control-Allow-Origin": Deno.env.get("WEBSITE_ORIGIN") ?? "https://tani-tom-yam.pages.dev",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type, x-browser-id",
  "Access-Control-Allow-Methods": "GET, POST, PATCH, PUT, DELETE, OPTIONS",
};

/**
 * Returns a preflight (OPTIONS) response with CORS headers.
 */
export function handleCors(req: Request): Response | null {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }
  return null;
}
