// Mints the café's FIRST owner key — the one secret the whole POS is built on — and stores only its
// hash, so the plaintext exists once: in the QR this returns.
//
// ### Why the provisioner has to do this
//
// `admin-recovery` is how a device becomes Main Admin, and both of its read paths are admin-only:
// `GET` needs an admin token, and `/regenerate` needs one too. On a brand-new café there is no admin,
// so nothing can mint the key that creates the first admin — the door needs the key that is behind it.
// The provisioner writes the row directly via the Supabase Management API query endpoint and hands the
// owner their QR. That closes the bootstrap with no unauthenticated endpoint and no schema change.
//
// ### Hash only, deliberately
//
// The key used to live in `settings.owner_recovery_token` as plaintext, which meant anyone who could
// read that table held the café. Writing `owner_recovery_token_hash` matches what `admin-recovery`
// now checks first, and matches how `devices.session_token_hash` has always been stored. Nothing on
// the server can reproduce the key afterwards — which is the point, and also why the response below
// is the only chance to save it.
//
// Run AFTER the schema step: it writes into `settings`, which the migrations create.

import type { PagesContext, ProvisionResponse, StepResult } from '../../_shared-ts/types'

interface ProvisionOwnerKeyRequest {
  /** Supabase Personal Access Token (account.supabase.com/tokens) */
  personalAccessToken: string
  /** Project reference ID, e.g. jxxzdmbvazxfbhkittlm */
  projectRef: string
  /** The café's website origin, so the response can carry a scannable /join link. */
  websiteOrigin: string
}

interface OwnerKeyResponse extends ProvisionResponse {
  /** Plaintext key — shown once, never retrievable again. */
  ownerKey?: string
  /** The URL to render as the owner QR. */
  ownerKeyUrl?: string
}

export async function onRequestPost(context: PagesContext): Promise<Response> {
  const { personalAccessToken, projectRef, websiteOrigin } =
    (await context.request.json()) as ProvisionOwnerKeyRequest

  const results: StepResult[] = []
  const key = generateKey()
  const hash = await sha256Hex(key)

  const url = `https://api.supabase.com/v1/projects/${encodeURIComponent(projectRef)}/database/query`

  try {
    const hashLiteral = hash.replace(/'/g, "''")
    // Upsert rather than insert: re-running provisioning on a café that already has a key replaces it,
    // which is the same "old QR stops working" contract as /regenerate. Never silently keeps two.
    const upsertResponse = await fetch(url, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${personalAccessToken}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        query: `insert into settings (key, value) values ('owner_recovery_token_hash', '${hashLiteral}')
                on conflict (key) do update set value = excluded.value`,
      }),
    })

    if (!upsertResponse.ok) {
      const detail = await upsertResponse.text().catch(() => `HTTP ${upsertResponse.status}`)
      results.push({ step: 'owner_recovery_token_hash', status: 'error', detail })
      return json({ results })
    }
    results.push({ step: 'owner_recovery_token_hash', status: 'ok' })

    // Any legacy plaintext row is removed in the same pass: leaving it would keep a readable copy
    // of a key we just promised is unreadable.
    const deleteResponse = await fetch(url, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${personalAccessToken}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        query: `delete from settings where key = 'owner_recovery_token'`,
      }),
    })

    if (!deleteResponse.ok) {
      const detail = await deleteResponse.text().catch(() => `HTTP ${deleteResponse.status}`)
      results.push({ step: 'remove_plaintext_key', status: 'error', detail })
    } else {
      results.push({ step: 'remove_plaintext_key', status: 'ok' })
    }
  } catch (e) {
    results.push({ step: 'owner_recovery_token_hash', status: 'error', detail: String(e) })
    return json({ results })
  }

  const origin = (websiteOrigin || '').replace(/\/+$/, '')
  return json({
    results,
    ownerKey: key,
    // Same shape `admin-recovery` mints, so one scanner handles both.
    ownerKeyUrl: origin ? `${origin}/join?recover=${key}` : undefined,
  })
}

/** 32 bytes of CSPRNG as hex — the same length `generateToken(32)` produces server-side. */
function generateKey(): string {
  const bytes = new Uint8Array(32)
  crypto.getRandomValues(bytes)
  return [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('')
}

/** Must match `_shared/auth.ts`'s `sha256`, or the key would never validate at sign-in. */
async function sha256Hex(input: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(input))
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('')
}

function json(body: OwnerKeyResponse): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}
