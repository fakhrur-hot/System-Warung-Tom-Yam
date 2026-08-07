// Orchestrates a full café provisioning run from a single POST request.
//
// This is the backend endpoint the APK installer calls. It performs every step that the
// individual /api/provision/* endpoints can do, but sequentially and in one request, so a
// tablet can provision a new café by collecting credentials once and showing a checklist.
//
// Supported combinations:
//   - Supabase new + Cloudflare Pages new (full greenfield)
//   - Supabase existing + Cloudflare Pages new
//   - Supabase new + Cloudflare Pages existing
//   - Supabase existing + Cloudflare Pages existing
//
// Cloudflare Pages existing project mode updates the project's environment variables so the
// website points at the chosen Supabase project.

import type { PagesContext, ProvisionResponse, StepResult } from '../../_shared-ts/types'
import { onRequestPost as schemaHandler } from './schema'
import { onRequestPost as functionsHandler } from './functions'
import { onRequestPost as secretsHandler } from './secrets'
import { onRequestPost as storageHandler } from './storage'
import { onRequestPost as authHandler } from './auth'
import { onRequestPost as dnsHandler } from './dns'
import { onRequestPost as ownerKeyHandler } from './owner-key'
import { TEMPLATE_GITHUB_OWNER, TEMPLATE_GITHUB_REPO } from '../../_generated/template-repo'

interface SupabaseNew {
  mode: 'new'
  personalAccessToken: string
  orgId: string
  region: string
  projectName: string
}

interface SupabaseExisting {
  mode: 'existing'
  personalAccessToken: string
  projectRef: string
  anonKey: string
  serviceRoleKey: string
}

type SupabaseConfig = SupabaseNew | SupabaseExisting

interface CloudflareNew {
  mode: 'new'
  accountId: string
  apiToken: string
  cafeSlug: string
  zoneId?: string
  customDomain?: string
}

interface CloudflareExisting {
  mode: 'existing'
  accountId: string
  apiToken: string
  projectName: string
  zoneId?: string
  customDomain?: string
}

type CloudflareConfig = CloudflareNew | CloudflareExisting

interface CafeConfig {
  cafeName: string
  brevoApiKey?: string
}

interface ProvisionRunRequest {
  supabase: SupabaseConfig
  cloudflare: CloudflareConfig
  cafe: CafeConfig
}

interface ResolvedSupabase {
  ref: string
  url: string
  anonKey: string
  serviceRoleKey: string
  personalAccessToken: string
}

interface ResolvedCloudflare {
  projectName: string
  websiteUrl: string
  accountId: string
  apiToken: string
  zoneId?: string
  customDomain?: string
}

interface ProvisionRunResponse extends ProvisionResponse {
  supabaseUrl?: string
  supabaseAnonKey?: string
  supabaseServiceRoleKey?: string
  websiteUrl?: string
  cafeName?: string
  ownerKeyUrl?: string
}

const SUPABASE_MANAGEMENT_BASE = 'https://api.supabase.com/v1'
const CLOUDFLARE_API_BASE = 'https://api.cloudflare.com/client/v4'

