import { Link } from 'react-router'

function ArrowIcon({ diagonal = false }: { diagonal?: boolean }) {
  return <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d={diagonal ? 'M6 18 18 6M6 6h12v12' : 'M4 12h15m-6-6 6 6-6 6'} /></svg>
}

export function LandingPage() {
  return (
    <div className="landing-page">
      <section className="hero page-container" aria-labelledby="hero-title">
        <div className="hero-copy">
          <p className="eyebrow"><span className="eyebrow-line" aria-hidden="true" /> PEOPLE. PARCELS. POSSIBILITIES.</p>
          <h1 id="hero-title">A little closer.<br />With every<br /><span className="hero-word">delivery<svg viewBox="0 0 410 20" preserveAspectRatio="none" aria-hidden="true"><path d="M3 13C99 1 251 0 407 9" /></svg></span><span className="hero-dot">.</span></h1>
          <p className="hero-description">From a thoughtful little something to your next big idea. We’re building a delivery experience that brings people closer.</p>
          <Link className="button button-primary hero-cta" to="/customer">Explore your customer space <ArrowIcon /></Link>
          <p className="hero-caption">One place to begin your Devarom journey.</p>
        </div>
        <div className="hero-art" aria-hidden="true">
          <div className="art-grid" /><div className="art-orbit orbit-one" /><div className="art-orbit orbit-two" /><div className="art-orbit orbit-three" />
          <div className="orbit-point point-start" /><div className="orbit-point point-end" />
          <span className="art-coordinate coordinate-top">A LITTLE SOMETHING</span><span className="art-coordinate coordinate-bottom">A WHOLE LOT CLOSER</span>
          <div className="art-spark spark-one">✳</div><div className="art-spark spark-two">+</div><div className="parcel-shadow" />
          <div className="parcel"><div className="parcel-top" /><div className="parcel-side" /><div className="parcel-front">
            <span className="parcel-tape" /><span className="parcel-brand">devarom.</span><span className="parcel-tagline">GOOD THINGS<br />ARE ON THEIR WAY.</span>
            <div className="parcel-label"><span>WITH CARE, ALWAYS.</span><i /><small>FROM ONE PERSON TO ANOTHER</small></div><span className="parcel-arrows">↑ ↑</span>
          </div></div>
          <div className="art-sticker"><ArrowIcon diagonal /><span>Made to<br /><strong>connect.</strong></span></div><span className="art-index">THE DEVAROM WAY / 01</span>
        </div>
      </section>
      <section className="promise-strip" aria-label="Our approach"><div className="page-container promise-inner">
        <span>Small parcels.</span><span className="promise-star" aria-hidden="true">✳</span><span>Big connections.</span><span className="promise-star" aria-hidden="true">✳</span><span>Thoughtfully delivered.</span><span className="promise-star" aria-hidden="true">✳</span>
      </div></section>
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
