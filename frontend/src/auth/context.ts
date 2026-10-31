import { createContext, useContext } from 'react'
import type { AuthIntegration } from './contracts'
import type { AuthProblem, AuthSession } from './session'
export type AuthContextValue = AuthIntegration & {
  problem: AuthProblem
  register: (returnUrl?: string) => Promise<void>
  googleLogin: (returnUrl?: string) => Promise<void>
  profileFetch: AuthSession['profileFetch']
}
export const AuthContext = createContext<AuthContextValue | null>(null)
export function useAuth() {
  const auth = useContext(AuthContext)
  if (!auth) throw new Error('Authentication provider is required')
  return auth
}
