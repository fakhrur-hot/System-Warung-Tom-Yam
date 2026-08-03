# Menu presets

Starter menus a café can adopt from **Admin Settings → Load sample menu**.

Two sources, same format:

- **Bundled** — `apk/app/src/main/assets/presets/`. Always available, works with no internet, and
  the only source a LAN or Kiosk café ever sees.
- **Published** — this folder, served over raw GitHub. Cloud Mode only, because off-cloud modes have
  no route to the internet by design (`NoInternetGuard`). A café can point at its own fork by
  editing `MenuPresetCatalog.REMOTE_INDEX_URL`.

## Adding a preset

1. Drop `your-menu.json` in the relevant folder.
2. Regenerate `index.json` beside it — the app reads the index, never the directory listing, so a
   file that is not indexed is invisible.

A preset that repeats a bundled `presetId` is ignored in favour of the bundled copy, which needs no
download and cannot fail halfway through setup.

## Format

```jsonc
{
  "presetId":   "tomyam-full",        // stable; also the dedupe key
  "presetName": "Tom Yam — full menu",
  "description": "…",                  // shown on the confirmation step
  "cuisine":    "Thai-Malay",
  "categories": [ { "name": "SAYUR", "sortOrder": 0 } ],
  "items":      [ { "id": "…", "code": "S01", "category": "SAYUR", "price": 8,
                    "name": { "en": "…", "bm": "…", "zh": "…", "ta": "…", "th": "…" } } ]
}
```

Loading a preset **replaces** the café's menu and category order, then pushes the snapshot to the
backend — so item names should carry all five locales, or that café's staff lose their language.
