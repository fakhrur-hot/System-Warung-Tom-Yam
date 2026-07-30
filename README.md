# Tani Tom Yam — Café Deployment

This branch (`tani-tom-yam`) is the **production build for the Tani Tom Yam café** — one
concrete deployment of the generic [RAZ POS template](../README.md), built from the same
shared source via a `git worktree` so fixes and features merge cleanly in both directions.

For the product's general architecture, features, and free-tier stack, see the
[`main` branch README](https://github.com/fakhrur-hot/System-Warung-Tom-Yam/blob/main/README.md).
This file only covers what's specific to **this** café's deployment.

## What's different here vs. `main`

| | `main` (template) | `tani-tom-yam` (this branch) |
|---|---|---|
| App name / branding | Neutral "RAZ POS", indigo theme | "Tani Tom Yam" branding, deep-red `#9B0600` theme |
| `applicationId` | `com.razstudio.pos` (default) | `com.warungtomyam.pos` — set via this checkout's local `apk/local.properties` `APPLICATION_ID`, so the installed app keeps its identity, signing, and update path |
| Backend config | Unconfigured — filled in via the in-app Setup screen | Baked in at build time via this checkout's local `apk/local.properties` (git-ignored) |
| Menu preset | Generic sample menu | Tani's real 16-category, ~140-item menu (`apk/app/src/main/assets/presets/tani-tom-yam.json`) |
| Website | Generic default `<slug>.pages.dev` | `tani-tom-yam.pages.dev` |

The Kotlin **source package stays `com.razstudio.pos` here too** — only the branding assets,
bundled menu preset, and this checkout's local build config differ from `main`. This is
deliberate: identical source keeps every future fix mergeable without file-move or import
conflicts (see the template README's "Package / application identity" section).

## Building this deployment

```bash
cd tani-tom-yam/apk
# local.properties (git-ignored) already carries Tani's SUPABASE_URL / SUPABASE_ANON_KEY /
# WEBSITE_URL / APPLICATION_ID=com.warungtomyam.pos for this checkout
./gradlew assembleDebug      # unsigned debug build
./gradlew assembleRelease    # signed FOSS release (needs keystore.properties)
```

Current release: see `versionCode`/`versionName` in `apk/app/build.gradle.kts`.

## Everything else

Architecture, feature list, RBAC model, free-tier stack rationale, repo layout, and the
BYOI provisioning wizard all live in the [`main` branch README](../README.md) — this
deployment shares all of it.
