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
