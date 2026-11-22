import { useEffect, useLayoutEffect, useRef, useState, type KeyboardEvent } from 'react'
import { Link, NavLink, Outlet, useLocation } from 'react-router'
import { useAuth } from '../../auth/context'
import type { AuthSnapshot, SwiftDropRole } from '../../auth/contracts'
import { ButtonLink } from '../ui/ButtonLink'
import { AiDeliveryAssistant } from '../../features/ai/AiDeliveryAssistant'
import {
  determineActiveWorkspace,
  getAuthorizedWorkspaces,
} from './navigation'

function BrandMark() {
  return <span className="brand-mark" aria-hidden="true"><span /><span /><span /></span>
}

/** Turns on the header's blurred backdrop once the page has scrolled past a tiny sentinel at the very top. */
function useScrolledPastTop() {
  const sentinel = useRef<HTMLDivElement>(null)
  const [scrolled, setScrolled] = useState(false)
  useEffect(() => {
    const node = sentinel.current
    if (!node || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver(([entry]) => setScrolled(!(entry?.isIntersecting ?? true)))
    observer.observe(node)
    return () => observer.disconnect()
  }, [])
  return { sentinel, scrolled }
}

/** Positions the small pill under the active link; CSS animates it between links (transform only). */
function useActiveIndicator(dependencyKey: string) {
  const links = useRef<HTMLDivElement>(null)
  const indicator = useRef<HTMLSpanElement>(null)
  useLayoutEffect(() => {
    const list = links.current
    const pill = indicator.current
    if (!list || !pill) return
    const place = () => {
      const active = list.querySelector<HTMLElement>('.nav-link[aria-current="page"]')
      if (!active) { pill.dataset.visible = 'false'; return }
      const x = active.offsetLeft + active.offsetWidth / 2 - pill.offsetWidth / 2
      pill.style.setProperty('--indicator-x', `${Math.round(x)}px`)
      pill.dataset.visible = 'true'
      list.dataset.indicator = 'ready'
    }
    place()
    const frame = requestAnimationFrame(() => { pill.dataset.animate = 'true' })
    const resize = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(place)
    resize?.observe(list)
    return () => { cancelAnimationFrame(frame); resize?.disconnect() }
  }, [dependencyKey])
  return { links, indicator }
}

function MainNavigation() {
  const auth = useAuth()
  const location = useLocation()
  const [menuOpen, setMenuOpen] = useState(false)
  const [switcherOpen, setSwitcherOpen] = useState(false)
  const menuButton = useRef<HTMLButtonElement>(null)
  const switcherRef = useRef<HTMLDivElement>(null)
  const switcherButtonRef = useRef<HTMLButtonElement>(null)

  const isAuthenticated = auth.snapshot.status === 'authenticated'
  const isRestoringSession = auth.snapshot.status === 'loading'
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
  const { sentinel, scrolled } = useScrolledPastTop()
  const { links, indicator } = useActiveIndicator(`${location.pathname}|${auth.snapshot.status}|${auth.snapshot.roles.join(',')}`)

  function closeMenu(event: KeyboardEvent<HTMLElement>) {
    if (event.key === 'Escape') {
      if (switcherOpen) {
        setSwitcherOpen(false)
        switcherButtonRef.current?.focus()
        event.stopPropagation()
      } else if (menuOpen) {
        setMenuOpen(false)
        menuButton.current?.focus()
        event.stopPropagation()
      }
    }
  }

  useEffect(() => {
    function handleKeyDown(event: globalThis.KeyboardEvent) {
      if (event.key === 'Escape') {
        if (switcherOpen) {
          setSwitcherOpen(false)
          switcherButtonRef.current?.focus()
        } else if (menuOpen) {
          setMenuOpen(false)
          menuButton.current?.focus()
        }
      }
    }
    if (menuOpen || switcherOpen) {
      window.addEventListener('keydown', handleKeyDown)
    }
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [menuOpen, switcherOpen])

  useEffect(() => {
    if (menuOpen) {
      document.body.style.overflow = 'hidden'
    } else {
      document.body.style.overflow = ''
    }
    return () => {
      document.body.style.overflow = ''
    }
  }, [menuOpen])

  return (
    <>
      <div ref={sentinel} className="header-sentinel" aria-hidden="true" />
      <header className="site-header" data-scrolled={scrolled} data-menu-open={menuOpen} onKeyDown={closeMenu}>
      <div className="container header-inner">
        <Link className="brand" to="/" aria-label="SwiftDrop home" onClick={() => setMenuOpen(false)}>
          <BrandMark />
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
          <div ref={links} className="nav-links">
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

          <span ref={indicator} className="nav-indicator" aria-hidden="true" />
          </div>
          <div className="nav-actions">
          {isRestoringSession ? (
            <span className="nav-auth-item nav-auth-placeholder" aria-hidden="true">
              <span />
              <span />
            </span>
          ) : isAuthenticated ? (
            <span className="nav-auth-item">
              {activeWorkspace && (
                <span className="header-role-badge">
                  {activeWorkspace.role.toLowerCase()}
                </span>
              )}
              <button
                type="button"
                className="button button-secondary header-sign-out-button"
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
            <>
              <ButtonLink to="/login" variant="secondary" onClick={() => setMenuOpen(false)}>Sign in</ButtonLink>
              <ButtonLink to="/login?start=register" variant="primary" onClick={() => setMenuOpen(false)}>Create account</ButtonLink>
              <ButtonLink to="/customer" variant="primary" onClick={() => setMenuOpen(false)}>Get started</ButtonLink>
            </>
          )}
          </div>
        </nav>
      </div>
    </header>
    </>
  )
}

/** Visitors see every entry point (as on the landing page); signed-in users only the workspaces their roles grant. */
function canSeeWorkspace(snapshot: AuthSnapshot, role: SwiftDropRole): boolean {
  return snapshot.status !== 'authenticated' || snapshot.roles.includes(role)
}

function SiteFooter() {
  const { snapshot } = useAuth()
  const isAuthenticated = snapshot.status === 'authenticated'
  const workspaces = [
    { role: 'MERCHANT', to: '/merchant', label: 'Merchant hub' },
    { role: 'COURIER', to: '/courier', label: 'Courier workspace' },
  ] as const satisfies readonly { role: SwiftDropRole; to: string; label: string }[]
  const visibleWorkspaces = workspaces.filter((workspace) => canSeeWorkspace(snapshot, workspace.role))
  return (
    <footer className="site-footer">
      <div className="container">
        <div className="footer-grid">
          <div className="footer-about">
            <Link className="footer-brand" to="/"><BrandMark /><span>SwiftDrop<span className="brand-period">.</span></span></Link>
            <p className="footer-tagline">Moving things. Connecting people.</p>
          </div>
          <nav className="footer-column" aria-label="Product">
            <p className="footer-heading">Product</p>
            <ul className="footer-links">
              <li><Link to="/tracking">Track parcel</Link></li>
              {canSeeWorkspace(snapshot, 'CUSTOMER') && <li><Link to="/customer">Customer space</Link></li>}
            </ul>
          </nav>
          {visibleWorkspaces.length > 0 && (
            <nav className="footer-column" aria-label="Workspaces">
              <p className="footer-heading">Workspaces</p>
              <ul className="footer-links">
                {visibleWorkspaces.map((workspace) => <li key={workspace.to}><Link to={workspace.to}>{workspace.label}</Link></li>)}
              </ul>
            </nav>
          )}
          <nav className="footer-column" aria-label="Account">
            <p className="footer-heading">Account</p>
            <ul className="footer-links">
              <li>{isAuthenticated ? <Link to="/logout">Sign out</Link> : <Link to="/login">Sign in</Link>}</li>
            </ul>
          </nav>
        </div>
        <div className="footer-bottom">
          <span>© {new Date().getFullYear()} SwiftDrop</span>
        </div>
      </div>
    </footer>
  )
}

export function AppLayout() {
  return (
    <div className="app-shell">
      <a className="skip-link" href="#main-content">Skip to content</a>
      <MainNavigation />
      <main id="main-content" className="app-main-content" tabIndex={-1}><Outlet /></main>
      <SiteFooter />
      <AiDeliveryAssistant />
    </div>
  )
}
