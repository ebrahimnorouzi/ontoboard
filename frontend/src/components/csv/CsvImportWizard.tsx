import { useEffect, useRef, useState } from "react";
import {
  useCsvImport,
  ColumnMapping,
  OntologyEntity,
} from "../../hooks/useCsvImport";
import styles from "./CsvImportWizard.module.css";

interface Props {
  boardId: string;
}

const STEP_LABELS = [
  "Upload",
  "Map Columns",
  "IRI Strategy",
  "Preview",
  "Build",
  "Files",
];

const DIRECTIVE_TYPES = [
  { value: "ID", label: "ID (Individual IRI)" },
  { value: "TYPE", label: "TYPE (rdf:type)" },
  { value: "A rdfs:label", label: "Label (rdfs:label)" },
  { value: "A rdfs:comment", label: "Comment (rdfs:comment)" },
  { value: "A", label: "Annotation Property" },
  { value: "I", label: "Object / Data Property" },
  { value: "IGNORE", label: "Ignore" },
];

const IRI_STRATEGIES = [
  { value: "auto_sequential", label: "Sequential (base/1, base/2, ...)" },
  { value: "auto_uuid", label: "UUID (random unique)" },
  { value: "auto_hash", label: "Hash (content-based)" },
  { value: "from_column", label: "From column value" },
  { value: "custom_pattern", label: "Custom pattern" },
];

