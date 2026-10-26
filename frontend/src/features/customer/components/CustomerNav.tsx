import { NavLink, useInRouterContext } from 'react-router'

export function CustomerNav() {
  const inRouter = useInRouterContext()

  if (!inRouter) {
    return (
      <nav className="customer-sub-nav" aria-label="Customer space sections">
        <a href="/customer" className="customer-nav-tab is-active">
          Account Profile
        </a>
        <a href="/customer/history" className="customer-nav-tab">
          Deliveries & Tracking
        </a>
      </nav>
    )
  }

  return (
    <nav className="customer-sub-nav" aria-label="Customer space sections">
      <NavLink
        end
        to="/customer"
        className={({ isActive }) => `customer-nav-tab${isActive ? ' is-active' : ''}`}
      >
        Account Profile
      </NavLink>
      <NavLink
        to="/customer/history"
        className={({ isActive }) => `customer-nav-tab${isActive ? ' is-active' : ''}`}
      >
        Deliveries & Tracking
      </NavLink>
    </nav>
  )
}
