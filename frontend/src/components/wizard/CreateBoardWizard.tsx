/**
 * CreateBoardWizard — 5-step modal for creating ODK-compliant or blank boards.
 *
 * Step 0: Choose mode (ODK / Blank)
 * Step 1: Configuration form (ODK) — with help tooltips for every field
 * Step 2: Imports & ID Ranges — import ontologies + configure contributor ID ranges
 * Step 3: YAML preview & edit
 * Step 4: Success — shows generated files
 */

import { useState, useCallback } from "react";
import { useNavigate } from "react-router-dom";
import { apiJson } from "../../api";
import styles from "./CreateBoardWizard.module.css";

/* ── Help tooltip component ───────────────────────────────── */
function HelpTip({ text }: { text: string }) {
  const [open, setOpen] = useState(false);
  return (
    <span className={styles.helpTip}>
      <button
        type="button"
        className={styles.helpBtn}
        onClick={(e) => { e.preventDefault(); setOpen(!open); }}
        aria-label="Help"
      >?</button>
      {open && (
        <div className={styles.helpPopup}>
          <div className={styles.helpText}>{text}</div>
          <button className={styles.helpClose} onClick={() => setOpen(false)}>&times;</button>
        </div>
      )}
    </span>
  );
}

/* ── Interfaces ───────────────────────────────────────────── */
interface ImportEntry {
  id: string;
  mirror_from: string;
  module_type: string;
  term_iris: string; // newline-separated IRIs to extract from the import
}

interface IdRangeEntry {
  owner: string;
  prefix: string;
  lower: number;
  upper: number;
}

interface Props {
  onClose: () => void;
  onCreated: (boardId: string) => void;
}

const STEP_LABELS = ["Mode", "Configure", "Imports & IDs", "Preview YAML", "Done"];

/* ── Common ontologies for quick-add ────────────────────────── */
const COMMON_IMPORTS = [
  { id: "ro", label: "RO (Relations Ontology)", mirror: "http://purl.obolibrary.org/obo/ro.owl" },
  { id: "iao", label: "IAO (Information Artifact)", mirror: "http://purl.obolibrary.org/obo/iao.owl" },
  { id: "bfo", label: "BFO (Basic Formal Ontology)", mirror: "http://purl.obolibrary.org/obo/bfo.owl" },
  { id: "pato", label: "PATO (Phenotypic Quality)", mirror: "http://purl.obolibrary.org/obo/pato.owl" },
  { id: "chebi", label: "ChEBI (Chemical Entities)", mirror: "http://purl.obolibrary.org/obo/chebi.owl" },
];

