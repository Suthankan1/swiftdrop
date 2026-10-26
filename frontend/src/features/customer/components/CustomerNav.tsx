import { NavLink, useInRouterContext } from 'react-router'

export function CustomerNav() {
  const inRouter = useInRouterContext()

  if (!inRouter) {
    return (
      <nav className="customer-sub-nav" aria-label="Customer space sections">
        <a href="/customer" className="customer-nav-tab is-active">
          Account Profile
        </a>
        <a href="/customer/quote" className="customer-nav-tab">
          Request Quote &amp; Book
        </a>
        <a href="/customer/history" className="customer-nav-tab">
          Deliveries &amp; Tracking
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
        to="/customer/quote"
        className={({ isActive }) => `customer-nav-tab${isActive ? ' is-active' : ''}`}
      >
        Request Quote &amp; Book
      </NavLink>
      <NavLink
        to="/customer/history"
        className={({ isActive }) => `customer-nav-tab${isActive ? ' is-active' : ''}`}
      >
        Deliveries &amp; Tracking
      </NavLink>
    </nav>
  )
}
