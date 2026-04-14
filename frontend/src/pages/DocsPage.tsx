/**
 * DocsPage — Public documentation for OntoBoard.
 * Renders markdown-like content about features, getting started, and architecture.
 */

import { useState } from "react";
import { Link } from "react-router-dom";
import Navbar from "../components/Navbar";
import styles from "./DocsPage.module.css";

const SECTIONS = [
  { id: "overview", title: "Overview" },
  { id: "getting-started", title: "Getting Started" },
  { id: "features", title: "Features" },
  { id: "architecture", title: "Architecture" },
  { id: "comparison", title: "vs Protege" },
  { id: "collaboration", title: "Collaboration" },
  { id: "limitations", title: "Limitations" },
];

export default function DocsPage() {
  const [active, setActive] = useState("overview");

  return (
    <div className={styles.page}>
      <Navbar />
      <div className={styles.layout}>
        {/* Sidebar */}
        <nav className={styles.sidebar}>
          <h3 className={styles.sidebarTitle}>Documentation</h3>
          {SECTIONS.map((s) => (
            <button key={s.id} className={`${styles.navItem} ${active === s.id ? styles.navActive : ""}`}
              onClick={() => setActive(s.id)}>{s.title}</button>
          ))}
          <div className={styles.sidebarDivider} />
          <Link to="/board" className={styles.navItem}>Go to Boards</Link>
        </nav>

        {/* Content */}
        <main className={styles.content}>
          {active === "overview" && <OverviewSection />}
          {active === "getting-started" && <GettingStartedSection />}
          {active === "features" && <FeaturesSection />}
          {active === "architecture" && <ArchitectureSection />}
          {active === "comparison" && <ComparisonSection />}
          {active === "collaboration" && <CollaborationSection />}
          {active === "limitations" && <LimitationsSection />}
        </main>
      </div>
    </div>
  );
}

function OverviewSection() {
  return (
    <article className={styles.article}>
      <h1>OntoBoard</h1>
      <p className={styles.lead}>
        A collaborative ontology engineering platform built on top of the Ontology Development Kit (ODK).
        OntoBoard provides visual ontology modeling on an infinite canvas with full ROBOT pipeline integration,
        real-time collaboration, and comprehensive ontology lifecycle management.
      </p>
      <h2>Who is it for?</h2>
      <ul>
        <li><strong>Ontology engineers</strong> who want a visual, web-based alternative to Protege</li>
        <li><strong>Research teams</strong> who need collaborative ontology development</li>
        <li><strong>Instructors</strong> teaching ontology engineering concepts</li>
        <li><strong>ODK users</strong> who want a GUI for their ODK workflows</li>
      </ul>
      <h2>Key Capabilities</h2>
      <div className={styles.grid}>
        <Card title="Visual Canvas" desc="Infinite Cytoscape.js canvas with drag-drop, edge drawing, layouts, minimap" />
        <Card title="Manchester Syntax" desc="Full parser: A and (B or C), some, only, min/max, nested expressions" />
        <Card title="ROBOT Integration" desc="7 commands: convert, report, reason, template, diff, query, explain" />
        <Card title="Real-time Collab" desc="Cursors, entity locking, instant save broadcast, comments, tasks" />
        <Card title="Pattern Library" desc="13 ODPA patterns, drag-drop, user uploads, auto-coloring" />
        <Card title="Import Management" desc="Resolve, download, catalog management for OWL imports" />
      </div>
    </article>
  );
}

