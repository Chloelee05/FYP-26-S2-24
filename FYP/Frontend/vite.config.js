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
        // Required for SSE (Server-Sent Events) streams: disable buffering so
        // event frames are forwarded to the browser as soon as Tomcat writes them.
        configure: (proxy) => {
          proxy.on('proxyRes', (proxyRes) => {
            const ct = proxyRes.headers['content-type'] || '';
            if (ct.includes('text/event-stream')) {
              proxyRes.headers['x-accel-buffering'] = 'no';
              proxyRes.headers['cache-control'] = 'no-cache, no-transform';
            }
          });
        },
      },
      '/uploads': {
        target: 'http://localhost:8080/online-auction',
        changeOrigin: true,
      },
    },
  },
}))
