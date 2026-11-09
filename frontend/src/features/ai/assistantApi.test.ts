import { describe, expect, it, vi } from 'vitest'
import { createAssistantApi, MAX_TURNS, trimConversation } from './assistantApi'

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

describe('assistantApi', () => {
  it('reports enabled only when the server says exactly true', async () => {
    for (const [body, expected] of [[{ enabled: true }, true], [{ enabled: 'true' }, false], [{}, false]] as const) {
      const api = createAssistantApi({ gatewayUrl: 'http://gw', fetch: vi.fn().mockResolvedValue(json(body)) })
      expect((await api.status()).enabled).toBe(expected)
    }
    const offline = createAssistantApi({ gatewayUrl: 'http://gw', fetch: vi.fn().mockRejectedValue(new Error('down')) })
    expect((await offline.status()).enabled).toBe(false)
  })

  it('sends the conversation with the bearer token when signed in, and without it otherwise', async () => {
    const fetch = vi.fn().mockImplementation(async () => json({ reply: 'ok', toolsUsed: ['list_my_parcels'] }))
    const signedIn = createAssistantApi({ gatewayUrl: 'http://gw/', fetch, getAccessToken: async () => 'jwt-1' })
    await expect(signedIn.chat([{ role: 'user', text: ' where is my parcel? ' }])).resolves.toEqual({ reply: 'ok', toolsUsed: ['list_my_parcels'] })
    const [url, init] = fetch.mock.calls[0]!
    expect(url).toBe('http://gw/api/v1/assistant/chat')
    expect((init as RequestInit).headers).toMatchObject({ Authorization: 'Bearer jwt-1' })
    expect(JSON.parse((init as RequestInit).body as string)).toEqual({ messages: [{ role: 'user', text: 'where is my parcel?' }] })

    const anonymous = createAssistantApi({ gatewayUrl: 'http://gw', fetch, getAccessToken: async () => null })
    await anonymous.chat([{ role: 'user', text: 'hi' }])
    expect((fetch.mock.calls[1]![1] as RequestInit).headers).not.toHaveProperty('Authorization')
  })

  it('maps server statuses to failure kinds', async () => {
    for (const [status, kind] of [[503, 'not-configured'], [429, 'busy'], [400, 'invalid'], [502, 'unavailable']] as const) {
      const api = createAssistantApi({ gatewayUrl: 'http://gw', fetch: vi.fn().mockResolvedValue(json({}, status)) })
      await expect(api.chat([{ role: 'user', text: 'hi' }])).rejects.toMatchObject({ kind })
    }
  })

  it('trims history to the server limits and starts with a user turn', () => {
    const many = Array.from({ length: 30 }, (_, i) => ({ role: i % 2 === 0 ? 'user' : 'assistant', text: `m${i}` }) as const)
    const trimmed = trimConversation([...many, { role: 'user', text: 'last' }])
    expect(trimmed.length).toBeLessThanOrEqual(MAX_TURNS)
    expect(trimmed[0]!.role).toBe('user')
    expect(trimmed[trimmed.length - 1]).toEqual({ role: 'user', text: 'last' })
    expect(trimConversation([{ role: 'user', text: 'x'.repeat(5000) }])[0]!.text).toHaveLength(2000)
  })
})
