/**
 * PatternLibrary — Browse, apply, and upload ODP/DOSDP patterns.
 * Patterns are added to the canvas without clearing existing entities.
 * Each applied pattern gets a unique color for visual distinction.
 * Supports drag-and-drop to place patterns on the canvas.
 */

import { useState, useEffect, useCallback, useRef } from "react";
import { apiJson } from "../../api";
import { useOntologyStore } from "../../store/ontologyStore";
import styles from "./PatternLibrary.module.css";

/** Predefined colors for ODP patterns — matches OntologyCanvas PATTERN_COLORS */
const PATTERN_COLORS: Record<string, string> = {
  "part-of": "#8b5cf6",
  "quality-pattern": "#06b6d4",
  "participation": "#f97316",
  "classification": "#ec4899",
  "information-entity": "#14b8a6",
};
const PATTERN_COLOR_PALETTE = ["#7c3aed", "#0891b2", "#ea580c", "#db2777", "#0d9488", "#4f46e5", "#059669", "#d97706", "#dc2626", "#7c2d12"];
let patternColorIdx = 0;
function getPatternColor(patternId: string): string {
  if (PATTERN_COLORS[patternId]) return PATTERN_COLORS[patternId];
  const color = PATTERN_COLOR_PALETTE[patternColorIdx % PATTERN_COLOR_PALETTE.length];
  PATTERN_COLORS[patternId] = color;
  patternColorIdx++;
  return color;
}

interface PatternSummary {
  id: string;
  name: string;
  description: string;
  category: string;
  class_count: number;
  property_count: number;
  source?: "odpa" | "user";
  uploaded_by?: string;
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
  const [showUpload, setShowUpload] = useState(false);
  const store = useOntologyStore();
  const [deleting, setDeleting] = useState<string | null>(null);
  const dragPatternRef = useRef<string | null>(null);

  // Upload form state
  const [uploadName, setUploadName] = useState("");
  const [uploadDesc, setUploadDesc] = useState("");
  const [uploadCategory, setUploadCategory] = useState("structural");
  const [uploadScope, setUploadScope] = useState("");
  const [uploadCqs, setUploadCqs] = useState("");
  const [uploadRef, setUploadRef] = useState("");
  const [uploadIri, setUploadIri] = useState("");
  const [uploadFile, setUploadFile] = useState<File | null>(null);

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

  const applyPattern = useCallback(async (patternId: string, dropX?: number, dropY?: number) => {
    setApplying(true);
    setError("");
    try {
      const result = await apiJson<{ classes: any[]; properties: any[]; individuals: any[]; pattern_id?: string }>(
        `/api/patterns/${boardId}/apply/${patternId}`,
        {
          method: "POST",
          body: JSON.stringify({
            base_iri: "http://example.org/ontology",
            x: dropX ?? 200 + Math.random() * 300,
            y: dropY ?? 100 + Math.random() * 200,
          }),
        }
      );

      const patternColor = getPatternColor(patternId);

      // MERGE pattern entities into existing store (don't reload — that clears everything)
      for (const cls of result.classes || []) {
        if (!store.classes.some((c) => c.iri === cls.iri)) {
          // Apply pattern-specific color
          store.addClass({ ...cls, color: patternColor });
        }
        // Track which pattern this class belongs to
        store.assignPattern(cls.iri, patternId);
      }
      for (const prop of result.properties || []) {
        if (!store.properties.some((p) => p.id === prop.id)) {
          store.addProperty(prop);
        }
      }
      setApplied(patternId);
      setTimeout(() => setApplied(null), 3000);
    } catch (e: any) {
      setError(e.message || "Failed to apply pattern");
    } finally {
      setApplying(false);
    }
  }, [boardId, store]);

  // ── Drag and drop handlers ──
  const handleDragStart = useCallback((e: React.DragEvent, patternId: string) => {
    dragPatternRef.current = patternId;
    e.dataTransfer.setData("application/x-odp-pattern", patternId);
    e.dataTransfer.effectAllowed = "copy";
    // Set a drag image
    const ghost = document.createElement("div");
    ghost.textContent = patterns.find((p) => p.id === patternId)?.name || patternId;
    ghost.style.cssText = "position:absolute;top:-100px;padding:6px 12px;background:#4f46e5;color:#fff;border-radius:6px;font-size:12px;font-weight:600;white-space:nowrap;";
    document.body.appendChild(ghost);
    e.dataTransfer.setDragImage(ghost, 0, 0);
    setTimeout(() => document.body.removeChild(ghost), 0);
  }, [patterns]);

