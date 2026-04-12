/**
 * CanvasFrame -- draggable, resizable rectangular region that groups entities.
 * Similar to frames in Figma/Miro. Positioned using graph coordinates.
 */

import { useState, useRef, useCallback, useEffect } from "react";
import styles from "./CanvasFrame.module.css";
import type { CanvasFrame as CanvasFrameType } from "../../store/ontologyStore";
import { useOntologyStore } from "../../store/ontologyStore";

const FRAME_COLORS = [
  { color: "rgba(79, 70, 229, 0.05)", border: "#c7d2fe" },   // indigo
  { color: "rgba(16, 185, 129, 0.05)", border: "#a7f3d0" },   // emerald
  { color: "rgba(245, 158, 11, 0.05)", border: "#fde68a" },    // amber
  { color: "rgba(239, 68, 68, 0.05)", border: "#fecaca" },     // red
  { color: "rgba(168, 85, 247, 0.05)", border: "#ddd6fe" },    // purple
  { color: "rgba(6, 182, 212, 0.05)", border: "#a5f3fc" },     // cyan
  { color: "rgba(107, 114, 128, 0.05)", border: "#d1d5db" },   // gray
  { color: "rgba(236, 72, 153, 0.05)", border: "#fbcfe8" },    // pink
];

interface Props {
  frame: CanvasFrameType;
  zoom: number;
  pan: { x: number; y: number };
  onUpdate: (id: string, updates: Partial<CanvasFrameType>) => void;
  onDelete: (id: string) => void;
}

