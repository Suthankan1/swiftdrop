import { useEffect, useSyncExternalStore, type ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { AuthContext } from './context'
import { browserSession, type AuthSession } from './session'

export function AuthProvider({ children, session = browserSession() }: { children: ReactNode; session?: AuthSession }) {
  const state = useSyncExternalStore(session.subscribe, session.getState)
  const cache = useQueryClient()
  useEffect(() => {
    let previous = session.getState().snapshot.subject
    const unsubscribe = session.subscribe(() => {
      const current = session.getState().snapshot.subject
      if (current !== previous) {
        previous = current
        void cache.cancelQueries()
        cache.clear()
      }
    })
    void session.initialize()
    return unsubscribe
  }, [session, cache])
  return <AuthContext value={{ ...state, getAccessToken: session.getAccessToken, login: session.login, logout: session.logout,
    handleSessionExpiry: session.handleSessionExpiry, register: session.register, googleLogin: session.googleLogin, profileFetch: session.profileFetch }}>{children}</AuthContext>
}
