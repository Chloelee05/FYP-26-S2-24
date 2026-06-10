/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        primary: {
          50:  '#eff6ff',
          100: '#dbeafe',
          500: '#6B8FD4',
          600: '#5B7BC4',
          700: '#4B6BB4',
        },
      },
    },
  },
  plugins: [],
}

