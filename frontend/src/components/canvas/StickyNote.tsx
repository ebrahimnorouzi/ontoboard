/**
 * StickyNote — draggable, resizable, editable, colored note overlay on canvas.
 * Positioned using Cytoscape graph coordinates converted to screen.
 */

import { useState, useRef, useCallback, useEffect } from "react";
import styles from "./StickyNote.module.css";
import type { StickyNote as StickyNoteType } from "../../store/ontologyStore";

const COLORS = [
  "#fef3c7", // yellow
  "#fce7f3", // pink
  "#dbeafe", // blue
  "#d1fae5", // green
  "#ede9fe", // purple
  "#fee2e2", // red
  "#f3f4f6", // gray
  "#ffedd5", // orange
];

interface Props {
  note: StickyNoteType;
  zoom: number;
  pan: { x: number; y: number };
  onUpdate: (id: string, updates: Partial<StickyNoteType>) => void;
  onDelete: (id: string) => void;
}

export default function StickyNote({ note, zoom, pan, onUpdate, onDelete }: Props) {
  const [editing, setEditing] = useState(false);
  const [text, setText] = useState(note.text);
  const [showColors, setShowColors] = useState(false);
  const [dragging, setDragging] = useState(false);
  const [resizing, setResizing] = useState(false);
  const dragStart = useRef({ mx: 0, my: 0, nx: 0, ny: 0 });
  const resizeStart = useRef({ mx: 0, my: 0, w: 0, h: 0 });
  const noteRef = useRef<HTMLDivElement>(null);

  // Convert graph coords to screen coords
  const screenX = note.x * zoom + pan.x;
  const screenY = note.y * zoom + pan.y;
  const scaledW = note.w * zoom;
  const scaledH = note.h * zoom;

  useEffect(() => { setText(note.text); }, [note.text]);

  const handleDragStart = useCallback((e: React.MouseEvent) => {
    if (editing || resizing) return;
    e.preventDefault();
    e.stopPropagation();
    setDragging(true);
    dragStart.current = { mx: e.clientX, my: e.clientY, nx: note.x, ny: note.y };

    const handleMove = (me: MouseEvent) => {
      const dx = (me.clientX - dragStart.current.mx) / zoom;
      const dy = (me.clientY - dragStart.current.my) / zoom;
      onUpdate(note.id, { x: dragStart.current.nx + dx, y: dragStart.current.ny + dy });
    };
    const handleUp = () => {
      setDragging(false);
      document.removeEventListener("mousemove", handleMove);
      document.removeEventListener("mouseup", handleUp);
    };
    document.addEventListener("mousemove", handleMove);
    document.addEventListener("mouseup", handleUp);
  }, [editing, resizing, note.id, note.x, note.y, zoom, onUpdate]);

  const handleResizeStart = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setResizing(true);
    resizeStart.current = { mx: e.clientX, my: e.clientY, w: note.w, h: note.h };

    const handleMove = (me: MouseEvent) => {
      const dw = (me.clientX - resizeStart.current.mx) / zoom;
      const dh = (me.clientY - resizeStart.current.my) / zoom;
      const newW = Math.max(100, resizeStart.current.w + dw);
      const newH = Math.max(60, resizeStart.current.h + dh);
      onUpdate(note.id, { w: newW, h: newH });
    };
    const handleUp = () => {
      setResizing(false);
      document.removeEventListener("mousemove", handleMove);
      document.removeEventListener("mouseup", handleUp);
    };
    document.addEventListener("mousemove", handleMove);
    document.addEventListener("mouseup", handleUp);
  }, [note.id, note.w, note.h, zoom, onUpdate]);

  const handleDoubleClick = () => {
    setEditing(true);
    setShowColors(false);
  };

  const handleBlur = () => {
    setEditing(false);
    if (text !== note.text) onUpdate(note.id, { text });
  };

  const handleContextMenu = (e: React.MouseEvent) => {
    e.preventDefault();
    setShowColors(!showColors);
  };

  return (
    <div
      ref={noteRef}
      className={`${styles.sticky} ${dragging ? styles.dragging : ""}`}
      style={{
        left: screenX,
        top: screenY,
        width: scaledW,
        minHeight: scaledH,
        backgroundColor: note.color,
        fontSize: `${note.fontSize * zoom}px`,
      }}
      onMouseDown={handleDragStart}
      onDoubleClick={handleDoubleClick}
      onContextMenu={handleContextMenu}
    >
      {/* Delete button */}
      <button className={styles.deleteBtn} onClick={(e) => { e.stopPropagation(); onDelete(note.id); }}
              title="Delete note">&times;</button>

      {/* Color picker */}
      {showColors && (
        <div className={styles.colorPicker}>
          {COLORS.map((c) => (
            <button key={c} className={styles.colorSwatch} style={{ background: c }}
                    onClick={(e) => { e.stopPropagation(); onUpdate(note.id, { color: c }); setShowColors(false); }} />
          ))}
        </div>
      )}

      {editing ? (
        <textarea
          className={styles.textArea}
          value={text}
          onChange={(e) => setText(e.target.value)}
          onBlur={handleBlur}
          onKeyDown={(e) => { if (e.key === "Escape") { e.preventDefault(); handleBlur(); } }}
          autoFocus
          style={{ fontSize: `${note.fontSize * zoom}px` }}
        />
      ) : (
        <div className={styles.textDisplay}>
          {note.text || "Double-click to edit..."}
        </div>
      )}

      {/* Resize handle (bottom-right corner) */}
      <div className={styles.resizeHandle} onMouseDown={handleResizeStart} />
    </div>
  );
}
