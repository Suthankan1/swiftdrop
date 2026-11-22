import { NavLink } from 'react-router'

export function MerchantNav() {
  return (
    <nav className="merchant-sub-nav" aria-label="Merchant space sections">
      <NavLink
        end
        to="/merchant"
        className={({ isActive }) => `merchant-nav-tab${isActive ? ' is-active' : ''}`}
      >
        Overview
      </NavLink>
      <NavLink
        end
        to="/merchant/orders"
        className={({ isActive }) => `merchant-nav-tab${isActive ? ' is-active' : ''}`}
      >
        Orders
      </NavLink>
      <NavLink
        to="/merchant/orders/new"
        className={({ isActive }) => `merchant-nav-tab${isActive ? ' is-active' : ''}`}
      >
        New Order
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
        Webhooks
      </NavLink>
    </nav>
  )
}
