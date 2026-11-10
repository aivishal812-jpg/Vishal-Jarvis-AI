import { defineConfig } from 'vite';
export default defineConfig({
  server: {
    host: '0.0.0.0', port: 5180, strictPort: true,
    allowedHosts: ['.e2b.app', 'localhost'],
    cors: true,
    fs: { allow: ['..'] },
  },
});
