import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth";
import { apiJson } from "../api";
import Navbar from "../components/Navbar";
import styles from "./AdminPage.module.css";

interface Stats {
  total_users: number;
  active_users: number;
  total_boards: number;
  public_boards: number;
  private_boards: number;
  total_activities: number;
}

interface UserDetail {
  id: number;
  username: string;
  email: string;
  role: string;
  is_active: boolean;
  display_name: string | null;
  board_count: number;
  membership_count: number;
  created_at: string;
}

interface BoardDetail {
  id: number;
  board_id: string;
  display_name: string;
  owner_username: string;
  is_public: boolean;
  member_count: number;
  activity_count: number;
  created_at: string;
}

export default function AdminPage() {
  const { user, isAdmin, loading: authLoading } = useAuth();
  const navigate = useNavigate();
  const [stats, setStats] = useState<Stats | null>(null);
  const [users, setUsers] = useState<UserDetail[]>([]);
  const [boards, setBoards] = useState<BoardDetail[]>([]);
  const [tab, setTab] = useState<"overview" | "users" | "boards">("overview");
  const [showCreate, setShowCreate] = useState(false);
  const [newUser, setNewUser] = useState({ username: "", email: "", password: "", role: "user" });
  const [error, setError] = useState("");

  useEffect(() => {
    if (!authLoading && !isAdmin) navigate("/login");
  }, [authLoading, isAdmin]);

  useEffect(() => {
    if (!isAdmin) return;
    apiJson<Stats>("/api/boards/admin/stats").then(setStats).catch(() => {});
    apiJson<UserDetail[]>("/api/users/").then(setUsers).catch(() => {});
    apiJson<BoardDetail[]>("/api/boards/").then(setBoards).catch(() => {});
  }, [isAdmin]);

  const handleCreateUser = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    try {
      await apiJson("/api/users/", {
        method: "POST",
        body: JSON.stringify(newUser),
      });
      setShowCreate(false);
      setNewUser({ username: "", email: "", password: "", role: "user" });
      const updated = await apiJson<UserDetail[]>("/api/users/");
      setUsers(updated);
    } catch (err: any) {
      setError(err.message);
    }
  };

  if (authLoading || !isAdmin) return null;

  return (
    <div className={styles.page}>
      <Navbar />
      <div className={styles.container}>
        <h1 className={styles.title}>Admin Dashboard</h1>

        {/* Stats cards */}
        {stats && (
          <div className={styles.statsGrid}>
            <StatCard label="Users" value={stats.total_users} sub={`${stats.active_users} active`} color="var(--accent)" />
            <StatCard label="Boards" value={stats.total_boards} sub={`${stats.public_boards} public`} color="var(--success)" />
            <StatCard label="Private" value={stats.private_boards} color="var(--warning)" />
            <StatCard label="Activities" value={stats.total_activities} color="var(--danger)" />
          </div>
        )}

        {/* Tabs */}
        <div className={styles.tabs}>
          <button className={`${styles.tab} ${tab === "overview" ? styles.tabActive : ""}`} onClick={() => setTab("overview")}>Overview</button>
          <button className={`${styles.tab} ${tab === "users" ? styles.tabActive : ""}`} onClick={() => setTab("users")}>Users</button>
          <button className={`${styles.tab} ${tab === "boards" ? styles.tabActive : ""}`} onClick={() => setTab("boards")}>All Boards</button>
        </div>

        {/* Users tab */}
        {tab === "users" && (
          <div>
            <div className={styles.sectionHeader}>
              <h2>Users ({users.length})</h2>
              <button className={styles.createBtn} onClick={() => setShowCreate(!showCreate)}>
                {showCreate ? "Cancel" : "+ Create User"}
              </button>
            </div>

            {showCreate && (
              <form onSubmit={handleCreateUser} className={styles.createForm}>
                {error && <div className={styles.error}>{error}</div>}
                <input className={styles.input} placeholder="Username" value={newUser.username} onChange={(e) => setNewUser({ ...newUser, username: e.target.value })} required />
                <input className={styles.input} placeholder="Email" type="email" value={newUser.email} onChange={(e) => setNewUser({ ...newUser, email: e.target.value })} required />
                <input className={styles.input} placeholder="Password" type="password" value={newUser.password} onChange={(e) => setNewUser({ ...newUser, password: e.target.value })} required />
                <select className={styles.input} value={newUser.role} onChange={(e) => setNewUser({ ...newUser, role: e.target.value })}>
                  <option value="user">User</option>
                  <option value="admin">Admin</option>
                </select>
                <button className={styles.submitBtn} type="submit">Create</button>
              </form>
            )}

            <div className={styles.table}>
              <div className={styles.tableHeader}>
                <span>Username</span><span>Email</span><span>Role</span><span>Boards</span><span>Status</span>
              </div>
              {users.map((u) => (
                <div key={u.id} className={styles.tableRow}>
                  <span className={styles.bold}>{u.username}</span>
                  <span>{u.email}</span>
                  <span className={u.role === "admin" ? styles.badgeAdmin : styles.badgeUser}>{u.role}</span>
                  <span>{u.board_count} owned / {u.membership_count} shared</span>
                  <span className={u.is_active ? styles.active : styles.inactive}>{u.is_active ? "Active" : "Disabled"}</span>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Boards tab */}
        {tab === "boards" && (
          <div>
            <h2 className={styles.sectionTitle}>All Boards ({boards.length})</h2>
            <div className={styles.table}>
              <div className={styles.tableHeader}>
                <span>Board</span><span>Owner</span><span>Access</span><span>Members</span><span>Activity</span>
              </div>
              {boards.map((b) => (
                <div key={b.id} className={styles.tableRow} onClick={() => navigate(`/board/${b.board_id}`)} style={{ cursor: "pointer" }}>
                  <span className={styles.bold}>{b.board_id}</span>
                  <span>{b.owner_username}</span>
                  <span className={b.is_public ? styles.badgePublic : styles.badgePrivate}>{b.is_public ? "Public" : "Private"}</span>
                  <span>{b.member_count}</span>
                  <span>{b.activity_count}</span>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Overview tab */}
        {tab === "overview" && (
          <div className={styles.overview}>
            <p className={styles.hint}>
              Manage users and boards from the tabs above. Admin credentials are set via environment variables
              <code> ADMIN_USERNAME</code> and <code>ADMIN_PASSWORD</code>.
            </p>
          </div>
        )}
      </div>
    </div>
  );
}

function StatCard({ label, value, sub, color }: { label: string; value: number; sub?: string; color: string }) {
  return (
    <div className={styles.statCard}>
      <div className={styles.statValue} style={{ color }}>{value}</div>
      <div className={styles.statLabel}>{label}</div>
      {sub && <div className={styles.statSub}>{sub}</div>}
    </div>
  );
}
