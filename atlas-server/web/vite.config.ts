import { defineConfig } from "vite";

export default defineConfig({
  base: "./",
  build: {
    outDir: "../build/web-bundle",
    emptyOutDir: true,
    rollupOptions: {
      input: ["index.html", "entity.html", "file.html", "query.html", "impact.html", "text.html", "projects.html"]
    }
  },
  server: {
    // `zpa-atlas serve` on its default port.
    proxy: { "/api": "http://127.0.0.1:8080" }
  }
});
