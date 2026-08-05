import tomYam from './src/themes/tailwind-presets/tom-yam'

/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        // Brand accent: "Tom Yam" spicy orange-red broth. The whole app was authored on
        // Tailwind's `emerald` scale, so overriding that scale here recolours every screen
        // from one place. Fully reversible — delete this `emerald` block to restore green.
        emerald: tomYam,

        // Fresh herb / lime garnish accent for highlights on customer-facing pages.
        // Used sparingly for things that should feel like the coriander/lime on top of Tom Yam.
        herb: {
          50:  '#F7FEE7',
          100: '#ECFCCB',
          200: '#D9F99D',
          300: '#BEF264',
          400: '#A3E635',
          500: '#84CC16',
          600: '#65A30D',
          700: '#4D7C0F',
          800: '#3F6212',
          900: '#365314',
        },
      },
    },
  },
  plugins: [],
}
