/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        // Brand accent: "Tom Yam" striking deep red, anchored on #9B0600 at 600 (the primary
        // accent used for buttons/active tabs/headings). The whole app was authored on Tailwind's
        // `emerald` scale, so overriding that scale here recolors every screen from one place.
        // Fully reversible — delete this `emerald` block to restore the original green.
        emerald: {
          50: '#FEF3F1',
          100: '#FADEDB',
          200: '#F0B2AC',
          300: '#E0786E',
          400: '#C83C30',
          500: '#B0160C',
          600: '#9B0600',
          700: '#7A0500',
          800: '#5C0400',
          900: '#400200',
        },
      },
    },
  },
  plugins: [],
}
