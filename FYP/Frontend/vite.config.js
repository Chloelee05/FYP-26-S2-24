import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig(({ mode }) => ({
  base: process.env.VITE_BASE || (mode === 'production' ? '/online-auction/' : '/'),
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080/online-auction',
        changeOrigin: true,
      },
      '/uploads': {
        target: 'http://localhost:8080/online-auction',
        changeOrigin: true,
      },
    },
  },
}))
