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
}

export default function DashboardPage() {
  const { user } = useAuth();
  const [boards, setBoards] = useState<Board[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [wizardOpen, setWizardOpen] = useState(false);
  const navigate = useNavigate();

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
          <div className={styles.grid}>
            {boards.map((b) => (
              <Link key={b.board_id} to={`/board/${b.board_id}`} className={styles.card}>
                <div className={styles.cardHeader}>
                  <span className={styles.cardIcon}>&#9678;</span>
                  <h3 className={styles.cardName}>{b.display_name || b.board_id}</h3>
                </div>
                {b.description && (
                  <p className={styles.cardDesc}>{b.description}</p>
                )}
                <div className={styles.badges}>
                  <span className={`${styles.badge} ${b.is_public ? styles.badgeOk : styles.badgeWarn}`}>
                    {b.is_public ? "Public" : "Private"}
                  </span>
                  {b.user_role && (
                    <span className={`${styles.badge} ${styles.badgeOk}`}>
                      {b.user_role}
                    </span>
                  )}
                  <span className={`${styles.badge} ${b.odk_seeded ? styles.badgeOk : styles.badgeWarn}`}>
                    {b.odk_seeded ? "ODK" : "No ODK"}
                  </span>
                </div>
                <div className={styles.cardMeta}>
                  <span>{b.owner_username}</span>
                  {b.member_count > 0 && <span>{b.member_count} members</span>}
                </div>
                <div className={styles.cardFooter}>Open board &rarr;</div>
              </Link>
            ))}
          </div>
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