export async function onRequestPost(context: PagesContext): Promise<Response> {
  let body: ProvisionRunRequest
  try {
    body = (await context.request.json()) as ProvisionRunRequest
  } catch (e) {
    return json({ results: [{ step: 'parse-request', status: 'error', detail: 'Invalid JSON body' }] }, 400)
  }

  const results: StepResult[] = []
  let supabase: ResolvedSupabase | null = null
  let cloudflare: ResolvedCloudflare | null = null
  let ownerKeyUrl: string | undefined

  try {
    // ── Supabase project ─────────────────────────────────────────────────────────────────────
    if (body.supabase.mode === 'new') {
      const created = await runStep(results, 'create-supabase-project', () =>
        createSupabaseProject(body.supabase as SupabaseNew),
      )
      supabase = created
    } else {
      const cfg = body.supabase as SupabaseExisting
      supabase = {
        ref: cfg.projectRef,
        url: `https://${cfg.projectRef}.supabase.co`,
        anonKey: cfg.anonKey,
        serviceRoleKey: cfg.serviceRoleKey,
        personalAccessToken: cfg.personalAccessToken,
      }
      results.push({ step: 'use-existing-supabase', status: 'ok', detail: supabase.url })
    }

    // ── Cloudflare Pages project ─────────────────────────────────────────────────────────────
    if (body.cloudflare.mode === 'new') {
      const cfg = body.cloudflare as CloudflareNew
      await runStep(results, 'create-cloudflare-pages', () =>
        createPagesProject({
          accountId: cfg.accountId,
          apiToken: cfg.apiToken,
          projectName: cfg.cafeSlug,
          supabaseUrl: supabase!.url,
          supabaseAnonKey: supabase!.anonKey,
          // Baked from template-repo.properties, with the env var kept as an override for a fork or
          // a white-label deployment. It used to be env-only, which meant a Wizard that was otherwise
          // configured correctly still failed at this step until someone remembered to set two
          // variables that are the same for every café.
          githubOwner: context.env.RAZSTUDIO_GITHUB_OWNER || TEMPLATE_GITHUB_OWNER,
          githubRepo: context.env.RAZSTUDIO_GITHUB_REPO || TEMPLATE_GITHUB_REPO,
        }),
      )
      cloudflare = {
        projectName: cfg.cafeSlug,
        websiteUrl: cfg.customDomain
          ? `https://${cfg.customDomain.replace(/^https?:\/\//, '').replace(/\/$/, '')}`
          : `https://${cfg.cafeSlug}.pages.dev`,
        accountId: cfg.accountId,
        apiToken: cfg.apiToken,
        zoneId: cfg.zoneId,
        customDomain: cfg.customDomain,
      }
      // runStep already records create-cloudflare-pages; the detail is returned but not needed here.
    } else {
      const cfg = body.cloudflare as CloudflareExisting
      const websiteUrl = cfg.customDomain
        ? `https://${cfg.customDomain.replace(/^https?:\/\//, '').replace(/\/$/, '')}`
        : `https://${cfg.projectName}.pages.dev`
      cloudflare = {
        projectName: cfg.projectName,
        websiteUrl,
        accountId: cfg.accountId,
        apiToken: cfg.apiToken,
        zoneId: cfg.zoneId,
        customDomain: cfg.customDomain,
      }
      await runStep(results, 'update-cloudflare-env', () =>
        updatePagesEnvVars({
          accountId: cfg.accountId,
          apiToken: cfg.apiToken,
          projectName: cfg.projectName,
          supabaseUrl: supabase!.url,
          supabaseAnonKey: supabase!.anonKey,
        }),
      )
    }

    // ── Supabase schema ────────────────────────────────────────────────────────────────────────
    await runStep(results, 'apply-schema', () =>
      callHandler(schemaHandler, {
        personalAccessToken: supabase!.personalAccessToken,
        projectRef: supabase!.ref,
      }),
    )

    // ── Edge Functions ─────────────────────────────────────────────────────────────────────────
    //
    // Not via callHandler, deliberately. That wrapper throws if ANY inner result is an error and
    // keeps only a concatenated message, which is the wrong shape for this step: 26 functions deploy
    // one at a time, and the useful answer is *which* ones landed. Collapsing 26 outcomes into one
    // thrown string loses exactly the information an operator needs to retry, and aborting the run on
    // the first failure leaves the café with an unknown subset deployed.
    //
    // So the per-function results are merged into the checklist verbatim, and the run continues. A
    // café missing one function is a café to re-run this step against — which the APK can now do on
    // its own (see ProvisionerViewModel.deployFunctionsOnly) — not a reason to abandon provisioning
    // with the owner key unminted.
    const fnResults = await deployEdgeFunctions({
      personalAccessToken: supabase!.personalAccessToken,
      projectRef: supabase!.ref,
    })
    results.push(...fnResults.map((r) => ({ ...r, step: `deploy-functions:${r.step}` })))

    // ── Secrets ────────────────────────────────────────────────────────────────────────────────
    await runStep(results, 'set-secrets', () =>
      callHandler(secretsHandler, {
        personalAccessToken: supabase!.personalAccessToken,
        projectRef: supabase!.ref,
        secrets: {
          BREVO_API_KEY: body.cafe.brevoApiKey,
          WEBSITE_ORIGIN: cloudflare!.websiteUrl,
        },
      }),
    )

    // ── Storage buckets ────────────────────────────────────────────────────────────────────────
    await runStep(results, 'create-storage', () =>
      callHandler(storageHandler, {
        projectRef: supabase!.ref,
        serviceRoleKey: supabase!.serviceRoleKey,
      }),
    )

    // ── Auth URLs ────────────────────────────────────────────────────────────────────────────────
    await runStep(results, 'configure-auth', () =>
      callHandler(authHandler, {
        personalAccessToken: supabase!.personalAccessToken,
        projectRef: supabase!.ref,
        websiteUrl: cloudflare!.websiteUrl,
      }),
    )

    // ── DNS record (optional) ──────────────────────────────────────────────────────────────────
    if (cloudflare.customDomain && cloudflare.zoneId) {
      await runStep(results, 'create-dns-record', () =>
        callHandler(dnsHandler, {
          zoneId: cloudflare!.zoneId,
          cloudflareApiToken: cloudflare!.apiToken,
          recordName: cloudflare!.customDomain,
          target: `${cloudflare!.projectName}.pages.dev`,
        }),
      )
    }

    // ── Owner key ──────────────────────────────────────────────────────────────────────────────
    const ownerKeyResult = await runStep(results, 'mint-owner-key', () =>
      callHandler(ownerKeyHandler, {
        personalAccessToken: supabase!.personalAccessToken,
        projectRef: supabase!.ref,
        websiteOrigin: cloudflare!.websiteUrl,
      }),
    )
    ownerKeyUrl = (ownerKeyResult as { ownerKeyUrl?: string }).ownerKeyUrl

    return json({
      results,
      supabaseUrl: supabase.url,
      supabaseAnonKey: supabase.anonKey,
      supabaseServiceRoleKey: supabase.serviceRoleKey,
      websiteUrl: cloudflare.websiteUrl,
      cafeName: body.cafe.cafeName,
      ownerKeyUrl,
    } satisfies ProvisionRunResponse)
  } catch (e) {
    const detail = e instanceof Error ? e.message : String(e)
    // Ensure the failing step is recorded if it was not already pushed by runStep.
    if (!results.some((r) => r.status === 'error')) {
      results.push({ step: 'provision-run', status: 'error', detail })
    }
    return json({
      results,
      supabaseUrl: supabase?.url,
      supabaseAnonKey: supabase?.anonKey,
      websiteUrl: cloudflare?.websiteUrl,
      cafeName: body.cafe.cafeName,
    } satisfies ProvisionRunResponse, 500)
  }
}

