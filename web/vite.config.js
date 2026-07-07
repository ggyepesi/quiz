import { sveltekit } from '@sveltejs/kit/vite';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [sveltekit()],
  server: {
    // Listen on all interfaces (not just localhost) so a phone on the same
    // Wi-Fi can reach the dev server at http://<mac-lan-ip>:5173. The /api proxy
    // still targets localhost:7070 — Vite runs on the Mac, so that resolves here.
    host: true,
    allowedHosts: true,
    proxy: {
      '/api': {
        target: 'http://localhost:7070',
        changeOrigin: true
      }
    }
  }
});