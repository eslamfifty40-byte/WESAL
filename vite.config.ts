import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import fs from 'node:fs';
import path from 'node:path';
import type {IncomingMessage, ServerResponse} from 'node:http';
import {defineConfig, type Plugin} from 'vite';

function serveRootArchivePlugin(): Plugin {
  const archivePath = path.resolve(import.meta.dirname, 'WesalAndroid.zip');
  const handler = (
    req: IncomingMessage,
    res: ServerResponse,
    next: () => void,
  ) => {
    const reqUrl = (req.url ?? '').split('?')[0];
    if (reqUrl !== '/WesalAndroid.zip' || !fs.existsSync(archivePath)) {
      next();
      return;
    }
    const stat = fs.statSync(archivePath);
    res.statusCode = 200;
    res.setHeader('Content-Type', 'application/zip');
    res.setHeader('Content-Length', String(stat.size));
    res.setHeader(
      'Content-Disposition',
      'attachment; filename="WesalAndroid.zip"',
    );
    fs.createReadStream(archivePath).pipe(res);
  };

  return {
    name: 'serve-wesal-archive',
    configureServer(server) {
      server.middlewares.use(handler);
    },
    configurePreviewServer(server) {
      server.middlewares.use(handler);
    },
  };
}

export default defineConfig(() => {
  return {
    plugins: [react(), tailwindcss(), serveRootArchivePlugin()],
    resolve: {
      alias: {
        '@': path.resolve(import.meta.dirname, '.'),
      },
    },
    build: {
      sourcemap: false,
    },
    server: {
      // HMR and file watching are disabled to avoid memory/CPU spikes during Android builds.
      hmr: false,
      watch: null,
    },
    preview: {
      host: '0.0.0.0',
      port: Number(process.env.PORT) || 3000,
      allowedHosts: true as const,
    },
  };
});
