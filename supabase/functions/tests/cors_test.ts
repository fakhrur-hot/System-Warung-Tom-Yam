import { assertEquals, assert } from "https://deno.land/std@0.177.0/testing/asserts.ts";

/**
 * Task 5.2 — an unset `WEBSITE_ORIGIN` must be diagnosable, not silently wrong.
 *
 * The old behaviour fell back to `https://your-site.pages.dev`. That is the worst failure shape
 * available: the deployment looks healthy, every function returns 200, and the browser refuses the
 * response later with a generic CORS message naming neither the variable nor the project. On a
 * café-specific branch the placeholder had been edited to a real domain, so a misconfigured
 * deployment could serve **another café's** origin — allowing the wrong site and blocking the right
 * one.
 *
 * `cors.ts` reads the environment at module load, so each case here runs in its own subprocess with
 * `WEBSITE_ORIGIN` set differently. That is heavier than calling a function, and it is the only
 * honest way to test a module-level constant.
 */

const CORS = new URL("../_shared/cors.ts", import.meta.url).href;

async function runWithEnv(env: Record<string, string>, body: string): Promise<string> {
  const cmd = new Deno.Command(Deno.execPath(), {
    args: ["eval", "--quiet", `const m = await import(${JSON.stringify(CORS)}); ${body}`],
    env: { ...env },
    clearEnv: true,
    stdout: "piped",
    stderr: "piped",
  });
  const { stdout, stderr } = await cmd.output();
  return new TextDecoder().decode(stdout) + new TextDecoder().decode(stderr);
}

Deno.test("a set origin is echoed, and trailing slashes are stripped", async () => {
  const out = await runWithEnv(
    { WEBSITE_ORIGIN: "https://cafe.pages.dev/" },
    "console.log(m.corsHeaders['Access-Control-Allow-Origin'])",
  );
  // A browser's Origin header never carries a trailing slash, so an unstripped value matches nothing.
  assertEquals(out.trim(), "https://cafe.pages.dev");
});

Deno.test("an unset origin omits the header entirely rather than guessing", async () => {
  const out = await runWithEnv(
    {},
    "console.log('HEADER=' + ('Access-Control-Allow-Origin' in m.corsHeaders))",
  );
  assert(
    out.includes("HEADER=false"),
    `the header must be absent, not a placeholder. got: ${out}`,
  );
});

Deno.test("an unset origin logs a diagnostic that names the variable", async () => {
  const out = await runWithEnv({}, "void m.corsHeaders");
  assert(out.includes("WEBSITE_ORIGIN"), `diagnostic must name the variable. got: ${out}`);
  assert(
    out.includes("pages.dev"),
    "the diagnostic should show the expected shape of the value",
  );
});

Deno.test("no placeholder café domain survives anywhere in the module", async () => {
  // The regression this whole task exists to prevent: a real or placeholder café domain compiled
  // into shared source, where every café inherits it.
  const source = await Deno.readTextFile(new URL("../_shared/cors.ts", import.meta.url));
  const offenders = ["your-site.pages.dev", "tani-tom-yam"];
  for (const bad of offenders) {
    assert(
      !source.includes(`"https://${bad}"`) && !source.includes(`'https://${bad}'`),
      `${bad} must not appear as a fallback value`,
    );
  }
});

Deno.test("requireWebsiteOrigin throws when unset, so a broken QR is never minted", async () => {
  const out = await runWithEnv(
    {},
    "try { m.requireWebsiteOrigin(); console.log('NO_THROW') } catch (e) { console.log('THREW:' + e.message) }",
  );
  assert(out.includes("THREW:"), `must throw when unset. got: ${out}`);
  assert(out.includes("WEBSITE_ORIGIN"), "the message must name the variable");
});

Deno.test("requireWebsiteOrigin returns the normalised origin when set", async () => {
  const out = await runWithEnv(
    { WEBSITE_ORIGIN: "https://cafe.pages.dev///" },
    "console.log(m.requireWebsiteOrigin())",
  );
  assertEquals(out.trim(), "https://cafe.pages.dev");
});

Deno.test("a whitespace-only origin counts as unset", async () => {
  const out = await runWithEnv(
    { WEBSITE_ORIGIN: "   " },
    "console.log('HEADER=' + ('Access-Control-Allow-Origin' in m.corsHeaders))",
  );
  assert(out.includes("HEADER=false"), `blank must be treated as absent. got: ${out}`);
});
