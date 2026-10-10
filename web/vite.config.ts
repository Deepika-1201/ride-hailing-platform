import { createReadStream, existsSync, statSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import react from "@vitejs/plugin-react";
import type { Plugin } from "vite";
import { defineConfig } from "vitest/config";

// In development the platform runs on :8080 and the map files are in ../.maps (scripts/setup-maps.sh); the dev
// server proxies the one and serves the other, so the app always talks to its own origin, as behind nginx.
const platform = process.env.PLATFORM_URL ?? "http://localhost:8080";
const maps = fileURLToPath(new URL("../.maps", import.meta.url));

/** Serves /maps/* from ../.maps with byte ranges, which PMTiles reads tiles by. */
function serveMaps(): Plugin {
  return {
    name: "serve-maps",
    configureServer(server) {
      server.middlewares.use("/maps", (req, res, next) => {
        const file = resolve(maps, "." + decodeURIComponent((req.url ?? "/").split("?")[0]));
        if (!file.startsWith(maps + "/") || !existsSync(file) || !statSync(file).isFile()) {
          return next();
        }
        const size = statSync(file).size;
        const range = /^bytes=(\d+)-(\d*)$/.exec(req.headers.range ?? "");
        res.setHeader("Accept-Ranges", "bytes");
        if (!range) {
          res.setHeader("Content-Length", size);
          createReadStream(file).pipe(res);
          return;
        }
        const start = Number(range[1]);
        const end = Math.min(range[2] ? Number(range[2]) : size - 1, size - 1);
        if (start > end) {
          res.statusCode = 416;
          res.setHeader("Content-Range", `bytes */${size}`);
          res.end();
          return;
        }
        res.statusCode = 206;
        res.setHeader("Content-Range", `bytes ${start}-${end}/${size}`);
        res.setHeader("Content-Length", end - start + 1);
        createReadStream(file, { start, end }).pipe(res);
      });
    },
  };
}

export default defineConfig({
  plugins: [react(), serveMaps()],
  // MapLibre alone is about 1 MB minified.
  build: { chunkSizeWarningLimit: 1600 },
  server: {
    port: 5173,
    proxy: {
      "/v1": platform,
      "/ws": { target: platform.replace(/^http/, "ws"), ws: true },
    },
  },
  test: {
    environment: "node",
    include: ["src/**/*.test.{ts,tsx}"],
  },
});
