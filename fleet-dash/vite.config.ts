import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import pkg from "./package.json";

export default defineConfig({
  base: "/__fleet/",
  define: {
    __APP_VERSION__: JSON.stringify(pkg.version)
  },
  plugins: [react()],
  server: {
    proxy: {
      "/__fleet/api": {
        target: "http://localhost:8081",
        changeOrigin: true
      },
      "/__fleet/proxy": {
        target: "http://localhost:8080",
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: "dist",
    emptyOutDir: true,
    rollupOptions: {
      output: {
        entryFileNames: "assets/[name]-[hash].js",
        chunkFileNames: "assets/[name]-[hash].js",
        assetFileNames: "assets/[name]-[hash][extname]"
      }
    }
  }
});