export default function CreateBoardWizard({ onClose, onCreated }: Props) {
  const navigate = useNavigate();
  const [step, setStep] = useState(0);
  const [mode, setMode] = useState<"odk" | "blank">("odk");

  // Step 0+1 form state
  const [ontId, setOntId] = useState("");
  const [title, setTitle] = useState("");
  const [githubOrg, setGithubOrg] = useState("");
  const [repo, setRepo] = useState("");
  const [uribase, setUribase] = useState("http://purl.obolibrary.org/obo/");
  const [gitMainBranch, setGitMainBranch] = useState("main");
  const [releaseArtefacts, setReleaseArtefacts] = useState<Set<string>>(new Set(["base", "full"]));
  const [primaryRelease, setPrimaryRelease] = useState("full");
  const [exportFormats, setExportFormats] = useState<Set<string>>(new Set(["owl", "ttl"]));
  const [versioningStrategy, setVersioningStrategy] = useState("date");
  const [robotJavaArgs, setRobotJavaArgs] = useState("-Xmx8G");
  const [docSystem, setDocSystem] = useState("mkdocs");
  const [isPublic, setIsPublic] = useState(true);

  // Step 2: Imports & ID Ranges
  const [imports, setImports] = useState<ImportEntry[]>([]);
  const [idRanges, setIdRanges] = useState<IdRangeEntry[]>([
    { owner: "Default", prefix: "ONTO", lower: 1, upper: 9999999 },
  ]);

  // YAML + API state
  const [yamlContent, setYamlContent] = useState("");
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState("");
  const [result, setResult] = useState<{ board_id: string; files: string[] } | null>(null);

  // ── Helpers ────────────────────────────────────────────────
  const normalizeId = (v: string) => v.toLowerCase().replace(/[^a-z0-9-]/g, "-").replace(/-+/g, "-").replace(/^-|-$/g, "");

  const toggleSet = (s: Set<string>, val: string) => {
    const next = new Set(s);
    if (next.has(val)) next.delete(val); else next.add(val);
    return next;
  };

  // Import helpers
  const addImport = () => setImports([...imports, { id: "", mirror_from: "", module_type: "custom", term_iris: "" }]);
  const addCommonImport = (ci: typeof COMMON_IMPORTS[0]) => {
    if (imports.some((imp) => imp.id === ci.id)) return; // already added
    setImports([...imports, { id: ci.id, mirror_from: ci.mirror, module_type: "custom", term_iris: "" }]);
  };
  const updateImport = (i: number, field: keyof ImportEntry, val: string) => {
    const next = [...imports];
    next[i] = { ...next[i], [field]: val };
    setImports(next);
  };
  const removeImport = (i: number) => setImports(imports.filter((_, idx) => idx !== i));

  // ID Range helpers
  const addIdRange = () => setIdRanges([...idRanges, { owner: "", prefix: normalizeId(ontId).toUpperCase() || "ONTO", lower: 0, upper: 0 }]);
  const updateIdRange = (i: number, field: keyof IdRangeEntry, val: string | number) => {
    const next = [...idRanges];
    next[i] = { ...next[i], [field]: val };
    setIdRanges(next);
  };
  const removeIdRange = (i: number) => setIdRanges(idRanges.filter((_, idx) => idx !== i));

  // ── YAML Generation ────────────────────────────────────────
  const generateYaml = useCallback(() => {
    const id = normalizeId(ontId);
    let yaml = "";
    yaml += `id: ${id}\n`;
    yaml += `title: "${title}"\n`;
    if (githubOrg) yaml += `github_org: ${githubOrg}\n`;
    yaml += `git_main_branch: ${gitMainBranch}\n`;
    yaml += `repo: ${repo || id}\n`;
    yaml += `uribase: ${uribase}${uribase.endsWith("/") ? "" : "/"}${id}\n`;
    yaml += `release_artefacts:\n`;
    for (const a of releaseArtefacts) yaml += `  - ${a}\n`;
    yaml += `primary_release: ${primaryRelease}\n`;
    yaml += `export_formats:\n`;
    for (const f of exportFormats) yaml += `  - ${f}\n`;

    // Imports
    yaml += `import_group:\n`;
    yaml += `  annotation_properties:\n`;
    yaml += `    - rdfs:label\n`;
    yaml += `    - IAO:0000115\n`;
    yaml += `    - skos:definition\n`;
    if (imports.length > 0) {
      yaml += `  products:\n`;
      for (const imp of imports) {
        if (!imp.id) continue;
        yaml += `    - id: ${imp.id}\n`;
        if (imp.mirror_from) yaml += `      mirror_from: "${imp.mirror_from}"\n`;
        yaml += `      module_type: ${imp.module_type}\n`;
      }
    } else {
      yaml += `  products: []\n`;
    }

    // ID Ranges configuration
    yaml += `# ID ranges for contributors (see ${id}-idranges.owl)\n`;
    yaml += `idranges:\n`;
    for (const range of idRanges) {
      if (!range.owner) continue;
      yaml += `  - owner: "${range.owner}"\n`;
      yaml += `    prefix: "${range.prefix}"\n`;
      yaml += `    lower: ${range.lower}\n`;
      yaml += `    upper: ${range.upper}\n`;
    }

    yaml += `documentation:\n`;
    yaml += `  documentation_system: ${docSystem}\n`;
    yaml += `robot_java_args: "${robotJavaArgs}"\n`;
    yaml += `robot_report:\n`;
    yaml += `  use_labels: TRUE\n`;
    yaml += `  fail_on: ERROR\n`;
    yaml += `  report_on:\n`;
    yaml += `    - edit\n`;

    return yaml;
  }, [ontId, title, githubOrg, repo, uribase, gitMainBranch, releaseArtefacts, primaryRelease, exportFormats, imports, idRanges, docSystem, robotJavaArgs]);

  // ── Actions ────────────────────────────────────────────────
  const handleChooseOdk = () => { setMode("odk"); setStep(1); };

  const handleChooseBlank = async () => {
    setMode("blank");
    if (!ontId) { setError("Enter an Ontology ID first"); return; }
    await createBoard("blank", "");
  };

  const handleNextToImports = () => { setStep(2); };
  const handleNextToPreview = () => {
    const yaml = generateYaml();
    setYamlContent(yaml);
    setStep(3);
  };
  const handleCreate = () => createBoard("odk", yamlContent);

  const createBoard = async (m: "odk" | "blank", yaml: string) => {
    const id = normalizeId(ontId);
    if (!id) { setError("Ontology ID is required"); return; }
    setCreating(true);
    setError("");
    try {
      const body: Record<string, any> = {
        ont_id: id,
        title: title || id,
        mode: m,
        yaml_config: yaml,
        versioning_strategy: versioningStrategy,
      };
      // Include term IRIs for imports that have them
      const importTerms: Record<string, string[]> = {};
      for (const imp of imports) {
        if (imp.term_iris.trim()) {
          importTerms[imp.id] = imp.term_iris.split("\n").map((l) => l.trim()).filter(Boolean);
        }
      }
      if (Object.keys(importTerms).length > 0) {
        body.import_terms = importTerms;
      }
      // Include ID ranges
      if (idRanges.length > 0) {
        body.id_ranges = idRanges.filter((r) => r.owner);
      }

      const data = await apiJson<{ board_id: string; files: string[]; success: boolean }>(
        "/api/odk-setup/create-board",
        { method: "POST", body: JSON.stringify(body) }
      );
      setResult(data);
      setStep(4);
    } catch (e: any) {
      setError(e.message || "Board creation failed");
    } finally {
      setCreating(false);
    }
  };

  const canProceedStep1 = ontId.trim().length > 0 && title.trim().length > 0;

  // Group files by directory for success view
  const groupFiles = (files: string[]) => {
    const groups: Record<string, string[]> = {};
    for (const f of files) {
      const parts = f.replace(/\\/g, "/").split("/");
      const dir = parts.length > 1 ? parts.slice(0, -1).join("/") : ".";
      if (!groups[dir]) groups[dir] = [];
      groups[dir].push(parts[parts.length - 1]);
    }
    return groups;
  };

  return (
    <div className={styles.overlay} onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className={styles.dialog}>
        {/* Header */}
        <div className={styles.header}>
          <h2 className={styles.title}>Create New Board</h2>
          <button className={styles.closeBtn} onClick={onClose}>&#10005;</button>
        </div>

        {/* Steps indicator */}
        <div className={styles.steps}>
          {STEP_LABELS.map((label, i) => (
            <div key={i} className={`${styles.step} ${i === step ? styles.stepActive : i < step ? styles.stepDone : ""}`}>
              {label}
            </div>
          ))}
        </div>

        {/* Body */}
        <div className={styles.body}>
          {error && <div className={styles.error}>{error}</div>}

          {creating ? (
            <div className={styles.creating}>
              <div className={styles.spinner} />
              <div className={styles.creatingText}>Creating board... This may take a moment.</div>
            </div>
          ) : (
            <>
              {/* ═══════════════════════════════════════════════
                  Step 0: Choose mode
                  ═══════════════════════════════════════════════ */}
              {step === 0 && (
                <>
                  <div className={styles.formSection}>
                    <div className={styles.formRow}>
                      <div className={styles.formGroup}>
                        <label className={styles.formLabel}>
                          Ontology ID <span className={styles.formRequired}>*</span>
                          <HelpTip text="A short lowercase identifier for your ontology (e.g. 'pizza', 'nfdicore'). This becomes part of all generated file names and IRIs. Use only letters, numbers, and hyphens." />
                        </label>
                        <input className={styles.formInput} placeholder="e.g. pizza, nfdicore, myonto"
                               value={ontId} onChange={(e) => setOntId(e.target.value)} />
                      </div>
                      <div className={styles.formGroup}>
                        <label className={styles.formLabel}>
                          Title
                          <HelpTip text="A human-readable name for your ontology. This appears in metadata and documentation." />
                        </label>
                        <input className={styles.formInput} placeholder="e.g. Pizza Ontology"
                               value={title} onChange={(e) => setTitle(e.target.value)} />
                      </div>
                    </div>
                  </div>

                  <div className={styles.modeCards}>
                    <div className={styles.modeCard} onClick={handleChooseOdk}>
                      <div className={styles.modeIcon}>&#9881;</div>
                      <div className={styles.modeTitle}>ODK Board</div>
                      <div className={styles.modeDesc}>
                        Full <strong>Ontology Development Kit</strong> setup with YAML config, Makefile, imports, CI/CD, ROBOT tools, and release pipeline. Recommended for collaborative ontology development.
                      </div>
                      <div className={styles.modeLearnMore}>
                        <a href="https://ontology-development-kit.readthedocs.io/en/latest/" target="_blank" rel="noreferrer">
                          Learn about ODK &rarr;
                        </a>
                      </div>
                    </div>
                    <div className={styles.modeCard} onClick={() => { if (ontId.trim()) handleChooseBlank(); else setError("Enter an Ontology ID first"); }}>
                      <div className={styles.modeIcon}>&#9998;</div>
                      <div className={styles.modeTitle}>Blank Board</div>
                      <div className={styles.modeDesc}>
                        Minimal scaffold for quick experimentation. You can add ODK configuration later from the board settings.
                      </div>
                    </div>
                  </div>
                </>
              )}

              {/* ═══════════════════════════════════════════════
                  Step 1: Configuration form
                  ═══════════════════════════════════════════════ */}
              {step === 1 && (
                <>
                  {/* Identity */}
                  <div className={styles.formSection}>
                    <div className={styles.formSectionTitle}>
                      Identity
                      <HelpTip text="Basic identifiers for your ontology. The ID is used in file names and IRIs. The title appears in documentation." />
                    </div>
                    <div className={styles.formRow}>
                      <div className={styles.formGroup}>
                        <label className={styles.formLabel}>Ontology ID <span className={styles.formRequired}>*</span></label>
                        <input className={styles.formInput} value={ontId} onChange={(e) => setOntId(e.target.value)} placeholder="nfdicore" />
                      </div>
                      <div className={styles.formGroup}>
                        <label className={styles.formLabel}>Title <span className={styles.formRequired}>*</span></label>
                        <input className={styles.formInput} value={title} onChange={(e) => setTitle(e.target.value)} placeholder="NFDI Core Ontology" />
                      </div>
                    </div>
                  </div>

                  {/* Repository */}
                  <div className={styles.formSection}>
                    <div className={styles.formSectionTitle}>
                      Repository
                      <HelpTip text="Optional GitHub settings. If you plan to host your ontology on GitHub, fill these in. The ODK can auto-generate CI/CD workflows for your repo." />
                    </div>
                    <div className={styles.formRow}>
                      <div className={styles.formGroup}>
                        <label className={styles.formLabel}>
                          GitHub Org
                          <HelpTip text="The GitHub organization or username (e.g. 'obophenotype', 'ISE-FIZKarlsruhe'). Leave empty if not using GitHub." />
                        </label>
                        <input className={styles.formInput} value={githubOrg} onChange={(e) => setGithubOrg(e.target.value)} placeholder="ISE-FIZKarlsruhe" />
                      </div>
                      <div className={styles.formGroup}>
                        <label className={styles.formLabel}>Repo Name</label>
                        <input className={styles.formInput} value={repo} onChange={(e) => setRepo(e.target.value)} placeholder={normalizeId(ontId) || "repo-name"} />
                      </div>
                      <div className={styles.formGroup}>
                        <label className={styles.formLabel}>Branch</label>
                        <input className={styles.formInput} value={gitMainBranch} onChange={(e) => setGitMainBranch(e.target.value)} />
                      </div>
                    </div>
                  </div>

                  {/* Namespace */}
                  <div className={styles.formSection}>
                    <div className={styles.formSectionTitle}>
                      Namespace
                      <HelpTip text="The base URI for your ontology's IRIs. For OBO ontologies this is typically 'http://purl.obolibrary.org/obo/'. For custom ontologies, use your organization's domain." />
                    </div>
                    <div className={styles.formGroup}>
                      <label className={styles.formLabel}>URI Base <span className={styles.formRequired}>*</span></label>
                      <input className={styles.formInput} value={uribase} onChange={(e) => setUribase(e.target.value)}
                             placeholder="http://purl.obolibrary.org/obo/" />
                      <div className={styles.fieldHint}>
                        Preview: {uribase}{uribase.endsWith("/") ? "" : "/"}{normalizeId(ontId) || "myonto"}
                      </div>
                    </div>
                  </div>

                  {/* Release */}
                  <div className={styles.formSection}>
                    <div className={styles.formSectionTitle}>
                      Release
                      <HelpTip text="Controls how your ontology is packaged for release. 'base' contains only asserted axioms. 'full' includes inferred axioms from reasoning. 'simple' removes imports." />
                    </div>
                    <div className={styles.formRow}>
                      <div className={styles.formGroup}>
                        <label className={styles.formLabel}>
                          Release Artefacts
                          <HelpTip text="Which versions of the ontology to produce on release. 'base' = asserted only, 'full' = with inferred axioms, 'simple' = without import statements." />
                        </label>
                        <div className={styles.checkboxGroup}>
                          {["base", "full", "simple"].map((a) => (
                            <label key={a} className={styles.checkboxLabel}>
                              <input type="checkbox" checked={releaseArtefacts.has(a)} onChange={() => setReleaseArtefacts(toggleSet(releaseArtefacts, a))} />
                              {a}
                            </label>
                          ))}
                        </div>
                      </div>
                      <div className={styles.formGroup}>
                        <label className={styles.formLabel}>
                          Primary Release
                          <HelpTip text="The default release file that users download. Usually 'full' (includes reasoning results) or 'base' (asserted axioms only)." />
                        </label>
                        <select className={styles.formSelect} value={primaryRelease} onChange={(e) => setPrimaryRelease(e.target.value)}>
                          <option value="base">base</option>
                          <option value="full">full</option>
                          <option value="simple">simple</option>
                        </select>
                      </div>
                    </div>
                    <div className={styles.formGroup}>
                      <label className={styles.formLabel}>
                        Export Formats
                        <HelpTip text="File formats to produce. OWL/XML is the standard. Turtle (.ttl) is human-readable. OBO is used in biomedical ontologies. JSON-LD is for linked data." />
                      </label>
                      <div className={styles.checkboxGroup}>
                        {["owl", "ttl", "obo", "jsonld"].map((f) => (
                          <label key={f} className={styles.checkboxLabel}>
                            <input type="checkbox" checked={exportFormats.has(f)} onChange={() => setExportFormats(toggleSet(exportFormats, f))} />
                            {f}
                          </label>
                        ))}
                      </div>
                    </div>
                  </div>

                  {/* Versioning */}
                  <div className={styles.formSection}>
                    <div className={styles.formSectionTitle}>
                      Versioning
                      <HelpTip text="How version numbers are assigned to releases. Date-based (YYYY-MM-DD) is the OBO default. Semantic versioning (X.Y.Z) is common in non-OBO projects like NFDIcore." />
                    </div>
                    <div className={styles.radioGroup}>
                      <label className={styles.radioLabel}>
                        <input type="radio" name="versioning" value="date" checked={versioningStrategy === "date"} onChange={() => setVersioningStrategy("date")} />
                        Date-based (YYYY-MM-DD)
                      </label>
                      <label className={styles.radioLabel}>
                        <input type="radio" name="versioning" value="semantic" checked={versioningStrategy === "semantic"} onChange={() => setVersioningStrategy("semantic")} />
                        Semantic (X.Y.Z)
                      </label>
                    </div>
                    <div className={styles.radioHint}>
                      {versioningStrategy === "date" ? "OBO default — release versions use today's date (e.g. 2026-04-12)" : "Uses major.minor.patch versioning (e.g. 1.2.3). Bump patch for fixes, minor for features, major for breaking changes."}
                    </div>
                  </div>

                  {/* Build */}
                  <div className={styles.formSection}>
                    <div className={styles.formSectionTitle}>
                      Build Settings
                      <HelpTip text="Advanced settings for the ODK build pipeline. ROBOT is the command-line tool used for ontology operations. Java args control memory allocation." />
                    </div>
                    <div className={styles.formRow}>
                      <div className={styles.formGroup}>
                        <label className={styles.formLabel}>
                          ROBOT Java Args
                          <HelpTip text="Memory allocation for ROBOT. -Xmx8G means 8GB max heap. Increase for large ontologies, decrease if your machine has limited RAM." />
                        </label>
                        <input className={styles.formInput} value={robotJavaArgs} onChange={(e) => setRobotJavaArgs(e.target.value)} />
                      </div>
                      <div className={styles.formGroup}>
                        <label className={styles.formLabel}>
                          Documentation
                          <HelpTip text="Which documentation system to use for auto-generated docs. MkDocs produces a static site from markdown." />
                        </label>
                        <select className={styles.formSelect} value={docSystem} onChange={(e) => setDocSystem(e.target.value)}>
                          <option value="mkdocs">MkDocs</option>
                          <option value="readthedocs">Read the Docs</option>
                        </select>
                      </div>
                    </div>
                  </div>

                  {/* Visibility */}
                  <div className={styles.formSection}>
                    <div className={styles.formSectionTitle}>Visibility</div>
                    <label className={styles.toggle}>
                      <input type="checkbox" checked={isPublic} onChange={(e) => setIsPublic(e.target.checked)} />
                      Public board (visible to all users)
                    </label>
                  </div>
                </>
              )}

              {/* ═══════════════════════════════════════════════
                  Step 2: Imports & ID Ranges
                  ═══════════════════════════════════════════════ */}
              {step === 2 && (
                <>
                  {/* ── Imports ──────────────────────────────── */}
                  <div className={styles.formSection}>
                    <div className={styles.formSectionTitle}>
                      Ontology Imports ({imports.length})
                      <HelpTip text="Import external ontologies to reuse their classes and properties. You can import the full ontology (mirror) or only specific terms you need (custom). ODK will manage downloading and updating these imports automatically." />
                    </div>

                    {/* Quick-add common ontologies */}
                    <div className={styles.quickAdd}>
                      <span className={styles.quickAddLabel}>Quick add:</span>
                      {COMMON_IMPORTS.map((ci) => (
                        <button key={ci.id} className={`${styles.quickAddBtn} ${imports.some((imp) => imp.id === ci.id) ? styles.quickAddBtnActive : ""}`}
                                onClick={() => addCommonImport(ci)} title={ci.label}>
                          {ci.id.toUpperCase()}
                        </button>
                      ))}
                    </div>

                    <div className={styles.importList}>
                      {imports.map((imp, i) => (
                        <div key={i} className={styles.importCard}>
                          <div className={styles.importCardHeader}>
                            <div className={styles.importRow}>
                              <div className={styles.formGroup}>
                                <label className={styles.formLabel}>
                                  Import ID
                                  <HelpTip text="Short identifier for this import (e.g. 'ro', 'bfo'). This becomes the import file name." />
                                </label>
                                <input className={styles.formInput} placeholder="e.g. ro, bfo, iao" value={imp.id} onChange={(e) => updateImport(i, "id", e.target.value)} />
                              </div>
                              <div className={styles.formGroup}>
                                <label className={styles.formLabel}>
                                  Module Type
                                  <HelpTip text="'custom' = import only specific terms you list below. 'mirror' = import the full ontology. 'slme' = use ROBOT's SLME module extraction. 'filter' = filter by annotations." />
                                </label>
                                <select className={styles.formSelect} value={imp.module_type} onChange={(e) => updateImport(i, "module_type", e.target.value)}>
                                  <option value="custom">custom (specific terms)</option>
                                  <option value="mirror">mirror (full ontology)</option>
                                  <option value="slme">slme (auto-extract)</option>
                                  <option value="filter">filter (by annotation)</option>
                                </select>
                              </div>
                              <button className={styles.importRemove} onClick={() => removeImport(i)} title="Remove import">&#10005;</button>
                            </div>
                            <div className={styles.formGroup}>
                              <label className={styles.formLabel}>
                                Source URL
                                <HelpTip text="The URL where the ontology OWL file can be downloaded. For OBO ontologies, this is usually http://purl.obolibrary.org/obo/{id}.owl" />
                              </label>
                              <input className={styles.formInput} placeholder="http://purl.obolibrary.org/obo/ro.owl"
                                     value={imp.mirror_from} onChange={(e) => updateImport(i, "mirror_from", e.target.value)} />
                            </div>
                          </div>

                          {/* Term IRIs — only for custom module type */}
                          {imp.module_type === "custom" && (
                            <div className={styles.termIriSection}>
                              <label className={styles.formLabel}>
                                Term IRIs to import
                                <HelpTip text="List the specific IRIs you want to import from this ontology, one per line. For example: http://purl.obolibrary.org/obo/BFO_0000001. Only these terms (and their labels/definitions) will be extracted." />
                              </label>
                              <textarea
                                className={styles.termIriInput}
                                placeholder={"Paste IRIs here, one per line:\nhttp://purl.obolibrary.org/obo/BFO_0000001\nhttp://purl.obolibrary.org/obo/BFO_0000002\n\n...or upload a file below"}
                                value={imp.term_iris}
                                onChange={(e) => updateImport(i, "term_iris", e.target.value)}
                                rows={4}
                              />
                              <div className={styles.termIriActions}>
                                <label className={styles.uploadBtn}>
                                  Upload IRI list (.txt)
                                  <input type="file" accept=".txt,.csv,.tsv" hidden onChange={(e) => {
                                    const file = e.target.files?.[0];
                                    if (!file) return;
                                    const reader = new FileReader();
                                    reader.onload = (ev) => {
                                      const text = ev.target?.result as string;
                                      const existing = imp.term_iris.trim();
                                      updateImport(i, "term_iris", existing ? existing + "\n" + text.trim() : text.trim());
                                    };
                                    reader.readAsText(file);
                                    e.target.value = "";
                                  }} />
                                </label>
                                <span className={styles.termCount}>
                                  {imp.term_iris.split("\n").filter((l) => l.trim()).length} terms
                                </span>
                              </div>
                            </div>
                          )}
                        </div>
                      ))}
                    </div>
                    <button className={styles.addImportBtn} onClick={addImport}>+ Add Import</button>
                  </div>

                  {/* ── ID Ranges ────────────────────────────── */}
                  <div className={styles.formSection}>
                    <div className={styles.formSectionTitle}>
                      ID Ranges
                      <HelpTip text="ID ranges allocate non-overlapping numeric ID spaces to each contributor, preventing merge conflicts. This generates the {ontology}-idranges.owl file. Each contributor gets a range like ONTO:0000001 to ONTO:0099999. When you create a new class, your ID is automatically assigned from your range." />
                    </div>
                    <div className={styles.infoBox}>
                      Generates <code>{normalizeId(ontId) || "ontology"}-idranges.owl</code> — assigns each contributor a numeric ID range so that new entity IRIs never collide.
                      This is especially important for collaborative development with multiple editors.
                    </div>
                    <div className={styles.idRangeTable}>
                      <div className={styles.idRangeHeader}>
                        <span>Owner</span>
                        <span>Prefix</span>
                        <span>Lower Bound</span>
                        <span>Upper Bound</span>
                        <span></span>
                      </div>
                      {idRanges.map((range, i) => (
                        <div key={i} className={styles.idRangeRow}>
                          <input className={styles.formInput} placeholder="Contributor name"
                                 value={range.owner} onChange={(e) => updateIdRange(i, "owner", e.target.value)} />
                          <input className={styles.formInput} placeholder="ONTO"
                                 value={range.prefix} onChange={(e) => updateIdRange(i, "prefix", e.target.value)} />
                          <input className={styles.formInput} type="number" placeholder="1"
                                 value={range.lower || ""} onChange={(e) => updateIdRange(i, "lower", parseInt(e.target.value) || 0)} />
                          <input className={styles.formInput} type="number" placeholder="9999999"
                                 value={range.upper || ""} onChange={(e) => updateIdRange(i, "upper", parseInt(e.target.value) || 0)} />
                          <button className={styles.importRemove} onClick={() => removeIdRange(i)} title="Remove range">&#10005;</button>
                        </div>
                      ))}
                    </div>
                    <button className={styles.addImportBtn} onClick={addIdRange}>+ Add Contributor Range</button>
                  </div>
                </>
              )}

              {/* ═══════════════════════════════════════════════
                  Step 3: YAML Preview
                  ═══════════════════════════════════════════════ */}
              {step === 3 && (
                <>
                  <div className={styles.yamlHint}>
                    Review and edit the ODK configuration. This will be saved as <code>{normalizeId(ontId)}-odk.yaml</code>.
                    You can always edit this later from the Files tab.
                  </div>
                  <textarea
                    className={styles.yamlEditor}
                    value={yamlContent}
                    onChange={(e) => setYamlContent(e.target.value)}
                    spellCheck={false}
                  />
                </>
              )}

              {/* ═══════════════════════════════════════════════
                  Step 4: Success
                  ═══════════════════════════════════════════════ */}
              {step === 4 && result && (
                <>
                  <div className={styles.successBanner}>
                    <div className={styles.successIcon}>&#10003;</div>
                    <div className={styles.successTitle}>Board Created</div>
                    <div className={styles.successSub}>
                      <strong>{result.board_id}</strong> is ready with {result.files.length} files
                    </div>
                  </div>

                  {result.files.length > 0 && (
                    <div className={styles.fileList}>
                      {Object.entries(groupFiles(result.files)).map(([dir, files]) => (
                        <div key={dir} className={styles.fileGroup}>
                          <div className={styles.fileGroupTitle}>{dir}</div>
                          {files.map((f) => (
                            <div key={f} className={styles.fileName}>{f}</div>
                          ))}
                        </div>
                      ))}
                    </div>
                  )}

                  <div className={styles.successHint}>
                    You can now use the ODK panel in the board to run <strong>ROBOT</strong> commands,
                    refresh imports, run the reasoner, and build releases.
                  </div>

                  <div className={styles.successActions}>
                    <button className={styles.btnPrimary} onClick={() => { onCreated(result.board_id); navigate(`/board/${result.board_id}`); }}>
                      Open Board Editor
                    </button>
                    <button className={styles.btn} onClick={onClose}>
                      Close
                    </button>
                  </div>
                </>
              )}
            </>
          )}
        </div>

        {/* Footer navigation */}
        {!creating && step > 0 && step < 4 && (
          <div className={styles.footer}>
            <button className={styles.btn} onClick={() => setStep(step - 1)}>
              Back
            </button>
            <div className={styles.footerRight}>
              {step === 1 && (
                <button className={styles.btnPrimary} onClick={handleNextToImports} disabled={!canProceedStep1}>
                  Next: Imports &amp; ID Ranges
                </button>
              )}
              {step === 2 && (
                <button className={styles.btnPrimary} onClick={handleNextToPreview}>
                  Next: Preview YAML
                </button>
              )}
              {step === 3 && (
                <button className={styles.btnSuccess} onClick={handleCreate}>
                  Create Board
                </button>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
