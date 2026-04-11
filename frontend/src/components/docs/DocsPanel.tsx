import { useDocs } from "../../hooks/useDocs";
import styles from "./DocsPanel.module.css";

interface Props { boardId: string }

export default function DocsPanel({ boardId }: Props) {
  const { status, building, build, events, progress, files, viewContent, viewFile, error } = useDocs(boardId);

  return (
    <div className={styles.container}>
      {/* Status card */}
      {status && (
        <div className={styles.section}>
          <h4 className={styles.sectionTitle}>Documentation Status</h4>
          <div className={styles.statusGrid}>
            <div className={styles.statusItem}>
              <span className={styles.statusLabel}>Status</span>
              <span className={`${styles.statusValue} ${status.generated ? styles.ok : styles.pending}`}>
                {status.generated ? "Generated" : "Not generated"}
              </span>
            </div>
            <div className={styles.statusItem}>
              <span className={styles.statusLabel}>Last Built</span>
              <span className={styles.statusValue}>
                {status.last_built ? new Date(status.last_built).toLocaleString() : "Never"}
              </span>
            </div>
            <div className={styles.statusItem}>
              <span className={styles.statusLabel}>Pages</span>
              <span className={styles.statusValue}>{status.page_count}</span>
            </div>
          </div>
        </div>
      )}

      {/* Build button + progress */}
      <div className={styles.section}>
        <div className={styles.sectionHeader}>
          <h4 className={styles.sectionTitle}>Generate Documentation</h4>
          <button className={styles.buildBtn} onClick={build} disabled={building}>
            {building ? "Building..." : "Build Docs"}
          </button>
        </div>

        {(building || progress > 0) && (
          <div className={styles.progressBar}>
            <div className={styles.progressFill} style={{ width: `${progress}%` }} />
          </div>
        )}

        {events.length > 0 && (
          <div className={styles.eventLog}>
            {events.map((evt, i) => (
              <div key={i} className={`${styles.eventRow} ${evt.step.includes("warn") || evt.step.includes("skip") ? styles.eventWarn : evt.step === "done" ? styles.eventDone : ""}`}>
                <span className={styles.eventStep}>[{evt.step}]</span>
                <span>{evt.message}</span>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* File list */}
      {files.length > 0 && (
        <div className={styles.section}>
          <h4 className={styles.sectionTitle}>Documentation Files ({files.length})</h4>
          <div className={styles.fileList}>
            {files.map((f) => (
              <button key={f} className={styles.fileBtn} onClick={() => viewFile(f)}>
                {f}
              </button>
            ))}
          </div>
        </div>
      )}

      {/* File viewer */}
      {viewContent && (
        <div className={styles.section}>
          <div className={styles.sectionHeader}>
            <h4 className={styles.sectionTitle}>Preview</h4>
            <button className={styles.closeBtn} onClick={() => {}}>
              {/* just view the content */}
            </button>
          </div>
          <pre className={styles.viewer}>{viewContent}</pre>
        </div>
      )}

      {error && <div className={styles.error}>{error}</div>}
    </div>
  );
}
