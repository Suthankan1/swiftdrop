import { NavLink, useInRouterContext } from 'react-router'

export function MerchantNav() {
  const inRouter = useInRouterContext()

  if (!inRouter) {
    return (
      <nav className="merchant-sub-nav" aria-label="Merchant space sections">
        <a href="/merchant" className="merchant-nav-tab is-active">
          Account Overview
        </a>
        <a href="/merchant/orders" className="merchant-nav-tab">
          Orders Dashboard
        </a>
        <a href="/merchant/credentials" className="merchant-nav-tab">
          API Credentials
        </a>
        <a href="/merchant/webhooks" className="merchant-nav-tab">
          Webhook Subscriptions
        </a>
      </nav>
    )
  }

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
        to="/merchant/orders"
        className={({ isActive }) => `merchant-nav-tab${isActive ? ' is-active' : ''}`}
      >
        Orders Dashboard
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
