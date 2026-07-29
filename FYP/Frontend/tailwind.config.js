/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      fontFamily: {
        sans: ['Inter', 'system-ui', '-apple-system', 'Segoe UI', 'Roboto', 'sans-serif'],
        display: ['"Plus Jakarta Sans"', 'Inter', 'system-ui', 'sans-serif'],
      },
      colors: {
        // Brand blue — primary action colour across the marketplace.
        primary: {
          50:  '#eff5ff',
          100: '#dbe8fe',
          200: '#bfd7fe',
          300: '#93bbfd',
          400: '#6098fa',
          500: '#3b76f6',
          600: '#2560eb',
          700: '#1d4dd8',
          800: '#1e40af',
          900: '#1e3a8a',
          950: '#172554',
        },
        // Warm accent used for live / urgent auction states.
        accent: {
          50:  '#fff8eb',
          100: '#ffecc6',
          200: '#ffd688',
          300: '#ffbb4a',
          400: '#ffa020',
          500: '#f97e07',
          600: '#dd5a02',
          700: '#b73d06',
          800: '#94300c',
          900: '#7a290d',
        },
        // Neutral ramp for text and surfaces.
        ink: {
          50:  '#f8fafc',
          100: '#f1f5f9',
          200: '#e2e8f0',
          300: '#cbd5e1',
          400: '#94a3b8',
          500: '#64748b',
          600: '#475569',
          700: '#334155',
          800: '#1e293b',
          900: '#0f172a',
        },
      },
      boxShadow: {
        soft: '0 1px 2px rgba(15, 23, 42, 0.04), 0 8px 24px -12px rgba(15, 23, 42, 0.10)',
        lift: '0 2px 4px rgba(15, 23, 42, 0.04), 0 18px 36px -18px rgba(15, 23, 42, 0.22)',
        pop: '0 12px 40px -12px rgba(15, 23, 42, 0.28)',
      },
      borderRadius: {
        '4xl': '2rem',
      },
      keyframes: {
        'fade-in': {
          from: { opacity: '0' },
          to: { opacity: '1' },
        },
        'fade-up': {
          from: { opacity: '0', transform: 'translateY(8px)' },
          to: { opacity: '1', transform: 'translateY(0)' },
        },
        'scale-in': {
          from: { opacity: '0', transform: 'scale(0.96)' },
          to: { opacity: '1', transform: 'scale(1)' },
        },
        'pulse-dot': {
          '0%, 100%': { opacity: '1', transform: 'scale(1)' },
          '50%': { opacity: '0.45', transform: 'scale(0.82)' },
        },
        // Single light sweep across a dark surface, triggered on hover.
        sheen: {
          from: { transform: 'translateX(-120%) skewX(-12deg)' },
          to: { transform: 'translateX(220%) skewX(-12deg)' },
        },
        // Soft expanding halo behind the "live auctions" indicator.
        'halo-pulse': {
          '0%': { opacity: '0.55', transform: 'scale(1)' },
          '70%, 100%': { opacity: '0', transform: 'scale(2.6)' },
        },
      },
      animation: {
        'fade-in': 'fade-in 0.25s ease-out both',
        'fade-up': 'fade-up 0.32s cubic-bezier(0.22, 1, 0.36, 1) both',
        'scale-in': 'scale-in 0.18s cubic-bezier(0.22, 1, 0.36, 1) both',
        'pulse-dot': 'pulse-dot 1.6s ease-in-out infinite',
        sheen: 'sheen 0.9s cubic-bezier(0.22, 1, 0.36, 1)',
        'halo-pulse': 'halo-pulse 2.4s ease-out infinite',
      },
    },
  },
  plugins: [],
}
