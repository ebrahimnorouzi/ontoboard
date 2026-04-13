/**
 * CommentsPanel — Threaded comments for board and entity-level discussions.
 *
 * Features:
 * - Board-level and entity-level comments
 * - Threaded replies
 * - @mention support
 * - Timestamps with relative time
 * - Delete own comments
 * - Visual distinction from other components (chat-bubble style)
 */

import { useState, useEffect, useCallback, useRef } from "react";
import { apiJson } from "../../api";
import { useAuth } from "../../auth";
import { useOntologyStore } from "../../store/ontologyStore";
import styles from "./CommentsPanel.module.css";

interface CommentData {
  id: number;
  board_id: number;
  entity_iri: string | null;
  user_id: number;
  username: string;
  text: string;
  created_at: string;
  parent_id: number | null;
  replies: CommentData[];
}

interface Props {
  boardId: string;
}

function relativeTime(dateStr: string): string {
  const now = Date.now();
  const then = new Date(dateStr).getTime();
  const diff = Math.floor((now - then) / 1000);
  if (diff < 60) return "just now";
  if (diff < 3600) return `${Math.floor(diff / 60)}m ago`;
  if (diff < 86400) return `${Math.floor(diff / 3600)}h ago`;
  if (diff < 604800) return `${Math.floor(diff / 86400)}d ago`;
  return new Date(dateStr).toLocaleDateString();
}

const AVATAR_COLORS = ["#6366f1", "#0ea5e9", "#10b981", "#f59e0b", "#ef4444", "#8b5cf6", "#ec4899", "#14b8a6"];
function avatarColor(username: string): string {
  let hash = 0;
  for (let i = 0; i < username.length; i++) hash = (hash << 5) - hash + username.charCodeAt(i);
  return AVATAR_COLORS[Math.abs(hash) % AVATAR_COLORS.length];
}

export default function CommentsPanel({ boardId }: Props) {
  const { user } = useAuth();
  const selectedEntity = useOntologyStore((s) => s.selectedEntity);
  const [comments, setComments] = useState<CommentData[]>([]);
  const [loading, setLoading] = useState(true);
  const [newText, setNewText] = useState("");
  const [replyTo, setReplyTo] = useState<{ id: number; username: string } | null>(null);
  const [filter, setFilter] = useState<"all" | "entity">("all");
  const [submitting, setSubmitting] = useState(false);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  const entityIri = selectedEntity?.iri || null;
  const entityLabel = selectedEntity?.label || null;

  const loadComments = useCallback(async () => {
    try {
      const params = filter === "entity" && entityIri ? `?entity_iri=${encodeURIComponent(entityIri)}` : "";
      const data = await apiJson<CommentData[]>(`/api/comments/${boardId}${params}`);
      setComments(data);
    } catch {
      setComments([]);
    } finally {
      setLoading(false);
    }
  }, [boardId, filter, entityIri]);

  useEffect(() => { loadComments(); }, [loadComments]);

  // Auto-refresh every 15s
  useEffect(() => {
    const interval = setInterval(loadComments, 15000);
    return () => clearInterval(interval);
  }, [loadComments]);

  const handleSubmit = useCallback(async () => {
    if (!newText.trim() || submitting) return;
    setSubmitting(true);
    try {
      await apiJson(`/api/comments/${boardId}`, {
        method: "POST",
        body: JSON.stringify({
          text: newText.trim(),
          entity_iri: filter === "entity" ? entityIri : null,
          parent_id: replyTo?.id || null,
        }),
      });
      setNewText("");
      setReplyTo(null);
      await loadComments();
    } catch {}
    finally { setSubmitting(false); }
  }, [boardId, newText, entityIri, filter, replyTo, loadComments, submitting]);

  const handleDelete = useCallback(async (commentId: number) => {
    try {
      await apiJson(`/api/comments/${boardId}/${commentId}`, { method: "DELETE" });
      await loadComments();
    } catch {}
  }, [boardId, loadComments]);

  const startReply = useCallback((id: number, username: string) => {
    setReplyTo({ id, username });
    setNewText(`@${username} `);
    inputRef.current?.focus();
  }, []);

  const totalCount = comments.reduce((acc, c) => acc + 1 + c.replies.length, 0);

  return (
    <div className={styles.container}>
      {/* Header with filter */}
      <div className={styles.header}>
        <h3 className={styles.title}>Comments ({totalCount})</h3>
        <div className={styles.filterGroup}>
          <button className={`${styles.filterBtn} ${filter === "all" ? styles.filterActive : ""}`}
            onClick={() => setFilter("all")}>All</button>
          <button className={`${styles.filterBtn} ${filter === "entity" ? styles.filterActive : ""}`}
            onClick={() => setFilter("entity")}
            disabled={!entityIri}
            title={entityIri ? `Comments on ${entityLabel || entityIri}` : "Select an entity first"}>
            {entityLabel ? entityLabel.slice(0, 15) : "Entity"}
          </button>
        </div>
      </div>

      {/* Comment list */}
      <div className={styles.list}>
        {loading ? (
          <div className={styles.empty}>Loading comments...</div>
        ) : comments.length === 0 ? (
          <div className={styles.empty}>
            No comments yet. Start a discussion below.
          </div>
        ) : (
          comments.map((c) => (
            <CommentThread
              key={c.id}
              comment={c}
              currentUserId={user?.id}
              onDelete={handleDelete}
              onReply={startReply}
              depth={0}
            />
          ))
        )}
      </div>

      {/* Input area */}
      <div className={styles.inputArea}>
        {replyTo && (
          <div className={styles.replyBanner}>
            Replying to <strong>{replyTo.username}</strong>
            <button className={styles.cancelReply} onClick={() => { setReplyTo(null); setNewText(""); }}>&times;</button>
          </div>
        )}
        {filter === "entity" && entityIri && (
          <div className={styles.entityBanner}>
            Commenting on: <strong>{entityLabel || entityIri.split("#").pop()}</strong>
          </div>
        )}
        <div className={styles.inputRow}>
          <textarea
            ref={inputRef}
            className={styles.textarea}
            value={newText}
            onChange={(e) => setNewText(e.target.value)}
            placeholder="Write a comment... (use @username to mention)"
            rows={2}
            onKeyDown={(e) => {
              if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) {
                e.preventDefault();
                handleSubmit();
              }
            }}
          />
          <button className={styles.sendBtn} onClick={handleSubmit}
            disabled={!newText.trim() || submitting}>
            {submitting ? "..." : "Send"}
          </button>
        </div>
        <div className={styles.inputHint}>Ctrl+Enter to send</div>
      </div>
    </div>
  );
}


