import { createElement } from 'react'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { AppProviders } from '../app/providers/AppProviders'
import { AppRoutes } from '../app/router/routes'
import { createAccountsApi } from '../features/customer/api/accounts'
import { customerFixture } from './fixtures'

describe('frontend token persistence boundary', () => {
  it('does not write browser storage during navigation or an isolated profile request', async () => {
    // Both localStorage and sessionStorage use this prototype in the browser.
    const persist = vi.spyOn(Storage.prototype, 'setItem')
    const user = userEvent.setup()
    const request = vi.fn<typeof fetch>().mockResolvedValue(Response.json(customerFixture))
    render(createElement(AppProviders, null,
      createElement(MemoryRouter, null, createElement(AppRoutes)),
    ))

    await user.click(within(screen.getByRole('navigation', { name: /main navigation/i })).getByRole('link', { name: /customer/i }))
    const api = createAccountsApi({ getAccessToken: async () => 'synthetic-memory-only-test-value', fetch: request })
    await api.getCurrentUser()

    expect(persist).not.toHaveBeenCalled()
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
  })

  it('keeps production source free of storage persistence and test-fixture imports', () => {
    const sources = import.meta.glob<string>('/src/**/*.{ts,tsx}', { query: '?raw', import: 'default', eager: true })
    const production = Object.entries(sources).filter(([path]) => !path.includes('/test/') && !/\.test\.tsx?$/.test(path))

    expect(production.length).toBeGreaterThan(0)
    for (const [path, source] of production) {
      expect(source, path).not.toMatch(/\b(?:localStorage|sessionStorage)\s*(?:\.\s*setItem|\[\s*['"]setItem['"]\s*\])/)
      expect(source, path).not.toMatch(/(?:from\s*|import\s*\()[\s'"]*[^\n]*(?:test\/fixtures|demo\.invalid)/)
    }
  })
})
