import { useEffect, useRef, useState } from "react";
import { useCsvImport, ColumnMapping } from "../../hooks/useCsvImport";
import styles from "./CsvImportWizard.module.css";

interface Props { boardId: string }

const MAPPING_TYPES = [
  { value: "data_property", label: "Data Property" },
  { value: "class_assertion", label: "Class Type" },
  { value: "object_property", label: "Object Property" },
  { value: "annotation", label: "Annotation" },
];

const IRI_STRATEGIES = [
  { value: "sequential", label: "Sequential (ind_0, ind_1, ...)" },
  { value: "timestamp", label: "Timestamp (EX_1713000000001)" },
  { value: "uuid", label: "UUID (random unique)" },
  { value: "hash", label: "Hash (content-based)" },
  { value: "pattern", label: "Custom pattern" },
];

const SAMPLE_ROBOT_CSV =
  "ID,Label,SubClass Of,Definition\n" +
  "EX:0000001,Example Class,owl:Thing,An example class definition\n" +
  "EX:0000002,Another Class,EX:0000001,Another example class\n";

const IRI_ITERATORS = [
  { value: "timestamp", label: "Timestamp" },
  { value: "uuid", label: "UUID" },
  { value: "sequential", label: "Sequential" },
];

export default function CsvImportWizard({ boardId }: Props) {
  const {
    analysis, files, uploading, uploadFile, fetchFiles,
    preview, runPreview, building, buildKg, buildResult, error,
  } = useCsvImport(boardId);

  const fileRef = useRef<HTMLInputElement>(null);
  const [step, setStep] = useState(0); // 0=upload, 1=map, 2=iri, 3=preview, 4=result
  const [mappings, setMappings] = useState<ColumnMapping[]>([]);
  const [iriStrategy, setIriStrategy] = useState("sequential");
  const [iriPattern, setIriPattern] = useState("");
  const [baseIri, setBaseIri] = useState("http://example.org/instance");
  const [ontologyPrefix, setOntologyPrefix] = useState("EX");
  const [iriIterator, setIriIterator] = useState("timestamp");

  useEffect(() => { fetchFiles(); }, [fetchFiles]);

  const prefixBaseIri = `http://example.org/${ontologyPrefix}_`;

  // Auto-init mappings when analysis arrives
  useEffect(() => {
    if (analysis) {
      setMappings(analysis.columns.map((c) => ({
        column: c.name,
        target_iri: `${prefixBaseIri}${c.name.replace(/\s+/g, '_')}`,
        mapping_type: "data_property",
        iri_role: "value",
      })));
      setStep(1);
    }
  }, [analysis, prefixBaseIri]);

  const handleUpload = () => fileRef.current?.click();
  const handleFile = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) uploadFile(file);
  };

  const updateMapping = (idx: number, field: string, value: string) => {
    setMappings((prev) => prev.map((m, i) => i === idx ? { ...m, [field]: value } : m));
  };

  const handlePreview = () => {
    if (analysis) {
      runPreview(analysis.filename, mappings, iriStrategy, iriPattern || null, baseIri);
      setStep(3);
    }
  };

  const handleBuild = () => {
    if (analysis) {
      buildKg(analysis.filename, mappings, iriStrategy, iriPattern || null, baseIri);
      setStep(4);
    }
  };

  return (
    <div className={styles.container}>
      {error && <div className={styles.error}>{error}</div>}

      {/* Step indicator */}
      <div className={styles.steps}>
        {["Upload", "Map", "IRI", "Preview", "Build"].map((label, i) => (
          <span key={i} className={`${styles.step} ${step >= i ? styles.stepActive : ""}`}
                onClick={() => i <= step && setStep(i)}>
            {label}
          </span>
        ))}
      </div>

      {/* Step 0: Upload */}
      {step === 0 && (
        <div className={styles.section}>
          <div style={{ marginBottom: 12, padding: "10px 14px", background: "#f4f6fb", borderRadius: 6, fontSize: 13 }}>
            <strong>CSV columns should follow ROBOT template conventions:</strong>
            <table style={{ marginTop: 6, borderCollapse: "collapse", width: "100%", fontSize: 12 }}>
              <thead>
                <tr style={{ borderBottom: "1px solid #ccc", textAlign: "left" }}>
                  <th style={{ padding: "4px 8px" }}>ID</th>
                  <th style={{ padding: "4px 8px" }}>Label</th>
                  <th style={{ padding: "4px 8px" }}>SubClass Of</th>
                  <th style={{ padding: "4px 8px" }}>Definition</th>
                </tr>
              </thead>
              <tbody>
                <tr style={{ color: "#666" }}>
                  <td style={{ padding: "4px 8px" }}>EX:0000001</td>
                  <td style={{ padding: "4px 8px" }}>Example Class</td>
                  <td style={{ padding: "4px 8px" }}>owl:Thing</td>
                  <td style={{ padding: "4px 8px" }}>An example class</td>
                </tr>
              </tbody>
            </table>
            <div style={{ marginTop: 8, display: "flex", gap: 8 }}>
              <button
                className={styles.nextBtn}
                style={{ fontSize: 12, padding: "4px 10px" }}
                onClick={() => {
                  const blob = new Blob([SAMPLE_ROBOT_CSV], { type: "text/csv" });
                  const url = URL.createObjectURL(blob);
                  const a = document.createElement("a");
                  a.href = url;
                  a.download = "robot_template_sample.csv";
                  a.click();
                  URL.revokeObjectURL(url);
                }}
              >
                Download Sample Template
              </button>
              <button
                className={styles.nextBtn}
                style={{ fontSize: 12, padding: "4px 10px" }}
                onClick={() => {
                  const blob = new Blob([SAMPLE_ROBOT_CSV], { type: "text/csv" });
                  const file = new File([blob], "default_template.csv", { type: "text/csv" });
                  uploadFile(file);
                }}
              >
                Use Default Template
              </button>
            </div>
          </div>
          <input ref={fileRef} type="file" accept=".csv,.tsv,.txt" onChange={handleFile} hidden />
          <button className={styles.uploadBtn} onClick={handleUpload} disabled={uploading}>
            {uploading ? "Uploading..." : "Upload CSV / TSV"}
          </button>
          {files.length > 0 && (
            <div className={styles.fileList}>
              <span className={styles.fileLabel}>Previous uploads:</span>
              {files.map((f) => (
                <span key={f} className={styles.fileChip}>{f}</span>
              ))}
            </div>
          )}
        </div>
      )}

      {/* Step 1: Column mappings */}
      {step === 1 && analysis && (
        <div className={styles.section}>
          <div className={styles.sectionHeader}>
            <h4 className={styles.sectionTitle}>Map Columns ({analysis.row_count} rows)</h4>
          </div>

          {/* Prefix and IRI iterator controls */}
          <div style={{ display: "flex", gap: 12, alignItems: "center", marginBottom: 12, flexWrap: "wrap" }}>
            <label style={{ fontSize: 13 }}>
              Ontology Prefix:
              <input
                className={styles.iriInput}
                style={{ marginLeft: 6, width: 100 }}
                value={ontologyPrefix}
                onChange={(e) => {
                  const prefix = e.target.value;
                  setOntologyPrefix(prefix);
                  setMappings((prev) =>
                    prev.map((m) => ({
                      ...m,
                      target_iri: `http://example.org/${prefix}_${m.column.replace(/\s+/g, "_")}`,
                    }))
                  );
                }}
                placeholder="e.g. EX"
              />
            </label>
            <label style={{ fontSize: 13 }}>
              IRI Iterator:
              <select
                className={styles.select}
                style={{ marginLeft: 6 }}
                value={iriIterator}
                onChange={(e) => {
                  setIriIterator(e.target.value);
                  setIriStrategy(e.target.value);
                }}
              >
                {IRI_ITERATORS.map((it) => (
                  <option key={it.value} value={it.value}>{it.label}</option>
                ))}
              </select>
            </label>
          </div>
          <div style={{ fontSize: 12, color: "#666", marginBottom: 10, padding: "6px 10px", background: "#f9f9fb", borderRadius: 4 }}>
            Sample IRI: <code>
              {iriIterator === "timestamp"
                ? `http://example.org/instance/${ontologyPrefix}_${Date.now()}`
                : iriIterator === "uuid"
                ? `http://example.org/instance/${ontologyPrefix}_${crypto.randomUUID?.() || "a1b2c3d4-..."}`
                : `http://example.org/instance/${ontologyPrefix}_0`}
            </code>
          </div>

          <div className={styles.mappingList}>
            {mappings.map((m, i) => (
              <div key={m.column} className={styles.mappingRow}>
                <span className={styles.colName}>{m.column}</span>
                <span className={styles.colType}>{analysis.columns[i]?.inferred_type}</span>
                <select className={styles.select}
                        value={m.mapping_type}
                        onChange={(e) => updateMapping(i, "mapping_type", e.target.value)}>
                  {MAPPING_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
                </select>
                <input className={styles.iriInput}
                       value={m.target_iri}
                       onChange={(e) => updateMapping(i, "target_iri", e.target.value)}
                       placeholder="Target IRI" />
              </div>
            ))}
          </div>
          <button className={styles.nextBtn} onClick={() => setStep(2)}>Next: IRI Strategy</button>
        </div>
      )}

      {/* Step 2: IRI strategy */}
      {step === 2 && (
        <div className={styles.section}>
          <h4 className={styles.sectionTitle}>IRI Generation Strategy</h4>
          <div className={styles.iriOptions}>
            {IRI_STRATEGIES.map((s) => (
              <label key={s.value} className={styles.radioLabel}>
                <input type="radio" name="iri" value={s.value}
                       checked={iriStrategy === s.value}
                       onChange={(e) => setIriStrategy(e.target.value)} />
                {s.label}
              </label>
            ))}
          </div>
          {iriStrategy === "pattern" && (
            <input className={styles.patternInput}
                   placeholder="e.g. http://example.org/{Name}_{City}"
                   value={iriPattern}
                   onChange={(e) => setIriPattern(e.target.value)} />
          )}
          <div className={styles.iriBaseRow}>
            <span className={styles.iriBaseLabel}>Base IRI:</span>
            <input className={styles.iriInput} value={baseIri} onChange={(e) => setBaseIri(e.target.value)} />
          </div>
          <button className={styles.nextBtn} onClick={handlePreview}>Preview Triples</button>
        </div>
      )}

      {/* Step 3: Preview */}
      {step === 3 && (
        <div className={styles.section}>
          <h4 className={styles.sectionTitle}>Preview ({preview.length} triples)</h4>
          <div className={styles.tripleList}>
            {preview.map((t, i) => (
              <div key={i} className={styles.tripleRow}>
                <span className={styles.tripleS}>{t.subject?.split("/").pop()}</span>
                <span className={styles.tripleP}>{t.predicate?.split("/").pop()?.split("#").pop()}</span>
                <span className={styles.tripleO}>{String(t.object).split("/").pop()}</span>
              </div>
            ))}
          </div>
          <div style={{ display: "flex", gap: 8, marginTop: 8 }}>
            <button
              className={styles.nextBtn}
              onClick={() => {
                const lines = preview.map(
                  (t) => `${t.subject}\t${t.predicate}\t${t.object}`
                );
                const content = "Subject\tPredicate\tObject\n" + lines.join("\n");
                const blob = new Blob([content], { type: "text/tab-separated-values" });
                const url = URL.createObjectURL(blob);
                const a = document.createElement("a");
                a.href = url;
                a.download = "preview_triples.tsv";
                a.click();
                URL.revokeObjectURL(url);
              }}
            >
              Download Preview
            </button>
            <button className={`${styles.nextBtn} ${styles.buildBtn}`} onClick={handleBuild} disabled={building}>
              {building ? "Building..." : "Build Knowledge Graph"}
            </button>
          </div>
        </div>
      )}

      {/* Step 4: Result */}
      {step === 4 && buildResult && (
        <div className={styles.section}>
          <div className={styles.resultBanner}>
            <span className={styles.resultIcon}>{"\u2713"}</span>
            <div>
              <div className={styles.resultTitle}>Knowledge Graph Built</div>
              <div className={styles.resultMeta}>
                {buildResult.individuals_count} individuals &middot; {buildResult.triples_count} triples
              </div>
            </div>
          </div>
          <div className={styles.resultPath}>
            Output: <code>{buildResult.output_path}</code>
          </div>
          <button className={styles.nextBtn} onClick={() => { setStep(0); }}>Import Another CSV</button>
        </div>
      )}
    </div>
  );
}
