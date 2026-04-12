/**
 * FileBrowser — Interactive tree-based file explorer for the board's ODK workspace.
 * Displays files and folders hierarchically with collapse/expand, inline preview,
 * editing, rename, delete, download, and new-file operations.
 */

import { useState, useEffect, useCallback } from "react";
import { api, apiJson } from "../../api";
import styles from "./FileBrowser.module.css";

/* ------------------------------------------------------------------ */
/*  Types                                                              */
/* ------------------------------------------------------------------ */

interface FileEntry {
  path: string;
  name: string;
  size: number;
  editable: boolean;
  description: string;
}

interface TreeNode {
  name: string;
  path: string;
  isDir: boolean;
  size: number;
  editable: boolean;
  description: string;
  children: TreeNode[];
}

interface Props {
  boardId: string;
}

/* ------------------------------------------------------------------ */
/*  Icon helpers                                                       */
/* ------------------------------------------------------------------ */

const EXT_ICONS: Record<string, string> = {
  ".owl": "\u{1F989}",   // owl
  ".ttl": "\u{1F422}",   // turtle
  ".rdf": "\u{1F310}",   // globe
  ".yaml": "\u2699\uFE0F",  // gear
  ".yml": "\u2699\uFE0F",
  ".py": "\u{1F40D}",    // snake
  ".md": "\u{1F4DD}",    // memo
  ".json": "{ }",
  ".txt": "\u{1F4C4}",
  ".sh": "\u{1F4BB}",
  ".mk": "\u{1F527}",
  ".sparql": "\u{1F50D}",
  ".ofn": "\u{1F4D6}",
};

