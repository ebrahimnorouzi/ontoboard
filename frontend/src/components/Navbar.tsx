import { Link, useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../auth";
import Logo from "./Logo";
import styles from "./Navbar.module.css";

export default function Navbar() {
  const { pathname } = useLocation();
  const { user, logout, isAdmin } = useAuth();
  const navigate = useNavigate();

  const handleLogout = () => {
    logout();
    navigate("/");
  };

  return (
    <nav className={styles.nav}>
      <Link to="/" className={styles.brand}>
        <Logo size={28} />
        <span className={styles.brandText}>OntoBoard</span>
      </Link>

      <div className={styles.links}>
        <Link
          to="/board"
          className={`${styles.link} ${pathname.startsWith("/board") ? styles.active : ""}`}
        >
          Boards
        </Link>

        {isAdmin && (
          <Link
            to="/admin"
            className={`${styles.link} ${pathname === "/admin" ? styles.active : ""}`}
          >
            Admin
          </Link>
        )}

        <a
          href="http://localhost:8000/docs"
          target="_blank"
          rel="noreferrer"
          className={styles.link}
        >
          API
        </a>

        {user ? (
          <div className={styles.userMenu}>
            <span className={styles.username}>
              {user.display_name || user.username}
              {isAdmin && <span className={styles.adminBadge}>admin</span>}
            </span>
            <button className={styles.logoutBtn} onClick={handleLogout}>
              Sign out
            </button>
          </div>
        ) : (
          <Link to="/login" className={`${styles.link} ${styles.loginLink}`}>
            Sign in
          </Link>
        )}
      </div>
    </nav>
  );
}
