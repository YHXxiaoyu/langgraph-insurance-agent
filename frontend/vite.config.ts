import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_');
  const backendHost = env.VITE_BACKEND_HOST || '127.0.0.1';
  // 阶段 6 起前端只认 Java：业务、鉴权、SSE 全部经 8080 中转，
  // Python 只在服务端被 Java 内部调用，浏览器不再直连 8001
  const backendPort = env.VITE_BACKEND_PORT || '8080';

  return {
    plugins: [react()],
    server: {
      port: 5173,
      proxy: {
        '/api': {
          target: `http://${backendHost}:${backendPort}`,
          changeOrigin: true,
        },
      },
    },
  };
});
