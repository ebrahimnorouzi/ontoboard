import { useEffect, useState } from "react";
import { useParams, Link, useNavigate } from "react-router-dom";
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
import CsvImportWizard from "../components/csv/CsvImportWizard";
import SparqlPanel from "../components/sparql/SparqlPanel";
import DocsPanel from "../components/docs/DocsPanel";
import ShareDialog from "../components/share/ShareDialog";

type Tab = "dashboard" | "axioms" | "reasoning" | "sparql" | "csv" | "tasks" | "publish" | "docs" | "console";

interface SelectedEntity {
  iri: string;
  type: string;
  label: string;
}

export default function BoardPage() {
  const { boardId } = useParams<{ boardId: string }>();
  const { user } = useAuth();
  const [status, setStatus] = useState<"loading" | "ready" | "provisioning" | "error">("loading");
  const [error, setError] = useState("");
  const [sideOpen, setSideOpen] = useState(true);
  const [activeTab, setActiveTab] = useState<Tab>("dashboard");
  const [consoleLog, setConsoleLog] = useState<string[]>([]);
  const [building, setBuilding] = useState(false);
  const [selectedEntity, setSelectedEntity] = useState<SelectedEntity | null>(null);
  const [shareOpen, setShareOpen] = useState(false);

  // Real-time collaboration
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

  const handleBuild = async () => {
    setBuilding(true);
    setActiveTab("console");
    setConsoleLog(["$ make all\n"]);
    try {
      const res = await api(`/api/odk/${boardId}/build`, { method: "POST" });
      if (!res.ok || !res.body) {
        setConsoleLog((p) => [...p, `[ERROR] Build request failed (${res.status})\n`]);
        setBuilding(false);
        return;
      }
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        const text = decoder.decode(value);
        const lines = text.split("\n\n").filter(Boolean);
        for (const line of lines) {
          const content = line.replace(/^data: /, "");
          setConsoleLog((p) => [...p, content]);
        }
      }
    } catch {
      setConsoleLog((p) => [...p, "[ERROR] Connection lost\n"]);
    } finally {
      setBuilding(false);
    }
  };

  if (status === "loading" || status === "provisioning") {
    return (
      <div className={styles.splash}>
        <div className={styles.spinner} />
        <p className={styles.splashText}>
          {status === "loading" ? "Loading board..." : `Provisioning ${boardId}...`}
        </p>
        <p className={styles.splashHint}>Setting up ODK scaffold and Git repository</p>
      </div>
    );
  }

  if (status === "error") {
    return (
      <div className={styles.splash}>
        <div className={styles.errorIcon}>!</div>
        <p className={styles.splashText}>{error}</p>
        <Link to="/board" className={styles.backLink}>
          &larr; Back to boards
        </Link>
      </div>
    );
  }

  return (
    <div className={styles.layout}>
      {/* ── Top toolbar ─────────────────────────────────────── */}
      <header className={styles.toolbar}>
        <div className={styles.toolbarLeft}>
          <Link to="/board" className={styles.backBtn}>
            <Logo size={22} />
          </Link>
          <div className={styles.boardTitle}>
            <span className={styles.boardName}>{boardId}</span>
            <span className={styles.statusDot} />
          </div>
        </div>

        <div className={styles.toolbarActions}>
          <button
            className={styles.toolBtn}
            onClick={() => setShareOpen(true)}
            title="Share this board"
          >
            Share
          </button>
          <button
            className={`${styles.toolBtn} ${styles.buildBtn}`}
            onClick={handleBuild}
            disabled={building}
            title="Run ODK Build"
          >
            {building ? "Building..." : "Build"}
          </button>
          <button
            className={styles.toolBtn}
            onClick={() => setSideOpen(!sideOpen)}
            title="Toggle side panel"
          >
            {sideOpen ? "Hide Panel" : "Show Panel"}
          </button>
          <CollabStatus
            connected={connected}
            users={collabUsers}
            currentUser={user?.display_name || user?.username || "anonymous"}
          />
        </div>
      </header>

      {/* ── Main area ───────────────────────────────────────── */}
      <div className={styles.main}>
        {/* Tree Browser (left panel) */}
        {boardId && (
          <TreeBrowser
            boardId={boardId}
            onSelectEntity={setSelectedEntity}
          />
        )}

        {/* Tldraw Canvas */}
        <div className={styles.canvas}>
          {boardId && (
            <OntologyCanvas
              boardId={boardId}
              onSelectEntity={setSelectedEntity}
            />
          )}
        </div>

        {/* Side panel */}
        {sideOpen && (
          <aside className={styles.side}>
            <div className={styles.tabs}>
              <button
                className={`${styles.tab} ${activeTab === "dashboard" ? styles.tabActive : ""}`}
                onClick={() => setActiveTab("dashboard")}
              >
                Dashboard
              </button>
              <button
                className={`${styles.tab} ${activeTab === "axioms" ? styles.tabActive : ""}`}
                onClick={() => setActiveTab("axioms")}
              >
                Axioms
              </button>
              <button
                className={`${styles.tab} ${activeTab === "reasoning" ? styles.tabActive : ""}`}
                onClick={() => setActiveTab("reasoning")}
              >
                Reason
              </button>
              <button
                className={`${styles.tab} ${activeTab === "sparql" ? styles.tabActive : ""}`}
                onClick={() => setActiveTab("sparql")}
              >
                SPARQL
              </button>
              <button
                className={`${styles.tab} ${activeTab === "csv" ? styles.tabActive : ""}`}
                onClick={() => setActiveTab("csv")}
              >
                CSV
              </button>
              <button
                className={`${styles.tab} ${activeTab === "tasks" ? styles.tabActive : ""}`}
                onClick={() => setActiveTab("tasks")}
              >
                Tasks
              </button>
              <button
                className={`${styles.tab} ${activeTab === "publish" ? styles.tabActive : ""}`}
                onClick={() => setActiveTab("publish")}
              >
                Publish
              </button>
              <button
                className={`${styles.tab} ${activeTab === "docs" ? styles.tabActive : ""}`}
                onClick={() => setActiveTab("docs")}
              >
                Docs
              </button>
              <button
                className={`${styles.tab} ${activeTab === "console" ? styles.tabActive : ""}`}
                onClick={() => setActiveTab("console")}
              >
                Log
              </button>
            </div>

            <div className={styles.tabContent}>
              {activeTab === "dashboard" && boardId && (
                <OntologyDashboard boardId={boardId} />
              )}

              {activeTab === "axioms" && boardId && (
                <AxiomEditor
                  boardId={boardId}
                  entityIri={selectedEntity?.iri}
                  entityLabel={selectedEntity?.label}
                  entityType={selectedEntity?.type}
                />
              )}

              {activeTab === "sparql" && boardId && (
                <SparqlPanel boardId={boardId} />
              )}

              {activeTab === "csv" && boardId && (
                <CsvImportWizard boardId={boardId} />
              )}

              {activeTab === "reasoning" && boardId && (
                <ReasoningPanel boardId={boardId} />
              )}

              {activeTab === "tasks" && boardId && (
                <TaskBoard boardId={boardId} />
              )}

              {activeTab === "publish" && boardId && (
                <PublishPanel boardId={boardId} />
              )}

              {activeTab === "docs" && boardId && (
                <DocsPanel boardId={boardId} />
              )}

              {activeTab === "console" && (
                <div className={styles.consolePanel}>
                  {consoleLog.length === 0 ? (
                    <p className={styles.panelHint}>
                      Click "Build" to run the ODK pipeline. Output will stream here.
                    </p>
                  ) : (
                    <pre className={styles.consolePre}>
                      {consoleLog.join("")}
                    </pre>
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
    </div>
  );
}
