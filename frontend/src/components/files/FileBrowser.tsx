/**
 * FileBrowser — View and edit files in the board's ODK workspace.
 * Shows the full generated directory structure with descriptions,
 * inline editing for text files, and download for binary files.
 */

import { useState, useEffect, useCallback } from "react";
import { api, apiJson } from "../../api";
import styles from "./FileBrowser.module.css";

interface FileEntry {
  path: string;
  name: string;
  size: number;
  editable: boolean;
  description: string;
}

interface Props {
  boardId: string;
}

export default function FileBrowser({ boardId }: Props) {
  const [files, setFiles] = useState<FileEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [openFile, setOpenFile] = useState<string | null>(null);
  const [fileContent, setFileContent] = useState("");
  const [editContent, setEditContent] = useState("");
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [error, setError] = useState("");
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null);

  const loadFiles = useCallback(async () => {
    setLoading(true);
    try {
      const data = await apiJson<FileEntry[]>(`/api/odk-setup/${boardId}/files`);
      setFiles(data);
    } catch { setFiles([]); }
    setLoading(false);
  }, [boardId]);

  useEffect(() => { loadFiles(); }, [loadFiles]);

  const openFileContent = async (path: string) => {
    try {
      const res = await api(`/api/odk-setup/${boardId}/file/${path}`);
      if (res.ok) {
        const text = await res.text();
        setFileContent(text);
        setEditContent(text);
        setOpenFile(path);
        setDirty(false);
      }
    } catch {
      setError("Failed to load file");
    }
  };

  const saveFile = async () => {
    if (!openFile) return;
    setSaving(true);
    try {
      await apiJson(`/api/odk-setup/${boardId}/file/${openFile}`, {
        method: "PUT",
        body: JSON.stringify({ content: editContent }),
      });
      setFileContent(editContent);
      setDirty(false);
    } catch (e: any) {
      setError(e.message || "Save failed");
    }
    setSaving(false);
  };

  const deleteFile = async (path: string) => {
    try {
      // Write empty content to effectively clear the file
      // (actual file deletion would need a new endpoint)
      await apiJson(`/api/odk-setup/${boardId}/file/${path}`, {
        method: "PUT",
        body: JSON.stringify({ content: "" }),
      });
      setConfirmDelete(null);
      if (openFile === path) {
        setOpenFile(null);
        setFileContent("");
        setEditContent("");
      }
      loadFiles();
    } catch (e: any) {
      setError(e.message || "Delete failed");
    }
  };

  const downloadFile = async (path: string, name: string) => {
    const res = await api(`/api/odk-setup/${boardId}/file/${path}`);
    if (res.ok) {
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = name;
      a.click();
      URL.revokeObjectURL(url);
    }
  };

  // Group files by directory
  const grouped = groupByDirectory(files);

  const formatSize = (bytes: number) => {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1048576) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / 1048576).toFixed(1)} MB`;
  };

  return (
    <div className={styles.container}>
      <div className={styles.header}>
        <h3 className={styles.title}>Workspace Files</h3>
        <button className={styles.refreshBtn} onClick={loadFiles} title="Refresh">&#8635;</button>
      </div>

      {error && (
        <div className={styles.error} onClick={() => setError("")}>
          {error} <span>&times;</span>
        </div>
      )}

      <div className={styles.content}>
        {/* File tree */}
        <div className={styles.fileTree}>
          {loading ? (
            <div className={styles.empty}>Loading files...</div>
          ) : files.length === 0 ? (
            <div className={styles.empty}>No files yet. Create a board first.</div>
          ) : (
            Object.entries(grouped).map(([dir, dirFiles]) => (
              <div key={dir} className={styles.dirGroup}>
                <div className={styles.dirName}>{dir || "/"}</div>
                {dirFiles.map((f) => (
                  <div
                    key={f.path}
                    className={`${styles.fileRow} ${openFile === f.path ? styles.fileActive : ""}`}
                    onClick={() => f.editable && openFileContent(f.path)}
                  >
                    <span className={styles.fileIcon}>{f.editable ? "\u{1F4C4}" : "\u{1F4E6}"}</span>
                    <div className={styles.fileInfo}>
                      <span className={styles.fileName}>{f.name}</span>
                      {f.description && <span className={styles.fileDesc}>{f.description}</span>}
                    </div>
                    <span className={styles.fileSize}>{formatSize(f.size)}</span>
                    <div className={styles.fileActions}>
                      <button className={styles.fileActionBtn} onClick={(e) => { e.stopPropagation(); downloadFile(f.path, f.name); }} title="Download">
                        &#8681;
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            ))
          )}
        </div>

        {/* File editor */}
        {openFile && (
          <div className={styles.editor}>
            <div className={styles.editorHeader}>
              <span className={styles.editorPath}>{openFile}</span>
              <div className={styles.editorActions}>
                {dirty && <span className={styles.unsaved}>Unsaved</span>}
                <button className={styles.saveBtn} onClick={saveFile} disabled={!dirty || saving}>
                  {saving ? "Saving..." : "Save"}
                </button>
                <button className={styles.closeBtn} onClick={() => { setOpenFile(null); setDirty(false); }}>
                  &#10005;
                </button>
              </div>
            </div>
            <textarea
              className={styles.editorArea}
              value={editContent}
              onChange={(e) => { setEditContent(e.target.value); setDirty(true); }}
              spellCheck={false}
            />
          </div>
        )}
      </div>

      {/* Delete confirm */}
      {confirmDelete && (
        <div className={styles.overlay}>
          <div className={styles.dialog}>
            <p>Clear the contents of this file?</p>
            <div className={styles.dialogActions}>
              <button onClick={() => deleteFile(confirmDelete)}>Clear</button>
              <button onClick={() => setConfirmDelete(null)}>Cancel</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function groupByDirectory(files: FileEntry[]): Record<string, FileEntry[]> {
  const groups: Record<string, FileEntry[]> = {};
  for (const f of files) {
    const parts = f.path.split("/");
    const dir = parts.length > 1 ? parts.slice(0, -1).join("/") : ".";
    if (!groups[dir]) groups[dir] = [];
    groups[dir].push(f);
  }
  return groups;
}
