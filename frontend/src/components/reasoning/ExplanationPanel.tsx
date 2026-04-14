import { useState, useCallback } from "react";
import { apiJson } from "../../api";
import styles from "./ReasoningPanel.module.css";

interface ExplanationResult {
  entity: string;
  explanation_text: string;
  justification_axioms: string[];
  suggested_fixes: string[];
}

interface Props {
  boardId: string;
  entityIri?: string;
  reasoner: string;
  onHighlightEntity?: (iri: string, color: "red" | "green" | null) => void;
  onClose: () => void;
}

export default function ExplanationPanel({
  boardId,
  entityIri,
  reasoner,
  onHighlightEntity,
  onClose,
}: Props) {
  const [explanation, setExplanation] = useState<ExplanationResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const runExplain = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const url = entityIri
        ? `/api/reasoning/${boardId}/explain/${encodeURIComponent(entityIri)}`
        : `/api/reasoning/${boardId}/explain`;
      const result = await apiJson<ExplanationResult>(url, {
        method: "POST",
        body: JSON.stringify({ reasoner }),
      });
      setExplanation(result);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, [boardId, entityIri, reasoner]);

  /** Try to extract an IRI from an axiom string for highlighting. */
  const extractIri = (axiom: string): string | null => {
    const match = axiom.match(/<([^>]+)>/);
    if (match) return match[1];
    // Try to find a CURIE-like reference
    const curieMatch = axiom.match(/\b(\w+:\w+)\b/);
    if (curieMatch) return curieMatch[1];
    return null;
  };

  return (
    <div className={styles.section}>
      <div className={styles.explainHeader}>
        <h4 className={styles.sectionTitle}>
          {entityIri
            ? `Explanation: ${entityIri.includes("#") ? entityIri.split("#").pop() : entityIri.split("/").pop()}`
            : "Inconsistency Explanation"}
        </h4>
        <button className={styles.explainCloseBtn} onClick={onClose}>
          {"\u2715"}
        </button>
      </div>

      {!explanation && !loading && (
        <div className={styles.explainPrompt}>
          <p className={styles.explainPromptText}>
            Run ROBOT explain to get a justification for why
            {entityIri ? " this entity is unsatisfiable" : " the ontology is inconsistent"}.
          </p>
          <button
            className={styles.runBtn}
            onClick={runExplain}
            disabled={loading}
          >
            Run Explain
          </button>
        </div>
      )}

      {loading && (
        <div className={styles.explainLoading}>Analyzing with {reasoner}...</div>
      )}

      {error && <div className={styles.error}>{error}</div>}

      {explanation && (
        <>
          {/* Justification axioms */}
          {explanation.justification_axioms.length > 0 && (
            <div className={styles.explainAxiomList}>
              <div className={styles.explainSubtitle}>Justification Axioms</div>
              {explanation.justification_axioms.map((axiom, i) => {
                const iri = extractIri(axiom);
                return (
                  <div
                    key={i}
                    className={styles.explainAxiomRow}
                    onClick={() => iri && onHighlightEntity?.(iri, "red")}
                    title={iri ? `Click to highlight ${iri}` : undefined}
                    style={{ cursor: iri ? "pointer" : "default" }}
                  >
                    <span className={styles.explainAxiomIdx}>{i + 1}</span>
                    <span className={styles.explainAxiomText}>{axiom}</span>
                  </div>
                );
              })}
            </div>
          )}

          {/* Raw explanation text (shown if no axioms were parsed) */}
          {explanation.justification_axioms.length === 0 && explanation.explanation_text && (
            <pre className={styles.logPre}>{explanation.explanation_text}</pre>
          )}

          {/* Suggested fixes */}
          {explanation.suggested_fixes.length > 0 && (
            <div className={styles.explainFixes}>
              <div className={styles.explainSubtitle}>Suggested Fixes</div>
              {explanation.suggested_fixes.map((fix, i) => (
                <div key={i} className={styles.explainFixRow}>
                  <span className={styles.explainFixIcon}>{">"}</span>
                  <span className={styles.explainFixText}>{fix}</span>
                </div>
              ))}
            </div>
          )}
        </>
      )}
    </div>
  );
}
