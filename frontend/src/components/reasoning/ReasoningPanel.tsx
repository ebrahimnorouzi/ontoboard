import { useState, useEffect } from "react";
import { useReasoning } from "../../hooks/useReasoning";
import { useOntologyStore } from "../../store/ontologyStore";
import styles from "./ReasoningPanel.module.css";

interface Props {
  boardId: string;
  onHighlightEntity?: (iri: string, color: "red" | "green" | null) => void;
}

const REASONERS = ["ELK", "HermiT", "JFact", "Whelk"];

export default function ReasoningPanel({ boardId, onHighlightEntity }: Props) {
  const { result, running, error, runReasoning, applyFix } = useReasoning(boardId);
  const [selectedReasoner, setSelectedReasoner] = useState("ELK");
  const store = useOntologyStore();

  // Store inferences in the ontology store when reasoning completes successfully
  useEffect(() => {
    if (result?.success && result.inferences.length > 0) {
      store.setInferences(result.inferences);
    } else if (result && !result.success) {
      store.setInferences([]);
    }
  }, [result]);

  return (
    <div className={styles.container}>
      {/* Reasoner selector + run button */}
      <div className={styles.controls}>
        <select
          className={styles.select}
          value={selectedReasoner}
          onChange={(e) => setSelectedReasoner(e.target.value)}
        >
          {REASONERS.map((r) => <option key={r} value={r}>{r}</option>)}
        </select>
        <button
          className={styles.runBtn}
          onClick={() => runReasoning(selectedReasoner)}
          disabled={running}
        >
          {running ? "Running..." : "Run Reasoning"}
        </button>
      </div>

      {error && <div className={styles.error}>{error}</div>}

      {result && (
        <>
          {/* Consistency banner */}
          <div className={`${styles.banner} ${result.consistent ? styles.bannerOk : styles.bannerFail}`}>
            <span className={styles.bannerIcon}>{result.consistent ? "\u2713" : "\u2717"}</span>
            <div>
              <div className={styles.bannerTitle}>
                {result.consistent ? "Ontology is Consistent" : "INCONSISTENT"}
              </div>
              <div className={styles.bannerMeta}>
                {result.reasoner} &middot; {result.duration_seconds.toFixed(1)}s
                &middot; {result.inferences.length} inferences
                &middot; {result.errors.length} errors
              </div>
            </div>
          </div>

          {/* Errors */}
          {result.errors.length > 0 && (
            <div className={styles.section}>
              <h4 className={styles.sectionTitle}>Errors ({result.errors.length})</h4>
              {result.errors.map((err, i) => (
                <div
                  key={i}
                  className={styles.errorRow}
                  onClick={() => err.entity_iri && onHighlightEntity?.(err.entity_iri, "red")}
                >
                  <span className={styles.errorIcon}>\u2717</span>
                  <div className={styles.errorContent}>
                    {err.entity_label && (
                      <span className={styles.errorEntity}>{err.entity_label}</span>
                    )}
                    <span className={styles.errorMsg}>{err.message}</span>
                  </div>
                </div>
              ))}
            </div>
          )}

          {/* Fix suggestions */}
          {result.fixes.length > 0 && (
            <div className={styles.section}>
              <h4 className={styles.sectionTitle}>Suggested Fixes</h4>
              {result.fixes.map((fix, i) => (
                <div key={i} className={styles.fixRow}>
                  <span className={styles.fixDesc}>{fix.description}</span>
                  <button
                    className={styles.fixBtn}
                    onClick={() => applyFix(fix)}
                  >
                    Apply
                  </button>
                </div>
              ))}
            </div>
          )}

          {/* Inferences */}
          {result.inferences.length > 0 && (
            <div className={styles.section}>
              <h4 className={styles.sectionTitle}>Inferences ({result.inferences.length})</h4>
              <div className={styles.inferenceList}>
                {result.inferences.slice(0, 50).map((inf, i) => (
                  <div
                    key={i}
                    className={styles.inferenceRow}
                    onClick={() => inf.subject && onHighlightEntity?.(inf.subject, "green")}
                  >
                    <span className={styles.infType}>{inf.inference_type}</span>
                    <span className={styles.infSubject}>{inf.subject_label || inf.subject}</span>
                    <span className={styles.infArrow}>&rarr;</span>
                    <span className={styles.infObject}>{inf.object_label || inf.object}</span>
                  </div>
                ))}
                {result.inferences.length > 50 && (
                  <div className={styles.more}>+{result.inferences.length - 50} more</div>
                )}
              </div>
            </div>
          )}

          {/* Logs — always visible when there is content */}
          {result.logs && result.logs.trim() && (
            <div className={styles.section}>
              <h4 className={styles.sectionTitle}>Reasoning Logs</h4>
              <pre className={styles.logPre}>{result.logs}</pre>
            </div>
          )}
        </>
      )}

      {!result && !running && (
        <p className={styles.hint}>
          Select a reasoner and click "Run Reasoning" to check ontology consistency
          and compute inferences.
        </p>
      )}
    </div>
  );
}
