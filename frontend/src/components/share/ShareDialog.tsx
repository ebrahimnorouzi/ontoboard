import { useEffect, useState } from "react";
import { useInvite } from "../../hooks/useInvite";
import styles from "./ShareDialog.module.css";

interface ShareDialogProps {
  boardId: string;
  onClose: () => void;
}

export default function ShareDialog({ boardId, onClose }: ShareDialogProps) {
  const {
    links,
    members,
    loading,
    fetchLinks,
    fetchMembers,
    createLink,
    revokeLink,
    shareByUsername,
    removeMember,
  } = useInvite(boardId);

  // Generate link form state
  const [linkRole, setLinkRole] = useState("editor");
  const [expiresHours, setExpiresHours] = useState("");
  const [maxUses, setMaxUses] = useState("0");
  const [generating, setGenerating] = useState(false);

  // Share by username form state
  const [shareUsername, setShareUsername] = useState("");
  const [shareRole, setShareRole] = useState("editor");
  const [sharing, setSharing] = useState(false);

  // Feedback
  const [feedback, setFeedback] = useState<{ type: "error" | "success"; msg: string } | null>(null);

  useEffect(() => {
    fetchLinks();
    fetchMembers();
  }, [fetchLinks, fetchMembers]);

  const handleGenerate = async () => {
    setGenerating(true);
    setFeedback(null);
    try {
      const result = await createLink(
        linkRole,
        expiresHours ? parseInt(expiresHours, 10) : null,
        parseInt(maxUses, 10) || 0
      );
      await navigator.clipboard.writeText(result.url);
      setFeedback({ type: "success", msg: "Link created and copied to clipboard" });
      fetchLinks();
    } catch (err: any) {
      setFeedback({ type: "error", msg: err.message || "Failed to create link" });
    } finally {
      setGenerating(false);
    }
  };

  const handleCopy = async (token: string) => {
    const url = `${window.location.origin}/invite/${token}`;
    try {
      await navigator.clipboard.writeText(url);
      setFeedback({ type: "success", msg: "Link copied" });
    } catch {
      setFeedback({ type: "error", msg: "Failed to copy" });
    }
  };

  const handleRevoke = async (token: string) => {
    try {
      await revokeLink(token);
      setFeedback({ type: "success", msg: "Link revoked" });
    } catch (err: any) {
      setFeedback({ type: "error", msg: err.message || "Failed to revoke" });
    }
  };

  const handleShare = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!shareUsername.trim()) return;
    setSharing(true);
    setFeedback(null);
    try {
      await shareByUsername(shareUsername.trim(), shareRole);
      setShareUsername("");
      setFeedback({ type: "success", msg: `Shared with ${shareUsername}` });
    } catch (err: any) {
      setFeedback({ type: "error", msg: err.message || "Failed to share" });
    } finally {
      setSharing(false);
    }
  };

  const handleRemove = async (username: string) => {
    try {
      await removeMember(username);
      setFeedback({ type: "success", msg: `Removed ${username}` });
    } catch (err: any) {
      setFeedback({ type: "error", msg: err.message || "Failed to remove" });
    }
  };

  return (
    <div className={styles.overlay} onClick={onClose}>
      <div className={styles.dialog} onClick={(e) => e.stopPropagation()}>
        <div className={styles.header}>
          <span className={styles.title}>Share Board</span>
          <button className={styles.closeBtn} onClick={onClose}>
            &times;
          </button>
        </div>

        <div className={styles.body}>
          {feedback && (
            <div className={feedback.type === "error" ? styles.error : styles.success}>
              {feedback.msg}
            </div>
          )}

          {/* ── Members ──────────────────────────────────── */}
          <div className={styles.section}>
            <span className={styles.sectionTitle}>Members</span>
            {members.length === 0 ? (
              <span className={styles.emptyMsg}>No members yet</span>
            ) : (
              <div className={styles.memberList}>
                {members.map((m) => (
                  <div key={m.user_id} className={styles.memberRow}>
                    <div className={styles.memberInfo}>
                      <span className={styles.memberName}>{m.username}</span>
                      <span className={styles.roleBadge}>{m.role}</span>
                    </div>
                    <button
                      className={styles.removeBtn}
                      onClick={() => handleRemove(m.username)}
                    >
                      Remove
                    </button>
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* ── Generate Link ────────────────────────────── */}
          <div className={styles.section}>
            <span className={styles.sectionTitle}>Generate Invite Link</span>
            <div className={styles.generateForm}>
              <div className={styles.formGroup}>
                <span className={styles.formLabel}>Role</span>
                <select
                  className={styles.select}
                  value={linkRole}
                  onChange={(e) => setLinkRole(e.target.value)}
                >
                  <option value="editor">Editor</option>
                  <option value="viewer">Viewer</option>
                </select>
              </div>
              <div className={styles.formGroup}>
                <span className={styles.formLabel}>Expires (hours)</span>
                <input
                  className={styles.input}
                  type="number"
                  min="1"
                  placeholder="Never"
                  value={expiresHours}
                  onChange={(e) => setExpiresHours(e.target.value)}
                />
              </div>
              <div className={styles.formGroup}>
                <span className={styles.formLabel}>Max uses</span>
                <input
                  className={styles.input}
                  type="number"
                  min="0"
                  placeholder="0"
                  value={maxUses}
                  onChange={(e) => setMaxUses(e.target.value)}
                />
              </div>
              <button
                className={styles.generateBtn}
                onClick={handleGenerate}
                disabled={generating}
              >
                {generating ? "..." : "Generate"}
              </button>
            </div>
          </div>

          {/* ── Active Links ─────────────────────────────── */}
          {links.length > 0 && (
            <div className={styles.section}>
              <span className={styles.sectionTitle}>Active Links</span>
              <div className={styles.linkList}>
                {links.map((link) => (
                  <div key={link.id} className={styles.linkRow}>
                    <div className={styles.linkInfo}>
                      <span className={styles.roleBadge}>{link.role}</span>
                      <span className={styles.linkToken}>{link.token}</span>
                      <span className={styles.linkMeta}>
                        {link.max_uses > 0
                          ? `${link.use_count}/${link.max_uses} used`
                          : `${link.use_count} used`}
                      </span>
                    </div>
                    <div className={styles.linkActions}>
                      <button
                        className={styles.copyBtn}
                        onClick={() => handleCopy(link.token)}
                      >
                        Copy
                      </button>
                      <button
                        className={styles.revokeBtn}
                        onClick={() => handleRevoke(link.token)}
                      >
                        Revoke
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* ── Share by Username ─────────────────────────── */}
          <div className={styles.section}>
            <span className={styles.sectionTitle}>Invite by Username</span>
            <form className={styles.shareForm} onSubmit={handleShare}>
              <div className={styles.formGroup}>
                <span className={styles.formLabel}>Username</span>
                <input
                  className={styles.inputWide}
                  type="text"
                  placeholder="username"
                  value={shareUsername}
                  onChange={(e) => setShareUsername(e.target.value)}
                  required
                />
              </div>
              <div className={styles.formGroup}>
                <span className={styles.formLabel}>Role</span>
                <select
                  className={styles.select}
                  value={shareRole}
                  onChange={(e) => setShareRole(e.target.value)}
                >
                  <option value="editor">Editor</option>
                  <option value="viewer">Viewer</option>
                </select>
              </div>
              <button
                className={styles.generateBtn}
                type="submit"
                disabled={sharing}
              >
                {sharing ? "..." : "Add"}
              </button>
            </form>
          </div>
        </div>
      </div>
    </div>
  );
}
