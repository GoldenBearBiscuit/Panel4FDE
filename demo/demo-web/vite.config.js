import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

// /api 与 /observe 都代理到 app 容器：
//   1. 浏览器同源 → 不需要处理 CORS
//   2. traceparent 等自定义头 normal 转发到后端
const proxy = {
  target: 'http://app:8080',
  changeOrigin: false,
};

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '0.0.0.0',
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': proxy,
      '/observe': proxy,
    },
    // ★ 必须开轮询：Windows 宿主机修改文件不会触发 Linux 容器内的 inotify，
    //   不轮询则 HMR 完全失效（改了代码页面不变，且无任何报错）
    watch: {
      usePolling: true,
      interval: 400,
    },
  },
});
