import "dotenv/config";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import express, { type Request, type Response } from "express";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

function resolveCliPortArg(): number | null {
  const args = process.argv.slice(2);
  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (arg === "--port" && args[i + 1]) {
      const parsed = Number.parseInt(args[i + 1], 10);
      if (Number.isInteger(parsed) && parsed > 0 && parsed <= 65535) return parsed;
    } else if (arg.startsWith("--port=")) {
      const parsed = Number.parseInt(arg.slice("--port=".length), 10);
      if (Number.isInteger(parsed) && parsed > 0 && parsed <= 65535) return parsed;
    }
  }
  return null;
}

function resolvePort(isDevMode: boolean): number {
  const cliPort = resolveCliPortArg();
  if (cliPort !== null) return cliPort;
  const rawPort = isDevMode
    ? process.env.DEFAULT_APP_PORT
    : process.env.PORT ?? process.env.DEFAULT_APP_PORT;
  const parsed = Number.parseInt(String(rawPort ?? "").trim(), 10);
  if (Number.isInteger(parsed) && parsed > 0 && parsed <= 65535) {
    return parsed;
  }
  return 3000;
}

const isDevMode =
  process.env.NODE_ENV !== "production" && !process.env.CLOUD_RUN_JOB;
const PORT = resolvePort(isDevMode);
const HOST = "0.0.0.0";
const distPath = path.resolve(__dirname, "dist");
const publicPath = path.resolve(__dirname, "public");

const DEFAULT_DEBUG_SHA256 =
  "D5:96:80:F8:72:2A:AE:9B:C3:C7:44:A5:F5:B4:A9:57:61:E0:08:C0:02:05:F7:EA:BA:79:4B:D8:D9:27:DC:0D";

function resolveApkDownloadUrl(): string | null {
  const explicit = String(process.env.WESAL_APK_DOWNLOAD_URL ?? "").trim();
  if (explicit.startsWith("https://") || explicit.startsWith("http://")) {
    return explicit;
  }
  const repo = String(process.env.WESAL_GITHUB_REPO ?? "")
    .trim()
    .replace(/^https?:\/\/github\.com\//i, "")
    .replace(/\/+$/, "");
  if (/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repo)) {
    return `https://github.com/${repo}/releases/latest/download/WESAL.apk`;
  }
  return null;
}

function resolveFingerprints(): string[] {
  const raw = String(process.env.WESAL_ANDROID_SHA256_FINGERPRINTS ?? "").trim();
  const parsed = raw
    .split(",")
    .map((s) => s.trim().toUpperCase())
    .filter((s) => /^([0-9A-F]{2}:){31}[0-9A-F]{2}$/.test(s));
  return parsed.length > 0 ? parsed : [DEFAULT_DEBUG_SHA256];
}

function sanitizeRefParam(raw: unknown): string {
  return String(raw ?? "")
    .trim()
    .replace(/^@+/, "")
    .replace(/[^A-Za-z0-9_.-]/g, "")
    .slice(0, 64);
}

function escapeHtml(raw: string): string {
  return raw
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;");
}

