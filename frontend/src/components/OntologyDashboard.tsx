/**
 * OntologyDashboard — Editable ontology metadata, statistics, prefixes, and annotations.
 *
 * Right panel "Onto" tab showing:
 * - Editable Ontology IRI + Version IRI (live update, no refresh needed)
 * - Editable language
 * - Statistics (read-only, live from store)
 * - Multiple authors/contributors with auto-retrieve
 * - Editable prefix table with color
 * - ROBOT report with detailed error logging
 */

import { useState, useCallback, useMemo, useEffect } from "react";
import { useOntologyDashboard } from "../hooks/useOntology";
import { useOntologyStore } from "../store/ontologyStore";
import { apiJson } from "../api";
import styles from "./OntologyDashboard.module.css";

interface Props {
  boardId: string;
}

export default function OntologyDashboard({ boardId }: Props) {
  const { data, setData, loading, error, runReport, reportLoading, reportError } = useOntologyDashboard(boardId);
  const [newPrefix, setNewPrefix] = useState("");
  const [newNs, setNewNs] = useState("");
  const [editingPrefix, setEditingPrefix] = useState<string | null>(null);
  const [editPrefixName, setEditPrefixName] = useState("");
  const [editPrefixNs, setEditPrefixNs] = useState("");
  const [metaFields, setMetaFields] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState(false);
  const [editingIri, setEditingIri] = useState(false);
  const [iriInput, setIriInput] = useState("");
  const [editingVersionIri, setEditingVersionIri] = useState(false);
  const [versionIriInput, setVersionIriInput] = useState("");
  const [editingLang, setEditingLang] = useState(false);
  const [langInput, setLangInput] = useState("");

  // Authors & contributors — multiple entries
  const [authors, setAuthors] = useState<string[]>([]);
  const [contributors, setContributors] = useState<string[]>([]);
  const [newAuthor, setNewAuthor] = useState("");
  const [newContributor, setNewContributor] = useState("");

  // Initialize authors/contributors from metadata annotations when data loads
  useEffect(() => {
    if (!data) return;
    // Try to extract from metadata fields
    const meta = data.metadata as any;
    if (meta?.creators) setAuthors(meta.creators);
    if (meta?.contributors) setContributors(meta.contributors);
  }, [data]);

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
      // Update local data immediately
      if (data) {
        setData({
          ...data,
          metadata: {
            ...data.metadata,
            prefixes: [...data.metadata.prefixes, { prefix: newPrefix, namespace: newNs }],
          },
        });
      }
      setNewPrefix("");
      setNewNs("");
    } catch {}
  }, [boardId, newPrefix, newNs, data, setData]);

  const updatePrefix = useCallback(async (oldPrefix: string, newPrefixName: string, newNsValue: string) => {
    if (!newPrefixName || !newNsValue) return;
    try {
      await apiJson(`/api/ontology/${boardId}/prefixes/${encodeURIComponent(oldPrefix)}`, {
        method: "PUT",
        body: JSON.stringify({ prefix: newPrefixName, namespace: newNsValue }),
      });
      if (data) {
        setData({
          ...data,
          metadata: {
            ...data.metadata,
            prefixes: data.metadata.prefixes.map((p) =>
              p.prefix === oldPrefix ? { prefix: newPrefixName, namespace: newNsValue } : p
            ),
          },
        });
      }
      setEditingPrefix(null);
    } catch {}
  }, [boardId, data, setData]);

  const saveIdentity = useCallback(async (fields: Record<string, string | null>) => {
    try {
      await apiJson(`/api/ontology/${boardId}/identity`, {
        method: "PUT",
        body: JSON.stringify(fields),
      });
      // Update local data immediately so UI reflects change without refresh
      if (data) {
        const updatedMeta = { ...data.metadata };
        if (fields.version_iri !== undefined) updatedMeta.version_iri = fields.version_iri;
        if (fields.ontology_iri !== undefined) (updatedMeta as any).ontology_iri = fields.ontology_iri;
        setData({ ...data, metadata: updatedMeta });
      }
    } catch {}
  }, [boardId, data, setData]);

  const saveLang = useCallback(async (lang: string) => {
    try {
      await saveMetadata({ language: lang });
      if (data) {
        setData({
          ...data,
          metadata: { ...data.metadata, languages: lang.split(",").map((l) => l.trim()).filter(Boolean) },
        });
      }
    } catch {}
  }, [boardId, data, setData, saveMetadata]);

  const addAuthor = useCallback(async () => {
    if (!newAuthor.trim()) return;
    const updated = [...authors, newAuthor.trim()];
    setAuthors(updated);
    setNewAuthor("");
    await saveMetadata({ creator: updated.join("; ") });
  }, [newAuthor, authors, saveMetadata]);

  const removeAuthor = useCallback(async (idx: number) => {
    const updated = authors.filter((_, i) => i !== idx);
    setAuthors(updated);
    await saveMetadata({ creator: updated.join("; ") });
  }, [authors, saveMetadata]);

  const addContributor = useCallback(async () => {
    if (!newContributor.trim()) return;
    const updated = [...contributors, newContributor.trim()];
    setContributors(updated);
    setNewContributor("");
    await saveMetadata({ contributor: updated.join("; ") });
  }, [newContributor, contributors, saveMetadata]);

  const removeContributor = useCallback(async (idx: number) => {
    const updated = contributors.filter((_, i) => i !== idx);
    setContributors(updated);
    await saveMetadata({ contributor: updated.join("; ") });
  }, [contributors, saveMetadata]);

  // Live statistics from Zustand store (updates instantly on graph changes)
  const storeClasses = useOntologyStore((s) => s.classes);
  const storeProperties = useOntologyStore((s) => s.properties);
  const storeIndividuals = useOntologyStore((s) => s.individuals);
  const prefixColors = useOntologyStore((s) => s.prefixColors);

  const liveStats = useMemo(() => ({
    classes: storeClasses.length,
    object_properties: new Set(storeProperties.filter((p) => p.property_type === "object").map((p) => p.iri)).size,
    data_properties: new Set(storeProperties.filter((p) => p.property_type === "data").map((p) => p.iri)).size,
    annotation_properties: new Set(storeProperties.filter((p) => p.property_type === "annotation" && p.iri !== "rdfs:subClassOf" && p.iri !== "rdf:type").map((p) => p.iri)).size,
    individuals: storeIndividuals.length,
    total_axioms: storeProperties.length,
    subclass_axioms: storeProperties.filter((p) => p.iri === "rdfs:subClassOf").length,
    total_triples: storeClasses.length + storeProperties.length + storeIndividuals.length,
  }), [storeClasses, storeProperties, storeIndividuals]);

  if (loading) return <div className={styles.loading}>Loading ontology info...</div>;
  if (error) return <div className={styles.error}>{error}</div>;
  if (!data) return null;

  const { metadata, report } = data;

  return (
    <div className={styles.dashboard}>
      {/* ── Editable IRI & Version ────────────────────── */}
      <section className={styles.section}>
        <h3 className={styles.sectionTitle}>Ontology Identity</h3>

        {/* Ontology IRI — click to edit */}
        <div className={styles.fieldGroup}>
          <label className={styles.fieldLabel}>Ontology IRI</label>
          {editingIri ? (
            <div className={styles.editRow}>
              <input
                className={styles.editInput}
                value={iriInput}
                onChange={(e) => setIriInput(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") {
                    saveIdentity({ ontology_iri: iriInput });
                    setEditingIri(false);
                  }
                  if (e.key === "Escape") setEditingIri(false);
                }}
                autoFocus
              />
              <button className={styles.smallBtn} onClick={() => { saveIdentity({ ontology_iri: iriInput }); setEditingIri(false); }}>Save</button>
              <button className={styles.smallBtnMuted} onClick={() => setEditingIri(false)}>Cancel</button>
            </div>
          ) : (
            <div
              className={styles.fieldValueEditable}
              onClick={() => { setIriInput(metadata.ontology_iri || ""); setEditingIri(true); }}
              title="Click to edit"
            >
              {metadata.ontology_iri || "\u2014 click to set \u2014"}
            </div>
          )}
        </div>

        {/* Version IRI — click to edit, updates immediately */}
        <div className={styles.fieldGroup}>
          <label className={styles.fieldLabel}>Version IRI</label>
          {editingVersionIri ? (
            <div className={styles.editRow}>
              <input
                className={styles.editInput}
                value={versionIriInput}
                onChange={(e) => setVersionIriInput(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") {
                    saveIdentity({ version_iri: versionIriInput });
                    setEditingVersionIri(false);
                  }
                  if (e.key === "Escape") setEditingVersionIri(false);
                }}
                autoFocus
              />
              <button className={styles.smallBtn} onClick={() => { saveIdentity({ version_iri: versionIriInput }); setEditingVersionIri(false); }}>Save</button>
              <button className={styles.smallBtnMuted} onClick={() => setEditingVersionIri(false)}>Cancel</button>
            </div>
          ) : (
            <div
              className={styles.fieldValueEditable}
              onClick={() => { setVersionIriInput(metadata.version_iri || ""); setEditingVersionIri(true); }}
              title="Click to edit"
            >
              {metadata.version_iri || "\u2014 click to set \u2014"}
            </div>
          )}
        </div>

        {/* Language — editable */}
        <div className={styles.fieldGroup}>
          <label className={styles.fieldLabel}>Languages</label>
          {editingLang ? (
            <div className={styles.editRow}>
              <input
                className={styles.editInput}
                value={langInput}
                onChange={(e) => setLangInput(e.target.value)}
                placeholder="en, de, fr"
                onKeyDown={(e) => {
                  if (e.key === "Enter") { saveLang(langInput); setEditingLang(false); }
                  if (e.key === "Escape") setEditingLang(false);
                }}
                autoFocus
              />
              <button className={styles.smallBtn} onClick={() => { saveLang(langInput); setEditingLang(false); }}>Save</button>
              <button className={styles.smallBtnMuted} onClick={() => setEditingLang(false)}>Cancel</button>
            </div>
          ) : (
            <div
              className={styles.fieldValueEditable}
              onClick={() => { setLangInput(metadata.languages.join(", ") || "en"); setEditingLang(true); }}
              title="Click to edit"
            >
              {metadata.languages.join(", ") || "en"}
            </div>
          )}
        </div>
      </section>

      {/* ── Authors & Contributors ────────────────────── */}
      <section className={styles.section}>
        <h3 className={styles.sectionTitle}>Authors & Contributors</h3>

        <div className={styles.fieldGroup}>
          <label className={styles.fieldLabel}>Authors (dc:creator)</label>
          <div className={styles.tagList}>
            {authors.map((a, i) => (
              <span key={i} className={styles.tag}>
                {a}
                <button className={styles.tagRemove} onClick={() => removeAuthor(i)}>&times;</button>
              </span>
            ))}
          </div>
          <div className={styles.editRow} style={{ marginTop: "0.3rem" }}>
            <input className={styles.editInput} placeholder="Add author..."
                   value={newAuthor} onChange={(e) => setNewAuthor(e.target.value)}
                   onKeyDown={(e) => e.key === "Enter" && addAuthor()} />
            <button className={styles.smallBtn} onClick={addAuthor}>Add</button>
          </div>
        </div>

        <div className={styles.fieldGroup}>
          <label className={styles.fieldLabel}>Contributors (dc:contributor)</label>
          <div className={styles.tagList}>
            {contributors.map((c, i) => (
              <span key={i} className={styles.tag}>
                {c}
                <button className={styles.tagRemove} onClick={() => removeContributor(i)}>&times;</button>
              </span>
            ))}
          </div>
          <div className={styles.editRow} style={{ marginTop: "0.3rem" }}>
            <input className={styles.editInput} placeholder="Add contributor..."
                   value={newContributor} onChange={(e) => setNewContributor(e.target.value)}
                   onKeyDown={(e) => e.key === "Enter" && addContributor()} />
            <button className={styles.smallBtn} onClick={addContributor}>Add</button>
          </div>
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
        {["title", "description", "license"].map((field) => (
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
          <StatCell label="Classes" value={liveStats.classes} color="#6366f1" />
          <StatCell label="Obj Props" value={liveStats.object_properties} color="#10b981" />
          <StatCell label="Data Props" value={liveStats.data_properties} color="#f59e0b" />
          <StatCell label="Individuals" value={liveStats.individuals} color="#ef4444" />
          <StatCell label="Axioms" value={liveStats.total_axioms} color="#8b5cf6" />
          <StatCell label="Triples" value={liveStats.total_triples} color="#64748b" />
        </div>
      </section>

      {/* ── Editable Prefixes with Color ─────────────── */}
      <section className={styles.section}>
        <h3 className={styles.sectionTitle}>Prefixes ({metadata.prefixes.length})</h3>
        <div className={styles.addPrefixRow}>
          <input className={styles.prefixInput} placeholder="prefix" value={newPrefix}
                 onChange={(e) => setNewPrefix(e.target.value)} />
          <input className={styles.nsInput} placeholder="http://namespace.org/"
                 value={newNs} onChange={(e) => setNewNs(e.target.value)} />
          <button className={styles.smallBtn} onClick={addPrefix}>Add</button>
        </div>
        <p className={styles.hint} style={{ marginBottom: "0.3rem" }}>
          Set a color to highlight all classes of that prefix on the canvas.
        </p>
        <div className={styles.prefixTable}>
          {metadata.prefixes.slice(0, 20).map((p) => {
            const pc = prefixColors.find((c) => c.prefix === p.prefix);
            const isEditing = editingPrefix === p.prefix;
            return (
              <div key={p.prefix} className={styles.prefixRow}>
                <input
                  type="color"
                  className={styles.prefixColorPick}
                  value={pc?.color || "#4f46e5"}
                  title={`Set color for ${p.prefix}: classes`}
                  onChange={(e) => {
                    useOntologyStore.getState().setPrefixColor(p.prefix, p.namespace, e.target.value);
                  }}
                />
                {isEditing ? (
                  <>
                    <input
                      className={styles.prefixEditInput}
                      value={editPrefixName}
                      onChange={(e) => setEditPrefixName(e.target.value)}
                      placeholder="prefix"
                      autoFocus
                      onKeyDown={(e) => {
                        if (e.key === "Enter") updatePrefix(p.prefix, editPrefixName, editPrefixNs);
                        if (e.key === "Escape") setEditingPrefix(null);
                      }}
                    />
                    <input
                      className={styles.prefixEditNsInput}
                      value={editPrefixNs}
                      onChange={(e) => setEditPrefixNs(e.target.value)}
                      placeholder="http://namespace.org/"
                      onKeyDown={(e) => {
                        if (e.key === "Enter") updatePrefix(p.prefix, editPrefixName, editPrefixNs);
                        if (e.key === "Escape") setEditingPrefix(null);
                      }}
                    />
                    <button className={styles.smallBtn} onClick={() => updatePrefix(p.prefix, editPrefixName, editPrefixNs)}>Save</button>
                    <button className={styles.smallBtnMuted} onClick={() => setEditingPrefix(null)}>✕</button>
                  </>
                ) : (
                  <>
                    <span
                      className={styles.prefixName}
                      onClick={() => { setEditPrefixName(p.prefix === "(default)" ? "" : p.prefix); setEditPrefixNs(p.namespace); setEditingPrefix(p.prefix); }}
                      title="Click to edit prefix"
                    >{p.prefix}</span>
                    <span
                      className={styles.prefixNs}
                      onClick={() => { setEditPrefixName(p.prefix === "(default)" ? "" : p.prefix); setEditPrefixNs(p.namespace); setEditingPrefix(p.prefix); }}
                      title="Click to edit namespace"
                    >{p.namespace}</span>
                  </>
                )}
                {pc && !isEditing && (
                  <button
                    className={styles.prefixColorReset}
                    title="Remove prefix color"
                    onClick={() => useOntologyStore.getState().removePrefixColor(p.prefix)}
                  >&times;</button>
                )}
              </div>
            );
          })}
          {metadata.prefixes.length > 20 && (
            <div className={styles.prefixMore}>+{metadata.prefixes.length - 20} more</div>
          )}
        </div>
      </section>

      {/* ── Provenance Settings ─────────────────────── */}
      <section className={styles.section}>
        <h3 className={styles.sectionTitle}>Provenance Tracking</h3>
        <div className={styles.fieldGroup}>
          <label className={styles.fieldLabel} style={{ display: "flex", alignItems: "center", gap: "0.5rem" }}>
            <input type="checkbox"
              checked={useOntologyStore.getState().trackProvenance}
              onChange={(e) => useOntologyStore.getState().setTrackProvenance(e.target.checked)}
            />
            Track who creates and modifies entities
          </label>
        </div>
        <div className={styles.fieldGroup}>
          <label className={styles.fieldLabel}>Record provenance in</label>
          <select className={styles.editInput} style={{ width: "100%" }}
            value={useOntologyStore.getState().provenanceTarget}
            onChange={(e) => useOntologyStore.getState().setProvenanceTarget(e.target.value as any)}
          >
            <option value="board">Board only (metadata)</option>
            <option value="ontology">Ontology only (OWL annotations)</option>
            <option value="both">Both board and ontology</option>
          </select>
        </div>
      </section>

      {/* ── ROBOT Report with detailed logging ───────── */}
      <section className={styles.section}>
        <div className={styles.sectionHeader}>
          <h3 className={styles.sectionTitle}>ROBOT Report</h3>
          <button className={styles.smallBtn} onClick={runReport} disabled={reportLoading}>
            {reportLoading ? "Running..." : "Run Report"}
          </button>
        </div>
        {reportError && (
          <div className={styles.reportError}>
            <strong>Error:</strong> {reportError}
          </div>
        )}
        {report ? (
          <>
            <div className={styles.reportSummary}>{report.summary}</div>
            {report.exit_code === -1 && (
              <div className={styles.reportWarn}>
                <strong>Docker / ODK Unavailable</strong>
                <p style={{ marginTop: "0.3rem" }}>
                  ROBOT report requires Docker with the <code>obolibrary/odkfull</code> image.
                  <br />Install Docker and run: <code>docker pull obolibrary/odkfull</code>
                </p>
              </div>
            )}
            {report.exit_code !== 0 && report.exit_code !== -1 && (
              <div className={styles.reportError}>
                <strong>Exit code:</strong> {report.exit_code}
                <p style={{ marginTop: "0.3rem" }}>
                  Check that the OWL file is valid and retry.
                </p>
              </div>
            )}
            {report.violations && report.violations.length > 0 && (
              <div className={styles.violationList}>
                {report.violations.slice(0, 50).map((v, i) => (
                  <div key={i} className={`${styles.violation} ${v.severity === "ERROR" ? styles.violationError : v.severity === "WARN" ? styles.violationWarn : styles.violationInfo}`}>
                    <span className={styles.violationSeverity}>{v.severity}</span>
                    <span className={styles.violationMsg}>{v.message}</span>
                    <span className={styles.violationSubject} title={v.subject}>{v.subject?.split("/").pop() || ""}</span>
                  </div>
                ))}
              </div>
            )}
          </>
        ) : (
          <div className={styles.hint}>
            Click "Run Report" to check ontology quality.
            <br />
            <span style={{ fontSize: "0.65rem" }}>
              Requires Docker and the <code>odkfull</code> image.
              If the report fails, check: (1) Docker is running, (2) <code>docker pull obolibrary/odkfull</code> succeeds, (3) the OWL file is valid.
            </span>
          </div>
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
