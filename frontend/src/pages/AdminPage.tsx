import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth";
import { api, apiJson } from "../api";
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

interface NotificationItem {
  id: number;
  category: string;
  title: string;
  message: string;
  link: string | null;
  is_read: boolean;
  created_at: string;
}

export default function AdminPage() {
  const { user, isAdmin, loading: authLoading } = useAuth();
  const navigate = useNavigate();
  const [stats, setStats] = useState<Stats | null>(null);
  const [users, setUsers] = useState<UserDetail[]>([]);
  const [boards, setBoards] = useState<BoardDetail[]>([]);
  const [notifications, setNotifications] = useState<NotificationItem[]>([]);
  const [unreadCount, setUnreadCount] = useState(0);
  const [tab, setTab] = useState<"overview" | "users" | "boards" | "notifications">("overview");
  const [showCreate, setShowCreate] = useState(false);
  const [newUser, setNewUser] = useState({ username: "", email: "", password: "", role: "user" });
  const [error, setError] = useState("");
  const [confirmDelete, setConfirmDelete] = useState<UserDetail | null>(null);

  useEffect(() => {
    if (!authLoading && !isAdmin) navigate("/login");
  }, [authLoading, isAdmin]);

  const refreshData = () => {
    apiJson<Stats>("/api/boards/admin/stats").then(setStats).catch(() => {});
    apiJson<UserDetail[]>("/api/users/").then(setUsers).catch(() => {});
    apiJson<BoardDetail[]>("/api/boards/").then(setBoards).catch(() => {});
    apiJson<{ unread_count: number; notifications: NotificationItem[] }>("/api/notifications/")
      .then(d => { setNotifications(d.notifications); setUnreadCount(d.unread_count); })
      .catch(() => {});
  };

  useEffect(() => {
    if (!isAdmin) return;
    refreshData();
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
      refreshData();
    } catch (err: any) {
      setError(err.message);
    }
  };

  const handleApprove = async (userId: number) => {
    try {
      await apiJson(`/api/users/${userId}/approve`, { method: "POST" });
      refreshData();
    } catch (err: any) { setError(err.message); }
  };

  const handleReject = async (userId: number) => {
    try {
      await apiJson(`/api/users/${userId}/reject`, { method: "POST" });
      refreshData();
    } catch (err: any) { setError(err.message); }
  };

  const handleDeactivate = async (userId: number) => {
    try {
      await api(`/api/users/${userId}`, { method: "DELETE" });
      refreshData();
    } catch (err: any) { setError(err.message); }
  };

  const handlePermanentDelete = async (userId: number) => {
    try {
      await api(`/api/users/${userId}/permanent`, { method: "DELETE" });
      setConfirmDelete(null);
      refreshData();
    } catch (err: any) { setError(err.message); }
  };

  const handleMarkAllRead = async () => {
    try {
      await apiJson("/api/notifications/read-all", { method: "POST" });
      refreshData();
    } catch {}
  };

  const pendingUsers = users.filter(u => !u.is_active && u.role !== "admin");

  if (authLoading || !isAdmin) return null;

  return (
    <div className={styles.page}>
      <Navbar />
      <div className={styles.container}>
        <h1 className={styles.title}>Admin Dashboard</h1>

        {error && <div className={styles.error} onClick={() => setError("")}>{error} <span>&times;</span></div>}

        {/* Stats cards */}
        {stats && (
          <div className={styles.statsGrid}>
            <StatCard label="Users" value={stats.total_users} sub={`${stats.active_users} active`} color="var(--accent)" />
            <StatCard label="Boards" value={stats.total_boards} sub={`${stats.public_boards} public`} color="var(--success)" />
            <StatCard label="Private" value={stats.private_boards} color="var(--warning)" />
            <StatCard label="Activities" value={stats.total_activities} color="var(--danger)" />
          </div>
        )}

        {/* Pending users banner */}
        {pendingUsers.length > 0 && (
          <div className={styles.pendingBanner} onClick={() => setTab("users")}>
            <span className={styles.pendingDot} />
            <strong>{pendingUsers.length}</strong> user{pendingUsers.length > 1 ? "s" : ""} awaiting approval
          </div>
        )}

        {/* Tabs */}
        <div className={styles.tabs}>
          <button className={`${styles.tab} ${tab === "overview" ? styles.tabActive : ""}`} onClick={() => setTab("overview")}>Overview</button>
          <button className={`${styles.tab} ${tab === "users" ? styles.tabActive : ""}`} onClick={() => setTab("users")}>
            Users {pendingUsers.length > 0 && <span className={styles.badge}>{pendingUsers.length}</span>}
          </button>
          <button className={`${styles.tab} ${tab === "boards" ? styles.tabActive : ""}`} onClick={() => setTab("boards")}>All Boards</button>
          <button className={`${styles.tab} ${tab === "notifications" ? styles.tabActive : ""}`} onClick={() => setTab("notifications")}>
            Notifications {unreadCount > 0 && <span className={styles.badge}>{unreadCount}</span>}
          </button>
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

            {/* Pending section */}
            {pendingUsers.length > 0 && (
              <div className={styles.pendingSection}>
                <h3 className={styles.pendingSectionTitle}>Pending Approval ({pendingUsers.length})</h3>
                {pendingUsers.map(u => (
                  <div key={u.id} className={styles.pendingCard}>
                    <div className={styles.pendingInfo}>
                      <span className={styles.bold}>{u.username}</span>
                      <span className={styles.muted}>{u.email}</span>
                      <span className={styles.muted}>Signed up {new Date(u.created_at).toLocaleDateString()}</span>
                    </div>
                    <div className={styles.pendingActions}>
                      <button className={styles.approveBtn} onClick={() => handleApprove(u.id)}>Approve</button>
                      <button className={styles.rejectBtn} onClick={() => handleReject(u.id)}>Reject</button>
                    </div>
                  </div>
                ))}
              </div>
            )}

            {/* All users table */}
            <div className={styles.table}>
              <div className={styles.tableHeader}>
                <span>Username</span><span>Email</span><span>Role</span><span>Boards</span><span>Status</span><span>Actions</span>
              </div>
              {users.map((u) => (
                <div key={u.id} className={styles.tableRow}>
                  <span className={styles.bold}>{u.username}</span>
                  <span>{u.email}</span>
                  <span className={u.role === "admin" ? styles.badgeAdmin : styles.badgeUser}>{u.role}</span>
                  <span>{u.board_count} owned / {u.membership_count} shared</span>
                  <span className={u.is_active ? styles.active : styles.inactive}>{u.is_active ? "Active" : "Disabled"}</span>
                  <span className={styles.actions}>
                    {u.role !== "admin" && (
                      <>
                        {!u.is_active && (
                          <button className={styles.actionBtn} onClick={() => handleApprove(u.id)} title="Activate">Activate</button>
                        )}
                        {u.is_active && (
                          <button className={styles.actionBtnWarn} onClick={() => handleDeactivate(u.id)} title="Deactivate">Deactivate</button>
                        )}
                        <button className={styles.actionBtnDanger} onClick={() => setConfirmDelete(u)} title="Delete permanently">Delete</button>
                      </>
                    )}
                  </span>
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

        {/* Notifications tab */}
        {tab === "notifications" && (
          <div>
            <div className={styles.sectionHeader}>
              <h2>Notifications ({notifications.length})</h2>
              {unreadCount > 0 && (
                <button className={styles.createBtn} onClick={handleMarkAllRead}>Mark all read</button>
              )}
            </div>
            {notifications.length === 0 ? (
              <p className={styles.hint}>No notifications yet.</p>
            ) : (
              <div className={styles.notifList}>
                {notifications.map(n => (
                  <div key={n.id} className={`${styles.notifItem} ${n.is_read ? "" : styles.notifUnread}`}
                       onClick={() => n.link && navigate(n.link)}>
                    <div className={styles.notifCategory}>{n.category}</div>
                    <div className={styles.notifTitle}>{n.title}</div>
                    <div className={styles.notifMessage}>{n.message}</div>
                    <div className={styles.notifTime}>{new Date(n.created_at).toLocaleString()}</div>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {/* Overview tab */}
        {tab === "overview" && (
          <div className={styles.overview}>
            <p className={styles.hint}>
              Manage users and boards from the tabs above. Admin credentials are set via environment variables
              <code> ADMIN_USERNAME</code> and <code>ADMIN_PASSWORD</code>.
            </p>
            {pendingUsers.length > 0 && (
              <p className={styles.hint}>
                You have <strong>{pendingUsers.length}</strong> pending user{pendingUsers.length > 1 ? "s" : ""} awaiting approval.
                Go to the <button className={styles.linkBtn} onClick={() => setTab("users")}>Users</button> tab to manage them.
              </p>
            )}
          </div>
        )}
      </div>

      {/* Delete confirmation dialog */}
      {confirmDelete && (
        <div className={styles.overlay}>
          <div className={styles.dialog}>
            <h3>Delete User Permanently</h3>
            <p>
              Are you sure you want to permanently delete <strong>{confirmDelete.username}</strong> ({confirmDelete.email})?
              This will remove their account, board memberships, and notifications. This cannot be undone.
            </p>
            <div className={styles.dialogActions}>
              <button className={styles.dangerBtn} onClick={() => handlePermanentDelete(confirmDelete.id)}>Delete Permanently</button>
              <button className={styles.cancelBtn} onClick={() => setConfirmDelete(null)}>Cancel</button>
            </div>
          </div>
        </div>
      )}
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
