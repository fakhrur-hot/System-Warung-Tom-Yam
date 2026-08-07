// Creates a Cloudflare Pages project for one café via git-integration — the officially
// documented path, chosen over Cloudflare's "Direct Upload" after confirming Direct Upload has no
// public REST contract (its only concrete endpoint sequence comes from a third party's
// reverse-engineering of Wrangler's network traffic, not from Cloudflare's own docs — see
// design.md's Cloudflare Pages + DNS section). This endpoint is well-documented and does not need
// a live test account to trust; unlike schema.ts/functions.ts, it is NOT gated by Requirement R8.
//
// PREREQUISITE (one-time, RAZStudio-side, done once ever): the Cloudflare Pages GitHub App must
// already be installed and authorized on the GitHub owner/repo named by RAZSTUDIO_GITHUB_OWNER /
// RAZSTUDIO_GITHUB_REPO (Wizard project env vars) before this endpoint is called for the first
// time. That authorization is account/org-level, so it never needs repeating per café.

import type { PagesContext, ProvisionResponse, StepResult } from '../../_shared-ts/types'
import { TEMPLATE_GITHUB_OWNER, TEMPLATE_GITHUB_REPO } from '../../_generated/template-repo'

interface ProvisionPagesRequest {
  cloudflareAccountId: string
  cloudflareApiToken: string
  cafeSlug: string
  supabaseUrl: string
  supabaseAnonKey: string
}

export async function onRequestPost(context: PagesContext): Promise<Response> {
  const body = (await context.request.json()) as ProvisionPagesRequest
  const { cloudflareAccountId, cloudflareApiToken, cafeSlug, supabaseUrl, supabaseAnonKey } = body

  // Baked from template-repo.properties at build time; the env vars remain an override for a fork or
  // white-label Wizard. Every café is created from the same repo, so requiring an env var per
  // deployment only created a step that could be forgotten.
  const githubOwner = context.env.RAZSTUDIO_GITHUB_OWNER || TEMPLATE_GITHUB_OWNER
  const githubRepo = context.env.RAZSTUDIO_GITHUB_REPO || TEMPLATE_GITHUB_REPO
  if (!githubOwner || !githubRepo) {
    return json({
      results: [
        {
          step: 'create-pages-project',
          status: 'error',
          detail:
            'No template repository configured — template-repo.properties is missing from the build, ' +
            'or RAZSTUDIO_GITHUB_OWNER / RAZSTUDIO_GITHUB_REPO were set to empty strings here.',
        },
      ],
    })
  }

  const result = await createPagesProject({
    cloudflareAccountId,
    cloudflareApiToken,
    cafeSlug,
    supabaseUrl,
    supabaseAnonKey,
    githubOwner,
    githubRepo,
  })

  return json({ results: [result] })
}

async function createPagesProject(params: {
  cloudflareAccountId: string
  cloudflareApiToken: string
  cafeSlug: string
  supabaseUrl: string
  supabaseAnonKey: string
  githubOwner: string
  githubRepo: string
}): Promise<StepResult> {
  const { cloudflareAccountId, cloudflareApiToken, cafeSlug, supabaseUrl, supabaseAnonKey, githubOwner, githubRepo } =
    params

  // Confirmed shape: POST /accounts/{account_id}/pages/projects, official Cloudflare API
  // reference (developers.cloudflare.com/api/resources/pages/subresources/projects/methods/create).
  const requestBody = {
    name: cafeSlug,
    production_branch: 'main',
    source: {
      type: 'github',
      config: {
        owner: githubOwner,
        repo_name: githubRepo,
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
          VITE_SUPABASE_URL: { type: 'plain_text', value: supabaseUrl },
          VITE_SUPABASE_PUBLISHABLE_KEY: { type: 'plain_text', value: supabaseAnonKey },
        },
      },
    },
  }

  try {
    const response = await fetch(
      `https://api.cloudflare.com/client/v4/accounts/${cloudflareAccountId}/pages/projects`,
      {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${cloudflareApiToken}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(requestBody),
      },
    )
    const data = (await response.json()) as { success: boolean; errors?: { message: string }[] }
    if (!response.ok || !data.success) {
      const message = data.errors?.map((e) => e.message).join('; ') || `HTTP ${response.status}`
      return { step: 'create-pages-project', status: 'error', detail: message }
    }
    return {
      step: 'create-pages-project',
      status: 'ok',
      detail: `Project "${cafeSlug}" created — Cloudflare will build and deploy from ${githubOwner}/${githubRepo}@main.`,
    }
  } catch (e) {
    return { step: 'create-pages-project', status: 'error', detail: String(e) }
  }
}

function json(body: ProvisionResponse, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}
