/**
 * CollabStatus — shows connection status and online users with a dropdown list.
 * Deduplicates users by name to avoid inflated counts from multiple connections.
 */

import { useState, useRef, useEffect } from "react";
import styles from "./CollabStatus.module.css";

interface CollabUser {
  name: string;
  color: string;
}

interface Props {
  connected: boolean;
  users: CollabUser[];
  currentUser: string;
}

export default function CollabStatus({ connected, users, currentUser }: Props) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  // Deduplicate users by name (multiple connections = same user)
  const seen = new Set<string>();
  seen.add(currentUser);
  const deduped: CollabUser[] = [{ name: currentUser, color: "#6c5ce7" }];
  for (const u of users) {
    if (!seen.has(u.name)) {
      seen.add(u.name);
      deduped.push(u);
    }
  }

  // Close dropdown on outside click
  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", handler);
    return () => document.removeEventListener("mousedown", handler);
  }, [open]);

  return (
    <div className={styles.container} ref={ref}>
      <div
        className={`${styles.dot} ${connected ? styles.dotConnected : styles.dotDisconnected}`}
        title={connected ? "Connected" : "Disconnected"}
      />

      {/* Clickable avatars + count */}
      <button className={styles.trigger} onClick={() => setOpen(!open)}>
        <div className={styles.avatars}>
          {deduped.slice(0, 4).map((u, i) => (
            <div
              key={u.name}
              className={styles.avatar}
              style={{ backgroundColor: u.color, zIndex: 10 - i }}
              title={u.name}
            >
              {u.name.charAt(0).toUpperCase()}
            </div>
          ))}
          {deduped.length > 4 && (
            <div className={styles.avatarMore}>+{deduped.length - 4}</div>
          )}
        </div>
        <span className={styles.count}>
          {deduped.length} online &#9662;
        </span>
      </button>

      {/* Dropdown list */}
      {open && (
        <div className={styles.dropdown}>
          <div className={styles.dropdownTitle}>Online Users ({deduped.length})</div>
          {deduped.map((u) => (
            <div key={u.name} className={styles.dropdownItem}>
              <div className={styles.dropdownAvatar} style={{ backgroundColor: u.color }}>
                {u.name.charAt(0).toUpperCase()}
              </div>
              <span className={styles.dropdownName}>{u.name}</span>
              {u.name === currentUser && <span className={styles.youBadge}>you</span>}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
