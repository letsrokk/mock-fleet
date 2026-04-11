import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  base: "/__fleet/",
  plugins: [react()],
  build: {
    outDir: "dist",
    emptyOutDir: true,
    rollupOptions: {
      output: {
        entryFileNames: "__fleet/assets/[name]-[hash].js",
        chunkFileNames: "__fleet/assets/[name]-[hash].js",
        assetFileNames: "__fleet/assets/[name]-[hash][extname]"
      }
    }
  }
});