  const handleUpload = useCallback(async () => {
    if (!uploadName.trim()) { setError("Pattern name is required"); return; }
    setError("");
    try {
      const formData = new FormData();
      formData.append("name", uploadName.trim());
      formData.append("description", uploadDesc.trim());
      formData.append("category", uploadCategory);
      formData.append("scope", uploadScope.trim());
      formData.append("competency_questions", uploadCqs.trim());
      formData.append("references", uploadRef.trim());
      formData.append("pattern_iri", uploadIri.trim());
      if (uploadFile) formData.append("file", uploadFile);

      const newPattern = await apiJson<PatternSummary>("/api/patterns/upload", {
        method: "POST",
        body: formData,
        headers: {}, // Let browser set content-type with boundary
      });
      setPatterns((prev) => [...prev, newPattern]);
      setShowUpload(false);
      setUploadName(""); setUploadDesc(""); setUploadScope("");
      setUploadCqs(""); setUploadRef(""); setUploadIri(""); setUploadFile(null);
      setApplied("uploaded");
      setTimeout(() => setApplied(null), 3000);
    } catch (e: any) {
      setError(e.message || "Upload failed");
    }
  }, [uploadName, uploadDesc, uploadCategory, uploadScope, uploadCqs, uploadRef, uploadIri, uploadFile]);

  /** Batch upload: multiple JSON+OWL file pairs */
  const handleBatchUpload = useCallback(async (files: FileList) => {
    setError("");
    try {
      const formData = new FormData();
      for (let i = 0; i < files.length; i++) {
        formData.append("files", files[i]);
      }
      const results = await apiJson<PatternSummary[]>("/api/patterns/upload-batch", {
        method: "POST",
        body: formData,
        headers: {},
      });
      setPatterns((prev) => [...prev, ...results]);
      setApplied("uploaded");
      setTimeout(() => setApplied(null), 3000);
    } catch (e: any) {
      setError(e.message || "Batch upload failed");
    }
  }, []);

  const handleDelete = useCallback(async (patternId: string) => {
    setDeleting(patternId);
    try {
      await apiJson(`/api/patterns/${patternId}`, { method: "DELETE" });
      setPatterns((prev) => prev.filter((p) => p.id !== patternId));
      if (selected?.id === patternId) setSelected(null);
    } catch (e: any) {
      setError(e.message || "Delete failed");
    } finally {
      setDeleting(null);
    }
  }, [selected]);

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
        <button className={styles.uploadToggle} onClick={() => setShowUpload(!showUpload)} title="Add new pattern">+</button>
      </div>

      {error && <div className={styles.error} onClick={() => setError("")}>{error}</div>}
      {applied && <div className={styles.success}>{applied === "uploaded" ? "Pattern uploaded" : "Pattern applied to canvas"}</div>}

      <div className={styles.dragHint}>
        Drag a pattern onto the canvas to place it, or click Apply.
      </div>

      {/* Batch upload zone */}
      <label className={styles.batchUpload}>
        <span>Upload multiple ODPs (JSON metadata + OWL files)</span>
        <input type="file" multiple accept=".json,.owl,.ttl,.rdf,.xml"
               style={{ display: "none" }}
               onChange={(e) => e.target.files && e.target.files.length > 0 && handleBatchUpload(e.target.files)} />
      </label>

