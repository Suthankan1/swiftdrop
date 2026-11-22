import { createElement } from 'react'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { anonymousAuth, TestProviders as AppProviders } from './authFixture'
import { AppRoutes } from '../app/router/routes'
import { createAccountsApi } from '../features/customer/api/accounts'
import { customerFixture } from './fixtures'
import { saveReturnTarget } from '../auth/redirect'

describe('frontend token persistence boundary', () => {
  it('does not write browser storage during navigation or an isolated profile request', async () => {
    // Both localStorage and sessionStorage use this prototype in the browser.
    const persist = vi.spyOn(Storage.prototype, 'setItem')
    const user = userEvent.setup()
    const request = vi.fn<typeof fetch>().mockResolvedValue(Response.json(customerFixture))
    const customerAuth = {
      ...anonymousAuth,
      snapshot: { status: 'authenticated' as const, subject: 'cust-1', roles: ['CUSTOMER' as const] },
    }
    render(createElement(AppProviders, {
      auth: customerAuth,
      children: createElement(MemoryRouter, null, createElement(AppRoutes)),
    }))

    await user.click(within(screen.getByRole('navigation', { name: /main navigation/i })).getByRole('link', { name: /customer/i }))
    const api = createAccountsApi({ getAccessToken: async () => 'synthetic-memory-only-test-value', fetch: request })
    await api.getCurrentUser()

    expect(persist).not.toHaveBeenCalled()
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
  })

  it('stores only the internal return path and its timestamp during auth handoff', () => {
    const persist = vi.spyOn(Storage.prototype, 'setItem')
    try {
      saveReturnTarget('/customer')
      expect(persist).toHaveBeenCalledExactlyOnceWith(
        'swiftdrop.auth.return-target',
        expect.any(String),
      )
      expect(JSON.parse(sessionStorage.getItem('swiftdrop.auth.return-target') ?? 'null')).toEqual({ target: '/customer', createdAt: expect.any(Number) })
      expect(localStorage.length).toBe(0)
    } finally {
      saveReturnTarget()
    }
  })

  it('allows only the scoped return-path write and disallows test-fixture imports', () => {
    const sources = import.meta.glob<string>('/src/**/*.{ts,tsx}', { query: '?raw', import: 'default', eager: true })
    const production = Object.entries(sources).filter(([path]) => !path.includes('/test/') && !/\.test\.tsx?$/.test(path))

    expect(production.length).toBeGreaterThan(0)
    for (const [path, source] of production) {
      const storageWrites = source.match(/\b(?:localStorage|sessionStorage)\s*(?:\.\s*setItem|\[\s*['"]setItem['"]\s*\])/g) ?? []
      expect(storageWrites, path).toEqual(path === '/src/auth/redirect.ts' ? ['sessionStorage.setItem'] : [])
      expect(source, path).not.toMatch(/(?:from\s*|import\s*\()[\s'"]*[^\n]*(?:test\/fixtures|demo\.invalid)/)
    }
  })
})
