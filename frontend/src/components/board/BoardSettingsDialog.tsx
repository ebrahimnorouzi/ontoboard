/**
 * BoardSettingsDialog — Board management actions:
 * rename, description, visibility, tags, clone, starred, delete.
 */

import { useState, useCallback } from "react";
import { useNavigate } from "react-router-dom";
import { api, apiJson } from "../../api";
import styles from "./BoardSettingsDialog.module.css";

interface Props {
  boardId: string;
  userRole: string | null;
  onClose: () => void;
  onBoardDeleted?: () => void;
}

interface BoardInfo {
  display_name: string;
  description: string;
  is_public: boolean;
  tags: string;
  is_starred: boolean;
}

export default function BoardSettingsDialog({ boardId, userRole, onClose, onBoardDeleted }: Props) {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [info, setInfo] = useState<BoardInfo>({ display_name: boardId, description: "", is_public: true, tags: "", is_starred: false });
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");

  // Clone state
  const [showClone, setShowClone] = useState(false);
  const [cloneId, setCloneId] = useState("");
  const [cloning, setCloning] = useState(false);

  // Delete state
  const [showDelete, setShowDelete] = useState(false);
  const [deleteConfirm, setDeleteConfirm] = useState("");

  // Load board info
  useState(() => {
    apiJson<any>(`/api/boards/${boardId}`)
      .then((data) => {
        setInfo({
          display_name: data.display_name || boardId,
          description: data.description || "",
          is_public: data.is_public ?? true,
          tags: data.tags || "",
          is_starred: data.is_starred ?? false,
        });
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  });

  const isOwner = userRole === "owner" || userRole === "admin";

  const saveSettings = useCallback(async (fields: Partial<BoardInfo>) => {
    setSaving(true);
    setError("");
    try {
      await api(`/api/boards/${boardId}`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(fields),
      });
      setInfo((prev) => ({ ...prev, ...fields }));
      setSuccess("Settings saved");
      setTimeout(() => setSuccess(""), 2000);
    } catch (e: any) {
      setError(e.message || "Save failed");
    } finally {
      setSaving(false);
    }
  }, [boardId]);

  const handleClone = useCallback(async () => {
    if (!cloneId.trim()) return;
    setCloning(true);
    setError("");
    try {
      await apiJson(`/api/boards/${boardId}/clone`, {
        method: "POST",
        body: JSON.stringify({ new_board_id: cloneId.trim() }),
      });
      navigate(`/board/${cloneId.trim()}`);
    } catch (e: any) {
      setError(e.message || "Clone failed");
    } finally {
      setCloning(false);
    }
  }, [boardId, cloneId, navigate]);

  const handleDelete = useCallback(async () => {
    try {
      const res = await api(`/api/boards/${boardId}`, { method: "DELETE" });
      if (res.ok) {
        onBoardDeleted?.();
        navigate("/board");
      } else {
        const data = await res.json().catch(() => ({}));
        setError(data.detail || "Delete failed");
      }
    } catch (e: any) {
      setError(e.message || "Delete failed");
    }
  }, [boardId, navigate, onBoardDeleted]);

  const handleToggleStar = useCallback(async () => {
    try {
      await apiJson(`/api/boards/${boardId}/star`, { method: "POST" });
      setInfo((prev) => ({ ...prev, is_starred: !prev.is_starred }));
    } catch {}
  }, [boardId]);

  return (
    <div className={styles.overlay} onClick={onClose}>
      <div className={styles.dialog} onClick={(e) => e.stopPropagation()}>
        <div className={styles.header}>
          <h2 className={styles.title}>Board Settings</h2>
          <button className={styles.closeBtn} onClick={onClose}>&times;</button>
        </div>

        {error && <div className={styles.error}>{error}</div>}
        {success && <div className={styles.success}>{success}</div>}

        {loading ? (
          <div className={styles.loading}>Loading...</div>
        ) : (
          <div className={styles.body}>
            {/* ── General ── */}
            <section className={styles.section}>
              <h3 className={styles.sectionTitle}>General</h3>

              <div className={styles.field}>
                <label className={styles.label}>Display Name</label>
                <input className={styles.input} value={info.display_name}
                  onChange={(e) => setInfo({ ...info, display_name: e.target.value })}
                  disabled={!isOwner} />
              </div>

              <div className={styles.field}>
                <label className={styles.label}>Description</label>
                <textarea className={styles.textarea} value={info.description} rows={2}
                  onChange={(e) => setInfo({ ...info, description: e.target.value })}
                  disabled={!isOwner}
                  placeholder="What is this ontology about?" />
              </div>

              <div className={styles.field}>
                <label className={styles.label}>Tags</label>
                <input className={styles.input} value={info.tags}
                  onChange={(e) => setInfo({ ...info, tags: e.target.value })}
                  disabled={!isOwner}
                  placeholder="e.g. biology, obo, experimental" />
              </div>

              {isOwner && (
                <button className={styles.saveBtn} onClick={() => saveSettings({
                  display_name: info.display_name,
                  description: info.description,
                  tags: info.tags,
                })} disabled={saving}>
                  {saving ? "Saving..." : "Save Changes"}
                </button>
              )}
            </section>

            {/* ── Visibility ── */}
            {isOwner && (
              <section className={styles.section}>
                <h3 className={styles.sectionTitle}>Visibility</h3>
                <div className={styles.toggleRow}>
                  <label className={styles.toggleLabel}>
                    <input type="checkbox" checked={info.is_public}
                      onChange={(e) => {
                        const val = e.target.checked;
                        setInfo({ ...info, is_public: val });
                        saveSettings({ is_public: val });
                      }} />
                    Public board
                  </label>
                  <span className={styles.toggleHint}>
                    {info.is_public ? "Anyone with the link can view" : "Only members can access"}
                  </span>
                </div>
              </section>
            )}

            {/* ── Star ── */}
            <section className={styles.section}>
              <h3 className={styles.sectionTitle}>Quick Access</h3>
              <button className={styles.starBtn} onClick={handleToggleStar}>
                {info.is_starred ? "\u2605 Starred" : "\u2606 Star this board"}
              </button>
            </section>

            {/* ── Clone ── */}
            <section className={styles.section}>
              <h3 className={styles.sectionTitle}>Clone Board</h3>
              <p className={styles.hint}>Create a full copy of this board with all ontology data and files.</p>
              {showClone ? (
                <div className={styles.inlineForm}>
                  <input className={styles.input} value={cloneId}
                    onChange={(e) => setCloneId(e.target.value)}
                    placeholder="New board ID (e.g. my-ontology-copy)"
                    onKeyDown={(e) => e.key === "Enter" && handleClone()} />
                  <button className={styles.actionBtn} onClick={handleClone} disabled={cloning || !cloneId.trim()}>
                    {cloning ? "Cloning..." : "Clone"}
                  </button>
                  <button className={styles.cancelBtn} onClick={() => setShowClone(false)}>Cancel</button>
                </div>
              ) : (
                <button className={styles.actionBtn} onClick={() => { setCloneId(`${boardId}-copy`); setShowClone(true); }}>
                  Clone Board
                </button>
              )}
            </section>

            {/* ── Danger Zone ── */}
            {isOwner && (
              <section className={`${styles.section} ${styles.dangerSection}`}>
                <h3 className={styles.sectionTitle}>Danger Zone</h3>
                {showDelete ? (
                  <div className={styles.deleteConfirm}>
                    <p>Type <strong>{boardId}</strong> to confirm deletion:</p>
                    <input className={styles.input} value={deleteConfirm}
                      onChange={(e) => setDeleteConfirm(e.target.value)}
                      placeholder={boardId} />
                    <div className={styles.deleteActions}>
                      <button className={styles.deleteBtn}
                        disabled={deleteConfirm !== boardId}
                        onClick={handleDelete}>
                        Permanently Delete
                      </button>
                      <button className={styles.cancelBtn} onClick={() => { setShowDelete(false); setDeleteConfirm(""); }}>
                        Cancel
                      </button>
                    </div>
                  </div>
                ) : (
                  <button className={styles.dangerBtn} onClick={() => setShowDelete(true)}>
                    Delete Board
                  </button>
                )}
                <p className={styles.dangerHint}>This will permanently remove all ontology data, files, and history.</p>
              </section>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
