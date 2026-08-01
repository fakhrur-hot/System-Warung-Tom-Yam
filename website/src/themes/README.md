# Theme Presets

Six style archetypes for café builds. One swap in `tailwind.config.js` recolours every screen — no component changes required.

## How the colour system works

The website uses Tailwind's `emerald` scale everywhere (`bg-emerald-50`, `text-emerald-900`, `bg-emerald-600`, etc.). `tailwind.config.js` overrides that scale with a café-specific ramp. Swapping the ramp is the entire theming mechanism.

| Shade | Role |
|-------|------|
| `50`  | Page background, input tint |
| `100` | Containers, chips |
| `600` | Primary accent — buttons, active states |
| `900` | Headings, primary text |

## Available presets

| Preset | File | Description |
|--------|------|-------------|
| 🌶 Tom Yam | `tom-yam.js` | Deep red — the default |
| 🎩 Luxury | `luxury.js` | Warm ivory + antique gold |
| 💎 Elegant | `elegant.js` | Champagne + rose gold |
| ⬜ Minimalist | `minimalist.js` | Pure white + charcoal |
| 🔥 Bold | `bold.js` | Cobalt blue + electric |
| 🌸 Soft | `soft.js` | Blush pink + lavender |
| ⚡ Edgy | `edgy.js` | Charcoal + electric lime |

## Switching themes at build time

Edit `tailwind.config.js` to import a preset:

```js
// tailwind.config.js
import luxury from './src/themes/tailwind-presets/luxury'

export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        emerald: luxury,  // ← swap to any preset
      },
    },
  },
}
```

## Using the TypeScript definitions

`presets.ts` exports typed ramp objects for use in any tooling (preview pages, Storybook, documentation):

```ts
import { themes, themePresets } from './presets'

// Typed ramp for a specific theme
const gold = themes.luxury[600]  // '#D4AF37'

// Array of all presets (name + description + ramp)
themePresets.forEach(p => console.log(p.name, p.description))
```

## APK counterparts

Each preset has a matching `colors.xml` in:
```
apk/app/src/main/res/values/themes/colors-{name}.xml
```

To apply an APK theme, copy the file contents into `res/values/colors.xml`
(or supply the file via a café profile source set — see `CAFE_PROFILE_DIR` in `local.properties`).
The resource names (`tom_yam_50` etc.) are kept stable so no Kotlin code changes are required.
