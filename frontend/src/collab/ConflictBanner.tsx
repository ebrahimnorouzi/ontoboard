/**
 * ConflictBanner — Displays true conflicts and auto-merge notices.
 *
 * Phase 3 of the CRDT collaboration system. Auto-resolved merges appear as
 * brief green notices. True conflicts (same semantic field) show amber with
 * resolution actions: "Keep mine", "Keep theirs", "Keep both".
 */

import type { OpConflict, AutoMergeNotice } from "./useOperationSync";
import styles from "./ConflictBanner.module.css";

interface Props {
  conflicts: OpConflict[];
  autoMerges: AutoMergeNotice[];
  onDismiss: (id: string) => void;
  onDismissAll: () => void;
  onResolve: (id: string, choice: "keepMine" | "keepTheirs" | "keepBoth") => void;
}

function shortIri(iri: string): string {
  const hash = iri.lastIndexOf("#");
  if (hash >= 0) return iri.slice(hash + 1);
  const slash = iri.lastIndexOf("/");
  if (slash >= 0) return iri.slice(slash + 1);
  return iri;
}

function opLabel(type: string): string {
  if (type.startsWith("add")) return "added";
  if (type.startsWith("update")) return "edited";
  if (type.startsWith("remove")) return "removed";
  return "changed";
}

export default function ConflictBanner({
  conflicts, autoMerges, onDismiss, onDismissAll, onResolve,
}: Props) {
  const hasConflicts = conflicts.length > 0;
  const hasNotices = autoMerges.length > 0;

  if (!hasConflicts && !hasNotices) return null;

  return (
    <div className={styles.wrapper}>
      {/* Auto-merge notices (green, fade quickly) */}
      {hasNotices && (
        <div className={styles.noticeContainer}>
          {autoMerges.map((n) => (
            <div key={n.id} className={styles.notice}>
              <span className={styles.noticeIcon}>&#10003;</span>
              <span className={styles.noticeText}>
                Auto-merged: <strong>{n.remoteUser}</strong> &mdash; {n.reason}
                {" "}<code className={styles.entity}>{shortIri(n.entityIri)}</code>
              </span>
            </div>
          ))}
        </div>
      )}

      {/* True conflicts (amber, require resolution) */}
      {hasConflicts && (
        <div className={styles.container}>
          <div className={styles.header}>
            <span className={styles.icon}>!</span>
            <span className={styles.title}>
              {conflicts.length === 1
                ? "Conflict requires resolution"
                : `${conflicts.length} conflicts require resolution`}
            </span>
            {conflicts.length > 1 && (
              <button className={styles.dismissAllBtn} onClick={onDismissAll}>
                Dismiss all
              </button>
            )}
          </div>
          <div className={styles.list}>
            {conflicts.map((c) => (
              <div key={c.id} className={styles.item}>
                <div className={styles.detail}>
                  <span>
                    <strong>{c.remoteUser}</strong> {opLabel(c.remoteOp.type)}{" "}
                    <code className={styles.entity}>{shortIri(c.entityIri)}</code>
                  </span>
                  <span className={styles.reason}>{c.mergeReason}</span>
                </div>
                <div className={styles.actions}>
                  <button
                    className={styles.resolveBtn}
                    onClick={() => onResolve(c.id, "keepMine")}
                    title="Revert remote change, keep your version"
                  >
                    Keep mine
                  </button>
                  <button
                    className={styles.resolveBtn}
                    onClick={() => onResolve(c.id, "keepTheirs")}
                    title="Accept remote change, overwrite your version"
                  >
                    Keep theirs
                  </button>
                  <button
                    className={styles.resolveBtnMuted}
                    onClick={() => onResolve(c.id, "keepBoth")}
                    title="Accept both changes as-is"
                  >
                    Keep both
                  </button>
                  <button
                    className={styles.dismissBtn}
                    onClick={() => onDismiss(c.id)}
                    title="Dismiss without action"
                  >
                    &times;
                  </button>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