export default function CsvImportWizard({ boardId }: Props) {
  const {
    analysis, entities, kgFiles,
    templateResult, buildResult,
    uploading, generating, building, merging, error,
    uploadFile, fetchEntities, fetchFiles,
    generateTemplate, buildKg, mergeKgs,
    getDownloadUrl, clearError,
    setTemplateResult, setBuildResult,
  } = useCsvImport(boardId);

  const fileRef = useRef<HTMLInputElement>(null);
  const [step, setStep] = useState(0);
  const [mappings, setMappings] = useState<ColumnMapping[]>([]);
  const [iriStrategy, setIriStrategy] = useState("auto_sequential");
  const [baseIri, setBaseIri] = useState("https://example.org/resource");
  const [fromColumn, setFromColumn] = useState("");
  const [customPattern, setCustomPattern] = useState("");
  const [templateName, setTemplateName] = useState("");

  // Load entities and files on mount
  useEffect(() => {
    fetchEntities();
    fetchFiles();
  }, [fetchEntities, fetchFiles]);

  // Initialize mappings when analysis arrives
  useEffect(() => {
    if (analysis) {
      setMappings(
        analysis.columns.map((c) => ({
          column_name: c.name,
          directive_type: c.suggested_type,
          property_iri: "",
          split_char: "",
        }))
      );
      setTemplateName(`${analysis.filename.replace(/\.[^.]+$/, "")}-template`);
      setStep(1);
    }
  }, [analysis]);

  const handleUpload = () => fileRef.current?.click();
  const handleFile = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) {
      clearError();
      uploadFile(file);
    }
  };

  const updateMapping = (idx: number, field: keyof ColumnMapping, value: string) => {
    setMappings((prev) =>
      prev.map((m, i) => (i === idx ? { ...m, [field]: value } : m))
    );
  };

  const handleGenerateTemplate = () => {
    if (!analysis) return;
    const effectiveBaseIri =
      iriStrategy === "custom_pattern" ? customPattern : baseIri;
    const effectiveMappings = iriStrategy === "from_column"
      ? [
          { column_name: fromColumn, directive_type: "ID", property_iri: "", split_char: "" },
          ...mappings.filter((m) => m.column_name !== fromColumn),
        ]
      : mappings;
    generateTemplate(
      analysis.filename,
      effectiveMappings,
      iriStrategy,
      effectiveBaseIri,
      templateName,
    );
    setStep(3);
  };

  const handleBuild = () => {
    if (templateResult) {
      buildKg(templateResult.template_path);
      setStep(4);
    }
  };

  const handleMergeAll = () => {
    const owlFiles = kgFiles.output
      .filter((f) => f.name.endsWith(".owl") && f.name !== "merged-kg.owl")
      .map((f) => f.path);
    if (owlFiles.length > 0) mergeKgs(owlFiles);
  };

  const handleStartNew = () => {
    setStep(0);
    setTemplateResult(null);
    setBuildResult(null);
    fetchFiles();
  };

  // Helper: get property options for a directive type
  const getPropertyOptions = (directiveType: string): OntologyEntity[] => {
    if (!entities) return [];
    if (directiveType === "TYPE") return entities.classes;
    if (directiveType === "A") return entities.annotation_properties;
    if (directiveType === "I") {
      return [...entities.object_properties, ...entities.data_properties];
    }
    return [];
  };

  // Helper: does this directive type need a property IRI dropdown?
  const needsPropertyIri = (dt: string) => ["TYPE", "A", "I"].includes(dt);

  // Build the directive preview string
  const directivePreview = (m: ColumnMapping): string => {
    if (m.directive_type === "IGNORE") return "(ignored)";
    if (m.directive_type === "ID") return "ID";
    if (m.directive_type === "TYPE") return "TYPE";
    if (m.directive_type === "A rdfs:label") return "A rdfs:label";
    if (m.directive_type === "A rdfs:comment") return "A rdfs:comment";
    let d = m.directive_type;
    if (m.property_iri) d += ` ${m.property_iri}`;
    if (m.split_char) d += ` SPLIT=${m.split_char}`;
    return d;
  };

  const formatBytes = (bytes: number): string => {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  };

  return (
    <div className={styles.container}>
      {error && <div className={styles.error}>{error}</div>}

      {/* Step indicator */}
      <div className={styles.steps}>
        {STEP_LABELS.map((label, i) => (
          <span
            key={i}
            className={`${styles.step} ${step === i ? styles.stepActive : step > i ? styles.stepDone : ""}`}
            onClick={() => {
              if (i <= step || i === 5) setStep(i);
            }}
          >
            {label}
          </span>
        ))}
      </div>

      {/* ── Step 0: Upload CSV ─────────────────────────────── */}
      {step === 0 && (
        <div className={styles.section}>
          <h4 className={styles.sectionTitle}>Upload CSV / TSV File</h4>
          <p className={styles.hint}>
            Upload a CSV or TSV file to map columns to ROBOT template directives.
            Or upload a ready-made ROBOT template to build a KG directly.
          </p>
          <input
            ref={fileRef}
            type="file"
            accept=".csv,.tsv,.txt"
            onChange={handleFile}
            hidden
          />

          <div className={styles.uploadOptions}>
            {/* Option 1: Raw data — full wizard */}
            <div className={styles.uploadOption}>
              <button
                className={styles.uploadBtn}
                onClick={handleUpload}
                disabled={uploading}
              >
                {uploading ? "Uploading..." : "Upload Data CSV/TSV"}
              </button>
              <span className={styles.uploadOptionHint}>Raw data — wizard guides you through mapping</span>
            </div>

            {/* Option 2: Ready ROBOT template — skip to build */}
            <div className={styles.uploadOption}>
              <input
                type="file"
                accept=".csv,.tsv,.txt"
                style={{ display: "none" }}
                id="template-upload"
                onChange={async (e) => {
                  const file = e.target.files?.[0];
                  if (!file) return;
                  clearError();
                  // Upload as template directly
                  const formData = new FormData();
                  formData.append("file", file);
                  try {
                    const { api: apiFn } = await import("../../api");
                    await apiFn(`/api/csv/${boardId}/upload`, {
                      method: "POST",
                      body: formData,
                    });
                    // Set as template and skip to build
                    setTemplateName(file.name.replace(/\.[^.]+$/, ""));
                    setTemplateResult({
                      template_path: `kg/uploads/${file.name}`,
                      preview_rows: [],
                    } as any);
                    setStep(4);
                  } catch (err: any) {
                    clearError();
                  }
                }}
              />
              <button
                className={`${styles.uploadBtn} ${styles.uploadBtnAlt}`}
                onClick={() => document.getElementById("template-upload")?.click()}
              >
                Upload Ready Template
              </button>
              <span className={styles.uploadOptionHint}>Already has ROBOT directives — skip to build</span>
            </div>
          </div>

          {/* Quick-access to existing uploads */}
          {kgFiles.uploads.length > 0 && (
            <div className={styles.existingFiles}>
              <span className={styles.existingLabel}>Previously uploaded:</span>
              <div className={styles.chipRow}>
                {kgFiles.uploads.map((f) => (
                  <button
                    key={f.name}
                    className={styles.fileChip}
                    onClick={() => {
                      // Re-analyze an existing upload
                      clearError();
                      // Simulate by creating a fetch to the analyze endpoint
                      const fakeFile = new File([""], f.name);
                      // We just re-upload — the backend overwrites gracefully
                      // Instead, use direct fetch for analysis
                      import("../../api").then(({ apiJson }) => {
                        apiJson(`/api/csv/${boardId}/upload`, {
                          method: "POST",
                          body: (() => {
                            const form = new FormData();
                            form.append("file", fakeFile);
                            return form;
                          })(),
                        }).catch(() => {
                          // Fallback: just navigate to files step
                          setStep(5);
                        });
                      });
                    }}
                  >
                    {f.name}
                  </button>
                ))}
              </div>
            </div>
          )}
        </div>
      )}

      {/* ── Step 1: Map Columns ────────────────────────────── */}
      {step === 1 && analysis && (
        <div className={styles.section}>
          <div className={styles.sectionHeader}>
            <h4 className={styles.sectionTitle}>
              Map Columns to ROBOT Directives
            </h4>
            <span className={styles.rowCount}>{analysis.row_count} rows</span>
          </div>

          {/* Data preview table */}
          <div className={styles.previewTable}>
            <table>
              <thead>
                <tr>
                  {analysis.columns.map((c) => (
                    <th key={c.name}>{c.name}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {analysis.sample_rows.slice(0, 3).map((row, i) => (
                  <tr key={i}>
                    {analysis.columns.map((c) => (
                      <td key={c.name}>{row[c.name] || ""}</td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* Column mapping controls */}
          <div className={styles.mappingList}>
            {mappings.map((m, i) => {
              const col = analysis.columns[i];
              const propOptions = getPropertyOptions(m.directive_type);
              return (
                <div key={m.column_name} className={styles.mappingRow}>
                  <div className={styles.mappingCol}>
                    <span className={styles.colName}>{m.column_name}</span>
                    {col && col.sample_values.length > 0 && (
                      <span className={styles.colSample}>
                        {col.sample_values[0]}
                      </span>
                    )}
                  </div>

                  <select
                    className={styles.select}
                    value={m.directive_type}
                    onChange={(e) =>
                      updateMapping(i, "directive_type", e.target.value)
                    }
                  >
                    {DIRECTIVE_TYPES.map((t) => (
                      <option key={t.value} value={t.value}>
                        {t.label}
                      </option>
                    ))}
                  </select>

                  {needsPropertyIri(m.directive_type) && (
                    <select
                      className={styles.select}
                      value={m.property_iri}
                      onChange={(e) =>
                        updateMapping(i, "property_iri", e.target.value)
                      }
                    >
                      <option value="">
                        {m.directive_type === "TYPE"
                          ? "-- select class --"
                          : "-- select property --"}
                      </option>
                      {propOptions.map((p) => (
                        <option key={p.iri} value={p.iri}>
                          {p.label} ({p.iri.split("/").pop()?.split("#").pop()})
                        </option>
                      ))}
                    </select>
                  )}

                  {(m.directive_type === "A" || m.directive_type === "I") && (
                    <input
                      className={styles.splitInput}
                      value={m.split_char}
                      onChange={(e) =>
                        updateMapping(i, "split_char", e.target.value)
                      }
                      placeholder="split"
                      title="Split character for multi-valued cells (e.g. comma)"
                    />
                  )}

                  {/* If no property options loaded, allow manual IRI entry */}
                  {needsPropertyIri(m.directive_type) &&
                    propOptions.length === 0 && (
                      <input
                        className={styles.iriInput}
                        value={m.property_iri}
                        onChange={(e) =>
                          updateMapping(i, "property_iri", e.target.value)
                        }
                        placeholder="Property IRI"
                      />
                    )}

                  <span className={styles.directivePreview}>
                    {directivePreview(m)}
                  </span>
                </div>
              );
            })}
          </div>

          <button className={styles.nextBtn} onClick={() => setStep(2)}>
            Next: IRI Strategy
          </button>
        </div>
      )}

      {/* ── Step 2: IRI Strategy ──────────────────────────── */}
      {step === 2 && (
        <div className={styles.section}>
          <h4 className={styles.sectionTitle}>IRI Generation Strategy</h4>
          <p className={styles.hint}>
            Choose how individual IRIs are generated for each row in your data.
          </p>

          <div className={styles.iriOptions}>
            {IRI_STRATEGIES.map((s) => (
              <label key={s.value} className={styles.radioLabel}>
                <input
                  type="radio"
                  name="iri_strategy"
                  value={s.value}
                  checked={iriStrategy === s.value}
                  onChange={(e) => setIriStrategy(e.target.value)}
                />
                {s.label}
              </label>
            ))}
          </div>

          {/* Base IRI */}
          {iriStrategy !== "custom_pattern" && (
            <div className={styles.iriBaseRow}>
              <label className={styles.iriBaseLabel}>Base IRI:</label>
              <input
                className={styles.iriInput}
                value={baseIri}
                onChange={(e) => setBaseIri(e.target.value)}
                placeholder="https://example.org/resource"
              />
            </div>
          )}

          {/* From column selector */}
          {iriStrategy === "from_column" && analysis && (
            <div className={styles.iriBaseRow}>
              <label className={styles.iriBaseLabel}>Column:</label>
              <select
                className={styles.select}
                value={fromColumn}
                onChange={(e) => setFromColumn(e.target.value)}
              >
                <option value="">-- select column --</option>
                {analysis.columns.map((c) => (
                  <option key={c.name} value={c.name}>
                    {c.name}
                  </option>
                ))}
              </select>
            </div>
          )}

          {/* Custom pattern */}
          {iriStrategy === "custom_pattern" && (
            <div className={styles.iriBaseRow}>
              <label className={styles.iriBaseLabel}>Pattern:</label>
              <input
                className={styles.iriInput}
                value={customPattern}
                onChange={(e) => setCustomPattern(e.target.value)}
                placeholder="https://example.org/{Name}_{City}"
              />
              {analysis && (
                <span className={styles.patternHint}>
                  Available: {analysis.columns.map((c) => `{${c.name}}`).join(", ")}
                </span>
              )}
            </div>
          )}

          {/* IRI preview */}
          <div className={styles.iriPreview}>
            <span className={styles.iriPreviewLabel}>Example IRI:</span>
            <code className={styles.iriPreviewValue}>
              {iriStrategy === "auto_sequential" && `${baseIri}/1`}
              {iriStrategy === "auto_uuid" && `${baseIri}/a1b2c3d4-e5f6-...`}
              {iriStrategy === "auto_hash" && `${baseIri}/3f2a9b1c4d5e`}
              {iriStrategy === "from_column" &&
                (fromColumn
                  ? `${baseIri}/${fromColumn}_value`
                  : "(select a column)")}
              {iriStrategy === "custom_pattern" &&
                (customPattern || "https://example.org/{Col1}_{Col2}")}
            </code>
          </div>

          {/* Template name */}
          <div className={styles.iriBaseRow}>
            <label className={styles.iriBaseLabel}>Template name:</label>
            <input
              className={styles.iriInput}
              value={templateName}
              onChange={(e) => setTemplateName(e.target.value)}
              placeholder="my-template"
            />
          </div>

          <div className={styles.buttonRow}>
            <button
              className={styles.backBtn}
              onClick={() => setStep(1)}
            >
              Back
            </button>
            <button
              className={styles.nextBtn}
              onClick={handleGenerateTemplate}
              disabled={generating}
            >
              {generating ? "Generating..." : "Generate Template"}
            </button>
          </div>
        </div>
      )}

      {/* ── Step 3: Preview Template ──────────────────────── */}
      {step === 3 && (
        <div className={styles.section}>
          <h4 className={styles.sectionTitle}>ROBOT Template Preview</h4>

          {generating && <p className={styles.hint}>Generating template...</p>}

          {templateResult && (
            <>
              <div className={styles.templateInfo}>
                <span>Template: <code>{templateResult.template_name}</code></span>
                <span>{templateResult.total_data_rows} data rows</span>
              </div>

              <div className={styles.templatePreview}>
                <table>
                  <tbody>
                    {templateResult.preview_rows.map((row, ri) => (
                      <tr
                        key={ri}
                        className={
                          ri === 0
                            ? styles.commentRow
                            : ri === 1
                            ? styles.directiveRow
                            : ""
                        }
                      >
                        {row.map((cell, ci) => (
                          <td key={ci}>{cell}</td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              <div className={styles.buttonRow}>
                <button
                  className={styles.backBtn}
                  onClick={() => setStep(2)}
                >
                  Back
                </button>
                <a
                  className={styles.downloadLink}
                  href={getDownloadUrl(
                    `kg/templates/${templateResult.template_name}`
                  )}
                  download={templateResult.template_name}
                >
                  Download Template
                </a>
                <button
                  className={`${styles.nextBtn} ${styles.buildBtn}`}
                  onClick={handleBuild}
                  disabled={building}
                >
                  {building ? "Building..." : "Build Knowledge Graph"}
                </button>
              </div>
            </>
          )}
        </div>
      )}

      {/* ── Step 4: Build Result ──────────────────────────── */}
      {step === 4 && (
        <div className={styles.section}>
          {building && (
            <div className={styles.buildingIndicator}>
              Building knowledge graph with ROBOT...
            </div>
          )}

          {buildResult && buildResult.success && (
            <>
              <div className={styles.resultBanner}>
                <span className={styles.resultIcon}>{"\u2713"}</span>
                <div>
                  <div className={styles.resultTitle}>Knowledge Graph Built</div>
                  <div className={styles.resultMeta}>
                    {buildResult.triples_count} triples generated
                  </div>
                </div>
              </div>

              {/* Build Pipeline Logs */}
              {buildResult.logs && buildResult.logs.length > 0 && (
                <div className={styles.buildLogs}>
                  <div className={styles.logsTitle}>Build Pipeline</div>
                  {buildResult.logs.map((log: any, i: number) => (
                    <div key={i} className={`${styles.logStep} ${
                      log.status === "success" ? styles.logStepOk :
                      log.status === "failed" ? styles.logStepFail :
                      log.status === "inconsistent" ? styles.logStepWarn : ""
                    }`}>
                      <span className={styles.logStepIcon}>
                        {log.status === "success" ? "\u2713" :
                         log.status === "failed" ? "\u2717" :
                         log.status === "inconsistent" ? "\u26A0" : "\u2022"}
                      </span>
                      <div className={styles.logStepContent}>
                        <div className={styles.logStepName}>
                          {log.step === "merge+template" ? "Merge Ontology + Build Template" :
                           log.step === "template" ? "Build Template" :
                           log.step === "consistency_check" ? "Consistency Check (HermiT)" :
                           log.step}
                        </div>
                        <code className={styles.logStepCmd}>{log.command}</code>
                        {log.note && <div className={styles.logStepNote}>{log.note}</div>}
                        {log.stderr && <pre className={styles.logStepErr}>{log.stderr}</pre>}
                      </div>
                    </div>
                  ))}
                </div>
              )}

              {/* Consistency Check Result */}
              {buildResult.consistency_check && (
                <div className={`${styles.consistencyBanner} ${
                  buildResult.consistency_check.consistent ? styles.consistencyOk : styles.consistencyFail
                }`}>
                  <span className={styles.consistencyIcon}>
                    {buildResult.consistency_check.consistent ? "\u2713" : "\u2717"}
                  </span>
                  <div>
                    <div className={styles.consistencyTitle}>
                      {buildResult.consistency_check.consistent
                        ? "Ontology is Consistent"
                        : "INCONSISTENT — Review Required"}
                    </div>
                    {buildResult.consistency_check.explanation && (
                      <pre className={styles.consistencyExpl}>
                        {buildResult.consistency_check.explanation}
                      </pre>
                    )}
                  </div>
                </div>
              )}

              <div className={styles.resultPath}>
                Output: <code>{buildResult.output_path}</code>
              </div>
              <div className={styles.resultActions}>
                <button className={styles.backBtn} onClick={handleStartNew}>
                  Import Another CSV
                </button>
                <button className={styles.nextBtn} onClick={() => { fetchFiles(); setStep(5); }}>
                  View All Files
                </button>
              </div>
            </>
          )}

          {buildResult && !buildResult.success && (
            <div className={styles.errorBanner}>
              <div className={styles.errorTitle}>Build Failed</div>
              <div className={styles.errorDetail}>
                {buildResult.error || "Unknown error"}
              </div>
              {buildResult.logs && buildResult.logs.map((log: any, i: number) => (
                <div key={i} className={styles.logStep}>
                  <code className={styles.logStepCmd}>{log.command}</code>
                  {log.stderr && <pre className={styles.logStepErr}>{log.stderr}</pre>}
                </div>
              ))}
              <button className={styles.backBtn} onClick={() => setStep(3)}>
                Back to Template
              </button>
            </div>
          )}
        </div>
      )}

      {/* ── Step 5: Multi-file Management ────────────────── */}
      {step === 5 && (
        <div className={styles.section}>
          <div className={styles.sectionHeader}>
            <h4 className={styles.sectionTitle}>KG File Manager</h4>
            <button
              className={styles.refreshBtn}
              onClick={fetchFiles}
              title="Refresh file list"
            >
              Refresh
            </button>
          </div>

          {/* Uploads */}
          <div className={styles.fileSection}>
            <h5 className={styles.fileSectionTitle}>Uploaded Data Files</h5>
            {kgFiles.uploads.length === 0 ? (
              <p className={styles.emptyHint}>No files uploaded yet.</p>
            ) : (
              <div className={styles.fileGrid}>
                {kgFiles.uploads.map((f) => (
                  <div key={f.name} className={styles.fileCard}>
                    <span className={styles.fileName}>{f.name}</span>
                    <span className={styles.fileSize}>
                      {formatBytes(f.size)}
                    </span>
                    <a
                      className={styles.fileAction}
                      href={getDownloadUrl(f.path)}
                      download={f.name}
                    >
                      Download
                    </a>
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* Templates */}
          <div className={styles.fileSection}>
            <h5 className={styles.fileSectionTitle}>ROBOT Templates</h5>
            {kgFiles.templates.length === 0 ? (
              <p className={styles.emptyHint}>No templates generated yet.</p>
            ) : (
              <div className={styles.fileGrid}>
                {kgFiles.templates.map((f) => (
                  <div key={f.name} className={styles.fileCard}>
                    <span className={styles.fileName}>{f.name}</span>
                    <span className={styles.fileSize}>
                      {formatBytes(f.size)}
                    </span>
                    <a
                      className={styles.fileAction}
                      href={getDownloadUrl(f.path)}
                      download={f.name}
                    >
                      Download
                    </a>
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* Output */}
          <div className={styles.fileSection}>
            <h5 className={styles.fileSectionTitle}>Generated KG Files</h5>
            {kgFiles.output.length === 0 ? (
              <p className={styles.emptyHint}>
                No knowledge graphs built yet.
              </p>
            ) : (
              <>
                <div className={styles.fileGrid}>
                  {kgFiles.output.map((f) => (
                    <div key={f.name} className={styles.fileCard}>
                      <span className={styles.fileName}>{f.name}</span>
                      <span className={styles.fileSize}>
                        {formatBytes(f.size)}
                      </span>
                      <a
                        className={styles.fileAction}
                        href={getDownloadUrl(f.path)}
                        download={f.name}
                      >
                        Download
                      </a>
                    </div>
                  ))}
                </div>
                {kgFiles.output.filter(
                  (f) =>
                    f.name.endsWith(".owl") && f.name !== "merged-kg.owl"
                ).length > 1 && (
                  <button
                    className={`${styles.nextBtn} ${styles.mergeBtn}`}
                    onClick={handleMergeAll}
                    disabled={merging}
                  >
                    {merging ? "Merging..." : "Merge All KG Files"}
                  </button>
                )}
              </>
            )}
          </div>

          <button className={styles.backBtn} onClick={handleStartNew}>
            Import New CSV
          </button>
        </div>
      )}
    </div>
  );
}