function fileIcon(name: string): string {
  const dot = name.lastIndexOf(".");
  if (dot !== -1) {
    const ext = name.slice(dot).toLowerCase();
    if (EXT_ICONS[ext]) return EXT_ICONS[ext];
  }
  return "\u{1F4C4}"; // default document
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1048576) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1048576).toFixed(1)} MB`;
}

/* ------------------------------------------------------------------ */
/*  Build tree from flat file list                                     */
/* ------------------------------------------------------------------ */

function buildTree(files: FileEntry[]): TreeNode[] {
  const root: TreeNode = {
    name: "",
    path: "",
    isDir: true,
    size: 0,
    editable: false,
    description: "",
    children: [],
  };

  for (const f of files) {
    const parts = f.path.split("/");
    let current = root;

    for (let i = 0; i < parts.length; i++) {
      const part = parts[i];
      const isLast = i === parts.length - 1;

      if (isLast) {
        // Leaf file node
        current.children.push({
          name: part,
          path: f.path,
          isDir: false,
          size: f.size,
          editable: f.editable,
          description: f.description,
          children: [],
        });
      } else {
        // Directory node
        let dirNode = current.children.find((c) => c.isDir && c.name === part);
        if (!dirNode) {
          const dirPath = parts.slice(0, i + 1).join("/");
          dirNode = {
            name: part,
            path: dirPath,
            isDir: true,
            size: 0,
            editable: false,
            description: "",
            children: [],
          };
          current.children.push(dirNode);
        }
        current = dirNode;
      }
    }
  }

  // Sort: dirs first, then alphabetical
  const sortNodes = (nodes: TreeNode[]) => {
    nodes.sort((a, b) => {
      if (a.isDir !== b.isDir) return a.isDir ? -1 : 1;
      return a.name.localeCompare(b.name);
    });
    for (const n of nodes) {
      if (n.isDir) sortNodes(n.children);
    }
  };
  sortNodes(root.children);
  return root.children;
}

/* ------------------------------------------------------------------ */
/*  Component                                                          */
/* ------------------------------------------------------------------ */

export default function FileBrowser({ boardId }: Props) {
  const [files, setFiles] = useState<FileEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  // Expanded folders (set of paths)
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  // File whose content is shown inline (preview)
  const [previewFile, setPreviewFile] = useState<string | null>(null);
  const [previewContent, setPreviewContent] = useState("");
  // File being edited
  const [editingFile, setEditingFile] = useState<string | null>(null);
  const [editContent, setEditContent] = useState("");
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);
  // Rename state
  const [renamingPath, setRenamingPath] = useState<string | null>(null);
  const [renameValue, setRenameValue] = useState("");
  // Delete confirm
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null);
  // New file state
  const [newFileDir, setNewFileDir] = useState<string | null>(null);
  const [newFileName, setNewFileName] = useState("");

  /* ---- data loading ---- */

  const loadFiles = useCallback(async () => {
    setLoading(true);
    try {
      const data = await apiJson<FileEntry[]>(`/api/odk-setup/${boardId}/files`);
      setFiles(data);
    } catch {
      setFiles([]);
    }
    setLoading(false);
  }, [boardId]);

  useEffect(() => {
    loadFiles();
  }, [loadFiles]);

  const tree = buildTree(files);

  /* ---- file operations ---- */

  const toggleFolder = (path: string) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    });
  };

  const togglePreview = async (path: string) => {
    if (previewFile === path) {
      setPreviewFile(null);
      setPreviewContent("");
      return;
    }
    try {
      const res = await api(`/api/odk-setup/${boardId}/file/${path}`);
      if (res.ok) {
        const text = await res.text();
        const lines = text.split("\n");
        setPreviewContent(lines.slice(0, 100).join("\n") + (lines.length > 100 ? "\n... (truncated)" : ""));
        setPreviewFile(path);
      }
    } catch {
      setError("Failed to load file preview");
    }
  };

  const startEdit = async (path: string) => {
    try {
      const res = await api(`/api/odk-setup/${boardId}/file/${path}`);
      if (res.ok) {
        const text = await res.text();
        setEditContent(text);
        setEditingFile(path);
        setDirty(false);
        // Close preview if same file
        if (previewFile === path) setPreviewFile(null);
      }
    } catch {
      setError("Failed to load file for editing");
    }
  };

  const saveFile = async () => {
    if (!editingFile) return;
    setSaving(true);
    try {
      await apiJson(`/api/odk-setup/${boardId}/file/${editingFile}`, {
        method: "PUT",
        body: JSON.stringify({ content: editContent }),
      });
      setDirty(false);
      loadFiles();
    } catch (e: any) {
      setError(e.message || "Save failed");
    }
    setSaving(false);
  };

  const cancelEdit = () => {
    setEditingFile(null);
    setEditContent("");
    setDirty(false);
  };

  const deleteItem = async (path: string) => {
    try {
      await apiJson(`/api/odk-setup/${boardId}/file/${path}`, { method: "DELETE" });
      setConfirmDelete(null);
      if (previewFile === path) {
        setPreviewFile(null);
        setPreviewContent("");
      }
      if (editingFile === path) cancelEdit();
      loadFiles();
    } catch (e: any) {
      setError(e.message || "Delete failed");
    }
  };

  const startRename = (path: string, currentName: string) => {
    setRenamingPath(path);
    setRenameValue(currentName);
  };

  const submitRename = async () => {
    if (!renamingPath || !renameValue.trim()) return;
    const parts = renamingPath.split("/");
    parts[parts.length - 1] = renameValue.trim();
    const newPath = parts.join("/");
    try {
      await apiJson(`/api/odk-setup/${boardId}/rename`, {
        method: "POST",
        body: JSON.stringify({ old_path: renamingPath, new_path: newPath }),
      });
      setRenamingPath(null);
      setRenameValue("");
      loadFiles();
    } catch (e: any) {
      setError(e.message || "Rename failed");
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

  const startNewFile = (dirPath: string) => {
    setNewFileDir(dirPath);
    setNewFileName("");
  };

  const submitNewFile = async () => {
    if (newFileDir === null || !newFileName.trim()) return;
    const filePath = newFileDir ? `${newFileDir}/${newFileName.trim()}` : newFileName.trim();
    try {
      await apiJson(`/api/odk-setup/${boardId}/new-file`, {
        method: "POST",
        body: JSON.stringify({ path: filePath, content: "" }),
      });
      setNewFileDir(null);
      setNewFileName("");
      // Expand the parent directory
      if (newFileDir) {
        setExpanded((prev) => new Set(prev).add(newFileDir));
      }
      loadFiles();
    } catch (e: any) {
      setError(e.message || "Create file failed");
    }
  };

  /* ---- tree rendering ---- */

  const renderNode = (node: TreeNode, depth: number): JSX.Element => {
    const isExpanded = expanded.has(node.path);
    const isPreviewing = previewFile === node.path;
    const isEditing = editingFile === node.path;
    const isRenaming = renamingPath === node.path;

    if (node.isDir) {
      return (
        <div key={node.path} className={styles.treeNodeWrapper}>
          <div
            className={`${styles.treeNode} ${styles.folderRow}`}
            style={{ paddingLeft: `${depth * 16 + 8}px` }}
            onClick={() => toggleFolder(node.path)}
          >
            <span className={styles.nodeIcon}>
              {isExpanded ? "\u{1F4C2}" : "\u{1F4C1}"}
            </span>
            {isRenaming ? (
              <input
                className={styles.renameInput}
                value={renameValue}
                onChange={(e) => setRenameValue(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") submitRename();
                  if (e.key === "Escape") setRenamingPath(null);
                }}
                onBlur={submitRename}
                onClick={(e) => e.stopPropagation()}
                autoFocus
              />
            ) : (
              <span className={styles.nodeName}>{node.name}</span>
            )}
            <span className={styles.nodeActions}>
              <button
                className={styles.actionBtn}
                title="New File"
                onClick={(e) => { e.stopPropagation(); startNewFile(node.path); }}
              >
                +
              </button>
              <button
                className={styles.actionBtn}
                title="Rename"
                onClick={(e) => { e.stopPropagation(); startRename(node.path, node.name); }}
              >
                &#9998;
              </button>
              <button
                className={`${styles.actionBtn} ${styles.dangerBtn}`}
                title="Delete"
                onClick={(e) => { e.stopPropagation(); setConfirmDelete(node.path); }}
              >
                &#128465;
              </button>
            </span>
          </div>

          {/* New file input row */}
          {newFileDir === node.path && (
            <div className={styles.newFileRow} style={{ paddingLeft: `${(depth + 1) * 16 + 8}px` }}>
              <span className={styles.nodeIcon}>{"\u{1F4C4}"}</span>
              <input
                className={styles.renameInput}
                placeholder="filename.ext"
                value={newFileName}
                onChange={(e) => setNewFileName(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") submitNewFile();
                  if (e.key === "Escape") setNewFileDir(null);
                }}
                onBlur={() => { if (!newFileName.trim()) setNewFileDir(null); }}
                autoFocus
              />
              <button className={styles.actionBtn} onClick={submitNewFile} title="Create">
                &#10003;
              </button>
              <button className={styles.actionBtn} onClick={() => setNewFileDir(null)} title="Cancel">
                &#10005;
              </button>
            </div>
          )}

          {isExpanded &&
            node.children.map((child) => renderNode(child, depth + 1))}
        </div>
      );
    }

    // File node
    return (
      <div key={node.path} className={styles.treeNodeWrapper}>
        <div
          className={`${styles.treeNode} ${styles.fileRow} ${isPreviewing || isEditing ? styles.fileActive : ""}`}
          style={{ paddingLeft: `${depth * 16 + 8}px` }}
          onClick={() => togglePreview(node.path)}
        >
          <span className={styles.nodeIcon}>{fileIcon(node.name)}</span>
          {isRenaming ? (
            <input
              className={styles.renameInput}
              value={renameValue}
              onChange={(e) => setRenameValue(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter") submitRename();
                if (e.key === "Escape") setRenamingPath(null);
              }}
              onBlur={submitRename}
              onClick={(e) => e.stopPropagation()}
              autoFocus
            />
          ) : (
            <span className={styles.nodeName}>{node.name}</span>
          )}
          <span className={styles.nodeSize}>{formatSize(node.size)}</span>
          <span className={styles.nodeActions}>
            {node.editable && (
              <button
                className={styles.actionBtn}
                title="Edit"
                onClick={(e) => { e.stopPropagation(); startEdit(node.path); }}
              >
                &#9998;
              </button>
            )}
            <button
              className={styles.actionBtn}
              title="Download"
              onClick={(e) => { e.stopPropagation(); downloadFile(node.path, node.name); }}
            >
              &#8681;
            </button>
            <button
              className={styles.actionBtn}
              title="Rename"
              onClick={(e) => { e.stopPropagation(); startRename(node.path, node.name); }}
            >
              Aa
            </button>
            <button
              className={`${styles.actionBtn} ${styles.dangerBtn}`}
              title="Delete"
              onClick={(e) => { e.stopPropagation(); setConfirmDelete(node.path); }}
            >
              &#128465;
            </button>
          </span>
        </div>

        {/* Inline preview */}
        {isPreviewing && !isEditing && (
          <div className={styles.filePreview} style={{ marginLeft: `${depth * 16 + 8}px` }}>
            <pre>{previewContent}</pre>
          </div>
        )}

        {/* Inline editor */}
        {isEditing && (
          <div className={styles.fileEditor} style={{ marginLeft: `${depth * 16 + 8}px` }}>
            <div className={styles.editorHeader}>
              <span className={styles.editorPath}>{node.path}</span>
              <div className={styles.editorActions}>
                {dirty && <span className={styles.unsaved}>Unsaved</span>}
                <button className={styles.saveBtn} onClick={saveFile} disabled={!dirty || saving}>
                  {saving ? "Saving..." : "Save"}
                </button>
                <button className={styles.closeBtn} onClick={cancelEdit}>
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
    );
  };

  /* ---- main render ---- */

  return (
    <div className={styles.container}>
      <div className={styles.header}>
        <h3 className={styles.title}>Workspace Files</h3>
        <div className={styles.headerActions}>
          <button
            className={styles.actionBtn}
            onClick={() => startNewFile("")}
            title="New File in Root"
          >
            + New
          </button>
          <button className={styles.refreshBtn} onClick={loadFiles} title="Refresh">
            &#8635;
          </button>
        </div>
      </div>

      {error && (
        <div className={styles.error} onClick={() => setError("")}>
          {error} <span>&times;</span>
        </div>
      )}

      <div className={styles.tree}>
        {loading ? (
          <div className={styles.empty}>Loading files...</div>
        ) : files.length === 0 ? (
          <div className={styles.empty}>No files yet. Create a board first.</div>
        ) : (
          <>
            {tree.map((node) => renderNode(node, 0))}
            {/* New file at root level */}
            {newFileDir === "" && (
              <div className={styles.newFileRow} style={{ paddingLeft: "8px" }}>
                <span className={styles.nodeIcon}>{"\u{1F4C4}"}</span>
                <input
                  className={styles.renameInput}
                  placeholder="filename.ext"
                  value={newFileName}
                  onChange={(e) => setNewFileName(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") submitNewFile();
                    if (e.key === "Escape") setNewFileDir(null);
                  }}
                  onBlur={() => { if (!newFileName.trim()) setNewFileDir(null); }}
                  autoFocus
                />
                <button className={styles.actionBtn} onClick={submitNewFile} title="Create">
                  &#10003;
                </button>
                <button className={styles.actionBtn} onClick={() => setNewFileDir(null)} title="Cancel">
                  &#10005;
                </button>
              </div>
            )}
          </>
        )}
      </div>

      {/* Delete confirmation dialog */}
      {confirmDelete && (
        <div className={styles.overlay}>
          <div className={styles.dialog}>
            <p>Are you sure you want to delete <strong>{confirmDelete}</strong>?</p>
            <div className={styles.dialogActions}>
              <button onClick={() => deleteItem(confirmDelete)}>Delete</button>
              <button onClick={() => setConfirmDelete(null)}>Cancel</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
