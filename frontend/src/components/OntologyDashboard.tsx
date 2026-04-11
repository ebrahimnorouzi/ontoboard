/**
 * OntologyDashboard — Editable ontology metadata, statistics, prefixes, and annotations.
 *
 * Right panel "Onto" tab showing:
 * - Editable Ontology IRI + Version IRI
 * - Version management with bump buttons
 * - Statistics (read-only)
 * - Editable prefix table (add/delete)
 * - Ontology annotations (dc:creator, rdfs:comment, etc.)
 * - ROBOT report trigger
 */

import { useState, useCallback } from "react";
import { useOntologyDashboard } from "../hooks/useOntology";
import { apiJson } from "../api";
import styles from "./OntologyDashboard.module.css";

interface Props {
  boardId: string;
}

export default function OntologyDashboard({ boardId }: Props) {
  const { data, loading, error, runReport, reportLoading } = useOntologyDashboard(boardId);
  const [newPrefix, setNewPrefix] = useState("");
  const [newNs, setNewNs] = useState("");
  const [editingVersion, setEditingVersion] = useState(false);
  const [versionInput, setVersionInput] = useState("");
  const [metaFields, setMetaFields] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState(false);

  const saveMetadata = useCallback(async (fields: Record<string, string>) => {
    setSaving(true);
    try {
      await apiJson(`/api/ontology/${boardId}/metadata`, {
        method: "PUT",
        body: JSON.stringify({ fields }),
      });
    } catch {}
    finally { setSaving(false); }
  }, [boardId]);

  const addPrefix = useCallback(async () => {
    if (!newPrefix || !newNs) return;
    try {
      await apiJson(`/api/ontology/${boardId}/prefixes`, {
        method: "POST",
        body: JSON.stringify({ prefix: newPrefix, namespace: newNs }),
      });
      setNewPrefix("");
      setNewNs("");
    } catch {}
  }, [boardId, newPrefix, newNs]);

  const bumpVersion = useCallback(async (bump: string) => {
    try {
      await apiJson(`/api/version/${boardId}`, {
        method: "PUT",
        body: JSON.stringify({ bump }),
      });
    } catch {}
  }, [boardId]);

  if (loading) return <div className={styles.loading}>Loading ontology info...</div>;
  if (error) return <div className={styles.error}>{error}</div>;
  if (!data) return null;

  const { metadata, statistics, report } = data;

  return (
    <div className={styles.dashboard}>
      {/* ── Editable IRI & Version ────────────────────── */}
      <section className={styles.section}>
        <h3 className={styles.sectionTitle}>Ontology Identity</h3>
        <div className={styles.fieldGroup}>
          <label className={styles.fieldLabel}>Ontology IRI</label>
          <div className={styles.fieldValue}>{metadata.ontology_iri || "—"}</div>
        </div>
        <div className={styles.fieldGroup}>
          <label className={styles.fieldLabel}>Version IRI</label>
          <div className={styles.fieldValue}>{metadata.version_iri || "—"}</div>
        </div>
        <div className={styles.fieldGroup}>
          <label className={styles.fieldLabel}>Version</label>
          <div className={styles.versionRow}>
            <span className={styles.fieldValue}>
              {metadata.version_iri?.split("/").pop() || "0.1.0"}
            </span>
            <button className={styles.smallBtn} onClick={() => bumpVersion("patch")}>+Patch</button>
            <button className={styles.smallBtn} onClick={() => bumpVersion("minor")}>+Minor</button>
            <button className={styles.smallBtn} onClick={() => bumpVersion("major")}>+Major</button>
          </div>
        </div>
        <div className={styles.fieldGroup}>
          <label className={styles.fieldLabel}>Languages</label>
          <div className={styles.fieldValue}>{metadata.languages.join(", ") || "en"}</div>
        </div>
      </section>

      {/* ── Ontology Annotations (Editable) ───────────── */}
      <section className={styles.section}>
        <div className={styles.sectionHeader}>
          <h3 className={styles.sectionTitle}>Annotations</h3>
          <button className={styles.smallBtn} onClick={() => {
            saveMetadata(metaFields);
          }} disabled={saving}>
            {saving ? "Saving..." : "Save"}
          </button>
        </div>
        {["title", "creator", "description", "license", "contributor"].map((field) => (
          <div key={field} className={styles.fieldGroup}>
            <label className={styles.fieldLabel}>{field}</label>
            <input
              className={styles.editInput}
              placeholder={`Enter ${field}...`}
              defaultValue={metaFields[field] || ""}
              onChange={(e) => setMetaFields((p) => ({ ...p, [field]: e.target.value }))}
            />
          </div>
        ))}
      </section>

      {/* ── Statistics ────────────────────────────────── */}
      <section className={styles.section}>
        <h3 className={styles.sectionTitle}>Statistics</h3>
        <div className={styles.statsGrid}>
          <StatCell label="Classes" value={statistics.classes} color="#6366f1" />
          <StatCell label="Obj Props" value={statistics.object_properties} color="#10b981" />
          <StatCell label="Data Props" value={statistics.data_properties} color="#f59e0b" />
          <StatCell label="Individuals" value={statistics.individuals} color="#ef4444" />
          <StatCell label="Axioms" value={statistics.total_axioms} color="#8b5cf6" />
          <StatCell label="Triples" value={statistics.total_triples} color="#64748b" />
        </div>
      </section>

      {/* ── Editable Prefixes ─────────────────────────── */}
      <section className={styles.section}>
        <h3 className={styles.sectionTitle}>Prefixes ({metadata.prefixes.length})</h3>
        <div className={styles.addPrefixRow}>
          <input className={styles.prefixInput} placeholder="prefix" value={newPrefix}
                 onChange={(e) => setNewPrefix(e.target.value)} />
          <input className={styles.nsInput} placeholder="http://namespace.org/"
                 value={newNs} onChange={(e) => setNewNs(e.target.value)} />
          <button className={styles.smallBtn} onClick={addPrefix}>Add</button>
        </div>
        <div className={styles.prefixTable}>
          {metadata.prefixes.slice(0, 15).map((p) => (
            <div key={p.prefix} className={styles.prefixRow}>
              <span className={styles.prefixName}>{p.prefix}</span>
              <span className={styles.prefixNs}>{p.namespace}</span>
            </div>
          ))}
          {metadata.prefixes.length > 15 && (
            <div className={styles.prefixMore}>+{metadata.prefixes.length - 15} more</div>
          )}
        </div>
      </section>

      {/* ── ROBOT Report ──────────────────────────────── */}
      <section className={styles.section}>
        <div className={styles.sectionHeader}>
          <h3 className={styles.sectionTitle}>ROBOT Report</h3>
          <button className={styles.smallBtn} onClick={runReport} disabled={reportLoading}>
            {reportLoading ? "Running..." : "Run Report"}
          </button>
        </div>
        {report ? (
          <div className={styles.reportSummary}>{report.summary}</div>
        ) : (
          <p className={styles.hint}>Click "Run Report" to check quality (requires odkfull image).</p>
        )}
      </section>
    </div>
  );
}

function StatCell({ label, value, color }: { label: string; value: number; color: string }) {
  return (
    <div className={styles.statCell}>
      <div className={styles.statValue} style={{ color }}>{value}</div>
      <div className={styles.statLabel}>{label}</div>
    </div>
  );
}
