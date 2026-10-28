import { useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { adminFailure } from '../api/userStaff'

export interface AdminScope {
  prefix: readonly string[]
  fail: (error: unknown, signal: AbortSignal) => Promise<void>
}
export function useAdminQuery<T>(scope: AdminScope, key: readonly unknown[], read: (signal: AbortSignal) => Promise<T>) {
  return useQuery({
    queryKey: [...scope.prefix, ...key],
    queryFn: async ({ signal }) => {
      try { const result = await read(signal); signal.throwIfAborted(); return result }
      catch (error) { await scope.fail(error, signal); throw error }
    },
    retry: false, gcTime: 0, staleTime: 0, refetchOnWindowFocus: false, refetchOnReconnect: false,
  })
}
/** Mounted-session commands: abort on teardown, suppress late feedback, no automatic replay. */
export function useAdminCommand(scope: AdminScope) {
  const cache = useQueryClient()
  const controller = useRef<AbortController | null>(null)
  const [pending, setPending] = useState(false)
  const [feedback, setFeedback] = useState<{ error: boolean; text: string } | null>(null)
  useEffect(() => () => { controller.current?.abort() }, [])
  async function run<T>(command: (signal: AbortSignal) => Promise<T>, after?: (result: T) => void, settled?: () => void) {
    if (controller.current) return
    const current = new AbortController()
    controller.current = current
    setPending(true); setFeedback(null)
    try {
      const result = await command(current.signal)
      if (current.signal.aborted) return
      setFeedback({ error: false, text: 'The server confirmed the operation. Review the refreshed business profile and identity audit.' })
      await cache.invalidateQueries({ queryKey: scope.prefix })
      if (!current.signal.aborted) after?.(result)
    } catch (error) {
      if (current.signal.aborted) return
      const failure = adminFailure(error)
      setFeedback({ error: true, text: failure.kind === 'unavailable' || failure.kind === 'invalid-response' ? 'The operation outcome is not confirmed. Refresh the business profile and audit before continuing. The command was not replayed.' : failure.message })
      await scope.fail(error, current.signal)
      if (!current.signal.aborted) await cache.invalidateQueries({ queryKey: scope.prefix })
    } finally {
      if (!current.signal.aborted) { controller.current = null; setPending(false); settled?.() }
    }
  }
  return { pending, feedback, run }
}
