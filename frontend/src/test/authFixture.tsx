import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AuthContext, type AuthContextValue } from '../auth/context'
import type { ReactNode } from 'react'
// Test-only context fixture shares a file with its wrapper.
// eslint-disable-next-line react-refresh/only-export-components
export const anonymousAuth: AuthContextValue = {
  snapshot: { status: 'unauthenticated', subject: null, roles: [] }, problem: null,
  getAccessToken: async () => null, login: async () => {}, logout: async () => {},
  register: async () => {}, googleLogin: async () => {}, handleSessionExpiry: async () => {},
  profileFetch: (...args) => fetch(...args),
}
export function TestProviders({ children, auth = anonymousAuth }: { children: ReactNode; auth?: AuthContextValue }) {
  return <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AuthContext value={auth}>{children}</AuthContext></QueryClientProvider>
}
