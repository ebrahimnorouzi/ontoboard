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
import { useYjsSync } from "../collab/useYjsSync";
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
import BoardSettingsDialog from "../components/board/BoardSettingsDialog";
import CommentsPanel from "../components/comments/CommentsPanel";
import { useOntologyStore } from "../store/ontologyStore";
import ExportOntologyDialog from "../components/export/ExportOntologyDialog";

type Tab = "ontology" | "axioms" | "reasoning" | "odk" | "sparql" | "csv" | "tasks" | "publish" | "docs" | "files" | "patterns" | "ids" | "comments" | "console";

export default function BoardPage() {
  const { boardId } = useParams<{ boardId: string }>();
  const { user } = useAuth();
  const navigate = useNavigate();
  const [status, setStatus] = useState<"loading" | "ready" | "provisioning" | "error">("loading");
  const [error, setError] = useState("");
  const [sideOpen, setSideOpen] = useState(true);
  const [leftOpen, setLeftOpen] = useState(true);
  const [activeTab, setActiveTab] = useState<Tab>("ontology");
  const [leftWidth, setLeftWidth] = useState(260);
  const [rightWidth, setRightWidth] = useState(340);
  const [consoleLog, setConsoleLog] = useState<string[]>([]);
  const [building, setBuilding] = useState(false);
  const [buildTarget, setBuildTarget] = useState("all");
  const [buildExitCode, setBuildExitCode] = useState<number | null>(null);
  const consoleEndRef = useRef<HTMLDivElement>(null);
  const [shareOpen, setShareOpen] = useState(false);
  const [userRole, setUserRole] = useState<string | null>(null);
  const [menuOpen, setMenuOpen] = useState(false);
  const [confirmDeleteBoard, setConfirmDeleteBoard] = useState(false);
  const [showExportOntology, setShowExportOntology] = useState(false);
  const [showBoardSettings, setShowBoardSettings] = useState(false);
  // Track which tabs have been visited so their state is preserved
  const [visitedTabs, setVisitedTabs] = useState<Set<Tab>>(new Set(["ontology"]));
  const switchTab = useCallback((t: Tab) => {
    setActiveTab(t);
    setVisitedTabs((v) => { const n = new Set(v); n.add(t); return n; });
  }, []);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const selectedEntity = useOntologyStore((s) => s.selectedEntity);
  const setCurrentUser = useOntologyStore((s) => s.setCurrentUser);

  // Set current user for provenance tracking
  useEffect(() => {
    if (user) setCurrentUser(user.display_name || user.username || "anonymous");
  }, [user, setCurrentUser]);

  const { connected, users: collabUsers, doc: yjsDoc, remoteCursors, broadcastCursor } = useCollaboration(
    status === "ready" ? boardId : undefined,
    user?.display_name || user?.username || "anonymous",
  );

  // Bind Yjs shared types ↔ Zustand store for real-time entity sync
  useYjsSync(yjsDoc, status === "ready" ? boardId : undefined);

  // Fallback poll — only when Yjs is NOT connected (offline/disconnected mode)
  useEffect(() => {
    if (status !== "ready" || !boardId || connected) return;
    const store = useOntologyStore.getState();
    let lastKnownSave = store.lastSaved;
    const interval = setInterval(async () => {
      try {
        const currentStore = useOntologyStore.getState();
        if (currentStore.saving || currentStore.dirty) return;
        const res = await apiJson<{ last_saved?: number }>(`/api/boards/${boardId}/sync-check`).catch(() => null);
        if (res?.last_saved && res.last_saved > lastKnownSave && res.last_saved > currentStore.lastSaved) {
          lastKnownSave = res.last_saved;
          await currentStore.loadFromBackend(boardId);
        }
      } catch { /* ignore polling errors */ }
    }, 15000);
    return () => clearInterval(interval);
  }, [status, boardId, connected]);

  useEffect(() => {
    if (!boardId) return;
    checkOrProvision();
  }, [boardId]);

  const checkOrProvision = async () => {
    try {
      const res = await api(`/api/boards/${boardId}`);
      if (res.ok) {
        const boardData = await res.json().catch(() => ({}));
        if (boardData.user_role) setUserRole(boardData.user_role);
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
  const handleBuild = async (target?: string) => {
    const t = target || buildTarget;
    setBuilding(true);
    setBuildExitCode(null);
    switchTab("console");
    setConsoleLog([`$ make ${t}\n`]);
    try {
      const res = await api(`/api/odk/${boardId}/build`, {
        method: "POST",
        body: JSON.stringify({ target: t }),
        headers: { "Content-Type": "application/json" },
      });
      if (!res.ok || !res.body) {
        setConsoleLog((p) => [...p, `[ERROR] Build failed (${res.status})\n`]);
        setBuildExitCode(res.status);
        setBuilding(false);
        return;
      }
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        for (const line of decoder.decode(value).split("\n\n").filter(Boolean)) {
          const text = line.replace(/^data: /, "");
          // Parse exit code from [EXIT N] marker
          const exitMatch = text.match(/\[EXIT (\d+)\]/);
          if (exitMatch) {
            setBuildExitCode(parseInt(exitMatch[1], 10));
          }
          setConsoleLog((p) => [...p, text]);
        }
      }
    } catch {
      setConsoleLog((p) => [...p, "[ERROR] Connection lost\n"]);
      setBuildExitCode(-1);
    } finally {
      setBuilding(false);
    }
  };

  // Auto-scroll console to bottom
  useEffect(() => {
    consoleEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [consoleLog]);

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
          const dlRes = await api(`/api/robot/${boardId}/download/${fileName}`);
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
            setError("Download failed — file not found after conversion");
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
                  <button className={styles.menuItem} onClick={() => { setShowExportOntology(true); setMenuOpen(false); }}>Export Ontology...</button>
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
                  <button className={styles.menuItem} onClick={() => { switchTab("sparql"); setMenuOpen(false); }}>
                    SPARQL Query
                  </button>
                  <button className={styles.menuItem} onClick={() => { switchTab("csv"); setMenuOpen(false); }}>
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
                  <button className={styles.menuItem} onClick={() => { switchTab("reasoning"); setMenuOpen(false); }}>
                    Run Reasoner
                  </button>
                  <button className={styles.menuItem} onClick={() => { handleBuild(); setMenuOpen(false); }}>
                    Run ODK Build
                  </button>
                  <button className={styles.menuItem} onClick={() => { switchTab("publish"); setMenuOpen(false); }}>
                    Publish / Release
                  </button>
                  <button className={styles.menuItem} onClick={() => { switchTab("docs"); setMenuOpen(false); }}>
                    Generate Docs
                  </button>
                </div>
                <div className={styles.menuSection}>
                  <div className={styles.menuLabel}>Board</div>
                  <button className={styles.menuItem} onClick={() => { setShowBoardSettings(true); setMenuOpen(false); }}>
                    Board Settings
                  </button>
                  <button className={styles.menuItem} onClick={() => { setShareOpen(true); setMenuOpen(false); }}>
                    Share Settings
                  </button>
                  <button className={styles.menuItem} onClick={() => { switchTab("files"); setMenuOpen(false); }}>
                    Browse Files
                  </button>
                  <button className={styles.menuItem} onClick={() => { switchTab("tasks"); setMenuOpen(false); }}>
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
          <button className={`${styles.toolBtn} ${styles.buildBtn}`} onClick={() => handleBuild()} disabled={building}>
            {building ? "Building..." : "Build"}
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
          <div style={{ width: leftWidth, flexShrink: 0 }}>
            <TreeBrowser boardId={boardId} />
          </div>
        )}

        {/* Left panel resize handle + toggle */}
        <div
          className={styles.panelResizeHandle}
          onMouseDown={(e) => {
            e.preventDefault();
            const startX = e.clientX;
            const startW = leftWidth;
            const onMove = (me: MouseEvent) => {
              const newW = Math.max(180, Math.min(500, startW + me.clientX - startX));
              setLeftWidth(newW);
            };
            const onUp = () => {
              document.removeEventListener("mousemove", onMove);
              document.removeEventListener("mouseup", onUp);
              document.body.style.cursor = "";
              document.body.style.userSelect = "";
            };
            document.body.style.cursor = "col-resize";
            document.body.style.userSelect = "none";
            document.addEventListener("mousemove", onMove);
            document.addEventListener("mouseup", onUp);
          }}
        >
          <button className={styles.panelToggle} onClick={() => setLeftOpen(!leftOpen)}
                  title={leftOpen ? "Hide left panel" : "Show left panel"}>
            {leftOpen ? "\u25C0" : "\u25B6"}
          </button>
        </div>

        {/* Canvas */}
        <div className={styles.canvas}>
          {boardId && <OntologyCanvas boardId={boardId}
            onOpenComments={() => { switchTab("comments"); setSideOpen(true); }}
            remoteCursors={remoteCursors}
            broadcastCursor={broadcastCursor} />}
        </div>

        {/* Right panel resize handle + toggle */}
        <div
          className={styles.panelResizeHandle}
          onMouseDown={(e) => {
            e.preventDefault();
            const startX = e.clientX;
            const startW = rightWidth;
            const onMove = (me: MouseEvent) => {
              const newW = Math.max(240, Math.min(600, startW - (me.clientX - startX)));
              setRightWidth(newW);
            };
            const onUp = () => {
              document.removeEventListener("mousemove", onMove);
              document.removeEventListener("mouseup", onUp);
              document.body.style.cursor = "";
              document.body.style.userSelect = "";
            };
            document.body.style.cursor = "col-resize";
            document.body.style.userSelect = "none";
            document.addEventListener("mousemove", onMove);
            document.addEventListener("mouseup", onUp);
          }}
        >
          <button className={styles.panelToggle} onClick={() => setSideOpen(!sideOpen)}
                  title={sideOpen ? "Hide right panel" : "Show right panel"}>
            {sideOpen ? "\u25B6" : "\u25C0"}
          </button>
        </div>

        {/* Right Panel (Tabs) */}
        {sideOpen && (
          <aside className={styles.side} style={{ width: rightWidth }}>
            <div className={styles.tabs}>
              {(["ontology", "axioms", "reasoning", "odk", "sparql", "csv", "tasks", "publish", "docs", "files", "patterns", "ids", "comments", "console"] as Tab[]).map((t) => (
                <button key={t} className={`${styles.tab} ${activeTab === t ? styles.tabActive : ""}`}
                        onClick={() => switchTab(t)}>
                  {({ ontology: "Onto", reasoning: "Reason", odk: "ODK", console: "Log", files: "Files", patterns: "ODP", ids: "IDs", comments: "Chat" } as Record<string, string>)[t] ||
                   t.charAt(0).toUpperCase() + t.slice(1)}
                </button>
              ))}
            </div>

            <div className={styles.tabContent}>
              {/* Tabs stay mounted once visited so their state is preserved */}
              {visitedTabs.has("ontology") && boardId && (
                <div style={{ display: activeTab === "ontology" ? "contents" : "none" }}>
                  <OntologyDashboard boardId={boardId} />
                </div>
              )}
              {visitedTabs.has("axioms") && boardId && (
                <div style={{ display: activeTab === "axioms" ? "contents" : "none" }}>
                  <AxiomEditor boardId={boardId} entityIri={selectedEntity?.iri}
                               entityLabel={selectedEntity?.label} entityType={selectedEntity?.type} />
                </div>
              )}
              {visitedTabs.has("reasoning") && boardId && (
                <div style={{ display: activeTab === "reasoning" ? "contents" : "none" }}>
                  <ReasoningPanel boardId={boardId} />
                </div>
              )}
              {visitedTabs.has("odk") && boardId && (
                <div style={{ display: activeTab === "odk" ? "contents" : "none" }}>
                  <OdkPanel boardId={boardId} />
                </div>
              )}
              {visitedTabs.has("sparql") && boardId && (
                <div style={{ display: activeTab === "sparql" ? "contents" : "none" }}>
                  <SparqlPanel boardId={boardId} />
                </div>
              )}
              {visitedTabs.has("csv") && boardId && (
                <div style={{ display: activeTab === "csv" ? "contents" : "none" }}>
                  <CsvImportWizard boardId={boardId} />
                </div>
              )}
              {visitedTabs.has("tasks") && boardId && (
                <div style={{ display: activeTab === "tasks" ? "contents" : "none" }}>
                  <TaskBoard boardId={boardId} members={collabUsers.map((u) => u.name)} />
                </div>
              )}
              {visitedTabs.has("publish") && boardId && (
                <div style={{ display: activeTab === "publish" ? "contents" : "none" }}>
                  <PublishPanel boardId={boardId} />
                </div>
              )}
              {visitedTabs.has("docs") && boardId && (
                <div style={{ display: activeTab === "docs" ? "contents" : "none" }}>
                  <DocsPanel boardId={boardId} />
                </div>
              )}
              {visitedTabs.has("files") && boardId && (
                <div style={{ display: activeTab === "files" ? "contents" : "none" }}>
                  <FileBrowser boardId={boardId} />
                </div>
              )}
              {visitedTabs.has("patterns") && boardId && (
                <div style={{ display: activeTab === "patterns" ? "contents" : "none" }}>
                  <PatternLibrary boardId={boardId} />
                </div>
              )}
              {visitedTabs.has("ids") && boardId && (
                <div style={{ display: activeTab === "ids" ? "contents" : "none" }}>
                  <IdRangeManager boardId={boardId} />
                </div>
              )}
              {visitedTabs.has("comments") && boardId && (
                <div style={{ display: activeTab === "comments" ? "contents" : "none" }}>
                  <CommentsPanel boardId={boardId} />
                </div>
              )}
              {visitedTabs.has("console") && (
                <div style={{ display: activeTab === "console" ? "contents" : "none" }}>
                <div className={styles.consolePanel}>
                  <div className={styles.consoleToolbar}>
                    <select
                      className={styles.consoleSelect}
                      value={buildTarget}
                      onChange={(e) => setBuildTarget(e.target.value)}
                      disabled={building}
                    >
                      <option value="all">all</option>
                      <option value="docs">docs</option>
                      <option value="test">test</option>
                      <option value="refresh-imports">refresh-imports</option>
                      <option value="reason">reason</option>
                      <option value="clean">clean</option>
                      <option value="update_repo">update_repo</option>
                      <option value="prepare_release">prepare_release</option>
                    </select>
                    <button
                      className={styles.consoleRunBtn}
                      onClick={() => handleBuild()}
                      disabled={building}
                    >
                      {building ? "Running..." : "Run"}
                    </button>
                    <button
                      className={styles.consoleClearBtn}
                      onClick={() => { setConsoleLog([]); setBuildExitCode(null); }}
                    >
                      Clear
                    </button>
                    {buildExitCode !== null && (
                      <span className={buildExitCode === 0 ? styles.exitCodeSuccess : styles.exitCodeError}>
                        Exit: {buildExitCode}
                      </span>
                    )}
                  </div>
                  <div className={styles.consolePre}>
                    {consoleLog.length === 0 ? (
                      <span className={styles.panelHint}>Select a target and click "Run" to execute the ODK build pipeline.</span>
                    ) : (
                      consoleLog.map((line, i) => {
                        let cls = styles.logLine;
                        if (/\[ERROR\]|error|Error|FATAL|fatal|failed|FAILED/.test(line)) cls = styles.logLineError;
                        else if (/\[EXIT 0\]|success|Success|completed successfully/.test(line)) cls = styles.logLineSuccess;
                        else if (/^\$\s/.test(line)) cls = styles.logLineCmd;
                        return <div key={i} className={cls}>{line}</div>;
                      })
                    )}
                    <div ref={consoleEndRef} />
                  </div>
                </div>
                </div>
              )}
            </div>
          </aside>
        )}
      </div>

      {shareOpen && boardId && (
        <ShareDialog
          boardId={boardId}
          userRole={userRole}
          onClose={() => setShareOpen(false)}
        />
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

      {showBoardSettings && boardId && (
        <BoardSettingsDialog
          boardId={boardId}
          userRole={userRole}
          onClose={() => setShowBoardSettings(false)}
        />
      )}

      {showExportOntology && boardId && (
        <ExportOntologyDialog boardId={boardId} onClose={() => setShowExportOntology(false)} onError={setError} />
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
