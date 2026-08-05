/**
 * Theme preset definitions for the café ordering website.
 *
 * Each preset maps a 10-shade ramp (50–900) that overrides Tailwind's `emerald` scale.
 * The app was authored on `emerald-*` everywhere, so swapping the scale recolours every
 * screen from one place — no component changes required.
 *
 * How to switch themes at build time:
 *   1. Import the desired preset from this file into tailwind.config.js
 *   2. Spread it as the `emerald` key in theme.extend.colors
 *
 * @example
 *   // tailwind.config.js
 *   import { themes } from './src/themes/presets'
 *   export default { theme: { extend: { colors: { emerald: themes.luxury } } } }
 */

export interface ThemeRamp {
  50: string
  100: string
  200: string
  300: string
  400: string
  500: string
  600: string  // primary accent — buttons, active states
  700: string  // hover / pressed
  800: string
  900: string  // headings / primary text
}

export interface ThemePreset {
  name: string
  description: string
  ramp: ThemeRamp
}

export const themes: Record<string, ThemeRamp> = {

  /**
   * 🎩 LUXURY — Warm ivory backgrounds with antique gold accents.
   * Opulent, high-end feel. Suitable for premium dining establishments.
   */
  luxury: {
    50:  '#F8F4E8',  // warm ivory page background
    100: '#EDE4CC',  // champagne containers
    200: '#D4C4A0',  // sand
    300: '#B8A070',  // bronze
    400: '#9C8448',  // dark bronze
    500: '#C9A227',  // antique gold
    600: '#D4AF37',  // GOLD — primary buttons, active states
    700: '#B8941F',  // deep gold hover
    800: '#2C1810',  // dark mahogany
    900: '#1A0F0A',  // rich dark brown headings
  },

  /**
   * 💎 ELEGANT — Ivory/champagne backgrounds with rose gold accents.
   * Refined, timeless. Suits upscale cafés and fine dining.
   */
  elegant: {
    50:  '#FDF8F5',  // near-white ivory background
    100: '#F5EBE0',  // warm white containers
    200: '#E8D5C4',  // pale sand
    300: '#D4B8A0',  // blush sand
    400: '#BF9880',  // warm terracotta
    500: '#B07B6A',  // dusty rose
    600: '#B5706A',  // rose gold primary
    700: '#9C5B55',  // deep rose hover
    800: '#3D2420',  // dark rosewood
    900: '#2A1815',  // rich espresso headings
  },

  /**
   * ⬜ MINIMALIST — Pure white with crisp charcoal.
   * Clean, uncluttered. Suits modern, design-forward cafés.
   */
  minimalist: {
    50:  '#FAFAFA',  // near-white background
    100: '#F4F4F5',  // light grey containers
    200: '#E4E4E7',  // soft border
    300: '#D1D1D6',  // muted line
    400: '#A1A1AA',  // secondary text light
    500: '#71717A',  // secondary text
    600: '#27272A',  // charcoal primary — buttons, active states
    700: '#18181B',  // near-black hover
    800: '#09090B',  // ink
    900: '#000000',  // pure black headings
  },

  /**
   * 🔥 BOLD — Vibrant cobalt blue with electric accents.
   * High contrast, energetic. Suits fast-casual and street food venues.
   */
  bold: {
    50:  '#EFF6FF',  // light blue background
    100: '#DBEAFE',  // sky containers
    200: '#BFDBFE',  // pale cobalt
    300: '#93C5FD',  // soft blue
    400: '#60A5FA',  // medium blue
    500: '#3B82F6',  // vivid blue
    600: '#1D4ED8',  // cobalt primary — buttons, active states
    700: '#1E3A8A',  // deep navy hover
    800: '#1E2A5E',  // dark indigo
    900: '#0F172A',  // near-black navy headings
  },

  /**
   * 🌸 SOFT — Blush pink with lavender undertones.
   * Gentle, romantic. Suits dessert cafés, patisseries and brunch spots.
   */
  soft: {
    50:  '#FDF2F8',  // pale blush background
    100: '#FCE7F3',  // light pink containers
    200: '#FBCFE8',  // soft pink
    300: '#F9A8D4',  // medium pink
    400: '#F472B6',  // warm pink
    500: '#EC4899',  // vivid pink
    600: '#BE185D',  // deep rose primary — buttons, active states
    700: '#9D174D',  // dark rose hover
    800: '#831843',  // plum
    900: '#500724',  // deep plum headings
  },

  /**
   * ⚡ EDGY — Dark charcoal with electric lime-green accents.
   * Sharp, rebellious. Suits underground cafés, late-night spots, and streetwear-adjacent brands.
   */
  edgy: {
    50:  '#F0FDF4',  // very pale lime tint (light bg keeps app readable)
    100: '#DCFCE7',  // pale green containers
    200: '#BBF7D0',  // light lime
    300: '#86EFAC',  // medium lime
    400: '#4ADE80',  // electric green
    500: '#22C55E',  // vivid green
    600: '#16A34A',  // neon green primary — buttons, active states
    700: '#15803D',  // deep green hover
    800: '#1C1C1C',  // dark charcoal
    900: '#0A0A0A',  // near-black headings
  },

  /**
   * 🌶 TOM YAM (default) — Spicy orange-red broth with fresh herb garnish.
   * The house default shipped with every template build. The ramp moves from creamy
   * broth lights through chilli-orange mids to a deep, savoury red-brown, so the
   * whole UI reads "Tom Yam" rather than flat crimson.
   */
  tomYam: {
    50:  '#FFF5F0',  // creamy broth page background
    100: '#FFE6D9',  // light peach containers
    200: '#FFC4B0',  // soft coral
    300: '#FF9B7A',  // fresh tomato
    400: '#F36B44',  // spicy orange-red
    500: '#D94824',  // chilli
    600: '#B52D10',  // Tom Yam red — primary accent
    700: '#8F210A',  // deep chilli hover
    800: '#691808',  // dark
    900: '#430F05',  // rich red-brown headings
  },
}

export const themePresets: ThemePreset[] = [
  { name: 'tomYam',     description: '🌶 Tom Yam — deep red (default)',            ramp: themes.tomYam },
  { name: 'luxury',     description: '🎩 Luxury — warm ivory and antique gold',    ramp: themes.luxury },
  { name: 'elegant',    description: '💎 Elegant — champagne and rose gold',       ramp: themes.elegant },
  { name: 'minimalist', description: '⬜ Minimalist — pure white and charcoal',    ramp: themes.minimalist },
  { name: 'bold',       description: '🔥 Bold — cobalt blue and electric',         ramp: themes.bold },
  { name: 'soft',       description: '🌸 Soft — blush pink and lavender',          ramp: themes.soft },
  { name: 'edgy',       description: '⚡ Edgy — charcoal and electric lime',       ramp: themes.edgy },
]