function CommentThread({ comment, currentUserId, onDelete, onReply, depth }: {
  comment: CommentData;
  currentUserId?: number;
  onDelete: (id: number) => void;
  onReply: (id: number, username: string) => void;
  depth: number;
}) {
  const isOwn = currentUserId === comment.user_id;
  const color = avatarColor(comment.username);

  return (
    <>
      <div className={`${styles.comment} ${isOwn ? styles.commentOwn : ""}`}
        style={{ marginLeft: depth > 0 ? `${Math.min(depth, 3) * 16}px` : undefined }}>
        {depth > 0 && <div className={styles.replyLine} style={{ borderColor: color }} />}
        <div className={styles.avatar} style={{ background: color }}>
          {comment.username.charAt(0).toUpperCase()}
        </div>
        <div className={styles.bubble}>
          <div className={styles.meta}>
            <span className={styles.username} style={{ color }}>{comment.username}</span>
            <span className={styles.time} title={new Date(comment.created_at).toLocaleString()}>
              {relativeTime(comment.created_at)}
            </span>
            {comment.entity_iri && depth === 0 && (
              <span className={styles.entityTag}>
                {comment.entity_iri.split("#").pop()?.split("/").pop()}
              </span>
            )}
          </div>
          <div className={styles.text}>{formatText(comment.text)}</div>
          <div className={styles.actions}>
            <button className={styles.actionBtn} onClick={() => onReply(comment.id, comment.username)}>
              Reply
            </button>
            {isOwn && (
              <button className={`${styles.actionBtn} ${styles.deleteAction}`}
                onClick={() => onDelete(comment.id)}>
                Delete
              </button>
            )}
          </div>
        </div>
      </div>
      {comment.replies.map((reply) => (
        <CommentThread
          key={reply.id}
          comment={reply}
          currentUserId={currentUserId}
          onDelete={onDelete}
          onReply={onReply}
          depth={depth + 1}
        />
      ))}
    </>
  );
}


function formatText(text: string): JSX.Element {
  // Highlight @mentions
  const parts = text.split(/(@\w+)/g);
  return (
    <>
      {parts.map((part, i) =>
        part.startsWith("@") ? (
          <span key={i} className={styles.mention}>{part}</span>
        ) : (
          <span key={i}>{part}</span>
        )
      )}
    </>
  );
}
