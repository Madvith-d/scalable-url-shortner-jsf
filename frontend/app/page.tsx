import Link from "next/link";
import { CreateForm } from "@/components/create-form";

export default function HomePage() {
  return <div className="container home-page">
    <section className="hero"><div><p className="eyebrow"><span className="live-dot" /> A LITTLE LINK. A BIG POSSIBILITY.</p><h1>Less link.<br /><span>More possibility.</span></h1><p className="hero-description">Make every destination easier to share.<br className="desktop-break" /> Create a short link, make it yours, and see where it goes.</p><div className="hero-tags"><span>Custom aliases</span><span>Click analytics</span><span>One clear workspace</span></div></div><aside className="hero-note"><span className="note-arrow" aria-hidden="true">↗</span><p>A shorter path to<br />what matters.</p><span>CREATE / SHARE / UNDERSTAND</span></aside></section>
    <section className="panel create-panel" aria-labelledby="create-title"><div className="panel-heading"><div><p className="eyebrow">01 / MAKE THE CONNECTION</p><h2 id="create-title">Give your link a little less length.</h2></div><span className="panel-symbol" aria-hidden="true">↗</span></div><CreateForm /></section>
    <section className="feature-grid" aria-label="How Shortify works"><article><span className="feature-number">01</span><h3>Make it memorable.</h3><p>Choose a custom alias or let us generate a short code. Same destination, a simpler way there.</p></article><article><span className="feature-number">02</span><h3>Stay in control.</h3><p>Set an expiration and pause or reactivate your links whenever you need to.</p></article><article><span className="feature-number">03</span><h3>See the real activity.</h3><p>Explore recorded clicks, daily activity, referrers, and devices. No invented numbers.</p></article></section>
    <div className="home-bottom"><p>Already have links out in the world?</p><Link className="text-link" href="/dashboard">Open your dashboard <span aria-hidden="true">→</span></Link></div>
  </div>;
}
