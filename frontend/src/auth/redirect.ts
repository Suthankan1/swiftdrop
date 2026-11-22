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

const returnTargetKey = 'swiftdrop.auth.return-target'
const returnTargetLifetime = 15 * 60_000

export function saveReturnTarget(path?: string) {
  try {
    const target = sanitizeRedirectUrl(path)
    if (target) {
      sessionStorage.setItem(returnTargetKey, JSON.stringify({ target, createdAt: Date.now() }))
    } else {
      sessionStorage.removeItem(returnTargetKey)
    }
  } catch {
    // Continue with the default workspace when storage is unavailable.
  }
}

export function readReturnTarget(): string | null {
  try {
    const value = sessionStorage.getItem(returnTargetKey)
    if (!value) return null
    const { target, createdAt } = JSON.parse(value) as { target?: string; createdAt?: number }
    if (typeof createdAt !== 'number' || Date.now() - createdAt >= returnTargetLifetime) return null
    return sanitizeRedirectUrl(target)
  } catch {
    return null
  }
}
