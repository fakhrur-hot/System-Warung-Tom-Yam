/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        // Brand accent: "Tom Yam" warm red-orange. The whole app was authored on Tailwind's
        // `emerald` scale, so overriding that scale here recolors every screen from one place.
        // Fully reversible — delete this `emerald` block to restore the original green.
        emerald: {
          50: '#FFF4F0',
          100: '#FFE4DA',
          200: '#FFC6B2',
          300: '#FB9E80',
          400: '#F4754E',
          500: '#EC5A34',
          600: '#E8502E',
          700: '#C23F20',
          800: '#98301A',
          900: '#7C2917',
        },
      },
    },
  },
  plugins: [],
}
