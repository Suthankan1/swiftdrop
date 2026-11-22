import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

describe('production Nginx security headers', () => {
  it('allows browser-generated image object URLs without weakening unrelated CSP directives', () => {
    const nginxConfig = readFileSync(resolve(process.cwd(), 'nginx.conf'), 'utf8')
    const csp = nginxConfig.match(/add_header Content-Security-Policy "([^"]+)" always;/)?.[1]

    expect(csp).toBeDefined()
    expect(csp).toContain("default-src 'self'")
    expect(csp).toContain("script-src 'self'")
    expect(csp).toContain("img-src 'self' data: blob:")
    expect(csp).toContain("frame-ancestors 'none'")
  })
})
