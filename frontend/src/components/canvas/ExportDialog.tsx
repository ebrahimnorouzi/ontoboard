/**
 * ExportDialog — export graph as PNG/SVG with optional legend and prefixes.
 * Legend and prefixes render in the bottom-left, outside the graph bounding box,
 * to avoid overlapping graph content. Only prefixes with entities in the graph are shown.
 */
import { useState, useRef, useEffect } from "react";
import { useOntologyStore } from "../../store/ontologyStore";
import styles from "./ExportDialog.module.css";

const DEFAULT_PREFIXES: Record<string, string> = {
  "http://www.w3.org/2002/07/owl#": "owl",
  "http://www.w3.org/2000/01/rdf-schema#": "rdfs",
  "http://www.w3.org/1999/02/22-rdf-syntax-ns#": "rdf",
  "http://www.w3.org/2009/XMLSchema#": "xml",
  "http://www.w3.org/2004/02/skos/core#": "skos",
  "http://purl.org/dc/terms/": "dcterms",
  "http://purl.org/dc/elements/1.1/": "dc",
  "http://xmlns.com/foaf/0.1/": "foaf",
  "http://www.w3.org/ns/prov#": "prov",
  "http://purl.obolibrary.org/obo/": "obo",
  "http://schema.org/": "schema",
};

interface Props {
  cyRef: React.MutableRefObject<cytoscape.Core | null>;
  onClose: () => void;
}

