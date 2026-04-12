/**
 * BoardPage — Main ontology editor layout.
 *
 * Layout:
 *   [Header: Logo + BoardName + AppMenu + Actions + Collab]
 *   [LeftPanel: Hierarchy + Patterns] | [Canvas: Cytoscape] | [RightPanel: Tabs]
 */

import { useEffect, useState, useRef, useCallback } from "react";
import { useParams, Link, useNavigate } from "react-router-dom";
import { useAuth } from "../auth";
import { api, apiJson, ApiError } from "../api";
import styles from "./BoardPage.module.css";
import Logo from "../components/Logo";
import OntologyDashboard from "../components/OntologyDashboard";
import OntologyCanvas from "../components/canvas/OntologyCanvas";
import AxiomEditor from "../components/axiom/AxiomEditor";
import TreeBrowser from "../components/tree/TreeBrowser";
import FileBrowser from "../components/files/FileBrowser";
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
import PatternLibrary from "../components/patterns/PatternLibrary";
import IdRangeManager from "../components/idranges/IdRangeManager";
import { useOntologyStore } from "../store/ontologyStore";

type Tab = "ontology" | "axioms" | "reasoning" | "odk" | "sparql" | "csv" | "tasks" | "publish" | "docs" | "files" | "patterns" | "ids" | "console";

