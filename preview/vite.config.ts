import { defineConfig } from 'vite';

/**
 * Scratch stage for looking at the mobile UI in a browser. It is not part of the APK build; the
 * Android build runs `vite.mobile.config.ts` on its own.
 *
 *  /hud.html        - the real app shell (main.ts) with the full-screen crimson HUD and mic dock
 *  /character.html  - the bare 3D character, for checking the model in isolation
 */
export default defineConfig({
  plugins: [
    {
      name: 'open-hud-at-root',
      configureServer(server) {
        server.middlewares.use((req, _res, next) => {
          if (req.url === '/' || req.url === '/index.html') req.url = '/preview/hud.html';
          next();
        });
      },
    },
  ],
  server: {
    host: '0.0.0.0', port: 5180, strictPort: true,
    allowedHosts: ['.e2b.app', 'localhost'],
    cors: true,
    fs: { allow: ['..'] },
  },
});
