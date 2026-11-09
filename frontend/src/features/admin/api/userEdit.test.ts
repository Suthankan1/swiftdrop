import { describe, expect, it, vi } from 'vitest'
import { AdminManagementError, createAdminManagementApi } from './userStaff'

const id = '11111111-2222-4333-8444-555555555555'
const ok = () => new Response(JSON.stringify({ profileId: id, email: 'c@b.test', fullName: 'New', phoneNumber: '' }), {
  status: 200, headers: { 'Content-Type': 'application/json' },
})

describe('admin user edit', () => {
  it('sends only the changed, trimmed fields as PATCH with the admin token', async () => {
    const fetch = vi.fn().mockImplementation(async () => ok())
    const api = createAdminManagementApi({ getAccessToken: async () => 'admin-jwt', gatewayUrl: 'http://gw', fetch })
    await api.updateUser!(id, { fullName: '  New Name ', phoneNumber: '+94 77 123 4567' }, new AbortController().signal)
    const [url, init] = fetch.mock.calls[0]!
    expect(url).toBe(`http://gw/api/v1/admin/users/${id}`)
    expect(init.method).toBe('PATCH')
    expect(init.headers.Authorization).toBe('Bearer admin-jwt')
    expect(JSON.parse(init.body)).toEqual({ fullName: 'New Name', phoneNumber: '+94 77 123 4567' })
  })

  it('rejects invalid input before calling the server', async () => {
    const fetch = vi.fn()
    const api = createAdminManagementApi({ getAccessToken: async () => 'admin-jwt', gatewayUrl: 'http://gw', fetch })
    const signal = new AbortController().signal
    for (const [profileId, changes] of [
      ['not-a-uuid', { fullName: 'X' }],
      [id, {}],
      [id, { fullName: '   ' }],
      [id, { phoneNumber: 'call me' }],
    ] as const) {
      await expect(api.updateUser!(profileId, changes, signal)).rejects.toBeInstanceOf(AdminManagementError)
    }
    expect(fetch).not.toHaveBeenCalled()
  })

  it('treats a response for a different user as unconfirmed', async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ profileId: 'other' }), { status: 200 }))
    const api = createAdminManagementApi({ getAccessToken: async () => 'admin-jwt', gatewayUrl: 'http://gw', fetch })
    await expect(api.updateUser!(id, { fullName: 'X' }, new AbortController().signal)).rejects.toMatchObject({ kind: 'unconfirmed' })
  })
})