/**
 * Runs the Edge Functions deploy and returns its per-function results instead of throwing.
 *
 * Shares one code path with `POST /api/provision/functions`, so a full provisioning run and a
 * functions-only re-run from the APK deploy byte-identical bundles. If these ever diverged, "re-run
 * the functions step" would stop being a reliable repair for a café provisioned by the other path.
 */
async function deployEdgeFunctions(body: {
  personalAccessToken: string
  projectRef: string
}): Promise<StepResult[]> {
  const response = await functionsHandler({
    request: new Request('http://localhost/api/provision/functions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),
    env: {},
  })
  const data = (await response.json()) as ProvisionResponse
  return data.results ?? [{ step: 'deploy-functions', status: 'error', detail: `HTTP ${response.status}` }]
}

async function runStep<T>(
  results: StepResult[],
  step: string,
  fn: () => Promise<T>,
): Promise<T> {
  try {
    const result = await fn()
    // If the handler already pushed its own step result(s), don't duplicate a generic one.
    if (!results.some((r) => r.step === step || r.step.startsWith(`${step}:`))) {
      results.push({ step, status: 'ok' })
    }
    return result
  } catch (e) {
    const detail = e instanceof Error ? e.message : String(e)
    results.push({ step, status: 'error', detail })
    throw e
  }
}

async function callHandler(handler: (ctx: PagesContext) => Promise<Response>, body: unknown): Promise<unknown> {
  const response = await handler({
    request: new Request('http://localhost/api/provision/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),
    env: {},
  })
  const data = (await response.json()) as ProvisionResponse & Record<string, unknown>
  if (!response.ok || data.results?.some((r) => r.status === 'error')) {
    const detail = data.results
      ?.filter((r) => r.status === 'error')
      .map((r) => `${r.step}: ${r.detail || 'failed'}`)
      .join('; ') || `HTTP ${response.status}`
    throw new Error(detail)
  }
  return data
}

async function createSupabaseProject(cfg: SupabaseNew): Promise<ResolvedSupabase> {
  const dbPass = generatePassword(32)
  const createRes = await fetch(`${SUPABASE_MANAGEMENT_BASE}/projects`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${cfg.personalAccessToken}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      name: cfg.projectName,
      organization_id: cfg.orgId,
      region: cfg.region,
      plan: 'free',
      db_pass: dbPass,
    }),
  })
  if (!createRes.ok) {
    const detail = await createRes.text().catch(() => `HTTP ${createRes.status}`)
    throw new Error(`Supabase project creation failed: ${detail}`)
  }
  const project = (await createRes.json()) as { id: string; ref: string; status: string }

  const active = await waitForProjectActive(cfg.personalAccessToken, project.ref)
  const keys = await getProjectApiKeys(cfg.personalAccessToken, active.ref)

  return {
    ref: active.ref,
    url: `https://${active.ref}.supabase.co`,
    anonKey: keys.anonKey,
    serviceRoleKey: keys.serviceRoleKey,
    personalAccessToken: cfg.personalAccessToken,
  }
}

