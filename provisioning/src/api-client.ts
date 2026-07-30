import type { ProvisionResponse, WizardState } from './types'

async function post(path: string, body: unknown): Promise<ProvisionResponse> {
  const response = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!response.ok) {
    return { results: [{ step: path, status: 'error', detail: `HTTP ${response.status}` }] }
  }
  return (await response.json()) as ProvisionResponse
}

export const provisionApi = {
  schema: (s: WizardState) =>
    post('/api/provision/schema', { connectionString: s.supabaseConnectionString }),

  functions: (s: WizardState) =>
    post('/api/provision/functions', {
      personalAccessToken: s.supabasePersonalAccessToken,
      projectRef: s.supabaseProjectRef,
    }),

  pages: (s: WizardState) =>
    post('/api/provision/pages', {
      cloudflareAccountId: s.cloudflareAccountId,
      cloudflareApiToken: s.cloudflareApiToken,
      cafeSlug: s.cafeSlug,
      supabaseUrl: `https://${s.supabaseProjectRef}.supabase.co`,
      supabaseAnonKey: s.supabaseAnonKey,
    }),

  dns: (s: WizardState) =>
    post('/api/provision/dns', {
      zoneId: s.cloudflareZoneId,
      cloudflareApiToken: s.cloudflareApiToken,
      recordName: s.customDomain,
      target: `${s.cafeSlug}.pages.dev`,
    }),
}
