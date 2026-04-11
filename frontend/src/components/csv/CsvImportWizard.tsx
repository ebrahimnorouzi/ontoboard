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
  { value: "uuid", label: "UUID (random unique)" },
  { value: "hash", label: "Hash (content-based)" },
  { value: "pattern", label: "Custom pattern" },
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

  useEffect(() => { fetchFiles(); }, [fetchFiles]);

  // Auto-init mappings when analysis arrives
  useEffect(() => {
    if (analysis) {
      setMappings(analysis.columns.map((c) => ({
        column: c.name,
        target_iri: `http://example.org/${c.name.replace(/\s+/g, '_')}`,
        mapping_type: "data_property",
        iri_role: "value",
      })));
      setStep(1);
    }
  }, [analysis]);

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
          <button className={`${styles.nextBtn} ${styles.buildBtn}`} onClick={handleBuild} disabled={building}>
            {building ? "Building..." : "Build Knowledge Graph"}
          </button>
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
