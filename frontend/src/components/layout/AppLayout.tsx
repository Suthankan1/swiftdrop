import { useRef, useState, type KeyboardEvent } from 'react'
import { Link, NavLink, Outlet } from 'react-router'
import { useAuth } from '../../auth/context'
import { Badge } from '../ui/Badge'
import { AiDeliveryAssistant } from '../../features/ai/AiDeliveryAssistant'

function MainNavigation() {
  const auth = useAuth()
  const [menuOpen, setMenuOpen] = useState(false)
  const menuButton = useRef<HTMLButtonElement>(null)

  function closeMenu(event: KeyboardEvent<HTMLElement>) {
    if (event.key === 'Escape' && menuOpen) {
      setMenuOpen(false)
      menuButton.current?.focus()
    }
  }

  return (
    <header className="site-header" onKeyDown={closeMenu}>
      <div className="header-inner">
        <Link className="brand" to="/" aria-label="SwiftDrop home" onClick={() => setMenuOpen(false)}>
          <span className="brand-mark" aria-hidden="true"><span /><span /><span /></span>
          <span>SwiftDrop<span className="brand-period">.</span></span>
        </Link>
        <button ref={menuButton} className="menu-toggle" type="button" aria-expanded={menuOpen} aria-controls="main-navigation" aria-label={menuOpen ? 'Close navigation' : 'Open navigation'} onClick={() => setMenuOpen((open) => !open)}>
          <span className={menuOpen ? 'menu-glyph is-open' : 'menu-glyph'} aria-hidden="true"><span /><span /></span>
        </button>
        <nav id="main-navigation" className={`main-navigation${menuOpen ? ' is-open' : ''}`} aria-label="Main navigation">
          <NavLink end to="/" className="nav-link" onClick={() => setMenuOpen(false)}>Home</NavLink>
          <NavLink to="/tracking" className="nav-link" onClick={() => setMenuOpen(false)}>Track parcel</NavLink>
          {/* Visitors see it as the way in; signed-in users only when they actually have the customer role. */}
          {(auth.snapshot.status !== 'authenticated' || auth.snapshot.roles.includes('CUSTOMER')) && (
            <NavLink to="/customer" className="nav-link" onClick={() => setMenuOpen(false)}>Customer space <span aria-hidden="true">↗</span></NavLink>
          )}
          {auth.snapshot.status === 'authenticated' && auth.snapshot.roles.includes('MERCHANT') && (
            <NavLink to="/merchant" className="nav-link" onClick={() => setMenuOpen(false)}>Merchant space <span aria-hidden="true">&#8599;</span></NavLink>
          )}
          {auth.snapshot.status === 'authenticated' && auth.snapshot.roles.includes('COURIER') && (
            <NavLink to="/courier" className="nav-link" onClick={() => setMenuOpen(false)}>Courier space <span aria-hidden="true">&#8599;</span></NavLink>
          )}
          {auth.snapshot.status === 'authenticated' && (auth.snapshot.roles.includes('DISPATCHER') || auth.snapshot.roles.includes('ADMIN')) && (
            <NavLink to="/dispatcher" className="nav-link" onClick={() => setMenuOpen(false)}>Dispatcher space <span aria-hidden="true">&#8599;</span></NavLink>
          )}
          {auth.snapshot.status === 'authenticated' && auth.snapshot.roles.includes('ADMIN') && (
            <NavLink to="/admin" className="nav-link" onClick={() => setMenuOpen(false)}>Admin space <span aria-hidden="true">&#8599;</span></NavLink>
          )}
          {auth.snapshot.status === 'authenticated' ? (
            <span className="nav-auth-item">
              {auth.snapshot.roles.length > 0 && (
                <Badge variant="brand" className="header-role-badge">
                  {primaryRole(auth.snapshot.roles)}
                </Badge>
              )}
              <Link to="/logout" className="header-auth-link" onClick={() => setMenuOpen(false)}>
                Sign out
              </Link>
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

/** The most specific role names the workspace; every account also starts as a customer. */
const ROLE_PRIORITY = ['ADMIN', 'DISPATCHER', 'COURIER', 'MERCHANT', 'CUSTOMER'] as const
function primaryRole(roles: readonly string[]): string {
  return ROLE_PRIORITY.find((role) => roles.includes(role)) ?? roles[0] ?? ''
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
