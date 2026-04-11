/**
 * BoardPage — Main ontology editor layout.
 *
 * Layout:
 *   [Header: Logo + BoardName + AppMenu + Actions + Collab]
 *   [LeftPanel: Hierarchy + Patterns] | [Canvas: React Flow] | [RightPanel: Tabs]
 */

import { useEffect, useState, useRef, useCallback } from "react";
import { useParams, Link } from "react-router-dom";
import { useAuth } from "../auth";
import { api, apiJson, ApiError } from "../api";
import styles from "./BoardPage.module.css";
import Logo from "../components/Logo";
import OntologyDashboard from "../components/OntologyDashboard";
import OntologyCanvas from "../components/canvas/OntologyCanvas";
import AxiomEditor from "../components/axiom/AxiomEditor";
import TreeBrowser from "../components/tree/TreeBrowser";
import { useCollaboration } from "../collab/useCollaboration";
import CollabStatus from "../collab/CollabStatus";
import PublishPanel from "../components/publish/PublishPanel";
import ReasoningPanel from "../components/reasoning/ReasoningPanel";
import TaskBoard from "../components/tasks/TaskBoard";
import OdkPanel from "../components/odk/OdkPanel";
import CsvImportWizard from "../components/csv/CsvImportWizard";
import SparqlPanel from "../components/sparql/SparqlPanel";
import DocsPanel from "../components/docs/DocsPanel";
import ShareDialog from "../components/share/ShareDialog";
import { useOntologyStore } from "../store/ontologyStore";

type Tab = "ontology" | "axioms" | "reasoning" | "odk" | "sparql" | "csv" | "tasks" | "publish" | "docs" | "console";