function renderInviteLandingHtml(opts: {
  kind: "join" | "channel";
  code: string;
  apkUrl: string;
}): string {
  const safeCode = escapeHtml(opts.code || "10001");
  const deepLink =
    opts.kind === "channel"
      ? `wesal://channel?code=${encodeURIComponent(opts.code)}`
      : `wesal://join?id=${encodeURIComponent(opts.code)}`;
  const safeDeepLink = escapeHtml(deepLink);
  const safeApkUrl = escapeHtml(opts.apkUrl);
  const title =
    opts.kind === "channel"
      ? `دعوة قناة WESAL • ${safeCode}`
      : `دعوة تواصل WESAL Messenger • ID ${safeCode}`;
  const badgeLabel =
    opts.kind === "channel"
      ? `رمز القناة: ${safeCode}`
      : `معرّف WESAL: ${safeCode}`;
  const headline =
    opts.kind === "channel"
      ? "انضم إلى القناة على WESAL Messenger"
      : "تواصل معي مباشرة على WESAL Messenger";
  const subtitle =
    opts.kind === "channel"
      ? "Open this channel directly inside WESAL Messenger, or download the Android APK below if the app is not installed yet."
      : "افتح هذا الحساب مباشرة داخل تطبيق WESAL Messenger، أو قم بتنزيل ملف التطبيق لأجهزة أندرويد بالأسفل.";

  return `<!doctype html>
<html lang="ar" dir="rtl">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>${title}</title>
  <style>
    :root {
      color-scheme: dark;
      --bg: #070e10;
      --surface: #0f1b1f;
      --border: #1e373d;
      --emerald: #10b981;
      --turquoise: #00d9e8;
      --text: #f3f7f8;
      --muted: #9eb3b8;
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      min-height: 100vh;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 20px;
      background: radial-gradient(circle at top, rgba(16,185,129,0.16), transparent 55%), var(--bg);
      color: var(--text);
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
    }
    .card {
      width: 100%;
      max-width: 440px;
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: 22px;
      padding: 28px 22px;
      text-align: center;
      box-shadow: 0 24px 60px rgba(0, 0, 0, 0.45);
    }
    .logo {
      width: 72px;
      height: 72px;
      border-radius: 20px;
      margin: 0 auto 14px;
      display: grid;
      place-items: center;
      background: linear-gradient(135deg, rgba(16,185,129,0.22), rgba(0,217,232,0.18));
      border: 1px solid rgba(0,217,232,0.35);
      font-weight: 800;
      font-size: 26px;
      letter-spacing: 1px;
      color: var(--turquoise);
    }
    .pill {
      display: inline-block;
      padding: 6px 14px;
      border-radius: 999px;
      background: rgba(16,185,129,0.14);
      border: 1px solid rgba(16,185,129,0.35);
      color: var(--emerald);
      font-size: 13px;
      font-weight: 700;
      margin-bottom: 12px;
      direction: ltr;
    }
    h1 {
      margin: 0 0 10px;
      font-size: 20px;
      line-height: 1.4;
    }
    p {
      margin: 0 0 22px;
      color: var(--muted);
      font-size: 14px;
      line-height: 1.6;
    }
    .btn {
      display: block;
      width: 100%;
      padding: 14px 18px;
      border-radius: 14px;
      font-size: 15px;
      font-weight: 700;
      text-decoration: none;
      margin-bottom: 10px;
    }
    .btn-primary {
      background: linear-gradient(135deg, var(--emerald), #059669);
      color: #04120e;
    }
    .btn-secondary {
      background: rgba(0,217,232,0.10);
      border: 1px solid rgba(0,217,232,0.35);
      color: var(--turquoise);
    }
  </style>
</head>
<body>
  <main class="card">
    <div class="logo">W</div>
    <div class="pill">${badgeLabel}</div>
    <h1>${headline}</h1>
    <p>${subtitle}</p>
    <a class="btn btn-primary" href="${safeDeepLink}">فتح في تطبيق WESAL / Open in WESAL</a>
    <a class="btn btn-secondary" href="${safeApkUrl}">تنزيل تطبيق WESAL (APK)</a>
  </main>
  <script>
    setTimeout(function () {
      try {
        window.location.href = ${JSON.stringify(deepLink)};
      } catch (_) {}
    }, 250);
  </script>
</body>
</html>`;
}

