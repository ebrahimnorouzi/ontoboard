import { useEffect } from "react";
import { usePublish } from "../../hooks/usePublish";
import styles from "./PublishPanel.module.css";

interface Props {
  boardId: string;
}

export default function PublishPanel({ boardId }: Props) {
  const {
    checks, checking, runChecks,
    publishing, runPublish, events, progress,
    status, fetchStatus, error,
  } = usePublish(boardId);

  useEffect(() => { fetchStatus(); }, [fetchStatus]);

  const allChecksPassed = checks.length > 0 && checks.every((c) => c.passed);

  return (
    <div className={styles.container}>
      {/* Status */}
      {status && (
        <div className={styles.section}>
          <h4 className={styles.sectionTitle}>Status</h4>
          <div className={styles.statusGrid}>
            <div className={styles.statusItem}>
              <span className={styles.statusLabel}>Last Published</span>
              <span className={styles.statusValue}>
                {status.last_published
                  ? new Date(status.last_published).toLocaleDateString()
                  : "Never"}
              </span>
            </div>
            <div className={styles.statusItem}>
              <span className={styles.statusLabel}>Version</span>
              <span className={styles.statusValue}>{status.version || "—"}</span>
            </div>
            <div className={styles.statusItem}>
              <span className={styles.statusLabel}>Artifacts</span>
              <span className={styles.statusValue}>{status.artifacts.length} files</span>
            </div>
          </div>
          {status.artifacts.length > 0 && (
            <div className={styles.artifactList}>
              {status.artifacts.map((a) => (
                <span key={a} className={styles.artifact}>{a}</span>
              ))}
            </div>
          )}
        </div>
      )}

      {/* Step 1: Quality checks */}
      <div className={styles.section}>
        <div className={styles.sectionHeader}>
          <h4 className={styles.sectionTitle}>Step 1: Quality Checks</h4>
          <button className={styles.btn} onClick={runChecks} disabled={checking}>
            {checking ? "Running..." : "Run Checks"}
          </button>
        </div>
        {checks.length > 0 && (
          <div className={styles.checkList}>
            {checks.map((c, i) => (
              <div key={i} className={`${styles.checkRow} ${c.passed ? styles.checkPass : styles.checkFail}`}>
                <span className={styles.checkIcon}>{c.passed ? "\u2713" : "\u2717"}</span>
                <span className={styles.checkName}>{c.name}</span>
                <span className={styles.checkMsg}>{c.message}</span>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Step 2: Publish */}
      <div className={styles.section}>
        <div className={styles.sectionHeader}>
          <h4 className={styles.sectionTitle}>Step 2: Publish</h4>
          <button
            className={`${styles.btn} ${styles.publishBtn}`}
            onClick={() => runPublish()}
            disabled={publishing || (checks.length > 0 && !allChecksPassed)}
          >
            {publishing ? "Publishing..." : "Publish"}
          </button>
        </div>
        {checks.length > 0 && !allChecksPassed && (
          <p className={styles.hint}>Fix check failures before publishing.</p>
        )}

        {/* Progress bar */}
        {(publishing || progress > 0) && (
          <div className={styles.progressBar}>
            <div className={styles.progressFill} style={{ width: `${progress}%` }} />
          </div>
        )}

        {/* Event log */}
        {events.length > 0 && (
          <div className={styles.eventLog}>
            {events.map((evt, i) => (
              <div
                key={i}
                className={`${styles.eventRow} ${
                  evt.type.includes("fail") || evt.type === "error"
                    ? styles.eventError
                    : evt.type.includes("done") ? styles.eventDone : ""
                }`}
              >
                <span className={styles.eventStep}>[{evt.step}]</span>
                <span>{evt.message}</span>
              </div>
            ))}
          </div>
        )}
      </div>

      {error && <div className={styles.error}>{error}</div>}
    </div>
  );
}
