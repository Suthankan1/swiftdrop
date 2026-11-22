import { useState, type CSSProperties, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { Button } from '../../components/ui/Button'
import { useAuth } from '../../auth/context'
import { ButtonLink } from '../../components/ui/ButtonLink'
import { ArrowRightIcon, ArrowUpRightIcon, PauseIcon, PlayIcon, SearchIcon, StoreIcon, TruckIcon, UserIcon } from '../../components/ui/icons'
import { usePointerEffects } from '../../components/ui/pointerEffects'
import { useRevealOnScroll } from '../../components/ui/useRevealOnScroll'
import { DeliveryScene } from './DeliveryScene'
import './LandingPage.css'

/** Inline custom properties used for staggered motion (load sequence and scroll reveals). */
const order = (index: number) => ({ '--i': index }) as CSSProperties
const revealDelay = (ms: number) => ({ '--reveal-delay': `${ms}ms` }) as CSSProperties

const PROMISES = ['Small parcels.', 'Big connections.', 'Thoughtfully delivered.'] as const

const WORKSPACES = [
  {
    tag: 'Customer Space', title: 'Personal & On-Demand', icon: <UserIcon />, to: '/customer', cta: 'Customer portal',
    body: 'Instant quotes, one-click booking, live parcel updates and downloadable receipts.',
  },
  {
    tag: 'Merchant Hub', title: 'Commercial Logistics', icon: <StoreIcon />, to: '/merchant', cta: 'Merchant hub',
    body: 'Bulk CSV uploads, webhooks, scoped API credentials and real-time order tracking.',
  },
  {
    tag: 'Courier Dispatch', title: 'Operational Agility', icon: <TruckIcon />, to: '/courier', cta: 'Courier workspace',
    body: 'Route rosters, stage-by-stage status updates and photo proof of delivery.',
  },
] as const

type Workspace = Omit<(typeof WORKSPACES)[number], 'to' | 'cta'> & { to: string; cta: string }

/** Glass card with a pointer-following glare and a gentle 3D tilt (mouse only, motion-safe). */
function WorkspaceCard({ workspace }: { workspace: Workspace }) {
  const pointer = usePointerEffects<HTMLElement>(4)
  return (
    <article className="workspace-card glass" {...pointer}>
      <span className="card-glare" aria-hidden="true" />
      <div className="workspace-card-top">
        <span className="workspace-icon" aria-hidden="true">{workspace.icon}</span>
        <span className="workspace-tag">{workspace.tag}</span>
      </div>
      <h3>{workspace.title}</h3>
      <p>{workspace.body}</p>
      <div className="workspace-card-footer">
        <ButtonLink to={workspace.to} variant="link" rightIcon={<ArrowUpRightIcon />}>{workspace.cta}</ButtonLink>
      </div>
    </article>
  )
}

/** Slow aurora light, a faint dot grid and grain behind the whole page. Purely decorative. */
function AmbientBackground() {
  return (
    <div className="landing-ambient" aria-hidden="true">
      <span className="ambient-blob blob-1" />
      <span className="ambient-blob blob-2" />
      <span className="ambient-blob blob-3" />
      <span className="ambient-blob blob-4" />
      <span className="ambient-dots" />
      <span className="ambient-grain" />
    </div>
  )
}

function PromiseMarquee() {
  const [paused, setPaused] = useState(false)
  // The first set is the readable copy; every repeat exists only to make the loop seamless, so it is hidden from assistive tech.
  const renderSet = (key: string, duplicate: boolean) => PROMISES.map((text) => (
    <span key={`${key}-${text}`} className={`marquee-unit${duplicate ? ' is-duplicate' : ''}`} aria-hidden={duplicate || undefined}>
      <span className="marquee-item">{text}</span>
      <span className="marquee-star" aria-hidden="true"><span>✳</span></span>
    </span>
  ))

  return (
    <section className="marquee glass" aria-label="Our approach">
      <div className="marquee-viewport">
        <div className="marquee-track" data-paused={paused}>
          <div className="marquee-group">{renderSet('a', false)}{renderSet('b', true)}</div>
          <div className="marquee-group" aria-hidden="true">{renderSet('c', true)}{renderSet('d', true)}</div>
        </div>
      </div>
      <Button
        variant="secondary"
        size="icon"
        className="marquee-toggle"
        aria-label={paused ? 'Play scrolling text' : 'Pause scrolling text'}
        onClick={() => setPaused((value) => !value)}
      >
        {paused ? <PlayIcon /> : <PauseIcon />}
      </Button>
    </section>
  )
}

export function LandingPage() {
  const [trackingNumber, setTrackingNumber] = useState('')
  const navigate = useNavigate()
  const auth = useAuth()

  const isAuthenticated = auth.snapshot.status === 'authenticated'
  const roles = isAuthenticated ? auth.snapshot.roles : []
  const hasCustomer = roles.includes('CUSTOMER')
  const hasMerchant = roles.includes('MERCHANT')
  const hasCourier = roles.includes('COURIER')
  const hasDispatcher = roles.includes('DISPATCHER')
  const hasAdmin = roles.includes('ADMIN')

  // Determine primary workspace target based on role priority
  const primaryWorkspacePath = hasAdmin
    ? '/admin'
    : hasDispatcher
      ? '/dispatcher'
      : hasCourier
        ? '/courier'
        : hasMerchant
          ? '/merchant'
          : '/customer'

  const primaryWorkspaceLabel = hasAdmin
    ? 'Admin space'
    : hasDispatcher
      ? 'Dispatcher space'
      : hasCourier
        ? 'Courier space'
        : hasMerchant
          ? 'Merchant space'
          : 'Customer space'

  const revealRoot = useRevealOnScroll<HTMLDivElement>()
  const heroPointer = usePointerEffects<HTMLElement>()

  const workspaceLinks = {
    '/customer': { to: '/customer', cta: isAuthenticated && hasCustomer ? 'Open Customer Space' : 'Customer portal' },
    '/merchant': { to: isAuthenticated && !hasMerchant ? '/merchant/enroll' : '/merchant', cta: isAuthenticated && hasMerchant ? 'Open Merchant Hub' : isAuthenticated ? 'Enroll as Merchant' : 'Merchant hub' },
    '/courier': { to: '/courier', cta: isAuthenticated && hasCourier ? 'Open Courier Workspace' : 'Courier workspace' },
  }
  const availableWorkspaces = WORKSPACES.map(workspace => ({ ...workspace, ...workspaceLinks[workspace.to] }))

  function handleTrackSubmit(e: FormEvent) {
    e.preventDefault()
    const trimmed = trackingNumber.trim()
    if (trimmed) {
      navigate(`/tracking/${encodeURIComponent(trimmed)}`)
    } else {
      navigate('/tracking')
    }
  }

  return (
    <div className="landing-page" ref={revealRoot}>
      <AmbientBackground />
      <div className="scroll-progress" aria-hidden="true" />
      <section className="hero container" aria-labelledby="hero-title" {...heroPointer}>
        <span className="hero-spotlight" aria-hidden="true" />
        <div className="hero-copy">
          <p className="eyebrow hero-anim" style={order(0)}><span className="eyebrow-line" aria-hidden="true" /> PEOPLE. PARCELS. POSSIBILITIES.</p>
          <h1 id="hero-title" className="hero-title">
            <span className="hero-line hero-anim" style={order(1)}>A little closer. </span>
            <span className="hero-line hero-anim" style={order(2)}>With every </span>
            <span className="hero-line hero-anim" style={order(3)}>
              <span className="hero-word">delivery<svg className="hero-underline" viewBox="0 0 410 20" preserveAspectRatio="none" aria-hidden="true"><path d="M3 13C99 1 251 0 407 9" pathLength={1} /></svg></span><span className="hero-dot">.</span>
            </span>
          </h1>
          <p className="hero-description hero-anim" style={order(4)}>From a thoughtful little something to your next big idea. We’re building a delivery experience that brings people closer.</p>

          <form className="track-form hero-anim" style={order(5)} onSubmit={handleTrackSubmit} role="search" aria-label="Quick parcel tracking">
            <div className="track-field glass">
              <SearchIcon className="track-field-icon" size={20} />
              <input
                type="text"
                className="track-input"
                placeholder="Enter tracking number (e.g. SWD-20261023-A1B2C3)"
                value={trackingNumber}
                onChange={(e) => setTrackingNumber(e.target.value)}
                aria-label="Parcel tracking number"
              />
              <Button type="submit" variant="primary" className="track-submit">
                Track
              </Button>
            </div>
          </form>

          <div className="hero-actions hero-anim" style={order(6)}>
            <ButtonLink to={isAuthenticated ? primaryWorkspacePath : "/customer"} variant="primary" size="lg" rightIcon={<ArrowRightIcon />}>{isAuthenticated ? `Go to ${primaryWorkspaceLabel}` : "Explore your customer space"}</ButtonLink>
            <ButtonLink to="/tracking" variant="secondary" size="lg">Live tracking</ButtonLink>
          </div>
        </div>
        <DeliveryScene />
      </section>

      <PromiseMarquee />

      <section className="workspaces container" aria-labelledby="showcase-title">
        <div className="section-header" data-reveal>
          <p className="eyebrow"><span className="eyebrow-line" aria-hidden="true" /> PURPOSE-BUILT WORKSPACES</p>
          <h2 id="showcase-title" className="landing-h2">Seamless logistics for everyone.</h2>
          <p className="landing-lead">Whether sending a single gift, fulfilling thousands of merchant orders, or navigating city routes.</p>
        </div>
        <div className="workspace-grid">
          {availableWorkspaces.map((workspace, index) => (
            <div key={workspace.to} className="workspace-item" data-reveal style={revealDelay(index * 80)}>
              <WorkspaceCard workspace={workspace} />
            </div>
          ))}
        </div>
      </section>

      <section className="cta-section container" aria-labelledby="introduction-title">
        <div className="cta-banner" data-reveal>
          <div className="cta-icon" aria-hidden="true"><ArrowUpRightIcon size={28} /></div>
          <div className="cta-copy">
            <p className="eyebrow">A GOOD PLACE TO START</p>
            <h2 id="introduction-title">Your journey. A shared beginning.</h2>
            <p className="cta-text">Sign in to book deliveries, follow them live and download receipts.</p>
          </div>
          <ButtonLink to="/customer" variant="accent" size="lg" rightIcon={<ArrowRightIcon />}>Customer space</ButtonLink>
        </div>
      </section>
    </div>
  )
}
