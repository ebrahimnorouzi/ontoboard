/**
 * ImportsPanel — Manage OWL imports: resolve status, download, add, remove.
 */

import { useState, useEffect, useCallback } from "react";
import { apiJson } from "../../api";
import styles from "./ImportsPanel.module.css";

interface ImportEntry {
  iri: string;
  status: "local" | "remote" | "missing";
  local_path: string | null;
  classes_count: number;
  properties_count: number;
}

interface Props {
  boardId: string;
}

export default function ImportsPanel({ boardId }: Props) {
  const [imports, setImports] = useState<ImportEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [downloading, setDownloading] = useState<string | null>(null);
  const [newIri, setNewIri] = useState("");
  const [adding, setAdding] = useState(false);

  const loadImports = useCallback(async () => {
    setLoading(true);
    try {
      const data = await apiJson<ImportEntry[]>(`/api/imports/${boardId}/resolve`);
      setImports(data);
    } catch { setImports([]); }
    finally { setLoading(false); }
  }, [boardId]);

  useEffect(() => { loadImports(); }, [loadImports]);

  const downloadImport = useCallback(async (iri: string) => {
    setDownloading(iri);
    setError("");
    try {
      await apiJson(`/api/imports/${boardId}/download`, {
        method: "POST",
        body: JSON.stringify({ import_iri: iri }),
      });
      await loadImports();
    } catch (e: any) { setError(e.message || "Download failed"); }
    finally { setDownloading(null); }
  }, [boardId, loadImports]);

  const removeImport = useCallback(async (iri: string) => {
    try {
      await apiJson(`/api/imports/${boardId}/${encodeURIComponent(iri)}`, { method: "DELETE" });
      await loadImports();
    } catch (e: any) { setError(e.message || "Remove failed"); }
  }, [boardId, loadImports]);

  const addImport = useCallback(async () => {
    if (!newIri.trim()) return;
    setAdding(true);
    try {
      await apiJson(`/api/imports/${boardId}`, {
        method: "POST",
        body: JSON.stringify({ import_iri: newIri.trim() }),
      });
      setNewIri("");
      await loadImports();
    } catch (e: any) { setError(e.message || "Add failed"); }
    finally { setAdding(false); }
  }, [boardId, newIri, loadImports]);

  const statusColor = (s: string) =>
    s === "local" ? "var(--success)" : s === "remote" ? "var(--warning)" : "var(--danger)";

  return (
    <div className={styles.container}>
      <h4 className={styles.title}>Ontology Imports</h4>

      {error && <div className={styles.error} onClick={() => setError("")}>{error}</div>}

      {loading ? (
        <div className={styles.hint}>Loading imports...</div>
      ) : imports.length === 0 ? (
        <div className={styles.hint}>No imports declared.</div>
      ) : (
        <div className={styles.list}>
          {imports.map((imp) => (
            <div key={imp.iri} className={styles.row}>
              <div className={styles.statusDot} style={{ background: statusColor(imp.status) }}
                   title={imp.status} />
              <div className={styles.info}>
                <span className={styles.iri}>{imp.iri.split("/").pop()}</span>
                <span className={styles.fullIri} title={imp.iri}>{imp.iri}</span>
                {imp.status === "local" && (
                  <span className={styles.counts}>{imp.classes_count}C {imp.properties_count}P</span>
                )}
              </div>
              <div className={styles.actions}>
                {imp.status !== "local" && (
                  <button className={styles.downloadBtn}
                    onClick={() => downloadImport(imp.iri)}
                    disabled={downloading === imp.iri}>
                    {downloading === imp.iri ? "..." : "Download"}
                  </button>
                )}
                <button className={styles.removeBtn} onClick={() => removeImport(imp.iri)}>Remove</button>
              </div>
            </div>
          ))}
        </div>
      )}

      <div className={styles.addRow}>
        <input className={styles.addInput} value={newIri}
          onChange={(e) => setNewIri(e.target.value)}
          placeholder="http://example.org/ontology.owl"
          onKeyDown={(e) => e.key === "Enter" && addImport()} />
        <button className={styles.addBtn} onClick={addImport} disabled={adding || !newIri.trim()}>
          {adding ? "..." : "Add Import"}
        </button>
      </div>
    </div>
  );
}
