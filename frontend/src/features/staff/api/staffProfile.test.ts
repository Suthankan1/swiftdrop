import { describe, expect, it, vi } from 'vitest'
import { createStaffApi } from './staffProfile'
import { StaffProfileError } from './contracts'
import { courierStaffFixture } from '../../../test/fixtures'

describe('createStaffApi client', () => {
  it('calls the approved Gateway /api/v1/staff/me endpoint with Bearer authorization', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(courierStaffFixture), { status: 200 }))
    const api = createStaffApi({
      getAccessToken: async () => 'test-valid-staff-token',
      gatewayUrl: 'http://localhost:8080',
      fetch: fetchMock,
    })

    const profile = await api.getStaffProfile()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('http://localhost:8080/api/v1/staff/me')
    expect(init.method).toBe('GET')
    expect(init.headers).toEqual({
      Accept: 'application/json',
      Authorization: 'Bearer test-valid-staff-token',
    })
    expect(init.credentials).toBe('omit')
    expect(init.cache).toBe('no-store')
    expect(init.redirect).toBe('error')
    expect(profile.staffId).toBe(courierStaffFixture.staffId)
    expect(profile.displayName).toBe(courierStaffFixture.displayName)
  })

  it('prevents network access and throws auth-required when getAccessToken returns null or empty string', async () => {
    const fetchMock = vi.fn()
    const apiNull = createStaffApi({
      getAccessToken: async () => null,
      fetch: fetchMock,
    })
    const apiEmpty = createStaffApi({
      getAccessToken: async () => '   ',
      fetch: fetchMock,
    })

    await expect(apiNull.getStaffProfile()).rejects.toThrow(StaffProfileError)
    await expect(apiNull.getStaffProfile()).rejects.toMatchObject({ kind: 'auth-required' })

    await expect(apiEmpty.getStaffProfile()).rejects.toThrow(StaffProfileError)
    await expect(apiEmpty.getStaffProfile()).rejects.toMatchObject({ kind: 'auth-required' })

    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('throws auth-required when getAccessToken throws', async () => {
    const fetchMock = vi.fn()
    const api = createStaffApi({
      getAccessToken: async () => {
        throw new Error('Keycloak failed to refresh')
      },
      fetch: fetchMock,
    })

    await expect(api.getStaffProfile()).rejects.toMatchObject({ kind: 'auth-required' })
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('translates HTTP 401 into StaffProfileError with kind auth-required', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 401 }))
    const api = createStaffApi({
      getAccessToken: async () => 'token',
      fetch: fetchMock,
    })

    await expect(api.getStaffProfile()).rejects.toMatchObject({ kind: 'auth-required' })
  })

  it('translates HTTP 403 into StaffProfileError with kind forbidden', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 403 }))
    const api = createStaffApi({
      getAccessToken: async () => 'token',
      fetch: fetchMock,
    })

    await expect(api.getStaffProfile()).rejects.toMatchObject({ kind: 'forbidden' })
  })

  it('translates HTTP 404 into StaffProfileError with kind not-found', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 404 }))
    const api = createStaffApi({
      getAccessToken: async () => 'token',
      fetch: fetchMock,
    })

    await expect(api.getStaffProfile()).rejects.toMatchObject({ kind: 'not-found' })
  })

  it.each([500, 502, 503, 504])('translates HTTP %s into StaffProfileError with kind unavailable', async (status) => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status }))
    const api = createStaffApi({
      getAccessToken: async () => 'token',
      fetch: fetchMock,
    })

    await expect(api.getStaffProfile()).rejects.toMatchObject({ kind: 'unavailable' })
  })

  it('translates network exceptions into StaffProfileError with kind unavailable', async () => {
    const fetchMock = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'))
    const api = createStaffApi({
      getAccessToken: async () => 'token',
      fetch: fetchMock,
    })

    await expect(api.getStaffProfile()).rejects.toMatchObject({ kind: 'unavailable' })
  })

  it('propagates AbortError when AbortSignal is aborted', async () => {
    const controller = new AbortController()
    controller.abort()
    const fetchMock = vi.fn().mockRejectedValue(new DOMException('The user aborted a request.', 'AbortError'))
    const api = createStaffApi({
      getAccessToken: async () => 'token',
      fetch: fetchMock,
    })

    await expect(api.getStaffProfile(controller.signal)).rejects.toThrow('The user aborted a request.')
  })

  it('throws invalid-response when the response body is not valid JSON', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('<html><body>Gateway Error</body></html>', { status: 200 }))
    const api = createStaffApi({
      getAccessToken: async () => 'token',
      fetch: fetchMock,
    })

    await expect(api.getStaffProfile()).rejects.toMatchObject({ kind: 'invalid-response' })
  })

  it.each([
    ['missing staffId', { ...courierStaffFixture, staffId: '' }],
    ['missing keycloakSub', { ...courierStaffFixture, keycloakSub: '' }],
    ['missing displayName', { ...courierStaffFixture, displayName: '' }],
    ['missing email', { ...courierStaffFixture, email: '' }],
    ['missing role', { ...courierStaffFixture, role: undefined }],
    ['invalid role', { ...courierStaffFixture, role: 'UNKNOWN_ROLE' }],
    ['missing status', { ...courierStaffFixture, businessStatus: undefined, status: undefined }],
    ['invalid status', { ...courierStaffFixture, businessStatus: 'INVALID_STATUS', status: 'INVALID_STATUS' }],
    ['missing department', { ...courierStaffFixture, department: undefined }],
    ['missing createdAt', { ...courierStaffFixture, createdAt: '' }],
  ])('throws invalid-response on %s', async (_, malformedData) => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(malformedData), { status: 200 }))
    const api = createStaffApi({
      getAccessToken: async () => 'token',
      fetch: fetchMock,
    })

    await expect(api.getStaffProfile()).rejects.toMatchObject({ kind: 'invalid-response' })
  })

  it('never prints raw tokens or backend stack traces in error messages', async () => {
    const secretToken = 'super-secret-jwt-token-12345'
    const fetchMock = vi.fn().mockResolvedValue(new Response('Internal server error at com.swiftdrop.Db.connect(line:42)', { status: 500 }))
    const api = createStaffApi({
      getAccessToken: async () => secretToken,
      fetch: fetchMock,
    })

    try {
      await api.getStaffProfile()
      expect.fail('Should have thrown')
    } catch (error) {
      expect(error).toBeInstanceOf(StaffProfileError)
      const err = error as StaffProfileError
      expect(err.message).not.toContain(secretToken)
      expect(err.message).not.toContain('Internal server error')
      expect(err.message).not.toContain('com.swiftdrop')
    }
  })
})
