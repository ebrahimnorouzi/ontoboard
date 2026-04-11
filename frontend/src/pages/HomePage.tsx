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
            Visual modeling with OWL, ROBOT templates, and the Ontology
            Development Kit — all in one place.
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
            desc="Drag squares for classes, draw arrows for properties. Manchester Syntax editor for complex axioms."
          />
          <FeatureCard
            icon="&#8644;"
            color="var(--success)"
            title="Bi-directional Sync"
            desc="Load existing .owl files onto the canvas. Save canvas changes back to ODK-compatible OWL."
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
        </div>
      </section>

      {/* Footer */}
      <footer className={styles.footer}>
        <span>Built with ODK, Tldraw, FastAPI</span>
        <span className={styles.dot}>&#183;</span>
        <span>ISE / FIZ Karlsruhe</span>
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
