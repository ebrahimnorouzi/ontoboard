import { useState, useEffect, useRef, useCallback } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../auth";
import { apiJson, api } from "../api";
import Logo from "./Logo";
import styles from "./Navbar.module.css";

interface NotificationItem {
  id: number;
  category: string;
  title: string;
  message: string;
  link: string | null;
  is_read: boolean;
  created_at: string;
}

const CATEGORY_ICONS: Record<string, string> = {
  signup: "\uD83D\uDC64", activated: "\u2705", board_shared: "\uD83D\uDCCB",
  board_update: "\uD83D\uDD14", mention: "@", system: "\u2699\uFE0F",
  welcome: "\uD83D\uDC4B", feedback: "\uD83D\uDCAC",
};

export default function Navbar() {
  const { pathname } = useLocation();
  const { user, logout, isAdmin } = useAuth();
  const navigate = useNavigate();
  const [unreadCount, setUnreadCount] = useState(0);
  const [notifications, setNotifications] = useState<NotificationItem[]>([]);
  const [bellOpen, setBellOpen] = useState(false);
  const bellRef = useRef<HTMLDivElement>(null);

  // Fetch unread count periodically
  useEffect(() => {
    if (!user) return;
    const fetchCount = () => {
      apiJson<{ unread_count: number }>("/api/notifications/count")
        .then((d) => setUnreadCount(d.unread_count))
        .catch(() => {});
    };
    fetchCount();
    const timer = setInterval(fetchCount, 30000); // every 30s
    return () => clearInterval(timer);
  }, [user]);

  // Fetch notifications when bell opens
  useEffect(() => {
    if (!bellOpen || !user) return;
    apiJson<{ unread_count: number; notifications: NotificationItem[] }>("/api/notifications/")
      .then((d) => { setNotifications(d.notifications); setUnreadCount(d.unread_count); })
      .catch(() => {});
  }, [bellOpen, user]);

  // Close bell on click outside
  useEffect(() => {
    if (!bellOpen) return;
    const handler = (e: MouseEvent) => {
      if (bellRef.current && !bellRef.current.contains(e.target as Node)) setBellOpen(false);
    };
    document.addEventListener("mousedown", handler);
    return () => document.removeEventListener("mousedown", handler);
  }, [bellOpen]);

  const handleNotifClick = useCallback(async (n: NotificationItem) => {
    if (!n.is_read) {
      await api(`/api/notifications/${n.id}/read`, { method: "POST" }).catch(() => {});
      setNotifications((prev) => prev.map((x) => x.id === n.id ? { ...x, is_read: true } : x));
      setUnreadCount((c) => Math.max(0, c - 1));
    }
    if (n.link) { navigate(n.link); setBellOpen(false); }
  }, [navigate]);

  const handleMarkAllRead = useCallback(async () => {
    await api("/api/notifications/read-all", { method: "POST" }).catch(() => {});
    setNotifications((prev) => prev.map((x) => ({ ...x, is_read: true })));
    setUnreadCount(0);
  }, []);

  const handleLogout = () => { logout(); navigate("/"); };

  const timeAgo = (dateStr: string) => {
    const diff = Date.now() - new Date(dateStr).getTime();
    const mins = Math.floor(diff / 60000);
    if (mins < 1) return "just now";
    if (mins < 60) return `${mins}m ago`;
    const hrs = Math.floor(mins / 60);
    if (hrs < 24) return `${hrs}h ago`;
    return `${Math.floor(hrs / 24)}d ago`;
  };

  return (
    <nav className={styles.nav}>
      <Link to="/" className={styles.brand}>
        <Logo size={28} />
        <span className={styles.brandText}>OntoBoard</span>
      </Link>

      <div className={styles.links}>
        <Link to="/board" className={`${styles.link} ${pathname.startsWith("/board") ? styles.active : ""}`}>
          Boards
        </Link>

        {isAdmin && (
          <Link to="/admin" className={`${styles.link} ${pathname.startsWith("/admin") ? styles.active : ""}`}>
            Admin
          </Link>
        )}

        <a href="http://localhost:8000/docs" target="_blank" rel="noreferrer" className={styles.link}>
          API
        </a>

        {/* Feedback button */}
        {user && (
          <Link to="/feedback" className={styles.link} title="Report issue or give feedback">
            Feedback
          </Link>
        )}

        {/* Notification Bell */}
        {user && (
          <div className={styles.bellContainer} ref={bellRef}>
            <button className={styles.bellBtn} onClick={() => setBellOpen(!bellOpen)} title="Notifications">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9" /><path d="M13.73 21a2 2 0 0 1-3.46 0" />
              </svg>
              {unreadCount > 0 && <span className={styles.bellBadge}>{unreadCount > 99 ? "99+" : unreadCount}</span>}
            </button>

            {bellOpen && (
              <div className={styles.bellDropdown}>
                <div className={styles.bellHeader}>
                  <span className={styles.bellTitle}>Notifications</span>
                  {unreadCount > 0 && (
                    <button className={styles.markAllBtn} onClick={handleMarkAllRead}>Mark all read</button>
                  )}
                </div>
                <div className={styles.bellList}>
                  {notifications.length === 0 ? (
                    <div className={styles.bellEmpty}>No notifications</div>
                  ) : (
                    notifications.slice(0, 15).map((n) => (
                      <div key={n.id} className={`${styles.bellItem} ${n.is_read ? "" : styles.bellItemUnread}`}
                           onClick={() => handleNotifClick(n)}>
                        <span className={styles.bellIcon}>{CATEGORY_ICONS[n.category] || "\uD83D\uDD14"}</span>
                        <div className={styles.bellContent}>
                          <div className={styles.bellItemTitle}>{n.title}</div>
                          {n.message && <div className={styles.bellItemMsg}>{n.message}</div>}
                          <div className={styles.bellItemTime}>{timeAgo(n.created_at)}</div>
                        </div>
                      </div>
                    ))
                  )}
                </div>
                {isAdmin && (
                  <div className={styles.bellFooter}>
                    <Link to="/admin/notifications" className={styles.bellSeeAll}
                          onClick={() => setBellOpen(false)}>See all notifications</Link>
                  </div>
                )}
              </div>
            )}
          </div>
        )}

        {user ? (
          <div className={styles.userMenu}>
            <span className={styles.username}>
              {user.display_name || user.username}
              {isAdmin && <span className={styles.adminBadge}>admin</span>}
            </span>
            <button className={styles.logoutBtn} onClick={handleLogout}>Sign out</button>
          </div>
        ) : (
          <Link to="/login" className={`${styles.link} ${styles.loginLink}`}>Sign in</Link>
        )}
      </div>
    </nav>
  );
}
