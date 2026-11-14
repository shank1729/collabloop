import { productName } from "../brand";
import BrandLockup from "./BrandLockup";
import "./LandingPage.css";

const features = [
  { number: "01", title: "Write in sync", description: "Edit the same document live, see who’s working with you, and revisit earlier versions when you need them.",
    icon: "M6 3h8l4 4v14H6z M14 3v5h4 M9 12h6 M9 16h6" },
  { number: "02", title: "Sketch it out", description: "Use pens, colors, and shapes on a shared whiteboard to map a flow or show what you mean.",
    icon: "M4 5h16v14H4z M7 15l3-4 3 2 4-5" },
  { number: "03", title: "Share on your terms", description: "Invite collaborators as editors or viewers, with access you can change whenever the work calls for it.",
    icon: "M8 13a3 3 0 1 0 0-6 3 3 0 0 0 0 6z M2 20v-1a4 4 0 0 1 4-4h4a4 4 0 0 1 4 4v1 M17 7a3 3 0 0 1 0 6 M22 20v-1a4 4 0 0 0-4-4" },
];

function WorkspacePreview() {
  return (
    <figure className="landing-preview" aria-label="Illustration of a shared document and whiteboard">
      <div className="landing-preview-bar" aria-hidden="true">
        <span className="landing-preview-dots"><i /><i /><i /></span>
        <span>Studio notes</span>
        <span className="landing-preview-live"><i /> Live</span>
      </div>
      <div className="landing-preview-body" aria-hidden="true">
        <div className="landing-preview-note">
          <span className="landing-preview-label">DOCUMENT</span>
          <strong>The next<br />big idea.</strong>
          <span className="landing-preview-line" />
          <span className="landing-preview-line" />
          <span className="landing-preview-line landing-preview-line-short" />
          <span className="landing-preview-chip">First draft</span>
        </div>
        <div className="landing-preview-board">
          <span className="landing-preview-label">WHITEBOARD</span>
          <svg viewBox="0 0 310 230" fill="none" focusable="false">
            <g stroke="#246757" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M28 30Q33 25 123 30L123 83Q84 86 28 81Z" fill="#e2efbb" />
              <path d="M124 58C163 54 173 60 181 88M172 81l10 10 5-13" />
              <path d="M176 97Q222 92 268 96L272 153Q226 155 176 152Z" fill="#fbdccc" />
              <path d="M178 127C143 122 121 139 117 167M110 158l6 12 11-9" />
              <path d="M49 182c16-11 44-13 72-7 27 5 36 22 21 32-20 14-77 14-100 2-16-9-11-21 7-27Z" fill="#d5e9f3" />
              <path d="M279 61v18m-9-9h18M38 141v13m-7-6h14" />
            </g>
            <g fill="#183a33" fontSize="16" fontWeight="600" textAnchor="middle">
              <text x="75" y="61">Idea</text>
              <text x="224" y="130">Sketch</text>
              <text x="92" y="199">Build</text>
            </g>
          </svg>
          <span className="landing-preview-cursor">↖ <span>Maya</span></span>
        </div>
      </div>
    </figure>
  );
}

export default function LandingPage({ signedIn, onEnter, onSignIn }) {
  const primaryLabel = signedIn ? "Open workspace" : "Get started";

  return (
    <div className="landing-page" id="top">
      <header className="landing-nav landing-container">
        <a className="landing-brand" href="#top">
          <BrandLockup />
        </a>
        <nav aria-label="Product navigation">
          <a href="#features">Features</a>
          <button className="landing-nav-action" type="button" onClick={signedIn ? onEnter : onSignIn}>
            {signedIn ? "Workspace" : "Sign in"}
          </button>
        </nav>
      </header>

      <main className="landing-container">
        <section className="landing-hero" aria-labelledby="landing-title">
          <div>
            <p className="landing-eyebrow"><span /> A live workspace for shared ideas</p>
            <h1 id="landing-title">Write together.<br /><em>Think out loud.</em></h1>
            <p className="landing-intro">
              {productName} brings live documents and shared whiteboards into one workspace.
              Start with a note, sketch the messy bits, and build on each other’s ideas.
            </p>
            <div className="landing-hero-actions">
              <button className="landing-primary" type="button" onClick={onEnter}>
                {primaryLabel} <span aria-hidden="true">↗</span>
              </button>
              <a href="#features">Explore features <span aria-hidden="true">↓</span></a>
            </div>
          </div>
          <WorkspacePreview />
        </section>

        <section className="landing-features" id="features" aria-labelledby="features-title">
          <p className="landing-eyebrow">Inside the workspace</p>
          <h2 id="features-title">From first thought to shared plan.</h2>
          <div className="landing-feature-grid">
            {features.map(({ number, title, description, icon }) => (
              <article className="landing-feature-card" key={title}>
                <div className="landing-feature-top">
                  <span className="landing-feature-icon" aria-hidden="true">
                    <svg viewBox="0 0 24 24"><path d={icon} /></svg>
                  </span>
                  <span aria-hidden="true">{number}</span>
                </div>
                <h3>{title}</h3>
                <p>{description}</p>
              </article>
            ))}
          </div>
        </section>
      </main>

      <footer className="landing-footer landing-container">
        <BrandLockup />
        <a href="#top">Back to top <span aria-hidden="true">↑</span></a>
      </footer>
    </div>
  );
}
