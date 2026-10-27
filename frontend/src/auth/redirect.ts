/**
 * Validates that redirect paths are strictly safe relative application routes.
 * Rejects protocol-relative URLs (//attacker.com), backslash vectors (/\\attacker.com),
 * external schemas, and auth-internal routes to prevent open-redirect vulnerabilities.
 */
export function sanitizeRedirectUrl(url: string | null | undefined): string | null {
  if (!url || typeof url !== 'string') return null
  const trimmed = url.trim()
  if (!trimmed.startsWith('/') || trimmed.startsWith('//') || trimmed.startsWith('/\\')) {
    return null
  }
  const firstSlash = trimmed.indexOf('/')
  const firstColon = trimmed.indexOf(':')
  if (firstColon !== -1 && firstColon < firstSlash) {
    return null
  }
  // Exclude auth control loops
  if (
    trimmed === '/login' ||
    trimmed.startsWith('/login?') ||
    trimmed === '/auth/callback' ||
    trimmed.startsWith('/auth/callback?') ||
    trimmed === '/logout'
  ) {
    return null
  }
  return trimmed
}
