import { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useAuth } from "../auth";
import { api, apiJson, ApiError } from "../api";
import Navbar from "../components/Navbar";
import CreateBoardWizard from "../components/wizard/CreateBoardWizard";
import styles from "./DashboardPage.module.css";

interface Board {
  board_id: string;
  display_name: string;
  description: string;
  is_public: boolean;
  is_starred: boolean;
  owner_username: string;
  member_count: number;
  user_role: string | null;
  odk_seeded: boolean;
  git_initialized: boolean;
  last_modified?: string | null;
  last_modified_by?: string | null;
}

export default function DashboardPage() {
  const { user } = useAuth();
  const [boards, setBoards] = useState<Board[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [wizardOpen, setWizardOpen] = useState(false);
  const [menuOpen, setMenuOpen] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null);
  const [cloning, setCloning] = useState<string | null>(null);
  const [cloneId, setCloneId] = useState("");
  const navigate = useNavigate();

  const toggleStar = async (boardId: string) => {
    try {
      const res = await api(`/api/boards/${boardId}/star`, { method: "POST" });
      if (res.ok) {
        const data = await res.json();
        setBoards((prev) =>
          prev.map((b) => b.board_id === boardId ? { ...b, is_starred: data.is_starred } : b)
        );
      }
    } catch { /* ignore */ }
  };

  const deleteBoard = async (boardId: string) => {
    try {
      const res = await api(`/api/boards/${boardId}`, { method: "DELETE" });
      if (res.ok) {
        setBoards((prev) => prev.filter((b) => b.board_id !== boardId));
      } else {
        const data = await res.json().catch(() => ({}));
        setError(data.detail || "Delete failed");
      }
    } catch (e: any) { setError(e.message || "Delete failed"); }
    setConfirmDelete(null);
  };

  const cloneBoard = async (boardId: string) => {
    if (!cloneId.trim()) return;
    try {
      await apiJson(`/api/boards/${boardId}/clone`, {
        method: "POST",
        body: JSON.stringify({ new_board_id: cloneId.trim() }),
      });
      navigate(`/board/${cloneId.trim()}`);
    } catch (e: any) { setError(e.message || "Clone failed"); }
    setCloning(null);
    setCloneId("");
  };

  const fetchBoards = async () => {
    try {
      const data = await apiJson<Board[]>("/api/boards/");
      setBoards(data);
    } catch {
      setError("Cannot connect to backend");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchBoards(); }, []);

  return (
    <div className={styles.page}>
      <Navbar />

      <div className={styles.container}>
        <header className={styles.header}>
          <div>
            <h1 className={styles.title}>Your Boards</h1>
            <p className={styles.subtitle}>
              {user
                ? "Create, manage, and collaborate on ontology boards"
                : "Sign in to create boards, or browse public ones below"}
            </p>
          </div>
        </header>

        {/* Create new (only if logged in) */}
        {user && (
          <div className={styles.createRow}>
            <button className={styles.createBtn} onClick={() => setWizardOpen(true)}>
              + Create Board
            </button>
          </div>
        )}

        {error && (
          <div className={styles.error}>
            {error}
            <button className={styles.dismiss} onClick={() => setError("")}>&times;</button>
          </div>
        )}

        {loading ? (
          <div className={styles.empty}>
            <div className={styles.spinner} />
            <p>Loading boards...</p>
          </div>
        ) : boards.length === 0 ? (
          <div className={styles.empty}>
            <div className={styles.emptyIcon}>&#9678;</div>
            <h3>No boards yet</h3>
            <p>{user ? "Create your first board to get started." : "Sign in to create boards."}</p>
          </div>
        ) : (
          <>
            <div className={styles.grid}>
              {boards.map((b) => {
                const isOwner = b.user_role === "owner" || b.user_role === "admin";
                return (
                  <div key={b.board_id} className={styles.card}>
                    <Link to={`/board/${b.board_id}`} className={styles.cardLink}>
                      <div className={styles.cardHeader}>
                        <span className={styles.cardIcon}>&#9678;</span>
                        <h3 className={styles.cardName}>{b.display_name || b.board_id}</h3>
                      </div>
                      {b.description && <p className={styles.cardDesc}>{b.description}</p>}
                      <div className={styles.badges}>
                        <span className={`${styles.badge} ${b.is_public ? styles.badgeOk : styles.badgeWarn}`}>
                          {b.is_public ? "Public" : "Private"}
                        </span>
                        {b.user_role && <span className={`${styles.badge} ${styles.badgeOk}`}>{b.user_role}</span>}
                      </div>
                      <div className={styles.cardMeta}>
                        <span>{b.owner_username}</span>
                        {b.member_count > 0 && <span>{b.member_count} members</span>}
                        {b.last_modified && (
                          <span title={b.last_modified}>
                            {b.last_modified_by ? `${b.last_modified_by} · ` : ""}
                            {new Date(b.last_modified).toLocaleDateString()}
                          </span>
                        )}
                      </div>
                    </Link>

                    {/* Board actions bar */}
                    <div className={styles.cardActions}>
                      <button className={styles.cardActionBtn}
                        onClick={() => toggleStar(b.board_id)}
                        title={b.is_starred ? "Unstar" : "Star"}>
                        {b.is_starred ? "\u2605" : "\u2606"}
                      </button>
                      <button className={styles.cardActionBtn}
                        onClick={() => { setCloning(b.board_id); setCloneId(`${b.board_id}-copy`); }}
                        title="Clone board">
                        Clone
                      </button>
                      {isOwner && (
                        <button className={`${styles.cardActionBtn} ${styles.cardActionDanger}`}
                          onClick={() => setConfirmDelete(b.board_id)}
                          title="Delete board">
                          Delete
                        </button>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>

            {/* Delete confirmation */}
            {confirmDelete && (
              <div className={styles.overlay} onClick={() => setConfirmDelete(null)}>
                <div className={styles.dialog} onClick={(e) => e.stopPropagation()}>
                  <h3>Delete Board</h3>
                  <p>Are you sure you want to delete <strong>{confirmDelete}</strong>? This cannot be undone.</p>
                  <div className={styles.dialogActions}>
                    <button className={styles.dangerBtn} onClick={() => deleteBoard(confirmDelete)}>Delete</button>
                    <button className={styles.cancelBtn} onClick={() => setConfirmDelete(null)}>Cancel</button>
                  </div>
                </div>
              </div>
            )}

            {/* Clone dialog */}
            {cloning && (
              <div className={styles.overlay} onClick={() => setCloning(null)}>
                <div className={styles.dialog} onClick={(e) => e.stopPropagation()}>
                  <h3>Clone Board</h3>
                  <p>Create a copy of <strong>{cloning}</strong>:</p>
                  <input className={styles.dialogInput} value={cloneId}
                    onChange={(e) => setCloneId(e.target.value)}
                    placeholder="New board ID"
                    onKeyDown={(e) => e.key === "Enter" && cloneBoard(cloning)} autoFocus />
                  <div className={styles.dialogActions}>
                    <button className={styles.primaryBtn} onClick={() => cloneBoard(cloning)}
                      disabled={!cloneId.trim()}>Clone</button>
                    <button className={styles.cancelBtn} onClick={() => setCloning(null)}>Cancel</button>
                  </div>
                </div>
              </div>
            )}
          </>
        )}
      </div>

      {wizardOpen && (
        <CreateBoardWizard
          onClose={() => setWizardOpen(false)}
          onCreated={() => { setWizardOpen(false); fetchBoards(); }}
        />
      )}
    </div>
  );
}
