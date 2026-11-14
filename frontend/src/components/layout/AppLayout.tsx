import { useEffect, useRef, useState, type KeyboardEvent } from 'react'
import { Link, NavLink, Outlet, useLocation } from 'react-router'
import { useAuth } from '../../auth/context'
import { Badge } from '../ui/Badge'
import { AiDeliveryAssistant } from '../../features/ai/AiDeliveryAssistant'
import {
  determineActiveWorkspace,
  getAuthorizedWorkspaces,
} from './navigation'

function MainNavigation() {
  const auth = useAuth()
  const location = useLocation()
  const [menuOpen, setMenuOpen] = useState(false)
  const [switcherOpen, setSwitcherOpen] = useState(false)
  const menuButton = useRef<HTMLButtonElement>(null)
  const switcherRef = useRef<HTMLDivElement>(null)
  const switcherButtonRef = useRef<HTMLButtonElement>(null)

  const isAuthenticated = auth.snapshot.status === 'authenticated'
  const authorizedWorkspaces = isAuthenticated ? getAuthorizedWorkspaces(auth.snapshot.roles) : []
  const activeWorkspace = determineActiveWorkspace(authorizedWorkspaces, location.pathname)
  const switchableWorkspaces = activeWorkspace
    ? authorizedWorkspaces.filter((ws) => ws.id !== activeWorkspace.id)
    : authorizedWorkspaces

  // Close switcher when clicking outside
  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (switcherRef.current && !switcherRef.current.contains(event.target as Node)) {
        setSwitcherOpen(false)
      }
    }
    if (switcherOpen) {
      document.addEventListener('mousedown', handleClickOutside)
    }
    return () => {
      document.removeEventListener('mousedown', handleClickOutside)
    }
  }, [switcherOpen])

  function closeMenu(event: KeyboardEvent<HTMLElement>) {
    if (event.key === 'Escape') {
      if (switcherOpen) {
        setSwitcherOpen(false)
        switcherButtonRef.current?.focus()
        event.stopPropagation()
      } else if (menuOpen) {
        setMenuOpen(false)
        menuButton.current?.focus()
      }
    }
  }

  return (
    <header className="site-header" onKeyDown={closeMenu}>
      <div className="header-inner">
        <Link className="brand" to="/" aria-label="SwiftDrop home" onClick={() => setMenuOpen(false)}>
          <span className="brand-mark" aria-hidden="true"><span /><span /><span /></span>
          <span>SwiftDrop<span className="brand-period">.</span></span>
        </Link>
        <button
          ref={menuButton}
          className="menu-toggle"
          type="button"
          aria-expanded={menuOpen}
          aria-controls="main-navigation"
          aria-label={menuOpen ? 'Close navigation' : 'Open navigation'}
          onClick={() => setMenuOpen((open) => !open)}
        >
          <span className={menuOpen ? 'menu-glyph is-open' : 'menu-glyph'} aria-hidden="true"><span /><span /></span>
        </button>
        <nav id="main-navigation" className={`main-navigation${menuOpen ? ' is-open' : ''}`} aria-label="Main navigation">
          <NavLink end to="/" className="nav-link" onClick={() => setMenuOpen(false)}>Home</NavLink>
          <NavLink to="/tracking" className="nav-link" onClick={() => setMenuOpen(false)}>Track parcel</NavLink>

          {/* Active workspace link for authenticated accounts */}
          {isAuthenticated && activeWorkspace && (
            <NavLink
              to={activeWorkspace.path}
              className="nav-link nav-workspace-link"
              onClick={() => {
                setMenuOpen(false)
                setSwitcherOpen(false)
              }}
            >
              {activeWorkspace.label} <span aria-hidden="true">&#8599;</span>
            </NavLink>
          )}

          {/* Switch Workspace menu for multi-role accounts */}
          {isAuthenticated && switchableWorkspaces.length > 0 && (
            <div className="workspace-switcher-container" ref={switcherRef}>
              <button
                ref={switcherButtonRef}
                type="button"
                className="nav-link workspace-switcher-toggle"
                aria-expanded={switcherOpen}
                aria-haspopup="menu"
                aria-controls="workspace-switcher-menu"
                aria-label="Switch workspace"
                onClick={() => setSwitcherOpen((open) => !open)}
              >
                <span>Switch workspace</span>
                <span className="switcher-caret" aria-hidden="true">{switcherOpen ? '▲' : '▼'}</span>
              </button>
              {switcherOpen && (
                <div
                  id="workspace-switcher-menu"
                  className="workspace-switcher-dropdown"
                  role="menu"
                  aria-label="Authorized workspaces"
                >
                  {switchableWorkspaces.map((ws) => (
                    <NavLink
                      key={ws.id}
                      to={ws.path}
                      role="menuitem"
                      className="workspace-switcher-item"
                      onClick={() => {
                        setSwitcherOpen(false)
                        setMenuOpen(false)
                      }}
                    >
                      <span>{ws.label}</span>
                      <span className="badge-subtle">{ws.role}</span>
                    </NavLink>
                  ))}
                </div>
              )}
            </div>
          )}

          {isAuthenticated ? (
            <span className="nav-auth-item">
              {activeWorkspace && (
                <Badge variant="brand" className="header-role-badge">
                  {activeWorkspace.role}
                </Badge>
              )}
              <button
                type="button"
                className="header-auth-link header-sign-out-button"
                onClick={() => {
                  setMenuOpen(false)
                  setSwitcherOpen(false)
                  void auth.logout()
                }}
              >
                Sign out
              </button>
            </span>
          ) : (
            <Link to="/login" className="header-auth-link" onClick={() => setMenuOpen(false)}>
              Sign in
            </Link>
          )}
        </nav>
        <span className="header-note"><span aria-hidden="true" /> A new way forward</span>
      </div>
    </header>
  )
}

export function AppLayout() {
  return (
    <div className="app-shell">
      <a className="skip-link" href="#main-content">Skip to content</a>
      <MainNavigation />
      <main id="main-content" className="app-main-content" tabIndex={-1}><Outlet /></main>
      <footer className="site-footer"><div className="footer-inner">
        <Link className="footer-brand" to="/">SwiftDrop.</Link><span>Moving things. Connecting people.</span>
        <span className="footer-location">Made for the journey ahead <span aria-hidden="true">↗</span></span>
      </div></footer>
      <AiDeliveryAssistant />
    </div>
  )
}
