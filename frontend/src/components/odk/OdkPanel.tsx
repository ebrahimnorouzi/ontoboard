/**
 * OdkPanel — Central ODK operations panel for the board editor.
 *
 * Exposes all 6 ODK workflows with live SSE terminal output:
 * 1. ODK Seed / Update Repo
 * 2. Refresh Imports
 * 3. Reasoning (ELK/HermiT)
 * 4. SPARQL Verification
 * 5. Release Pipeline
 * 6. DOSDP Patterns
 */

import { useState, useRef, useCallback } from "react";
import { api } from "../../api";
import styles from "./OdkPanel.module.css";

interface Props {
  boardId: string;
}

interface LogEntry {
  type: string;
  message: string;
  progress: number;
  ts: number;
}

export default function OdkPanel({ boardId }: Props) {
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [running, setRunning] = useState(false);
  const [currentOp, setCurrentOp] = useState("");
  const [reasoner, setReasoner] = useState("ELK");
  const [artifacts, setArtifacts] = useState<{ name: string; size: number }[]>([]);
  const logRef = useRef<HTMLPreElement>(null);

  const runWorkflow = useCallback(async (name: string, endpoint: string, body?: any) => {
    setRunning(true);
    setCurrentOp(name);
    setLogs([{ type: "info", message: `Starting: ${name}...`, progress: 0, ts: Date.now() }]);

    try {
      const res = await api(`/api/odk-mediator/${boardId}/${endpoint}`, {
        method: "POST",
        body: body ? JSON.stringify(body) : undefined,
      });

      if (!res.ok || !res.body) {
        setLogs((p) => [...p, { type: "error", message: `Request failed (${res.status})`, progress: 100, ts: Date.now() }]);
        setRunning(false);
        return;
      }

      const reader = res.body.getReader();
      const decoder = new TextDecoder();

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        const text = decoder.decode(value);
        for (const line of text.split("\n\n").filter(Boolean)) {
          const content = line.replace(/^data: /, "");
          try {
            const entry: LogEntry = JSON.parse(content);
            setLogs((p) => [...p, entry]);
            // Auto-scroll log
            if (logRef.current) {
              logRef.current.scrollTop = logRef.current.scrollHeight;
            }
          } catch {
            setLogs((p) => [...p, { type: "log", message: content, progress: 0, ts: Date.now() }]);
          }
        }
      }
    } catch (e: any) {
      setLogs((p) => [...p, { type: "error", message: e.message || "Connection lost", progress: 100, ts: Date.now() }]);
    } finally {
      setRunning(false);
    }
  }, [boardId]);

  const fetchArtifacts = useCallback(async () => {
    try {
      const res = await api(`/api/odk-mediator/${boardId}/release/artifacts`);
      if (res.ok) setArtifacts(await res.json());
    } catch {}
  }, [boardId]);

  return (
    <div className={styles.container}>
      {/* ── Workflow Buttons ─────────────────────────────────── */}
      <div className={styles.section}>
        <h4 className={styles.sectionTitle}>Project Lifecycle</h4>
        <div className={styles.btnRow}>
          <button className={styles.actionBtn} onClick={() => runWorkflow("ODK Seed", "seed")} disabled={running}>
            ODK Seed
          </button>
          <button className={styles.actionBtn} onClick={() => runWorkflow("Update Repository", "update-repo")} disabled={running}>
            Update Repo
          </button>
        </div>
      </div>

      <div className={styles.section}>
        <h4 className={styles.sectionTitle}>Dependencies</h4>
        <button className={styles.actionBtn} onClick={() => runWorkflow("Refresh Imports", "refresh-imports")} disabled={running}>
          Refresh Imports
        </button>
      </div>

      <div className={styles.section}>
        <h4 className={styles.sectionTitle}>Validation</h4>
        <div className={styles.btnRow}>
          <select className={styles.select} value={reasoner} onChange={(e) => setReasoner(e.target.value)}>
            <option value="ELK">ELK</option>
            <option value="HermiT">HermiT</option>
            <option value="JFact">JFact</option>
          </select>
          <button className={styles.actionBtn} onClick={() => runWorkflow(`Reason (${reasoner})`, "reason", { reasoner })} disabled={running}>
            Run Reasoner
          </button>
          <button className={styles.actionBtn} onClick={() => runWorkflow("Test Suite", "test")} disabled={running}>
            Run Tests
          </button>
        </div>
      </div>

      <div className={styles.section}>
        <h4 className={styles.sectionTitle}>Release</h4>
        <div className={styles.btnRow}>
          <button className={`${styles.actionBtn} ${styles.releaseBtn}`}
                  onClick={() => { runWorkflow("Release Pipeline", "release"); setTimeout(fetchArtifacts, 5000); }}
                  disabled={running}>
            Build Release
          </button>
          <button className={styles.actionBtn} onClick={fetchArtifacts}>
            List Artifacts
          </button>
        </div>
        {artifacts.length > 0 && (
          <div className={styles.artifactList}>
            {artifacts.map((a) => (
              <a key={a.name} className={styles.artifactLink}
                 href={`/api/odk-mediator/${boardId}/release/download/${a.name}`}
                 target="_blank" rel="noreferrer">
                {a.name} ({(a.size / 1024).toFixed(1)} KB)
              </a>
            ))}
          </div>
        )}
      </div>

      {/* ── Live Terminal Output ─────────────────────────────── */}
      <div className={styles.terminal}>
        <div className={styles.termHeader}>
          <span className={styles.termTitle}>
            {running ? `Running: ${currentOp}` : currentOp ? `Completed: ${currentOp}` : "ODK Terminal"}
          </span>
          {running && <span className={styles.spinner} />}
          <button className={styles.clearBtn} onClick={() => setLogs([])}>Clear</button>
        </div>
        <pre className={styles.termBody} ref={logRef}>
          {logs.length === 0 ? (
            <span className={styles.termHint}>Click a workflow button above to see live output...</span>
          ) : (
            logs.map((entry, i) => (
              <div key={i} className={`${styles.logLine} ${
                entry.type === "error" ? styles.logError :
                entry.type === "success" ? styles.logSuccess :
                entry.type === "step" ? styles.logStep :
                entry.type === "info" ? styles.logInfo : ""
              }`}>
                {entry.message}
              </div>
            ))
          )}
        </pre>
      </div>
    </div>
  );
}
