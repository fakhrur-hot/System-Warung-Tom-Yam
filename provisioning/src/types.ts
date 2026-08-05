/** Mirrors functions/_shared-ts/types.ts — duplicated deliberately, see that file's comment. */
export interface StepResult {
  step: string
  status: 'ok' | 'error' | 'skipped'
  detail?: string
}

export interface ProvisionResponse {
  results: StepResult[]
}

/**
 * Everything the operator enters. Lives ONLY in React state for the lifetime of this page load —
 * never written to localStorage/sessionStorage/cookies/analytics. This is the concrete
 * implementation of design.md's Correctness Property 1 (no high-privilege credential is ever
 * persisted) on the frontend half of that property; the backend half is that each
 * /api/provision/* Function uses a credential only for the one outbound call that needs it.
 */
export interface WizardState {
  // Supabase
  supabaseProjectRef: string
  supabasePersonalAccessToken: string
  supabaseAnonKey: string
  supabaseServiceRoleKey: string
  // Cloudflare
  cloudflareAccountId: string
  cloudflareApiToken: string
  cloudflareZoneId: string
  customDomain: string
  // Café identity
  cafeSlug: string
  cafeName: string
  // Secrets / integrations
  brevoApiKey: string
  websiteUrl: string
}

export const EMPTY_WIZARD_STATE: WizardState = {
  supabaseProjectRef: '',
  supabasePersonalAccessToken: '',
  supabaseAnonKey: '',
  supabaseServiceRoleKey: '',
  cloudflareAccountId: '',
  cloudflareApiToken: '',
  cloudflareZoneId: '',
  customDomain: '',
  cafeSlug: '',
  cafeName: '',
  brevoApiKey: '',
  websiteUrl: '',
}