async function waitForProjectActive(pat: string, ref: string): Promise<{ ref: string; status: string }> {
  const deadline = Date.now() + 10 * 60 * 1000 // 10 minutes
  while (Date.now() < deadline) {
    const res = await fetch(`${SUPABASE_MANAGEMENT_BASE}/projects/${encodeURIComponent(ref)}`, {
      headers: { Authorization: `Bearer ${pat}` },
    })
    if (!res.ok) {
      const detail = await res.text().catch(() => `HTTP ${res.status}`)
      throw new Error(`Supabase project status check failed: ${detail}`)
    }
    const project = (await res.json()) as { id: string; ref: string; status: string }
    if (project.status === 'ACTIVE') return { ref: project.ref, status: project.status }
    await sleep(15000)
  }
  throw new Error('Timed out waiting for Supabase project to become ACTIVE')
}

async function getProjectApiKeys(pat: string, ref: string): Promise<{ anonKey: string; serviceRoleKey: string }> {
  const res = await fetch(
    `${SUPABASE_MANAGEMENT_BASE}/projects/${encodeURIComponent(ref)}/api-keys?reveal=true`,
    { headers: { Authorization: `Bearer ${pat}` } },
  )
  if (!res.ok) {
    const detail = await res.text().catch(() => `HTTP ${res.status}`)
    throw new Error(`Supabase API keys retrieval failed: ${detail}`)
  }
  const payload = (await res.json()) as
    | { api_keys?: Array<{ name: string; api_key: string }> }
    | Array<{ name: string; api_key: string }>
  const list = Array.isArray(payload) ? payload : payload.api_keys || []
  const anonKey = list.find((k) => k.name === 'anon')?.api_key
  const serviceRoleKey = list.find((k) => k.name === 'service_role')?.api_key
  if (!anonKey || !serviceRoleKey) {
    throw new Error(
      `Supabase API keys response did not include anon/service_role keys. Names found: ${list.map((k) => k.name).join(', ')}`,
    )
  }
  return { anonKey, serviceRoleKey }
}

