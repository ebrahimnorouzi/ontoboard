/**
 * ExportDialog — export graph as PNG/SVG with optional legend and prefixes.
 * Legend and prefixes render as two separate, tidy boxes in the upper-right.
 */
import { useState, useRef, useEffect } from "react";
import styles from "./ExportDialog.module.css";

const PREFIXES: Record<string, string> = {
  "http://www.w3.org/2002/07/owl#": "owl:",
  "http://www.w3.org/2000/01/rdf-schema#": "rdfs:",
  "http://www.w3.org/1999/02/22-rdf-syntax-ns#": "rdf:",
  "http://www.w3.org/2004/02/skos/core#": "skos:",
  "http://purl.org/dc/terms/": "dcterms:",
  "http://purl.org/dc/elements/1.1/": "dc:",
  "http://xmlns.com/foaf/0.1/": "foaf:",
  "http://www.w3.org/ns/prov#": "prov:",
  "http://purl.obolibrary.org/obo/": "obo:",
  "http://schema.org/": "schema:",
};

interface Props {
  cyRef: React.MutableRefObject<cytoscape.Core | null>;
  onClose: () => void;
}

export default function ExportDialog({ cyRef, onClose }: Props) {
  const [format, setFormat] = useState<"png" | "svg">("png");
  const [includeLegend, setIncludeLegend] = useState(true);
  const [includePrefixes, setIncludePrefixes] = useState(true);
  const previewRef = useRef<HTMLImageElement>(null);

  useEffect(() => {
    const cy = cyRef.current;
    if (!cy || !previewRef.current) return;
    previewRef.current.src = cy.png({ scale: 1, bg: "#ffffff", full: true });
  }, [cyRef]);

  const handleExport = () => {
    const cy = cyRef.current;
    if (!cy) return;

    if (format === "png") {
      exportPng(cy);
    } else {
      exportSvg(cy);
    }
    onClose();
  };

  const exportPng = (cy: cytoscape.Core) => {
    const scale = 2;
    const graphDataUrl = cy.png({ scale, bg: "#ffffff", full: true });
    const img = new Image();
    img.onload = () => {
      // Measure overlay boxes first to determine canvas size
      const BOX_PADDING = 12;
      const BOX_GAP = 10;
      const BOX_MARGIN = 16;

      // Measure legend box
      const legendItems = [
        { label: "Class", color: "#4f46e5", shape: "rect" as const },
        { label: "Individual", color: "#d97706", shape: "diamond" as const },
        { label: "Literal", color: "#16a34a", shape: "ellipse" as const },
      ];
      const edgeItems = [
        { label: "SubClassOf", color: "#6366f1", dash: true },
        { label: "ObjProp", color: "#10b981", dash: false },
        { label: "DataProp", color: "#f59e0b", dash: true },
        { label: "rdf:type", color: "#94a3b8", dash: true },
      ];

      // Calculate legend box dimensions
      const legendLineH = 20;
      const legendTitleH = 22;
      const legendBoxW = 200;
      const legendRows = legendItems.length + edgeItems.length;
      const legendBoxH = legendTitleH + legendRows * legendLineH + BOX_PADDING * 2;

      // Calculate prefix box dimensions
      const prefixEntries = Object.entries(PREFIXES);
      const prefixLineH = 16;
      const prefixTitleH = 22;
      const prefixBoxW = 360;
      const prefixBoxH = prefixTitleH + prefixEntries.length * prefixLineH + BOX_PADDING * 2;

      // Build right-side overlay width
      const overlayW = Math.max(
        includeLegend ? legendBoxW : 0,
        includePrefixes ? prefixBoxW : 0,
      );

      // Canvas dimensions — ensure enough space for graph + overlay boxes
      const canvasW = Math.max(img.width, overlayW + BOX_MARGIN * 2 + 200);
      const canvasH = Math.max(
        img.height,
        BOX_MARGIN + (includeLegend ? legendBoxH + BOX_GAP : 0) + (includePrefixes ? prefixBoxH + BOX_GAP : 0) + BOX_MARGIN,
      );

      const canvas = document.createElement("canvas");
      canvas.width = canvasW;
      canvas.height = canvasH;
      const ctx = canvas.getContext("2d")!;
      ctx.fillStyle = "#ffffff";
      ctx.fillRect(0, 0, canvasW, canvasH);
      ctx.drawImage(img, 0, 0);

      let boxY = BOX_MARGIN;

      // ── Legend box (upper right) ──
      if (includeLegend) {
        const boxX = canvasW - legendBoxW - BOX_MARGIN;
        drawBox(ctx, boxX, boxY, legendBoxW, legendBoxH);

        // Title
        ctx.font = "bold 12px Inter, sans-serif";
        ctx.fillStyle = "#1e293b";
        ctx.fillText("Legend", boxX + BOX_PADDING, boxY + BOX_PADDING + 12);

        let ly = boxY + BOX_PADDING + legendTitleH;

        // Node items
        ctx.font = "11px Inter, sans-serif";
        for (const it of legendItems) {
          const ix = boxX + BOX_PADDING;
          ctx.fillStyle = it.color;
          if (it.shape === "rect") {
            ctx.fillRect(ix, ly + 2, 14, 14);
          } else if (it.shape === "diamond") {
            ctx.beginPath();
            ctx.moveTo(ix + 7, ly + 1);
            ctx.lineTo(ix + 14, ly + 9);
            ctx.lineTo(ix + 7, ly + 17);
            ctx.lineTo(ix, ly + 9);
            ctx.fill();
          } else {
            ctx.beginPath();
            ctx.arc(ix + 7, ly + 9, 6, 0, Math.PI * 2);
            ctx.fill();
          }
          ctx.fillStyle = "#334155";
          ctx.fillText(it.label, ix + 22, ly + 13);
          ly += legendLineH;
        }

        // Edge items
        for (const it of edgeItems) {
          const ix = boxX + BOX_PADDING;
          ctx.strokeStyle = it.color;
          ctx.lineWidth = 2;
          ctx.setLineDash(it.dash ? [4, 3] : []);
          ctx.beginPath();
          ctx.moveTo(ix, ly + 9);
          ctx.lineTo(ix + 20, ly + 9);
          ctx.stroke();
          ctx.setLineDash([]);
          // Arrow head
          ctx.fillStyle = it.color;
          ctx.beginPath();
          ctx.moveTo(ix + 20, ly + 9);
          ctx.lineTo(ix + 15, ly + 5);
          ctx.lineTo(ix + 15, ly + 13);
          ctx.fill();
          ctx.fillStyle = "#334155";
          ctx.fillText(it.label, ix + 26, ly + 13);
          ly += legendLineH;
        }

        boxY += legendBoxH + BOX_GAP;
      }

      // ── Prefixes box (upper right, below legend) ──
      if (includePrefixes) {
        const boxX = canvasW - prefixBoxW - BOX_MARGIN;
        drawBox(ctx, boxX, boxY, prefixBoxW, prefixBoxH);

        // Title
        ctx.font = "bold 12px Inter, sans-serif";
        ctx.fillStyle = "#1e293b";
        ctx.fillText("Prefixes", boxX + BOX_PADDING, boxY + BOX_PADDING + 12);

        let py = boxY + BOX_PADDING + prefixTitleH;
        ctx.font = "10px Cascadia Code, Consolas, monospace";
        for (const [ns, prefix] of prefixEntries) {
          ctx.fillStyle = "#6366f1";
          ctx.fillText(prefix.replace(":", ""), boxX + BOX_PADDING, py + 10);
          ctx.fillStyle = "#475569";
          const prefixWidth = ctx.measureText(prefix.replace(":", "")).width;
          ctx.fillText(`: <${ns}>`, boxX + BOX_PADDING + prefixWidth, py + 10);
          py += prefixLineH;
        }
      }

      const a = document.createElement("a");
      a.href = canvas.toDataURL("image/png");
      a.download = "ontology-graph.png";
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
    };
    img.src = graphDataUrl;
  };

  const exportSvg = (cy: cytoscape.Core) => {
    const svgContent = (cy as any).svg?.({ scale: 1, full: true, bg: "#fff" }) || cy.png({ scale: 2, bg: "#fff", full: true });
    const blob = new Blob([svgContent], { type: "image/svg+xml" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = "ontology-graph.svg";
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  };

  return (
    <div className={styles.overlay} onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className={styles.dialog}>
        <div className={styles.header}>
          <h3 className={styles.title}>Export Graph Image</h3>
          <button className={styles.closeBtn} onClick={onClose}>&times;</button>
        </div>
        <div className={styles.preview}>
          <img ref={previewRef} alt="Graph preview" className={styles.previewImg} />
        </div>
        <div className={styles.options}>
          <label className={styles.option}>
            <input type="checkbox" checked={includeLegend} onChange={(e) => setIncludeLegend(e.target.checked)} />
            Include Legend
          </label>
          <label className={styles.option}>
            <input type="checkbox" checked={includePrefixes} onChange={(e) => setIncludePrefixes(e.target.checked)} />
            Include Prefixes
          </label>
          <div className={styles.formatRow}>
            <label className={styles.option}>
              <input type="radio" name="fmt" checked={format === "png"} onChange={() => setFormat("png")} /> PNG
            </label>
            <label className={styles.option}>
              <input type="radio" name="fmt" checked={format === "svg"} onChange={() => setFormat("svg")} /> SVG
            </label>
          </div>
        </div>
        <div className={styles.actions}>
          <button className={styles.cancelBtn} onClick={onClose}>Cancel</button>
          <button className={styles.exportBtn} onClick={handleExport}>Export</button>
        </div>
      </div>
    </div>
  );
}

/** Draw a rounded box with border and semi-transparent white background */
function drawBox(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number) {
  const r = 6;
  ctx.save();
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.lineTo(x + w - r, y);
  ctx.quadraticCurveTo(x + w, y, x + w, y + r);
  ctx.lineTo(x + w, y + h - r);
  ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
  ctx.lineTo(x + r, y + h);
  ctx.quadraticCurveTo(x, y + h, x, y + h - r);
  ctx.lineTo(x, y + r);
  ctx.quadraticCurveTo(x, y, x + r, y);
  ctx.closePath();
  ctx.fillStyle = "rgba(255, 255, 255, 0.95)";
  ctx.fill();
  ctx.strokeStyle = "#cbd5e1";
  ctx.lineWidth = 1;
  ctx.stroke();
  ctx.restore();
}
