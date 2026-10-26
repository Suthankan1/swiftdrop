# syntax=docker/dockerfile:1
# ==============================================================================
# SwiftDrop React SPA Production Container Image (Issue #128)
# Multi-stage build: Node 24 + pnpm 12 -> NGINX Unprivileged Alpine
# ==============================================================================

# Build Stage
FROM node:24-alpine AS builder

WORKDIR /app

# Enable pnpm matching pinned repository version (12.11.2)
RUN corepack enable && corepack prepare pnpm@12.11.2 --activate

# Copy dependency lockfiles
COPY frontend/package.json frontend/pnpm-lock.yaml ./

# Install dependencies with strict frozen lockfile
RUN pnpm install --frozen-lockfile

# Copy frontend application code
COPY frontend/ ./

# Execute quality gates and production build
RUN pnpm lint && pnpm typecheck && pnpm test --run && pnpm build

# Runtime Stage: Hardened, non-root NGINX unprivileged Alpine
FROM nginxinc/nginx-unprivileged:alpine

LABEL org.opencontainers.image.title="SwiftDrop React SPA" \
      org.opencontainers.image.description="Compiled production React 19 Single-Page Application served via unprivileged NGINX" \
      org.opencontainers.image.source="https://github.com/Suthankan1/swiftdrop" \
      org.opencontainers.image.licenses="Apache-2.0"

USER root
# Apply Alpine security patches to resolve OS package CVEs
RUN apk update && apk upgrade --no-cache
USER 101

# Copy custom NGINX configuration supporting client-side SPA routing
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf

# Copy compiled static assets from builder stage
COPY --from=builder --chown=101:101 /app/dist /usr/share/nginx/html

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/health || exit 1

ENTRYPOINT ["nginx", "-g", "daemon off;"]
