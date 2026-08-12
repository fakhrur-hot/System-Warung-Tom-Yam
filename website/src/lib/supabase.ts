import { createClient, type SupabaseClient } from '@supabase/supabase-js'

// Client-side Supabase using the PUBLISHABLE key only (never the secret key, which stays
// server-side in Edge Functions). Lazily created so an unconfigured build/preview doesn't
// crash the skeleton page.
let client: SupabaseClient | null = null

export function getSupabase(): SupabaseClient {
  if (client) return client

  const url = import.meta.env.VITE_SUPABASE_URL
  const key = import.meta.env.VITE_SUPABASE_PUBLISHABLE_KEY

  if (!url || !key || url.includes('REPLACE_WITH')) {
    throw new Error(
      'Supabase is not configured. Set VITE_SUPABASE_URL and VITE_SUPABASE_PUBLISHABLE_KEY ' +
        'in website/.env.local (see .env.example).',
    )
  }

  client = createClient(url, key)
  return client
}

/**
 * supabase-js's `functions.invoke` error only ever has a generic message ("Edge
 * Function returned a non-2xx status code") — the actual `{ error, message }` body
 * our Edge Functions send back lives on `error.context`, a raw fetch `Response`, and
 * is never parsed automatically. Without this, every Edge Function 4xx/404/409 shows
 * the same unhelpful generic string regardless of what actually went wrong.
 */
export async function functionErrorMessage(error: unknown, fallback: string): Promise<string> {
  const context = (error as { context?: Response })?.context
  if (context && typeof context.json === 'function') {
    try {
      const body = await context.clone().json()
      if (typeof body?.message === 'string' && body.message) return body.message
    } catch {
      // Body wasn't JSON (or already consumed) — fall through to the generic message.
    }
  }
  return (error as { message?: string })?.message || fallback
}
