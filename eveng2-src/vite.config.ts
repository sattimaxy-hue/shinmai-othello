import { defineConfig } from 'vite';

// GitHub Pages のサブパス配信でも動くよう相対ベースにする。
// ビルド成果物はリポジトリの eveng2/ にそのまま置く。
export default defineConfig({
  base: './',
  build: {
    outDir: '../eveng2',
    emptyOutDir: true,
  },
});