async function createPagesProject(params: {
  accountId: string
  apiToken: string
  projectName: string
  supabaseUrl: string
  supabaseAnonKey: string
  githubOwner?: string
  githubRepo?: string
}): Promise<{ detail: string }> {
  // Unreachable while template-repo.properties is present (the generator fails the build otherwise),
  // kept because this function is also callable with explicit params.
  if (!params.githubOwner || !params.githubRepo) {
    throw new Error(
      'No template repository configured — template-repo.properties is missing from the build, or ' +
        'RAZSTUDIO_GITHUB_OWNER / RAZSTUDIO_GITHUB_REPO were set to empty strings on this deployment.',
    )
  }

  const requestBody = {
    name: params.projectName,
    production_branch: 'main',
    source: {
      type: 'github',
      config: {
        owner: params.githubOwner,
        repo_name: params.githubRepo,
        production_branch: 'main',
        deployments_enabled: true,
        production_deployments_enabled: true,
        preview_deployment_setting: 'none',
      },
    },
    build_config: {
      build_command: 'npm run build',
      destination_dir: 'dist',
      // The café site lives in the monorepo's website/ folder; the repo root has no package.json,
      // so root_dir '/' makes every build fail with "npm run build: no such script" and the
      // project sits at zero deployments serving a 522.
      root_dir: 'website',
    },
    deployment_configs: {
      production: {
        env_vars: {
          VITE_SUPABASE_URL: { type: 'plain_text', value: params.supabaseUrl },
          VITE_SUPABASE_PUBLISHABLE_KEY: { type: 'plain_text', value: params.supabaseAnonKey },
        },
      },
    },
  }

  const res = await fetch(
    `${CLOUDFLARE_API_BASE}/accounts/${encodeURIComponent(params.accountId)}/pages/projects`,
    {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${params.apiToken}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(requestBody),
    },
  )
  const data = (await res.json()) as { success: boolean; errors?: { message: string }[] }
  if (!res.ok || !data.success) {
    const message = data.errors?.map((e) => e.message).join('; ') || `HTTP ${res.status}`
    throw new Error(`Cloudflare Pages project creation failed: ${message}`)
  }
  return { detail: `Project "${params.projectName}" created` }
}

async function updatePagesEnvVars(params: {
  accountId: string
  apiToken: string
  projectName: string
  supabaseUrl: string
  supabaseAnonKey: string
}): Promise<void> {
  const envVars = [
    { name: 'VITE_SUPABASE_URL', value: params.supabaseUrl },
    { name: 'VITE_SUPABASE_PUBLISHABLE_KEY', value: params.supabaseAnonKey },
  ]

  for (const { name, value } of envVars) {
    const res = await fetch(
      `${CLOUDFLARE_API_BASE}/accounts/${encodeURIComponent(params.accountId)}/pages/projects/${encodeURIComponent(params.projectName)}/env/${encodeURIComponent(name)}`,
      {
        method: 'PUT',
        headers: {
          Authorization: `Bearer ${params.apiToken}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ type: 'plain_text', value }),
      },
    )
    if (!res.ok) {
      const detail = await res.text().catch(() => `HTTP ${res.status}`)
      throw new Error(`Cloudflare Pages env update ${name} failed: ${detail}`)
    }
  }
}

function generatePassword(length: number): string {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*-_+='
  const bytes = new Uint8Array(length)
  crypto.getRandomValues(bytes)
  return [...bytes].map((b) => chars[b % chars.length]).join('')
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function json(body: ProvisionRunResponse, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

// Need to satisfy the module shape for a Cloudflare Pages Function, even though this file
// only exports the POST handler.
export default { onRequestPost }
