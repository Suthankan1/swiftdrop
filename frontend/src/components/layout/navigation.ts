import type { SwiftDropRole } from '../../auth/contracts'

/** Central role priority ordered from highest authority to entry role */
export const ROLE_PRIORITY: readonly SwiftDropRole[] = [
  'ADMIN',
  'DISPATCHER',
  'COURIER',
  'MERCHANT',
  'CUSTOMER',
] as const

export interface WorkspaceItem {
  id: string
  role: SwiftDropRole
  label: string
  path: string
}

export function getAuthorizedWorkspaces(roles: readonly SwiftDropRole[]): WorkspaceItem[] {
  const list: WorkspaceItem[] = []

  // Check roles in order of privilege
  if (roles.includes('ADMIN')) {
    list.push({ id: 'admin', role: 'ADMIN', label: 'Admin space', path: '/admin' })
    list.push({ id: 'dispatcher', role: 'DISPATCHER', label: 'Dispatcher space', path: '/dispatcher' })
  } else if (roles.includes('DISPATCHER')) {
    list.push({ id: 'dispatcher', role: 'DISPATCHER', label: 'Dispatcher space', path: '/dispatcher' })
  }

  if (roles.includes('COURIER')) {
    list.push({ id: 'courier', role: 'COURIER', label: 'Courier space', path: '/courier' })
  }

  if (roles.includes('MERCHANT')) {
    list.push({ id: 'merchant', role: 'MERCHANT', label: 'Merchant space', path: '/merchant' })
  }

  if (roles.includes('CUSTOMER')) {
    list.push({ id: 'customer', role: 'CUSTOMER', label: 'Customer space', path: '/customer' })
  }

  return list
}

export function determineActiveWorkspace(
  authorizedWorkspaces: readonly WorkspaceItem[],
  pathname: string,
): WorkspaceItem | null {
  if (authorizedWorkspaces.length === 0) return null

  // Route-based match first: check if user is currently inside a workspace's route tree
  const matched = authorizedWorkspaces.find((ws) =>
    pathname === ws.path || pathname.startsWith(ws.path + '/')
  )
  if (matched) return matched

  // Fallback to highest priority authorized workspace
  return authorizedWorkspaces[0] ?? null
}
