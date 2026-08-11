import { formatCurrency } from "@/lib/format-currency";

const sources = [
  {
    name: "Google Organic",
    newMrr: 2000,
    retainedMrr: 900,
    retention: 45,
    signal: "Needs attention",
  },
  {
    name: "Reddit",
    newMrr: 700,
    retainedMrr: 650,
    retention: 93,
    signal: "Best quality",
  },
  {
    name: "Newsletter",
    newMrr: 520,
    retainedMrr: 460,
    retention: 88,
    signal: "Strong",
  },
];

export default function Home() {
  return (
    <main>
      <nav className="nav shell">
        <a className="brand" href="#top" aria-label="MRROrigin home">
          <span className="brand-mark">M</span>
          MRROrigin
        </a>
        <span className="phase">Phase 1 foundation</span>
      </nav>

      <section id="top" className="hero shell">
        <div className="eyebrow">Revenue-quality attribution for SaaS</div>
        <h1>
          Know where your MRR came from.
          <span>And whether it stayed.</span>
        </h1>
        <p className="hero-copy">
          Connect acquisition journeys to Stripe subscription movements. Compare
          the channels that create customers with the channels that create
          durable revenue.
        </p>
        <div className="hero-actions">
          <span className="primary-action">Private beta coming soon</span>
          <a href="https://github.com/MrH3lmy/mrr-origin">
            View the build on GitHub
          </a>
        </div>
      </section>

      <section className="preview shell" aria-labelledby="preview-title">
        <div className="preview-heading">
          <div>
            <p className="section-label">Illustrative dashboard</p>
            <h2 id="preview-title">
              Revenue that arrived vs. revenue that survived
            </h2>
          </div>
          <div className="period">Last 90 days</div>
        </div>

        <div className="metric-grid">
          <article>
            <span>New MRR</span>
            <strong>{formatCurrency(3220)}</strong>
            <small>Across attributed sources</small>
          </article>
          <article>
            <span>Retained MRR</span>
            <strong>{formatCurrency(2010)}</strong>
            <small>62% after 90 days</small>
          </article>
          <article>
            <span>Attribution coverage</span>
            <strong>91%</strong>
            <small>Deterministic evidence only</small>
          </article>
        </div>

        <div
          className="source-table"
          role="table"
          aria-label="Illustrative source quality"
        >
          <div className="source-row source-header" role="row">
            <span role="columnheader">Source</span>
            <span role="columnheader">New MRR</span>
            <span role="columnheader">90-day MRR</span>
            <span role="columnheader">Retention</span>
            <span role="columnheader">Signal</span>
          </div>
          {sources.map((source) => (
            <div className="source-row" role="row" key={source.name}>
              <strong role="cell">{source.name}</strong>
              <span role="cell">{formatCurrency(source.newMrr)}</span>
              <span role="cell">{formatCurrency(source.retainedMrr)}</span>
              <span role="cell">{source.retention}%</span>
              <span role="cell" className="signal">
                {source.signal}
              </span>
            </div>
          ))}
        </div>
      </section>

      <footer className="shell">
        <span>MRROrigin</span>
        <span>Evidence over vanity metrics.</span>
      </footer>
    </main>
  );
}
