import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { Button } from '../../components/ui/Button'

function ArrowIcon({ diagonal = false }: { diagonal?: boolean }) {
  return <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d={diagonal ? 'M6 18 18 6M6 6h12v12' : 'M4 12h15m-6-6 6 6-6 6'} /></svg>
}

export function LandingPage() {
  const [trackingNumber, setTrackingNumber] = useState('')
  const navigate = useNavigate()

  function handleTrackSubmit(e: FormEvent) {
    e.preventDefault()
    const trimmed = trackingNumber.trim()
    if (trimmed) {
      navigate(`/tracking?trackingNumber=${encodeURIComponent(trimmed)}`)
    } else {
      navigate('/tracking')
    }
  }

  return (
    <div className="landing-page">
      <section className="hero page-container" aria-labelledby="hero-title">
        <div className="hero-copy">
          <p className="eyebrow"><span className="eyebrow-line" aria-hidden="true" /> PEOPLE. PARCELS. POSSIBILITIES.</p>
          <h1 id="hero-title">A little closer.<br />With every<br /><span className="hero-word">delivery<svg viewBox="0 0 410 20" preserveAspectRatio="none" aria-hidden="true"><path d="M3 13C99 1 251 0 407 9" /></svg></span><span className="hero-dot">.</span></h1>
          <p className="hero-description">From a thoughtful little something to your next big idea. We’re building a delivery experience that brings people closer.</p>
          
          <form className="hero-track-form" onSubmit={handleTrackSubmit} role="search" aria-label="Quick parcel tracking">
            <div className="hero-track-bar">
              <input
                type="text"
                className="hero-track-input"
                placeholder="Enter tracking number (e.g. TRK-2026-...)"
                value={trackingNumber}
                onChange={(e) => setTrackingNumber(e.target.value)}
                aria-label="Parcel tracking number"
              />
              <Button type="submit" variant="primary" className="hero-track-submit">
                Track
              </Button>
            </div>
          </form>

          <div className="hero-action-row">
            <Link className="button button-primary hero-cta" to="/customer">Explore your customer space <ArrowIcon /></Link>
            <Link className="button button-tonal hero-secondary-cta" to="/tracking">Live tracking</Link>
          </div>
          <p className="hero-caption">One place to begin your SwiftDrop journey.</p>
        </div>
        <div className="hero-art" aria-hidden="true">
          <div className="art-grid" /><div className="art-orbit orbit-one" /><div className="art-orbit orbit-two" /><div className="art-orbit orbit-three" />
          <div className="orbit-point point-start" /><div className="orbit-point point-end" />
          <span className="art-coordinate coordinate-top">A LITTLE SOMETHING</span><span className="art-coordinate coordinate-bottom">A WHOLE LOT CLOSER</span>
          <div className="art-spark spark-one">✳</div><div className="art-spark spark-two">+</div><div className="parcel-shadow" />
          <div className="parcel"><div className="parcel-top" /><div className="parcel-side" /><div className="parcel-front">
            <span className="parcel-tape" /><span className="parcel-brand">SwiftDrop.</span><span className="parcel-tagline">GOOD THINGS<br />ARE ON THEIR WAY.</span>
            <div className="parcel-label"><span>WITH CARE, ALWAYS.</span><i /><small>FROM ONE PERSON TO ANOTHER</small></div><span className="parcel-arrows">↑ ↑</span>
          </div></div>
          <div className="art-sticker"><ArrowIcon diagonal /><span>Made to<br /><strong>connect.</strong></span></div><span className="art-index">THE SWIFTDROP WAY / 01</span>
        </div>
      </section>
      <section className="promise-strip" aria-label="Our approach"><div className="page-container promise-inner">
        <span>Small parcels.</span><span className="promise-star" aria-hidden="true">✳</span><span>Big connections.</span><span className="promise-star" aria-hidden="true">✳</span><span>Thoughtfully delivered.</span><span className="promise-star" aria-hidden="true">✳</span>
      </div></section>
      
      <section className="portal-showcase page-container" aria-labelledby="showcase-title">
        <div className="portal-showcase-header">
          <p className="eyebrow"><span className="eyebrow-line" aria-hidden="true" /> PURPOSE-BUILT WORKSPACES</p>
          <h2 id="showcase-title">Seamless logistics for everyone.</h2>
          <p className="showcase-subtitle">Whether sending a single gift, fulfilling thousands of merchant orders, or navigating city routes.</p>
        </div>
        <div className="portal-grid">
          <div className="portal-card">
            <div className="portal-card-tag">Customer Space</div>
            <h3>Personal & On-Demand</h3>
            <p>Instant pricing quotes across 5 delivery zones, one-click booking, live parcel updates, and downloadable delivery receipts.</p>
            <Link to="/customer" className="button button-tonal portal-cta">
              Customer portal <ArrowIcon diagonal />
            </Link>
          </div>
          <div className="portal-card">
            <div className="portal-card-tag">Merchant Hub</div>
            <h3>Commercial Logistics</h3>
            <p>Bulk CSV uploads, automated webhooks, developer API credentials with scopes, and real-time order lifecycle tracking.</p>
            <Link to="/merchant" className="button button-tonal portal-cta">
              Merchant hub <ArrowIcon diagonal />
            </Link>
          </div>
          <div className="portal-card">
            <div className="portal-card-tag">Courier Dispatch</div>
            <h3>Operational Agility</h3>
            <p>Assigned route rosters, dynamic status transitions, signature capture, and secure tamper-evident photo proof-of-delivery.</p>
            <Link to="/courier" className="button button-tonal portal-cta">
              Courier workspace <ArrowIcon diagonal />
            </Link>
          </div>
        </div>
      </section>

      <section className="introduction page-container" aria-labelledby="introduction-title">
        <div className="introduction-heading"><p className="eyebrow">A GOOD PLACE TO START</p><h2 id="introduction-title">Your journey.<br />A shared beginning.</h2></div>
        <div className="introduction-copy"><p>Every delivery starts with a person. Your customer space brings that idea home, with a dedicated place for your profile.</p><p className="muted">This is our first step. Account sign-in and connected delivery services are coming next.</p><Link className="text-link" to="/customer">Take a look around <ArrowIcon diagonal /></Link></div>
      </section>
      <section className="welcome-banner page-container" aria-label="Customer space introduction"><div className="welcome-banner-inner">
        <div className="welcome-symbol" aria-hidden="true">↗</div><div><p className="eyebrow">THERE’S A PLACE FOR YOU HERE</p><h2>Your next chapter starts with you.</h2></div><Link className="button button-light" to="/customer">Customer space <ArrowIcon /></Link>
      </div></section>
    </div>
  )
}
