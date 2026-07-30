/**
 * Shapes shared across the /api/provision/* Cloudflare Pages Functions. Duplicated (not
 * cross-imported) into src/types.ts for the frontend, since Pages Functions and the Vite frontend
 * are two separate build contexts in Cloudflare's model — a real cross-directory import between
 * them is a bundler-fragility risk not worth taking for a handful of small interfaces.
 */

/** One result row per underlying operation (one migration file, one function, etc.) — see
 * design.md Correctness Property 2: no endpoint returns a single pass/fail for a batch. */
export interface StepResult {
  step: string
  status: 'ok' | 'error' | 'skipped'
  detail?: string
}

export interface ProvisionResponse {
  results: StepResult[]
}

/** Minimal Cloudflare Pages Function context — avoids depending on @cloudflare/workers-types
 * for the handful of fields these endpoints actually touch. */
export interface PagesContext {
  request: Request
  env: Record<string, string>
}
