/**
 * SwiftDrop Centralized Environment & Runtime Endpoint Configuration
 *
 * Single source of truth for API Gateway, Keycloak OIDC, and application base URLs.
 * Provides resilient defaults and diagnostic telemetry if production environment variables are missing.
 */

export function getAppOrigin(): string {
  if (typeof window !== 'undefined' && window.location?.origin) {
    return window.location.origin
  }
  return 'http://localhost:5173'
}

export function getApiGatewayUrl(): string {
  const url = import.meta.env.VITE_API_GATEWAY_URL
  if (url && typeof url === 'string' && url.trim().length > 0) {
    return url.trim().replace(/\/$/, '')
  }
  if (import.meta.env.PROD && typeof window !== 'undefined') {
    console.warn(
      '[SwiftDrop Configuration Warning] VITE_API_GATEWAY_URL is not defined in this environment. ' +
      'Falling back to default http://localhost:8080. Check your hosting provider settings.'
    )
  }
  return 'http://localhost:8080'
}

export function getKeycloakUrl(): string {
  const url = import.meta.env.VITE_KEYCLOAK_URL || import.meta.env.VITE_KEYCLOAK_ORIGIN
  if (url && typeof url === 'string' && url.trim().length > 0) {
    return url.trim().replace(/\/$/, '')
  }
  if (import.meta.env.PROD && typeof window !== 'undefined') {
    console.warn(
      '[SwiftDrop Configuration Warning] VITE_KEYCLOAK_URL is not defined in this environment. ' +
      'Falling back to default http://localhost:8090. Check your hosting provider settings.'
    )
  }
  return 'http://localhost:8090'
}

export function getKeycloakRealm(): string {
  return import.meta.env.VITE_KEYCLOAK_REALM || 'swiftdrop'
}

export function getKeycloakClientId(): string {
  return import.meta.env.VITE_KEYCLOAK_CLIENT_ID || 'swiftdrop-frontend'
}
