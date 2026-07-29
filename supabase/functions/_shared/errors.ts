/**
 * Standard error response helpers.
 * All non-2xx responses follow: { "error": "<MACHINE_CODE>", "message": "<human readable>" }
 */
import { corsHeaders } from "./cors.ts";

export function errorResponse(
  status: number,
  code: string,
  message: string
): Response {
  return new Response(JSON.stringify({ error: code, message }), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}
