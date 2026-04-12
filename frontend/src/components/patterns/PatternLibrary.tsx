/**
 * PatternLibrary — Browse and apply ODP/DOSDP patterns to the canvas.
 * Lists built-in patterns with preview, applies to board on click.
 */

import { useState, useEffect, useCallback } from "react";
import { apiJson } from "../../api";
import { useOntologyStore } from "../../store/ontologyStore";
import styles from "./PatternLibrary.module.css";

interface PatternSummary {
  id: string;
  name: string;
  description: string;
  category: string;
  class_count: number;
  property_count: number;
}

interface PatternDetail extends PatternSummary {
  classes: { iri: string; label: string }[];
  properties: { iri: string; label: string; source: string; target: string }[];
}

interface Props {
  boardId: string;
}

export default function PatternLibrary({ boardId }: Props) {
  const [patterns, setPatterns] = useState<PatternSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [selected, setSelected] = useState<PatternDetail | null>(null);
  const [applying, setApplying] = useState(false);
  const [applied, setApplied] = useState<string | null>(null);
  const [error, setError] = useState("");
  const store = useOntologyStore();

  useEffect(() => {
    apiJson<PatternSummary[]>("/api/patterns/")
      .then(setPatterns)
      .catch(() => setPatterns([]))
      .finally(() => setLoading(false));
  }, []);

  const loadDetail = async (id: string) => {
    try {
      const detail = await apiJson<PatternDetail>(`/api/patterns/${id}`);
      setSelected(detail);
    } catch { setError("Failed to load pattern details"); }
  };

  const applyPattern = useCallback(async (patternId: string) => {
    setApplying(true);
    setError("");
    try {
      const result = await apiJson<{ classes: any[]; properties: any[] }>(
        `/api/patterns/${boardId}/apply/${patternId}`,
        {
          method: "POST",
          body: JSON.stringify({
            base_iri: "http://example.org/ontology",
            x: 200 + Math.random() * 300,
            y: 100 + Math.random() * 200,
          }),
        }
      );
      // Reload the canvas to show new entities
      await store.loadFromBackend(boardId);
      setApplied(patternId);
      setTimeout(() => setApplied(null), 3000);
    } catch (e: any) {
      setError(e.message || "Failed to apply pattern");
    } finally {
      setApplying(false);
    }
  }, [boardId, store]);

  // Group patterns by category
  const categories = patterns.reduce((acc, p) => {
    if (!acc[p.category]) acc[p.category] = [];
    acc[p.category].push(p);
    return acc;
  }, {} as Record<string, PatternSummary[]>);

  return (
    <div className={styles.container}>
      <div className={styles.header}>
        <h3 className={styles.title}>Pattern Library</h3>
        <span className={styles.count}>{patterns.length} patterns</span>
      </div>

      {error && <div className={styles.error} onClick={() => setError("")}>{error}</div>}
      {applied && <div className={styles.success}>Pattern applied to canvas</div>}

      {loading ? (
        <div className={styles.empty}>Loading patterns...</div>
      ) : patterns.length === 0 ? (
        <div className={styles.empty}>
          <p>No patterns available.</p>
          <p className={styles.hint}>Patterns are ODP (Ontology Design Patterns) that provide reusable ontology building blocks.</p>
        </div>
      ) : (
        <div className={styles.list}>
          {Object.entries(categories).map(([cat, pats]) => (
            <div key={cat} className={styles.category}>
              <div className={styles.categoryTitle}>{cat}</div>
              {pats.map((p) => (
                <div
                  key={p.id}
                  className={`${styles.patternCard} ${selected?.id === p.id ? styles.patternSelected : ""}`}
                  onClick={() => loadDetail(p.id)}
                >
                  <div className={styles.patternHeader}>
                    <span className={styles.patternName}>{p.name}</span>
                    <span className={styles.patternMeta}>{p.class_count}C {p.property_count}P</span>
                  </div>
                  <div className={styles.patternDesc}>{p.description}</div>
                  <button
                    className={styles.applyBtn}
                    onClick={(e) => { e.stopPropagation(); applyPattern(p.id); }}
                    disabled={applying}
                  >
                    {applying ? "..." : "Apply"}
                  </button>
                </div>
              ))}
            </div>
          ))}
        </div>
      )}

      {/* Pattern detail preview */}
      {selected && (
        <div className={styles.preview}>
          <h4 className={styles.previewTitle}>{selected.name}</h4>
          <p className={styles.previewDesc}>{selected.description}</p>
          <div className={styles.previewSection}>
            <strong>Classes:</strong>
            {selected.classes.map((c) => (
              <span key={c.iri} className={styles.previewEntity}>{c.label}</span>
            ))}
          </div>
          {selected.properties.length > 0 && (
            <div className={styles.previewSection}>
              <strong>Properties:</strong>
              {selected.properties.map((p, i) => (
                <span key={i} className={styles.previewProp}>{p.label}</span>
              ))}
            </div>
          )}
          <button className={styles.applyBtnLg} onClick={() => applyPattern(selected.id)} disabled={applying}>
            {applying ? "Applying..." : "Apply to Board"}
          </button>
        </div>
      )}
    </div>
  );
}