export default function BoardPage() {
  const { boardId } = useParams<{ boardId: string }>();
  const { user } = useAuth();
  const [status, setStatus] = useState<"loading" | "ready" | "provisioning" | "error">("loading");
  const [error, setError] = useState("");
  const [sideOpen, setSideOpen] = useState(true);
  const [activeTab, setActiveTab] = useState<Tab>("ontology");
  const [consoleLog, setConsoleLog] = useState<string[]>([]);
  const [building, setBuilding] = useState(false);
  const [shareOpen, setShareOpen] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const selectedEntity = useOntologyStore((s) => s.selectedEntity);
  const setSelectedEntity = useOntologyStore((s) => s.selectEntity);

  const { connected, users: collabUsers } = useCollaboration(
    status === "ready" ? boardId : undefined,
    user?.display_name || user?.username || "anonymous",
  );

  useEffect(() => {
    if (!boardId) return;
    checkOrProvision();
  }, [boardId]);

  const checkOrProvision = async () => {
    try {
      const res = await api(`/api/boards/${boardId}`);
      if (res.ok) {
        setStatus("ready");
      } else if (res.status === 404) {
        setStatus("provisioning");
        const create = await api(`/api/boards/${boardId}`, { method: "POST" });
        if (create.ok) {
          setStatus("ready");
        } else {
          const data = await create.json().catch(() => ({}));
          setError(data.detail || "Provisioning failed — sign in first?");
          setStatus("error");
        }
      } else if (res.status === 401) {
        setError("Please sign in to access this board");
        setStatus("error");
      } else if (res.status === 403) {
        setError("Access denied — this board is private");
        setStatus("error");
      } else {
        setError("Unexpected error");
        setStatus("error");
      }
    } catch {
      setError("Cannot connect to backend");
      setStatus("error");
    }
  };

  // ── Epic 1: File Upload ─────────────────────────────────────
  const handleFileUpload = useCallback(async (file: File) => {
    if (!boardId) return;
    const form = new FormData();
    form.append("file", file);
    try {
      const res = await api(`/api/boards/${boardId}/from-file?is_public=true`, { method: "POST", body: form });
      if (res.ok) {
        useOntologyStore.getState().loadFromBackend(boardId);
      } else {
        const data = await res.json().catch(() => ({}));
        setError(data.detail || "File upload failed");
      }
    } catch (e: any) {
      setError(e.message || "Upload error");
    }
  }, [boardId]);

  const handleFileInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) handleFileUpload(file);
    e.target.value = "";
  };

  // ── Build / ODK ─────────────────────────────────────────────
  const handleBuild = async () => {
    setBuilding(true);
    setActiveTab("console");
    setConsoleLog(["$ make all\n"]);
    try {
      const res = await api(`/api/odk/${boardId}/build`, { method: "POST" });
      if (!res.ok || !res.body) {
        setConsoleLog((p) => [...p, `[ERROR] Build failed (${res.status})\n`]);
        setBuilding(false);
        return;
      }
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        for (const line of decoder.decode(value).split("\n\n").filter(Boolean)) {
          setConsoleLog((p) => [...p, line.replace(/^data: /, "")]);
        }
      }
    } catch {
      setConsoleLog((p) => [...p, "[ERROR] Connection lost\n"]);
    } finally {
      setBuilding(false);
    }
  };

  // ── Export (triggers browser download) ───────────────────────
  const handleExport = async (format: string) => {
    if (!boardId) return;
    setMenuOpen(false);
    try {
      // For ZIP export (full ODP repo)
      if (format === "zip") {
        const res = await api(`/api/export/${boardId}/zip`, { method: "POST" });
        if (res.ok) {
          const blob = await res.blob();
          const url = URL.createObjectURL(blob);
          const a = document.createElement("a");
          a.href = url;
          a.download = `${boardId}-odp-repo.zip`;
          a.click();
          URL.revokeObjectURL(url);
        }
        return;
      }
      // For single format conversion — first convert, then trigger download
      const res = await api(`/api/robot/${boardId}/convert`, {
        method: "POST",
        body: JSON.stringify({ output_format: format }),
      });
      if (res.ok) {
        const data = await res.json();
        if (data.output_file) {
          // Download the generated file
          const dlRes = await api(`/api/odk-mediator/${boardId}/release/download/${data.output_file.split("/").pop()}`);
          if (dlRes.ok) {
            const blob = await dlRes.blob();
            const url = URL.createObjectURL(blob);
            const a = document.createElement("a");
            a.href = url;
            a.download = data.output_file.split("/").pop() || `${boardId}.${format}`;
            a.click();
            URL.revokeObjectURL(url);
          }
        }
      }
    } catch (e: any) {
      setError(e.message || "Export failed");
    }
  };

  if (status === "loading" || status === "provisioning") {
    return (
      <div className={styles.splash}>
        <div className={styles.spinner} />
        <p className={styles.splashText}>
          {status === "loading" ? "Loading board..." : `Provisioning ${boardId}...`}
        </p>
      </div>
    );
  }

  if (status === "error") {
    return (
      <div className={styles.splash}>
        <div className={styles.errorIcon}>!</div>
        <p className={styles.splashText}>{error}</p>
        <Link to="/board" className={styles.backLink}>&larr; Back to boards</Link>
      </div>
    );
  }

  return (
    <div className={styles.layout}>
      {/* Hidden file input for uploads */}
      <input ref={fileInputRef} type="file" accept=".owl,.ttl,.rdf,.obo,.jsonld,.json,.nt,.xml"
             onChange={handleFileInputChange} hidden />

      {/* ══════════════════════════════════════════════════════════
          Epic 2: Header with App Menu
          ══════════════════════════════════════════════════════════ */}
      <header className={styles.toolbar}>
        <div className={styles.toolbarLeft}>
          <Link to="/board" className={styles.backBtn}>
            <Logo size={22} />
          </Link>
          <div className={styles.boardTitle}>
            <span className={styles.boardName}>{boardId}</span>
            <span className={styles.statusDot} />
          </div>

          {/* App Menu (Protege-style) */}
          <div className={styles.menuContainer}>
            <button className={styles.menuBtn} onClick={() => setMenuOpen(!menuOpen)}>
              Menu &#9662;
            </button>
            {menuOpen && (
              <div className={styles.menuDropdown}>
                <div className={styles.menuSection}>
                  <div className={styles.menuLabel}>File</div>
                  <button className={styles.menuItem} onClick={() => { fileInputRef.current?.click(); setMenuOpen(false); }}>
                    Open File...
                  </button>
                  <button className={styles.menuItem} onClick={() => { useOntologyStore.getState().saveToBackend(); setMenuOpen(false); }}>
                    Save
                  </button>
                  <button className={styles.menuItem} onClick={() => handleExport("ttl")}>Export as Turtle</button>
                  <button className={styles.menuItem} onClick={() => handleExport("obo")}>Export as OBO</button>
                  <button className={styles.menuItem} onClick={() => handleExport("jsonld")}>Export as JSON-LD</button>
                  <button className={styles.menuItem} onClick={() => handleExport("zip")}>Export ODP Repository (ZIP)</button>
                </div>
                <div className={styles.menuSection}>
                  <div className={styles.menuLabel}>Edit</div>
                  <button className={styles.menuItem} onClick={() => { apiJson(`/api/refactor/${boardId}/undo`, { method: "POST" }); setMenuOpen(false); }}>Undo</button>
                  <button className={styles.menuItem} onClick={() => { apiJson(`/api/refactor/${boardId}/redo`, { method: "POST" }); setMenuOpen(false); }}>Redo</button>
                </div>
                <div className={styles.menuSection}>
                  <div className={styles.menuLabel}>View</div>
                  <button className={styles.menuItem} onClick={() => { setSideOpen(!sideOpen); setMenuOpen(false); }}>
                    {sideOpen ? "Hide Right Panel" : "Show Right Panel"}
                  </button>
                </div>
                <div className={styles.menuSection}>
                  <div className={styles.menuLabel}>Board</div>
                  <button className={styles.menuItem} onClick={() => { setShareOpen(true); setMenuOpen(false); }}>Share Settings</button>
                  <button className={styles.menuItem} onClick={() => { handleBuild(); setMenuOpen(false); }}>Run ODK Build</button>
                </div>
              </div>
            )}
          </div>
        </div>

        <div className={styles.toolbarActions}>
          <button className={styles.toolBtn} onClick={() => setShareOpen(true)}>Share</button>
          <button className={`${styles.toolBtn} ${styles.buildBtn}`} onClick={handleBuild} disabled={building}>
            {building ? "Building..." : "Build"}
          </button>
          <button className={styles.toolBtn} onClick={() => setSideOpen(!sideOpen)}>
            {sideOpen ? "Hide" : "Show"}
          </button>
          <CollabStatus connected={connected} users={collabUsers}
                        currentUser={user?.display_name || user?.username || "anonymous"} />
        </div>
      </header>

      {/* Click outside menu to close */}
      {menuOpen && <div className={styles.menuOverlay} onClick={() => setMenuOpen(false)} />}

      {/* ══════════════════════════════════════════════════════════
          Main 3-panel layout
          ══════════════════════════════════════════════════════════ */}
      <div className={styles.main}>
        {/* ── Epic 3: Left Panel (Hierarchy + Patterns) ────────── */}
        {boardId && (
          <TreeBrowser boardId={boardId} onSelectEntity={setSelectedEntity} />
        )}

        {/* ── Epic 4: Canvas (React Flow) ──────────────────────── */}
        <div className={styles.canvas}>
          {boardId && <OntologyCanvas boardId={boardId} />}
        </div>

        {/* ── Epic 5: Right Panel (Metadata + Tabs) ────────────── */}
        {sideOpen && (
          <aside className={styles.side}>
            <div className={styles.tabs}>
              {(["ontology", "axioms", "reasoning", "sparql", "csv", "tasks", "publish", "docs", "console"] as Tab[]).map((t) => (
                <button key={t} className={`${styles.tab} ${activeTab === t ? styles.tabActive : ""}`}
                        onClick={() => setActiveTab(t)}>
                  {t === "ontology" ? "Onto" : t === "reasoning" ? "Reason" : t === "console" ? "Log" :
                   t.charAt(0).toUpperCase() + t.slice(1)}
                </button>
              ))}
            </div>

            <div className={styles.tabContent}>
              {activeTab === "ontology" && boardId && (
                <OntologyDashboard boardId={boardId} />
              )}
              {activeTab === "axioms" && boardId && (
                <AxiomEditor boardId={boardId} entityIri={selectedEntity?.iri}
                             entityLabel={selectedEntity?.label} entityType={selectedEntity?.type} />
              )}
              {activeTab === "reasoning" && boardId && <ReasoningPanel boardId={boardId} />}
              {activeTab === "odk" && boardId && <OdkPanel boardId={boardId} />}
              {activeTab === "sparql" && boardId && <SparqlPanel boardId={boardId} />}
              {activeTab === "csv" && boardId && <CsvImportWizard boardId={boardId} />}
              {activeTab === "tasks" && boardId && <TaskBoard boardId={boardId} />}
              {activeTab === "publish" && boardId && <PublishPanel boardId={boardId} />}
              {activeTab === "docs" && boardId && <DocsPanel boardId={boardId} />}
              {activeTab === "console" && (
                <div className={styles.consolePanel}>
                  {consoleLog.length === 0 ? (
                    <p className={styles.panelHint}>Click "Build" to run the ODK pipeline.</p>
                  ) : (
                    <pre className={styles.consolePre}>{consoleLog.join("")}</pre>
                  )}
                </div>
              )}
            </div>
          </aside>
        )}
      </div>

      {shareOpen && boardId && (
        <ShareDialog boardId={boardId} onClose={() => setShareOpen(false)} />
      )}

      {/* Toast for errors */}
      {error && (
        <div className={styles.toast} onClick={() => setError("")}>
          {error} <span>&times;</span>
        </div>
      )}
    </div>
  );
}
