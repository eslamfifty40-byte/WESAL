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
      mode: isDevMode ? "development" : "production",
    });
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

