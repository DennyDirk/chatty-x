import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import tailwind from '@tailwindcss/vite';
export default defineConfig({plugins:[react(),tailwind()],server:{proxy:{'/api':'http://localhost:8080','/health':'http://localhost:8080'}},test:{include:['src/**/*.test.{ts,tsx}'],environment:'jsdom',setupFiles:'./src/test-setup.ts'}});