/** Collect all IRIs from the graph and return only prefixes that are actually used */
function getUsedPrefixes(classes: { iri: string }[], properties: { iri: string }[], individuals: { iri: string }[]): [string, string][] {
  const allIris = [
    ...classes.map((c) => c.iri),
    ...properties.map((p) => p.iri),
    ...individuals.map((i) => i.iri),
  ];
  const used: [string, string][] = [];
  for (const [ns, prefix] of Object.entries(DEFAULT_PREFIXES)) {
    if (allIris.some((iri) => iri.startsWith(ns))) {
      used.push([ns, prefix]);
    }
  }
  // Detect custom prefixes (e.g. http://example.org/ontology#)
  for (const iri of allIris) {
    const hashIdx = iri.lastIndexOf("#");
    const slashIdx = iri.lastIndexOf("/");
    const sepIdx = Math.max(hashIdx, slashIdx);
    if (sepIdx > 0) {
      const ns = iri.slice(0, sepIdx + 1);
      if (!Object.keys(DEFAULT_PREFIXES).includes(ns) && !used.some(([u]) => u === ns)) {
        // Derive a short prefix name
        const match = ns.match(/\/([^/#]+)[#/]$/);
        const prefix = match ? match[1] : ns;
        used.push([ns, prefix]);
      }
    }
  }
  return used;
}

export default function ExportDialog({ cyRef, onClose }: Props) {
  const [format, setFormat] = useState<"png" | "svg">("png");
  const [includeLegend, setIncludeLegend] = useState(true);
  const [includePrefixes, setIncludePrefixes] = useState(true);
  const previewRef = useRef<HTMLImageElement>(null);
  const store = useOntologyStore();

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
      const BOX_PADDING = 14;
      const BOX_GAP = 12;
      const BOX_MARGIN = 20;

      // Get prefix colors from store for legend
      const prefixColorMap = new Map(store.prefixColors.map((pc) => [pc.prefix, pc.color]));

      // ── Legend items ──
      const nodeItems: { label: string; color: string; shape: "rect" | "diamond" | "ellipse" }[] = [
        { label: "Class", color: "#4f46e5", shape: "rect" },
        { label: "Individual", color: "#d97706", shape: "diamond" },
        { label: "Literal", color: "#16a34a", shape: "ellipse" },
      ];
      // Add prefix-colored entries
      for (const pc of store.prefixColors) {
        nodeItems.push({ label: `${pc.prefix}: classes`, color: pc.color, shape: "rect" });
      }
      const edgeItems = [
        { label: "SubClassOf", color: "#6366f1", dash: true, dotted: false },
        { label: "Object Property", color: "#10b981", dash: false, dotted: false },
        { label: "Data Property", color: "#f59e0b", dash: true, dotted: false },
        { label: "rdf:type", color: "#94a3b8", dash: true, dotted: true },
      ];

      // ── Dimensions ──
      const legendLineH = 24;
      const legendTitleH = 28;
      const legendBoxW = 220;
      const legendRows = nodeItems.length + edgeItems.length;
      const legendBoxH = legendTitleH + legendRows * legendLineH + BOX_PADDING * 2;

      // Only include used prefixes
      const usedPrefixes = getUsedPrefixes(store.classes, store.properties, store.individuals);
      const prefixLineH = 18;
      const prefixTitleH = 28;
      const prefixBoxW = 380;
      const prefixBoxH = prefixTitleH + usedPrefixes.length * prefixLineH + BOX_PADDING * 2;

      // Calculate overlay height
      const overlayH = BOX_MARGIN
        + (includeLegend ? legendBoxH + BOX_GAP : 0)
        + (includePrefixes && usedPrefixes.length > 0 ? prefixBoxH : 0)
        + BOX_MARGIN;

      // Canvas: graph on top, overlays below the graph bounding box
      const canvasW = Math.max(img.width, (includePrefixes ? prefixBoxW : legendBoxW) + BOX_MARGIN * 2);
      const canvasH = img.height + overlayH;

      const canvas = document.createElement("canvas");
      canvas.width = canvasW;
      canvas.height = canvasH;
      const ctx = canvas.getContext("2d")!;
      ctx.fillStyle = "#ffffff";
      ctx.fillRect(0, 0, canvasW, canvasH);
      ctx.drawImage(img, 0, 0);

      // Position overlays at bottom-left, below the graph
      let boxY = img.height + BOX_MARGIN;

      // ── Legend box ──
      if (includeLegend) {
        const boxX = BOX_MARGIN;
        drawBox(ctx, boxX, boxY, legendBoxW, legendBoxH);

        ctx.font = "bold 13px Inter, system-ui, sans-serif";
        ctx.fillStyle = "#0f172a";
        ctx.fillText("Legend", boxX + BOX_PADDING, boxY + BOX_PADDING + 14);

        let ly = boxY + BOX_PADDING + legendTitleH;

        // Separator line under title
        ctx.strokeStyle = "#e2e8f0";
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.moveTo(boxX + BOX_PADDING, ly - 4);
        ctx.lineTo(boxX + legendBoxW - BOX_PADDING, ly - 4);
        ctx.stroke();

        ctx.font = "12px Inter, system-ui, sans-serif";
        for (const it of nodeItems) {
          const ix = boxX + BOX_PADDING;
          ctx.fillStyle = it.color;
          if (it.shape === "rect") {
            // Rounded mini rectangle
            roundRect(ctx, ix, ly + 3, 16, 14, 3);
            ctx.fill();
            ctx.strokeStyle = it.color;
            ctx.lineWidth = 1.5;
            ctx.stroke();
          } else if (it.shape === "diamond") {
            ctx.beginPath();
            ctx.moveTo(ix + 8, ly + 2);
            ctx.lineTo(ix + 16, ly + 10);
            ctx.lineTo(ix + 8, ly + 18);
            ctx.lineTo(ix, ly + 10);
            ctx.fill();
          } else {
            ctx.beginPath();
            ctx.arc(ix + 8, ly + 10, 7, 0, Math.PI * 2);
            ctx.fill();
          }
          ctx.fillStyle = "#1e293b";
          ctx.fillText(it.label, ix + 24, ly + 14);
          ly += legendLineH;
        }

        // Separator before edges
        ctx.strokeStyle = "#f1f5f9";
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.moveTo(boxX + BOX_PADDING, ly - 2);
        ctx.lineTo(boxX + legendBoxW - BOX_PADDING, ly - 2);
        ctx.stroke();
        ly += 4;

        for (const it of edgeItems) {
          const ix = boxX + BOX_PADDING;
          ctx.strokeStyle = it.color;
          ctx.lineWidth = 2.5;
          ctx.setLineDash(it.dotted ? [2, 3] : it.dash ? [6, 4] : []);
          ctx.beginPath();
          ctx.moveTo(ix, ly + 10);
          ctx.lineTo(ix + 22, ly + 10);
          ctx.stroke();
          ctx.setLineDash([]);
          // Arrow head
          ctx.fillStyle = it.color;
          ctx.beginPath();
          ctx.moveTo(ix + 22, ly + 10);
          ctx.lineTo(ix + 16, ly + 5);
          ctx.lineTo(ix + 16, ly + 15);
          ctx.fill();
          ctx.fillStyle = "#1e293b";
          ctx.fillText(it.label, ix + 28, ly + 14);
          ly += legendLineH;
        }

        boxY += legendBoxH + BOX_GAP;
      }

      // ── Prefixes box ──
      if (includePrefixes && usedPrefixes.length > 0) {
        const boxX = BOX_MARGIN;
        drawBox(ctx, boxX, boxY, prefixBoxW, prefixBoxH);

        ctx.font = "bold 13px Inter, system-ui, sans-serif";
        ctx.fillStyle = "#0f172a";
        ctx.fillText("Prefixes", boxX + BOX_PADDING, boxY + BOX_PADDING + 14);

        // Separator
        ctx.strokeStyle = "#e2e8f0";
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.moveTo(boxX + BOX_PADDING, boxY + BOX_PADDING + 22);
        ctx.lineTo(boxX + prefixBoxW - BOX_PADDING, boxY + BOX_PADDING + 22);
        ctx.stroke();

        let py = boxY + BOX_PADDING + prefixTitleH;
        ctx.font = "11px 'JetBrains Mono', 'Cascadia Code', Consolas, monospace";
        for (const [ns, prefix] of usedPrefixes) {
          const pcColor = prefixColorMap.get(prefix);
          ctx.fillStyle = pcColor || "#6366f1";
          ctx.fillText(prefix, boxX + BOX_PADDING, py + 12);
          ctx.fillStyle = "#475569";
          const prefixWidth = ctx.measureText(prefix).width;
          ctx.fillText(`: <${ns}>`, boxX + BOX_PADDING + prefixWidth, py + 12);
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
  const r = 8;
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
  ctx.fillStyle = "rgba(255, 255, 255, 0.97)";
  ctx.fill();
  ctx.strokeStyle = "#cbd5e1";
  ctx.lineWidth = 1.5;
  ctx.stroke();
  // Subtle shadow effect
  ctx.shadowColor = "rgba(0, 0, 0, 0.05)";
  ctx.shadowBlur = 8;
  ctx.shadowOffsetY = 2;
  ctx.restore();
}

/** Draw a rounded rectangle path */
function roundRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number) {
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
}
