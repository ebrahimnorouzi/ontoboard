/**
 * CollabStatus — shows connection status and online users in the toolbar.
 */

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
  const allUsers = [
    { name: currentUser, color: "#6c5ce7" },
    ...users,
  ];

  return (
    <div className={styles.container}>
      {/* Connection indicator */}
      <div
        className={`${styles.dot} ${connected ? styles.dotConnected : styles.dotDisconnected}`}
        title={connected ? "Connected" : "Disconnected"}
      />

      {/* User avatars */}
      <div className={styles.avatars}>
        {allUsers.slice(0, 5).map((u, i) => (
          <div
            key={`${u.name}-${i}`}
            className={styles.avatar}
            style={{ backgroundColor: u.color, zIndex: 10 - i }}
            title={u.name}
          >
            {u.name.charAt(0).toUpperCase()}
          </div>
        ))}
        {allUsers.length > 5 && (
          <div className={styles.avatarMore}>+{allUsers.length - 5}</div>
        )}
      </div>

      {/* Count label */}
      <span className={styles.count}>
        {allUsers.length} online
      </span>
    </div>
  );
}