export default function CanvasFrame({ frame, zoom, pan, onUpdate, onDelete }: Props) {
  const [editingLabel, setEditingLabel] = useState(false);
  const [label, setLabel] = useState(frame.label);
  const [showColors, setShowColors] = useState(false);
  const [dragging, setDragging] = useState(false);
  const [resizing, setResizing] = useState(false);
  const dragStart = useRef({ mx: 0, my: 0, fx: 0, fy: 0 });
  const resizeStart = useRef({ mx: 0, my: 0, w: 0, h: 0 });

  const store = useOntologyStore();

  // Convert graph coords to screen coords
  const screenX = frame.x * zoom + pan.x;
  const screenY = frame.y * zoom + pan.y;
  const scaledW = frame.w * zoom;
  const scaledH = frame.h * zoom;

  useEffect(() => { setLabel(frame.label); }, [frame.label]);

  const handleDragStart = useCallback((e: React.MouseEvent) => {
    if (editingLabel || resizing) return;
    e.preventDefault();
    e.stopPropagation();
    setDragging(true);
    dragStart.current = { mx: e.clientX, my: e.clientY, fx: frame.x, fy: frame.y };

    const handleMove = (me: MouseEvent) => {
      const dx = (me.clientX - dragStart.current.mx) / zoom;
      const dy = (me.clientY - dragStart.current.my) / zoom;
      onUpdate(frame.id, { x: dragStart.current.fx + dx, y: dragStart.current.fy + dy });
    };
    const handleUp = () => {
      setDragging(false);
      document.removeEventListener("mousemove", handleMove);
      document.removeEventListener("mouseup", handleUp);
    };
    document.addEventListener("mousemove", handleMove);
    document.addEventListener("mouseup", handleUp);
  }, [editingLabel, resizing, frame.id, frame.x, frame.y, zoom, onUpdate]);

  const handleResizeStart = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setResizing(true);
    resizeStart.current = { mx: e.clientX, my: e.clientY, w: frame.w, h: frame.h };

    const handleMove = (me: MouseEvent) => {
      const dw = (me.clientX - resizeStart.current.mx) / zoom;
      const dh = (me.clientY - resizeStart.current.my) / zoom;
      const newW = Math.max(120, resizeStart.current.w + dw);
      const newH = Math.max(80, resizeStart.current.h + dh);
      onUpdate(frame.id, { w: newW, h: newH });
    };
    const handleUp = () => {
      setResizing(false);
      document.removeEventListener("mousemove", handleMove);
      document.removeEventListener("mouseup", handleUp);
    };
    document.addEventListener("mousemove", handleMove);
    document.addEventListener("mouseup", handleUp);
  }, [frame.id, frame.w, frame.h, zoom, onUpdate]);

  const handleLabelBlur = () => {
    setEditingLabel(false);
    if (label !== frame.label) onUpdate(frame.id, { label });
  };

  /** Collect entities whose positions fall within this frame's bounds, then trigger TTL download. */
  const exportFrameAsTtl = useCallback(() => {
    const fx = frame.x, fy = frame.y, fx2 = frame.x + frame.w, fy2 = frame.y + frame.h;

    // Collect class IRIs inside the frame
    const insideClasses = store.classes.filter(
      (c) => c.x >= fx && c.y >= fy && c.x <= fx2 && c.y <= fy2
    );
    const insideIndividuals = store.individuals.filter(
      (i) => i.x >= fx && i.y >= fy && i.x <= fx2 && i.y <= fy2
    );
    const insideLiterals = store.literals.filter(
      (l) => l.x >= fx && l.y >= fy && l.x <= fx2 && l.y <= fy2
    );

    const insideIds = new Set([
      ...insideClasses.map((c) => c.iri),
      ...insideIndividuals.map((i) => i.iri),
      ...insideLiterals.map((l) => l.id),
    ]);

    // Collect properties connecting entities inside the frame
    const insideProps = store.properties.filter(
      (p) => insideIds.has(p.source_id) && insideIds.has(p.target_id)
    );

    // Build simple TTL
    const lines: string[] = [
      `@prefix owl: <http://www.w3.org/2002/07/owl#> .`,
      `@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .`,
      `@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .`,
      `@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .`,
      ``,
      `# Frame: ${frame.label}`,
      `# Exported ${insideClasses.length} classes, ${insideIndividuals.length} individuals, ${insideProps.length} properties`,
      ``,
    ];

    for (const c of insideClasses) {
      lines.push(`<${c.iri}> a owl:Class ;`);
      lines.push(`    rdfs:label "${c.label}" .`);
      lines.push(``);
    }
    for (const ind of insideIndividuals) {
      lines.push(`<${ind.iri}> a owl:NamedIndividual ;`);
      lines.push(`    rdfs:label "${ind.label}" .`);
      if (ind.class_iri) {
        lines.push(`<${ind.iri}> rdf:type <${ind.class_iri}> .`);
      }
      lines.push(``);
    }
    for (const p of insideProps) {
      lines.push(`<${p.source_id}> <${p.iri}> <${p.target_id}> .`);
    }

    const blob = new Blob([lines.join("\n")], { type: "text/turtle" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `${frame.label.replace(/\s+/g, "_")}_frame.ttl`;
    a.click();
    URL.revokeObjectURL(url);
  }, [frame, store.classes, store.individuals, store.literals, store.properties]);

  return (
    <div
      className={`${styles.frame} ${dragging ? styles.dragging : ""}`}
      style={{
        left: screenX,
        top: screenY,
        width: scaledW,
        height: scaledH,
        backgroundColor: frame.color,
        borderColor: frame.borderColor,
        ["--border-color" as any]: frame.borderColor,
      }}
      onMouseDown={handleDragStart}
      onContextMenu={(e) => { e.preventDefault(); setShowColors(!showColors); }}
    >
      {/* Label bar at top-left */}
      <div className={styles.labelBar} style={{ fontSize: `${Math.max(10, 12 * zoom)}px` }}>
        {editingLabel ? (
          <input
            className={styles.labelInput}
            value={label}
            onChange={(e) => setLabel(e.target.value)}
            onBlur={handleLabelBlur}
            onKeyDown={(e) => { if (e.key === "Enter") handleLabelBlur(); if (e.key === "Escape") { setLabel(frame.label); setEditingLabel(false); } }}
            autoFocus
            onClick={(e) => e.stopPropagation()}
            onMouseDown={(e) => e.stopPropagation()}
          />
        ) : (
          <span className={styles.labelText} onDoubleClick={(e) => { e.stopPropagation(); setEditingLabel(true); }}>
            {frame.label}
          </span>
        )}
      </div>

      {/* Hover toolbar */}
      <div className={styles.toolbar}>
        <button className={styles.toolBtn} title="Edit label" onClick={(e) => { e.stopPropagation(); setEditingLabel(true); }}>{"\u270E"}</button>
        <button className={styles.toolBtn} title="Change color" onClick={(e) => { e.stopPropagation(); setShowColors(!showColors); }}>{"\uD83C\uDFA8"}</button>
        <button className={styles.toolBtn} title="Export frame as TTL" onClick={(e) => { e.stopPropagation(); exportFrameAsTtl(); }}>{"\u2B07"}</button>
        <button className={`${styles.toolBtn} ${styles.toolBtnDanger}`} title="Delete frame" onClick={(e) => { e.stopPropagation(); onDelete(frame.id); }}>{"\u2716"}</button>
      </div>

      {/* Color picker */}
      {showColors && (
        <div className={styles.colorPickerWrap}>
          {FRAME_COLORS.map((fc, i) => (
            <button
              key={i}
              className={styles.colorSwatch}
              style={{ background: fc.border }}
              onClick={(e) => {
                e.stopPropagation();
                onUpdate(frame.id, { color: fc.color, borderColor: fc.border });
                setShowColors(false);
              }}
            />
          ))}
        </div>
      )}

      {/* Resize handle */}
      <div className={styles.resizeHandle} onMouseDown={handleResizeStart} />
    </div>
  );
}