async function startServer(): Promise<void> {
  const app = express();
  app.disable("x-powered-by");
  app.use(express.json({ limit: "2mb" }));

  // Dev vs Production mode is determined at module level from NODE_ENV

  app.get(["/health", "/healthz", "/api/health"], (_req: Request, res: Response) => {
    res.status(200).json({
      ok: true,
      service: "wesal-portal",
      status: "healthy",
      port: PORT,
      apkConfigured: Boolean(resolveApkDownloadUrl()),
      mode: isDevMode ? "development" : "production",
    });
  });

  // Android App Links verification for com.wesal.messenger
  app.get("/.well-known/assetlinks.json", (_req: Request, res: Response) => {
    res.setHeader("Content-Type", "application/json; charset=utf-8");
    res.setHeader("Cache-Control", "public, max-age=300");
    res.status(200).json([
      {
        relation: ["delegate_permission/common.handle_all_urls"],
        target: {
          namespace: "android_app",
          package_name: "com.wesal.messenger",
          sha256_cert_fingerprints: resolveFingerprints(),
        },
      },
    ]);
  });

  // Invite landing target (/join?id=<wesalId>):
  // If WESAL is installed on Android, App Links open the app directly.
  // If opened in a browser, shows Arabic+English RTL invite page with deep link + APK download fallback.
  app.get(["/join", "/join/:id"], (req: Request, res: Response) => {
    const refId = sanitizeRefParam(req.query.id ?? req.params.id);
    if (refId) {
      res.cookie("wesal_ref_id", refId, {
        maxAge: 30 * 24 * 60 * 60 * 1000,
        httpOnly: false,
        sameSite: "lax",
        path: "/",
      });
    }
    const apkUrl = resolveApkDownloadUrl() ?? "/download/app-debug.apk";
    res.setHeader("Content-Type", "text/html; charset=utf-8");
    res.setHeader("Cache-Control", "no-store");
    res.status(200).send(renderInviteLandingHtml({ kind: "join", code: refId, apkUrl }));
  });

  // Channel invite landing (/channel?code=<code>)
  app.get(["/channel", "/channel/:code"], (req: Request, res: Response) => {
    const code = sanitizeRefParam(req.query.code ?? req.params.code);
    if (code) {
      res.cookie("wesal_channel_ref", code, {
        maxAge: 30 * 24 * 60 * 60 * 1000,
        httpOnly: false,
        sameSite: "lax",
        path: "/",
      });
    }
    const apkUrl = resolveApkDownloadUrl() ?? "/download/app-debug.apk";
    res.setHeader("Content-Type", "text/html; charset=utf-8");
    res.setHeader("Cache-Control", "no-store");
    res.status(200).send(renderInviteLandingHtml({ kind: "channel", code, apkUrl }));
  });

  const apkOutputPath = path.resolve(
    __dirname,
    "WesalAndroid/app/build/outputs/apk/debug/app-debug.apk"
  );
  const zipArchivePath = path.resolve(__dirname, "WesalAndroid.zip");

  app.get("/api/release-info", (_req: Request, res: Response) => {
    const zipStat = fs.existsSync(zipArchivePath) ? fs.statSync(zipArchivePath) : null;
    const apkStat = fs.existsSync(apkOutputPath) ? fs.statSync(apkOutputPath) : null;
    res.setHeader("Cache-Control", "no-store");
    res.status(200).json({
      zipAvailable: Boolean(zipStat && zipStat.size > 0),
      zipSizeBytes: zipStat?.size ?? 0,
      apkAvailable: Boolean(apkStat && apkStat.size > 0),
      apkSizeBytes: apkStat?.size ?? 0,
      packageName: "com.wesal.messenger",
    });
  });

  app.get("/WesalAndroid.zip", (_req: Request, res: Response) => {
    if (!fs.existsSync(zipArchivePath)) {
      res.status(404).json({ ok: false, error: "WesalAndroid.zip not found" });
      return;
    }
    res.setHeader("Content-Type", "application/zip");
    res.download(zipArchivePath, "WesalAndroid.zip");
  });

  app.get("/download/app-debug.apk", (_req: Request, res: Response) => {
    if (!fs.existsSync(apkOutputPath)) {
      res.status(404).json({ ok: false, error: "app-debug.apk is not built yet" });
      return;
    }
    res.setHeader("Content-Type", "application/vnd.android.package-archive");
    res.download(apkOutputPath, "WESAL-Messenger-debug.apk");
  });

  let viteMiddlewarePromise: Promise<(req: Request, res: Response, next: express.NextFunction) => void> | null = null;

  if (isDevMode) {
    viteMiddlewarePromise = import("vite").then(async ({ createServer: createViteServer }) => {
      const vite = await createViteServer({
        server: {
          middlewareMode: true,
          hmr: false,
          watch: null,
        },
        appType: "spa",
      });
      return vite.middlewares as unknown as (
        req: Request,
        res: Response,
        next: express.NextFunction
      ) => void;
    });

    app.use((req: Request, res: Response, next) => {
      if (!viteMiddlewarePromise) {
        next();
        return;
      }
      viteMiddlewarePromise
        .then((middlewares) => middlewares(req, res, next))
        .catch(next);
    });
  } else {
    const indexHtmlPath = path.join(distPath, "index.html");
    if (!fs.existsSync(indexHtmlPath)) {
      throw new Error(
        `Production build missing at ${indexHtmlPath}. Run "npm run build" before starting the server.`
      );
    }

    app.use(
      express.static(distPath, {
        index: "index.html",
        etag: true,
        maxAge: "1h",
        setHeaders: (res, filePath) => {
          if (filePath.endsWith(".html")) {
            res.setHeader("Cache-Control", "no-cache");
          }
        },
      })
    );

    if (fs.existsSync(publicPath)) {
      app.use(express.static(publicPath, { index: false, maxAge: "1h" }));
    }

    app.use("/api", (_req: Request, res: Response) => {
      res.status(404).json({ ok: false, error: "API route not found" });
    });

    app.use((req: Request, res: Response, next) => {
      if (req.method !== "GET" && req.method !== "HEAD") {
        next();
        return;
      }
      res.sendFile(indexHtmlPath);
    });
  }

  const server = app.listen(PORT, HOST, () => {
    console.log(
      `WESAL server listening on http://${HOST}:${PORT} (mode=${isDevMode ? "development" : "production"})`
    );
  });

  server.on("error", (err: NodeJS.ErrnoException) => {
    console.error(`Failed to start server on ${HOST}:${PORT}:`, err.message);
    process.exit(1);
  });

  const shutdown = (signal: string) => {
    console.log(`Received ${signal}, shutting down WESAL server cleanly...`);
    server.close(() => {
      process.exit(0);
    });
  };

  process.on("SIGTERM", () => shutdown("SIGTERM"));
  process.on("SIGINT", () => shutdown("SIGINT"));
}

startServer().catch((err) => {
  console.error("Fatal startup error:", err);
  process.exit(1);
});
