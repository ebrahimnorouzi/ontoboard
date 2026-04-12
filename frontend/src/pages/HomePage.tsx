import { useState } from "react";
import { useNavigate } from "react-router-dom";
import Navbar from "../components/Navbar";
import Logo from "../components/Logo";
import styles from "./HomePage.module.css";

export default function HomePage() {
  const [boardName, setBoardName] = useState("");
  const navigate = useNavigate();

  const handleGo = () => {
    const name = boardName.trim().replace(/[^a-zA-Z0-9_-]/g, "-");
    if (name) navigate(`/board/${name}`);
  };

  return (
    <div className={styles.page}>
      <Navbar />

      {/* Hero */}
      <section className={styles.hero}>
        <div className={styles.glowOrb} />
        <div className={styles.heroContent}>
          <Logo size={64} />
          <h1 className={styles.title}>
            Collaborative Ontology
            <br />
            <span className={styles.gradient}>Editing on an Infinite Canvas</span>
          </h1>
          <p className={styles.subtitle}>
            Visual ontology modeling with OWL, ROBOT templates, and the Ontology
            Development Kit — powered by Cytoscape.js on an infinite canvas.
          </p>

          {/* Quick-start input */}
          <div className={styles.inputRow}>
            <input
              className={styles.input}
              placeholder="Enter board name (e.g. pizza-ontology)"
              value={boardName}
              onChange={(e) => setBoardName(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && handleGo()}
            />
            <button className={styles.ctaBtn} onClick={handleGo} disabled={!boardName.trim()}>
              Open Board
            </button>
          </div>
          <p className={styles.hint}>
            or <a href="/board">browse existing boards</a>
          </p>
        </div>
      </section>

      {/* Features */}
      <section className={styles.features}>
        <div className={styles.featureGrid}>
          <FeatureCard
            icon="&#9632;"
            color="var(--accent)"
            title="Visual OWL Modeling"
            desc="Cytoscape.js-powered canvas with classes, individuals, literals. Draw edges for properties, SubClassOf, rdf:type. Manchester Syntax axiom editor."
          />
          <FeatureCard
            icon="&#8644;"
            color="var(--success)"
            title="Bi-directional Sync"
            desc="Import .owl, .ttl, .obo, .jsonld files or GitHub repos. Export to multiple formats. Full round-trip between visual canvas and OWL."
          />
          <FeatureCard
            icon="&#9881;"
            color="var(--warning)"
            title="ODK Pipeline"
            desc="Run ODK builds with one click. Stream make output in a live console. Full ROBOT integration."
          />
          <FeatureCard
            icon="&#9783;"
            color="var(--danger)"
            title="CSV to Knowledge Graph"
            desc="Upload CSVs, map columns to ontology classes, generate ROBOT templates and Turtle KGs."
          />
          <FeatureCard
            icon="&#9733;"
            color="var(--accent-light)"
            title="Real-time Collaboration"
            desc="Work together with live cursors, comments with @mentions, task boards, and invite links. See who's editing what in real-time."
          />
          <FeatureCard
            icon="&#9670;"
            color="#8b5cf6"
            title="Ontology Design Patterns"
            desc="Browse and apply ODPs from a built-in library. Drag-and-drop patterns onto the canvas. Each pattern gets a unique color."
          />
        </div>
      </section>

      {/* Footer */}
      <footer className={styles.footer}>
        <span>OntoBoard — Collaborative Ontology Engineering</span>
        <span className={styles.dot}>&#183;</span>
        <span>Built with ODK, Cytoscape.js, FastAPI</span>
        <span className={styles.dot}>&#183;</span>
        <a href="https://www.fiz-karlsruhe.de/en/forschung/information-service-engineering" target="_blank" rel="noreferrer" className={styles.footerLink}>ISE / FIZ Karlsruhe</a>
        <span className={styles.dot}>&#183;</span>
        <span>Developed by <a href="https://ebrahimnorouzi.github.io/" target="_blank" rel="noreferrer" className={styles.footerLink}>Ebrahim Norouzi</a></span>
        <span className={styles.dot}>&#183;</span>
        <a href="mailto:ebrahim.norouzi@fiz-karlsruhe.de" className={styles.footerLink}>Contact</a>
      </footer>
    </div>
  );
}

function FeatureCard({
  icon,
  color,
  title,
  desc,
}: {
  icon: string;
  color: string;
  title: string;
  desc: string;
}) {
  return (
    <div className={styles.card}>
      <div className={styles.cardIcon} style={{ color }}>
        {icon}
      </div>
      <h3 className={styles.cardTitle}>{title}</h3>
      <p className={styles.cardDesc}>{desc}</p>
    </div>
  );
}