export default function BoardPage() {
  const { boardId } = useParams<{ boardId: string }>();
  const { user } = useAuth();
  const navigate = useNavigate();
  const [status, setStatus] = useState<"loading" | "ready" | "provisioning" | "error">("loading");
  const [error, setError] = useState("");
  const [sideOpen, setSideOpen] = useState(true);
  const [leftOpen, setLeftOpen] = useState(true);
  const [activeTab, setActiveTab] = useState<Tab>("ontology");
  const [consoleLog, setConsoleLog] = useState<string[]>([]);
  const [building, setBuilding] = useState(false);
  const [shareOpen, setShareOpen] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const [confirmDeleteBoard, setConfirmDeleteBoard] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const selectedEntity = useOntologyStore((s) => s.selectedEntity);

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

  // ── File Upload ────────────────────────────────────────────
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

  // ── Build / ODK ────────────────────────────────────────────
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

  // ── Export ─────────────────────────────────────────────────
  const handleExport = async (format: string) => {
    if (!boardId) return;
    setMenuOpen(false);
    try {
      if (format === "zip") {
        const res = await api(`/api/export/${boardId}/zip`, { method: "POST" });
        if (res.ok) {
          const blob = await res.blob();
          const url = URL.createObjectURL(blob);
          const a = document.createElement("a");
          a.href = url;
          a.download = `${boardId}-odp-repo.zip`;
          document.body.appendChild(a);
          a.click();
          document.body.removeChild(a);
          URL.revokeObjectURL(url);
        } else {
          setError("ZIP export failed");
        }
        return;
      }
      // Save first, then convert via OWL service
      await useOntologyStore.getState().saveToBackend();
      const res = await api(`/api/robot/${boardId}/convert`, {
        method: "POST",
        body: JSON.stringify({ output_format: format }),
      });
      if (res.ok) {
        const data = await res.json();
        if (data.output_file) {
          const fileName = data.output_file.split("/").pop() || `${boardId}.${format}`;
          const dlRes = await api(`/api/odk-mediator/${boardId}/release/download/${fileName}`);
          if (dlRes.ok) {
            const blob = await dlRes.blob();
            const url = URL.createObjectURL(blob);
            const a = document.createElement("a");
            a.href = url;
            a.download = fileName;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
          } else {
            // Fallback: try direct download from output_file path
            const fb = await api(`/api/odk-setup/${boardId}/file/${data.output_file}`);
            if (fb.ok) {
              const blob = await fb.blob();
              const url = URL.createObjectURL(blob);
              const a = document.createElement("a");
              a.href = url;
              a.download = fileName;
              document.body.appendChild(a);
              a.click();
              document.body.removeChild(a);
              URL.revokeObjectURL(url);
            } else {
              setError("Download failed — file not found after conversion");
            }
          }
        }
      } else {
        setError("Export failed — conversion error");
      }
    } catch (e: any) {
      setError(e.message || "Export failed");
    }
  };

  // ── Delete board ──────────────────────────────────────────
  const handleDeleteBoard = async () => {
    if (!boardId) return;
    try {
      const res = await api(`/api/boards/${boardId}`, { method: "DELETE" });
      if (res.ok) {
        navigate("/board");
      } else {
        const data = await res.json().catch(() => ({}));
        setError(data.detail || "Delete failed");
      }
    } catch (e: any) {
      setError(e.message || "Delete failed");
    }
    setConfirmDeleteBoard(false);
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
      {/* Hidden file input */}
      <input ref={fileInputRef} type="file" accept=".owl,.ttl,.rdf,.obo,.jsonld,.json,.nt,.xml"
             onChange={handleFileInputChange} hidden />

      {/* ══════════════════════════════════════════════════════════
          Header with App Menu
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

          {/* App Menu */}
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
                  <div className={styles.menuDivider} />
                  <button className={styles.menuItem} onClick={() => handleExport("ttl")}>Export as Turtle (.ttl)</button>
                  <button className={styles.menuItem} onClick={() => handleExport("obo")}>Export as OBO (.obo)</button>
                  <button className={styles.menuItem} onClick={() => handleExport("jsonld")}>Export as JSON-LD (.jsonld)</button>
                  <button className={styles.menuItem} onClick={() => handleExport("ofn")}>Export as OWL Functional (.ofn)</button>
                  <button className={styles.menuItem} onClick={() => handleExport("nt")}>Export as N-Triples (.nt)</button>
                  <div className={styles.menuDivider} />
                  <button className={styles.menuItem} onClick={() => handleExport("zip")}>Export ODP Repository (ZIP)</button>
                </div>
                <div className={styles.menuSection}>
                  <div className={styles.menuLabel}>Edit</div>
                  <button className={styles.menuItem} onClick={() => { apiJson(`/api/refactor/${boardId}/undo`, { method: "POST" }).then(() => useOntologyStore.getState().loadFromBackend(boardId!)).catch(() => {}); setMenuOpen(false); }}>
                    Undo
                  </button>
                  <button className={styles.menuItem} onClick={() => { apiJson(`/api/refactor/${boardId}/redo`, { method: "POST" }).then(() => useOntologyStore.getState().loadFromBackend(boardId!)).catch(() => {}); setMenuOpen(false); }}>
                    Redo
                  </button>
                  <div className={styles.menuDivider} />
                  <button className={styles.menuItem} onClick={() => { setActiveTab("sparql"); setMenuOpen(false); }}>
                    SPARQL Query
                  </button>
                  <button className={styles.menuItem} onClick={() => { setActiveTab("csv"); setMenuOpen(false); }}>
                    Import CSV
                  </button>
                </div>
                <div className={styles.menuSection}>
                  <div className={styles.menuLabel}>View</div>
                  <button className={styles.menuItem} onClick={() => { setLeftOpen(!leftOpen); setMenuOpen(false); }}>
                    {leftOpen ? "Hide Left Panel" : "Show Left Panel"}
                  </button>
                  <button className={styles.menuItem} onClick={() => { setSideOpen(!sideOpen); setMenuOpen(false); }}>
                    {sideOpen ? "Hide Right Panel" : "Show Right Panel"}
                  </button>
                </div>
                <div className={styles.menuSection}>
                  <div className={styles.menuLabel}>Tools</div>
                  <button className={styles.menuItem} onClick={() => { setActiveTab("reasoning"); setMenuOpen(false); }}>
                    Run Reasoner
                  </button>
                  <button className={styles.menuItem} onClick={() => { handleBuild(); setMenuOpen(false); }}>
                    Run ODK Build
                  </button>
                  <button className={styles.menuItem} onClick={() => { setActiveTab("publish"); setMenuOpen(false); }}>
                    Publish / Release
                  </button>
                  <button className={styles.menuItem} onClick={() => { setActiveTab("docs"); setMenuOpen(false); }}>
                    Generate Docs
                  </button>
                </div>
                <div className={styles.menuSection}>
                  <div className={styles.menuLabel}>Board</div>
                  <button className={styles.menuItem} onClick={() => { setShareOpen(true); setMenuOpen(false); }}>
                    Share Settings
                  </button>
                  <button className={styles.menuItem} onClick={() => { setActiveTab("files"); setMenuOpen(false); }}>
                    Browse Files
                  </button>
                  <button className={styles.menuItem} onClick={() => { setActiveTab("tasks"); setMenuOpen(false); }}>
                    Task Board
                  </button>
                  <div className={styles.menuDivider} />
                  <button className={`${styles.menuItem} ${styles.menuDanger}`} onClick={() => { setConfirmDeleteBoard(true); setMenuOpen(false); }}>
                    Delete Board
                  </button>
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
        {/* Left Panel (Tree Browser) */}
        {leftOpen && boardId && (
          <TreeBrowser boardId={boardId} />
        )}

        {/* Canvas */}
        <div className={styles.canvas}>
          {boardId && <OntologyCanvas boardId={boardId} />}
        </div>

        {/* Right Panel (Tabs) */}
        {sideOpen && (
          <aside className={styles.side}>
            <div className={styles.tabs}>
              {(["ontology", "axioms", "reasoning", "odk", "sparql", "csv", "tasks", "publish", "docs", "files", "patterns", "ids", "console"] as Tab[]).map((t) => (
                <button key={t} className={`${styles.tab} ${activeTab === t ? styles.tabActive : ""}`}
                        onClick={() => setActiveTab(t)}>
                  {({ ontology: "Onto", reasoning: "Reason", odk: "ODK", console: "Log", files: "Files", patterns: "ODP", ids: "IDs" } as Record<string, string>)[t] ||
                   t.charAt(0).toUpperCase() + t.slice(1)}
                </button>
              ))}
            </div>

            <div className={styles.tabContent}>
              {activeTab === "ontology" && boardId && <OntologyDashboard boardId={boardId} />}
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
              {activeTab === "files" && boardId && <FileBrowser boardId={boardId} />}
              {activeTab === "patterns" && boardId && <PatternLibrary boardId={boardId} />}
              {activeTab === "ids" && boardId && <IdRangeManager boardId={boardId} />}
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

      {/* Delete board confirmation */}
      {confirmDeleteBoard && (
        <div className={styles.confirmOverlay}>
          <div className={styles.confirmDialog}>
            <h3>Delete Board</h3>
            <p>Are you sure you want to delete <strong>{boardId}</strong>? This will permanently remove all ontology data, files, and history. This action cannot be undone.</p>
            <div className={styles.confirmActions}>
              <button className={styles.confirmDeleteBtn} onClick={handleDeleteBoard}>Delete Board</button>
              <button className={styles.confirmCancelBtn} onClick={() => setConfirmDeleteBoard(false)}>Cancel</button>
            </div>
          </div>
        </div>
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