function GettingStartedSection() {
  return (
    <article className={styles.article}>
      <h1>Getting Started</h1>
      <h2>Prerequisites</h2>
      <ul>
        <li>Docker and Docker Compose</li>
        <li>~2 GB disk space (no odkfull image needed)</li>
      </ul>
      <h2>Quick Start</h2>
      <pre className={styles.code}>{`git clone https://github.com/ISE-FIZKarlsruhe/ontoboard.git
cd ontoboard
./run.sh           # Build + start everything`}</pre>
      <p>Open <strong>http://localhost:3000</strong> and login with <code>admin</code> / <code>admin</code>.</p>
      <h2>Creating Your First Board</h2>
      <ol>
        <li>Click <strong>+ Create Board</strong> on the dashboard</li>
        <li>Enter an ID (e.g., <code>pizza</code>) and title</li>
        <li>Choose mode: <strong>ODK</strong> (full scaffold) or <strong>Blank</strong> (minimal)</li>
        <li>Start adding classes by double-clicking the canvas</li>
        <li>Draw edges by dragging from node handles</li>
      </ol>
      <h2>Importing from GitHub</h2>
      <ol>
        <li>Click <strong>+ Create Board</strong></li>
        <li>Select <strong>From GitHub Repository</strong></li>
        <li>Paste the repo URL (e.g., <code>https://github.com/ISE-FIZKarlsruhe/mwo</code>)</li>
        <li>OWL Functional Syntax files are auto-converted via ROBOT</li>
      </ol>
      <h2>Commands</h2>
      <pre className={styles.code}>{`./run.sh              # Build + start
./run.sh build        # Force rebuild images
./run.sh dev          # Development mode (hot-reload)
./run.sh test         # Run 455+ backend tests
./run.sh logs         # View logs
./run.sh down         # Stop services`}</pre>
    </article>
  );
}

function FeaturesSection() {
  return (
    <article className={styles.article}>
      <h1>Features</h1>
      <h2>Manchester Syntax Parser</h2>
      <p>Recursive descent parser supporting full OWL 2 class expressions:</p>
      <pre className={styles.code}>{`Animal and hasPart some (Organ or Tissue) and not Plant
hasPart min 2 Wing
eats only (Plant or Insect)
not (Cat and Dog)`}</pre>
      <p>Bidirectional: parse Manchester to RDF triples, render RDF back to Manchester.</p>

      <h2>Property Characteristics</h2>
      <p>Checkboxes for all 7 OWL property characteristics: Functional, InverseFunctional,
      Transitive, Symmetric, Asymmetric, Reflexive, Irreflexive. Plus property chain editor.</p>

      <h2>ROBOT Explain (Ontology Debugging)</h2>
      <p>Click "Explain" on any reasoning error to see justification axioms and suggested fixes.</p>

      <h2>SWRL Rules</h2>
      <p>View, create, and delete SWRL rules in human-readable format:</p>
      <pre className={styles.code}>{`Person(?x) ^ hasAge(?x, ?a) ^ greaterThan(?a, 18) -> Adult(?x)`}</pre>

      <h2>Embedded Reasoner</h2>
      <p>owlready2 integration as alternative to ROBOT subprocess. Supports HermiT and Pellet.</p>

      <h2>Ontology Design Patterns</h2>
      <p>13 curated ODPA patterns: Part-Of, Participation, Classification, Agent-Role, Situation,
      Collection, Sequence, Description, Observation, Time-Interval, Place, Co-Participation, Information-Realization.</p>

      <h2>Ontology File Switcher</h2>
      <p>Dropdown to switch between edit, release, and import module OWL files within a board.</p>
    </article>
  );
}

function ArchitectureSection() {
  return (
    <article className={styles.article}>
      <h1>Architecture</h1>
      <p>5-service microservice architecture. ROBOT 1.9.6 and Java 21 are installed directly
      in the backend and worker images (no Docker-in-Docker).</p>
      <pre className={styles.code}>{`Frontend (React 18 + Cytoscape.js)     :3000
Backend  (FastAPI + ROBOT + Java)       :8000
Collab   (Hocuspocus / Yjs WebSocket)   :1234
Worker   (Python + ROBOT + Java)        (background)
Redis    (Job queue + pub/sub)          :6379`}</pre>
      <h2>Technology Stack</h2>
      <table className={styles.table}>
        <thead><tr><th>Layer</th><th>Technologies</th></tr></thead>
        <tbody>
          <tr><td>Frontend</td><td>React 18, TypeScript 5.6, Cytoscape.js 3.30, Zustand 5, Monaco Editor, Vite 5</td></tr>
          <tr><td>Backend</td><td>FastAPI 0.115, Python 3.12, rdflib 7.1, owlready2 0.47, SQLAlchemy 2</td></tr>
          <tr><td>Ontology</td><td>ROBOT 1.9.6, Java 21, OWL API</td></tr>
          <tr><td>Collab</td><td>Yjs 13.6, y-websocket, Hocuspocus 3.4</td></tr>
          <tr><td>Database</td><td>SQLite (dev) / PostgreSQL-ready</td></tr>
          <tr><td>Infra</td><td>Docker, Docker Compose, Redis 7</td></tr>
        </tbody>
      </table>
      <h2>Test Suite</h2>
      <p>455+ test functions across 46 test files covering: Manchester parser, property characteristics,
      chains, XSD ranges, ROBOT explain, imports, SWRL, embedded reasoner, canvas operations, and more.</p>
    </article>
  );
}

function ComparisonSection() {
  return (
    <article className={styles.article}>
      <h1>OntoBoard vs Protege</h1>
      <table className={styles.table}>
        <thead><tr><th>Feature</th><th>Protege</th><th>OntoBoard</th></tr></thead>
        <tbody>
          <tr><td>Manchester Syntax editing</td><td>Yes</td><td>Yes (recursive descent)</td></tr>
          <tr><td>Property characteristics (all 7)</td><td>Yes</td><td>Yes</td></tr>
          <tr><td>Property chains</td><td>Yes</td><td>Yes</td></tr>
          <tr><td>Reasoner (ELK, HermiT)</td><td>Yes</td><td>Yes (ROBOT + owlready2)</td></tr>
          <tr><td>Explanation / justification</td><td>Yes</td><td>Yes (ROBOT explain)</td></tr>
          <tr><td>SWRL rules</td><td>Yes</td><td>Yes (annotation-based)</td></tr>
          <tr><td>Visual graph canvas</td><td>Plugin</td><td>Built-in (Cytoscape.js)</td></tr>
          <tr><td>Real-time collaboration</td><td>No</td><td>Yes (Yjs/Hocuspocus)</td></tr>
          <tr><td>Web-based (no install)</td><td>No (desktop)</td><td>Yes (Docker)</td></tr>
          <tr><td>ODK/ROBOT integration</td><td>No</td><td>Yes</td></tr>
          <tr><td>Pattern library</td><td>No</td><td>Yes (13 ODPA)</td></tr>
          <tr><td>Import resolution UI</td><td>Partial</td><td>Yes</td></tr>
          <tr><td>DataRange restrictions</td><td>Yes</td><td>No</td></tr>
          <tr><td>Plugin ecosystem</td><td>Yes (100+)</td><td>No</td></tr>
        </tbody>
      </table>
    </article>
  );
}

function CollaborationSection() {
  return (
    <article className={styles.article}>
      <h1>Collaboration</h1>
      <h2>How It Works</h2>
      <ul>
        <li><strong>Cursors</strong>: Real-time via Yjs awareness (~50ms latency)</li>
        <li><strong>Entity locking</strong>: When you select an entity, others see it highlighted with your color</li>
        <li><strong>Save broadcast</strong>: When you save, all other users reload within ~1 second</li>
        <li><strong>Polling fallback</strong>: 30-second backend poll when Yjs disconnects</li>
      </ul>
      <h2>Scaling Guide</h2>
      <table className={styles.table}>
        <thead><tr><th>Users</th><th>Experience</th></tr></thead>
        <tbody>
          <tr><td>1-5</td><td>Seamless, no coordination needed</td></tr>
          <tr><td>5-10</td><td>Entity locks show who's editing what</td></tr>
          <tr><td>10-15</td><td>Works well if users edit different parts</td></tr>
          <tr><td>15+</td><td>Consider separate boards and merging</td></tr>
        </tbody>
      </table>
    </article>
  );
}

function LimitationsSection() {
  return (
    <article className={styles.article}>
      <h1>Known Limitations</h1>

      <h2>Resolved in Current Version</h2>
      <ul>
        <li><s>Manchester parser: no HasSelf, no ObjectOneOf, no datatype restrictions</s> — <strong>All implemented</strong>: <code>likes Self</code>, <code>{"{john, jane}"}</code>, <code>xsd:integer[&gt;= 0, &lt;= 100]</code></li>
        <li><s>SWRL: stored as annotations</s> — <strong>Native OWL/XML format</strong>: <code>swrl:Imp</code> + <code>swrl:ClassAtom</code> + <code>swrl:Variable</code></li>
        <li><s>DataRange restrictions</s> — <strong>Implemented</strong> with facet support</li>
      </ul>

      <h2>Remaining Limitations</h2>
      <ol>
        <li><strong>Collaboration model</strong>: polling-based with instant save broadcast (not CRDT). Last-write-wins for concurrent edits. Entity locking is advisory only. Tested up to ~10-15 users.</li>
        <li><strong>Scale</strong>: canvas performance degrades beyond ~500 classes (Cytoscape.js limitation). Use tree browser for larger ontologies.</li>
        <li><strong>ROBOT commands</strong>: 7 of 24 fully implemented (convert, report, reason, template, diff, query, explain)</li>
        <li><strong>No plugin system</strong>: cannot extend without modifying source code</li>
        <li><strong>No offline mode</strong>: requires network connectivity</li>
        <li><strong>No SHACL validation</strong>: only OWL reasoning</li>
        <li><strong>No OBO format editing</strong>: read-only via rdflib</li>
        <li><strong>owlready2 Windows issues</strong>: file:/// URI handling on Windows paths</li>
      </ol>

      <h2>Roadmap</h2>
      <ul>
        <li>Operation-based real-time sync (CRDT for ontology operations)</li>
        <li>SHACL validation via pyshacl</li>
        <li>More ROBOT commands (merge, extract, annotate, rename, repair)</li>
        <li>Canvas scale optimization (WebGL rendering or viewport virtualization)</li>
        <li>OBO format writing via ROBOT convert</li>
      </ul>
    </article>
  );
}

function Card({ title, desc }: { title: string; desc: string }) {
  return (
    <div className={styles.card}>
      <h3 className={styles.cardTitle}>{title}</h3>
      <p className={styles.cardDesc}>{desc}</p>
    </div>
  );
}
