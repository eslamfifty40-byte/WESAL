import "dotenv/config";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import express, { type Request, type Response } from "express";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

function resolvePort(rawPort: string | undefined): number {
  const parsed = Number.parseInt(String(rawPort ?? "").trim(), 10);
  if (Number.isInteger(parsed) && parsed > 0 && parsed <= 65535) {
    return parsed;
  }
  return 3000;
}

const PORT = resolvePort(process.env.PORT);
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

async function startServer(): Promise<void> {
  const app = express();
  app.disable("x-powered-by");
  app.use(express.json({ limit: "2mb" }));

  const isDevMode =
    process.env.NODE_ENV === "development" &&
    !process.env.K_SERVICE &&
    !process.env.CLOUD_RUN_JOB;

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

  // Single-link WhatsApp invite target:
  // If WESAL is installed on Android, App Links intercept /join?id=<wesalId> directly.
  // If NOT installed, browser hits /join?id=<wesalId> and receives an immediate HTTP 302
  // direct redirect to GitHub Releases WESAL.apk (no intermediate landing page), while storing
  // the inviter's ID in a cookie.
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
    const apkUrl = resolveApkDownloadUrl();
    if (!apkUrl) {
      res.status(503).json({
        ok: false,
        error: "WESAL_GITHUB_REPO or WESAL_APK_DOWNLOAD_URL is not configured yet for direct APK download.",
        inviterId: refId || null,
      });
      return;
    }
    res.setHeader("Cache-Control", "no-store");
    res.redirect(302, apkUrl);
  });

  // Channel invite link redirect (same direct APK fallback when app is not installed)
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
    const apkUrl = resolveApkDownloadUrl();
    if (!apkUrl) {
      res.status(503).json({
        ok: false,
        error: "WESAL_GITHUB_REPO or WESAL_APK_DOWNLOAD_URL is not configured yet for direct APK download.",
        channelCode: code || null,
      });
      return;
    }
    res.setHeader("Cache-Control", "no-store");
    res.redirect(302, apkUrl);
  });

  if (isDevMode) {
    const { createServer: createViteServer } = await import("vite");
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: "spa",
    });
    app.use(vite.middlewares);
  } else {
    const indexHtmlPath = path.join(distPath, "index.html");
    if (!fs.existsSync(indexHtmlPath)) {
      throw new Error(
        `Production build missing at ${indexHtmlPath}. Run "npm run build" before starting the server.`
      );
    }

    app.get("/WesalAndroid.zip", (_req: Request, res: Response) => {
      const zipPath = path.resolve(__dirname, "WesalAndroid.zip");
      if (!fs.existsSync(zipPath)) {
        res.status(404).json({ ok: false, error: "WesalAndroid.zip not found" });
        return;
      }
      res.setHeader("Content-Type", "application/zip");
      res.download(zipPath, "WesalAndroid.zip");
    });

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
