/**
 * Client for the server-side AI assistant (ai-assistant service, Gemini function calling).
 * The Gemini key never reaches the browser: the browser only talks to /api/v1/assistant/* on the gateway.
 */

export type AssistantFailureKind = 'not-configured' | 'busy' | 'unavailable' | 'invalid'

export class AssistantApiError extends Error {
  readonly kind: AssistantFailureKind

  constructor(kind: AssistantFailureKind, message: string) {
    super(message)
    this.name = 'AssistantApiError'
    this.kind = kind
  }
}

export interface AssistantTurn {
  role: 'user' | 'assistant'
  text: string
}

export interface AssistantChatResult {
  reply: string
  toolsUsed: string[]
}

export interface AssistantApi {
  /** true only when the server confirms an AI provider is configured */
  status: (signal?: AbortSignal) => Promise<{ enabled: boolean }>
  chat: (turns: AssistantTurn[], signal?: AbortSignal) => Promise<AssistantChatResult>
}

export const MAX_TURNS = 20
export const MAX_TURN_CHARS = 2000

/** Keeps the most recent turns within the server's limits and makes sure the conversation ends with the user. */
export function trimConversation(turns: AssistantTurn[]): AssistantTurn[] {
  const cleaned = turns
    .map((turn) => ({ role: turn.role, text: turn.text.trim().slice(0, MAX_TURN_CHARS) }))
    .filter((turn) => turn.text.length > 0)
  let recent = cleaned.slice(-MAX_TURNS)
  while (recent.length > 0 && recent[0]!.role !== 'user') recent = recent.slice(1)
  return recent
}

export function createAssistantApi({
  getAccessToken,
  gatewayUrl = import.meta.env.VITE_API_GATEWAY_URL || 'http://localhost:8080',
  fetch: fetchRequest = (...args) => globalThis.fetch(...args),
}: {
  getAccessToken?: () => Promise<string | null>
  gatewayUrl?: string
  fetch?: typeof globalThis.fetch
} = {}): AssistantApi {
  const base = `${gatewayUrl.replace(/\/$/, '')}/api/v1/assistant`

  return {
    async status(signal) {
      try {
        const response = await fetchRequest(`${base}/status`, {
          headers: { Accept: 'application/json' }, credentials: 'omit', cache: 'no-store', redirect: 'error', signal,
        })
        if (!response.ok) return { enabled: false }
        const data: unknown = await response.json()
        return { enabled: typeof data === 'object' && data !== null && (data as { enabled?: unknown }).enabled === true }
      } catch (error) {
        if (signal?.aborted) throw error
        return { enabled: false }
      }
    },

    async chat(turns, signal) {
      const messages = trimConversation(turns)
      if (messages.length === 0 || messages[messages.length - 1]!.role !== 'user') {
        throw new AssistantApiError('invalid', 'There is no question to send.')
      }
      // Anonymous questions still work without a session; "my parcels" needs one.
      const token = await (async () => {
        try {
          return getAccessToken ? await getAccessToken() : null
        } catch {
          return null
        }
      })()
      let response: Response
      try {
        response = await fetchRequest(`${base}/chat`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Accept: 'application/json',
            ...(token?.trim() ? { Authorization: `Bearer ${token}` } : {}),
          },
          body: JSON.stringify({ messages }),
          credentials: 'omit', cache: 'no-store', redirect: 'error', signal,
        })
      } catch (error) {
        if (signal?.aborted) throw error
        throw new AssistantApiError('unavailable', 'The assistant could not be reached.')
      }
      if (response.status === 503) throw new AssistantApiError('not-configured', 'The AI assistant is not configured.')
      if (response.status === 429) throw new AssistantApiError('busy', 'The assistant is busy. Please wait a moment and try again.')
      if (response.status === 400) throw new AssistantApiError('invalid', 'That message could not be sent. Please shorten or rephrase it.')
      if (!response.ok) throw new AssistantApiError('unavailable', 'The assistant is unavailable right now.')
      let data: unknown
      try {
        data = await response.json()
      } catch {
        throw new AssistantApiError('unavailable', 'The assistant returned an unreadable answer.')
      }
      const value = data as { reply?: unknown; toolsUsed?: unknown }
      if (typeof value?.reply !== 'string') throw new AssistantApiError('unavailable', 'The assistant returned an unreadable answer.')
      return {
        reply: value.reply,
        toolsUsed: Array.isArray(value.toolsUsed) ? value.toolsUsed.filter((t): t is string => typeof t === 'string') : [],
      }
    },
  }
}

/** Used when the AI service is not wanted at all (tests, or keyword-only deployments). */
export const keywordOnlyAssistantApi: AssistantApi = {
  status: async () => ({ enabled: false }),
  chat: async () => {
    throw new AssistantApiError('not-configured', 'The AI assistant is not configured.')
  },
}
