import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  build: {
    rollupOptions: {
      output: {
        manualChunks(id: string) {
          if (id.includes('node_modules')) {
            if (id.includes('react') || id.includes('react-dom') || id.includes('react-router')) {
              return 'vendor-react'
            }
            if (id.includes('@tanstack')) {
              return 'vendor-query'
            }
            if (id.includes('keycloak-js')) {
              return 'vendor-keycloak'
            }
            return 'vendor'
          }
        },
      },
    },
  },
})
