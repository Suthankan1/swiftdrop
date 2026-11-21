import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { getApiGatewayUrl, getKeycloakUrl, getKeycloakRealm, getKeycloakClientId, getAppOrigin } from './env'

describe('Centralized Environment Configuration (env.ts)', () => {
  const originalEnv = { ...process.env }

  beforeEach(() => {
    delete (import.meta.env as Record<string, unknown>).VITE_API_GATEWAY_URL
    delete (import.meta.env as Record<string, unknown>).VITE_KEYCLOAK_URL
    delete (import.meta.env as Record<string, unknown>).VITE_KEYCLOAK_ORIGIN
  })

  afterEach(() => {
    process.env = { ...originalEnv }
  })

  it('returns default http://localhost:8080 when VITE_API_GATEWAY_URL is not set', () => {
    expect(getApiGatewayUrl()).toBe('http://localhost:8080')
  })

  it('returns formatted VITE_API_GATEWAY_URL when provided', () => {
    ;(import.meta.env as Record<string, unknown>).VITE_API_GATEWAY_URL = 'https://api.swiftdrop.example.com/'
    expect(getApiGatewayUrl()).toBe('https://api.swiftdrop.example.com')
  })

  it('returns default http://localhost:8090 when Keycloak URL is not set', () => {
    expect(getKeycloakUrl()).toBe('http://localhost:8090')
  })

  it('returns configured Keycloak URL without trailing slash', () => {
    ;(import.meta.env as Record<string, unknown>).VITE_KEYCLOAK_URL = 'https://auth.swiftdrop.example.com/'
    expect(getKeycloakUrl()).toBe('https://auth.swiftdrop.example.com')
  })

  it('returns standard Keycloak realm and client ID defaults', () => {
    expect(getKeycloakRealm()).toBe('swiftdrop')
    expect(getKeycloakClientId()).toBe('swiftdrop-frontend')
  })

  it('returns origin or fallback in getAppOrigin', () => {
    expect(getAppOrigin()).toBeDefined()
  })
})
