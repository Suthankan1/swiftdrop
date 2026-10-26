import { NavLink } from 'react-router'

export function MerchantNav() {
  return (
    <nav className="merchant-sub-nav" aria-label="Merchant space sections">
      <NavLink
        end
        to="/merchant"
        className={({ isActive }) => `merchant-nav-tab${isActive ? ' is-active' : ''}`}
      >
        Account Overview
      </NavLink>
      <NavLink
        to="/merchant/credentials"
        className={({ isActive }) => `merchant-nav-tab${isActive ? ' is-active' : ''}`}
      >
        API Credentials
      </NavLink>
      <NavLink
        to="/merchant/webhooks"
        className={({ isActive }) => `merchant-nav-tab${isActive ? ' is-active' : ''}`}
      >
        Webhook Subscriptions
      </NavLink>
    </nav>
  )
}
