import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    // コンテナからWindowsのバインドマウントを見る場合、ファイル監視イベントが届かないことがある。
    // ホットリロードが効かないときだけ VITE_USE_POLLING=1 でポーリングへ切り替える。
    watch: process.env.VITE_USE_POLLING ? { usePolling: true } : undefined,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/hello': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
