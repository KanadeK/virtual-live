import { defineConfig, loadEnv } from "vite";
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, "..", "");
  return {
    server: {
      port: 5173,
      strictPort: true,
      proxy: {
        "/api": {
          target: `http://127.0.0.1:${env.SERVER_PORT || 8080}`,
          changeOrigin: true,
        },
      },
    },
    preview: { port: 4173 },
  };
});