      {/* Upload form */}
      {showUpload && (
        <div className={styles.uploadForm}>
          <h4 className={styles.uploadTitle}>Add New Pattern</h4>
          <input className={styles.uploadInput} placeholder="Pattern name *" value={uploadName} onChange={(e) => setUploadName(e.target.value)} />
          <textarea className={styles.uploadTextarea} placeholder="Description" value={uploadDesc} onChange={(e) => setUploadDesc(e.target.value)} rows={2} />
          <select className={styles.uploadSelect} value={uploadCategory} onChange={(e) => setUploadCategory(e.target.value)}>
            <option value="structural">Structural</option>
            <option value="behavioral">Behavioral</option>
            <option value="information">Information</option>
            <option value="alignment">Alignment</option>
            <option value="domain">Domain-specific</option>
          </select>
          <input className={styles.uploadInput} placeholder="Scope (e.g., part-whole relations)" value={uploadScope} onChange={(e) => setUploadScope(e.target.value)} />
          <textarea className={styles.uploadTextarea} placeholder="Competency Questions (one per line)" value={uploadCqs} onChange={(e) => setUploadCqs(e.target.value)} rows={2} />
          <input className={styles.uploadInput} placeholder="References / Citations" value={uploadRef} onChange={(e) => setUploadRef(e.target.value)} />
          <input className={styles.uploadInput} placeholder="Pattern IRI (e.g., http://ontologydesignpatterns.org/...)" value={uploadIri} onChange={(e) => setUploadIri(e.target.value)} />
          <label className={styles.uploadFileLabel}>
            Pattern File (OWL/TTL)
            <input type="file" accept=".owl,.ttl,.rdf,.xml" onChange={(e) => setUploadFile(e.target.files?.[0] || null)} />
          </label>
          <div className={styles.uploadActions}>
            <button className={styles.uploadBtn} onClick={handleUpload}>Add Pattern</button>
            <button className={styles.uploadCancel} onClick={() => setShowUpload(false)}>Cancel</button>
          </div>
        </div>
      )}

      {loading ? (
        <div className={styles.empty}>Loading patterns...</div>
      ) : patterns.length === 0 ? (
        <div className={styles.empty}>
          <p>No patterns available.</p>
          <p className={styles.hint}>Click + to add an ODP (Ontology Design Pattern).</p>
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
                  draggable
                  onDragStart={(e) => handleDragStart(e, p.id)}
                >
                  <div className={styles.patternHeader}>
                    <span className={styles.patternColorDot} style={{ background: getPatternColor(p.id) }} />
                    <span className={styles.patternName}>{p.name}</span>
                    <span className={`${styles.sourceBadge} ${p.source === "user" ? styles.sourceUser : styles.sourceOdpa}`}
                          title={p.uploaded_by ? `Uploaded by ${p.uploaded_by}` : ""}>
                      {p.source === "user" ? (p.uploaded_by || "user") : "ODPA"}
                    </span>
                    <span className={styles.patternMeta}>{p.class_count}C {p.property_count}P</span>
                  </div>
                  <div className={styles.patternDesc}>{p.description}</div>
                  <div className={styles.patternActions}>
                    <button
                      className={styles.applyBtn}
                      onClick={(e) => { e.stopPropagation(); applyPattern(p.id); }}
                      disabled={applying}
                    >
                      {applying ? "..." : "Apply"}
                    </button>
                    {p.source === "user" && (
                      <button
                        className={styles.deleteBtn}
                        onClick={(e) => { e.stopPropagation(); handleDelete(p.id); }}
                        disabled={deleting === p.id}
                        title="Delete user pattern"
                      >
                        {deleting === p.id ? "..." : "\u2715"}
                      </button>
                    )}
                  </div>
                </div>
              ))}
            </div>
          ))}
        </div>
      )}

      {/* Pattern detail preview */}
      {selected && (
        <div className={styles.preview}>
          <div className={styles.previewHeader}>
            <span className={styles.previewColorBar} style={{ background: getPatternColor(selected.id) }} />
            <h4 className={styles.previewTitle}>{selected.name}</h4>
          </div>
          <p className={styles.previewDesc}>{selected.description}</p>
          <div className={styles.previewSection}>
            <strong>Classes:</strong>
            {selected.classes.map((c) => (
              <span key={c.iri} className={styles.previewEntity} style={{ borderColor: getPatternColor(selected.id) }}>{c.label}</span>
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
          <button className={styles.applyBtnLg} style={{ background: getPatternColor(selected.id) }}
                  onClick={() => applyPattern(selected.id)} disabled={applying}>
            {applying ? "Applying..." : "Apply to Board"}
          </button>
        </div>
      )}
    </div>
  );
}
