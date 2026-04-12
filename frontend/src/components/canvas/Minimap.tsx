/**
 * Minimap — small interactive overview of the Cytoscape graph.
 * Shows a thumbnail and a viewport rectangle that can be dragged.
 */

import { useEffect, useRef, useState, useCallback } from "react";
import styles from "./Minimap.module.css";

interface Props {
  cyRef: React.MutableRefObject<cytoscape.Core | null>;
}

const SIZE = 160;
const UPDATE_INTERVAL = 500;

export default function Minimap({ cyRef }: Props) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [viewRect, setViewRect] = useState({ x: 0, y: 0, w: 1, h: 1 });
  const [bounds, setBounds] = useState({ x1: 0, y1: 0, w: 1, h: 1 });
  const [collapsed, setCollapsed] = useState(false);

  const updateMinimap = useCallback(() => {
    const cy = cyRef.current;
    const canvas = canvasRef.current;
    if (!cy || !canvas || cy.nodes().length === 0) return;

    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    // Get bounding box of all elements
    const bb = cy.elements().boundingBox();
    const padding = 40;
    const bx = bb.x1 - padding;
    const by = bb.y1 - padding;
    const bw = bb.w + padding * 2;
    const bh = bb.h + padding * 2;
    setBounds({ x1: bx, y1: by, w: bw, h: bh });

    // Scale to fit minimap
    const scale = Math.min(SIZE / bw, SIZE / bh);
    const offX = (SIZE - bw * scale) / 2;
    const offY = (SIZE - bh * scale) / 2;

    ctx.clearRect(0, 0, SIZE, SIZE);

    // Draw edges
    cy.edges().forEach((edge) => {
      const src = edge.source().position();
      const tgt = edge.target().position();
      ctx.strokeStyle = "#cbd5e1";
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo((src.x - bx) * scale + offX, (src.y - by) * scale + offY);
      ctx.lineTo((tgt.x - bx) * scale + offX, (tgt.y - by) * scale + offY);
      ctx.stroke();
    });

    // Draw nodes
    cy.nodes().forEach((node) => {
      const pos = node.position();
      const x = (pos.x - bx) * scale + offX;
      const y = (pos.y - by) * scale + offY;
      const entityType = node.data("entityType");
      ctx.fillStyle = entityType === "individual" ? "#d97706" : "#4f46e5";
      ctx.beginPath();
      ctx.arc(x, y, Math.max(2, 4 * scale), 0, Math.PI * 2);
      ctx.fill();
    });

    // Calculate viewport rectangle
    const pan = cy.pan();
    const zoom = cy.zoom();
    const cw = cy.width();
    const ch = cy.height();
    const vx1 = (-pan.x / zoom);
    const vy1 = (-pan.y / zoom);
    const vw = cw / zoom;
    const vh = ch / zoom;
    setViewRect({
      x: (vx1 - bx) * scale + offX,
      y: (vy1 - by) * scale + offY,
      w: vw * scale,
      h: vh * scale,
    });
  }, [cyRef]);

  useEffect(() => {
    const timer = setInterval(updateMinimap, UPDATE_INTERVAL);
    updateMinimap();

    const cy = cyRef.current;
    if (cy) {
      cy.on("zoom pan", updateMinimap);
    }
    return () => {
      clearInterval(timer);
      if (cy) cy.off("zoom pan", updateMinimap as any);
    };
  }, [updateMinimap, cyRef]);

  // Click on minimap to navigate
  const handleClick = useCallback((e: React.MouseEvent<HTMLCanvasElement>) => {
    const cy = cyRef.current;
    if (!cy || bounds.w === 0) return;

    const rect = e.currentTarget.getBoundingClientRect();
    const mx = e.clientX - rect.left;
    const my = e.clientY - rect.top;

    const scale = Math.min(SIZE / bounds.w, SIZE / bounds.h);
    const offX = (SIZE - bounds.w * scale) / 2;
    const offY = (SIZE - bounds.h * scale) / 2;

    // Convert minimap coords to graph coords
    const gx = (mx - offX) / scale + bounds.x1;
    const gy = (my - offY) / scale + bounds.y1;

    cy.animate({
      center: { eles: cy.collection() },
      pan: { x: -gx * cy.zoom() + cy.width() / 2, y: -gy * cy.zoom() + cy.height() / 2 },
    }, { duration: 200 });
  }, [cyRef, bounds]);

  if (collapsed) {
    return (
      <div className={styles.collapsed} onClick={() => setCollapsed(false)} title="Show minimap">
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
          <rect x="1" y="1" width="14" height="14" rx="2" stroke="#64748b" strokeWidth="1.5" fill="none" />
          <rect x="3" y="3" width="5" height="4" rx="1" fill="#94a3b8" />
        </svg>
      </div>
    );
  }

  return (
    <div className={styles.container}>
      <div className={styles.header}>
        <span className={styles.title}>Overview</span>
        <button className={styles.closeBtn} onClick={() => setCollapsed(true)}>&minus;</button>
      </div>
      <div className={styles.canvasWrap}>
        <canvas ref={canvasRef} width={SIZE} height={SIZE} className={styles.canvas}
                onClick={handleClick} />
        {/* Viewport rectangle */}
        <div className={styles.viewRect} style={{
          left: Math.max(0, viewRect.x),
          top: Math.max(0, viewRect.y),
          width: Math.min(viewRect.w, SIZE),
          height: Math.min(viewRect.h, SIZE),
        }} />
      </div>
    </div>
  );
}
