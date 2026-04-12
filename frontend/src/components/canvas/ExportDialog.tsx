/**
 * ExportDialog — export graph as PNG/SVG with optional legend and prefixes.
 */
import { useState, useRef, useEffect } from "react";
import styles from "./ExportDialog.module.css";

const PREFIXES: Record<string, string> = {
  "http://www.w3.org/2002/07/owl#": "owl:", "http://www.w3.org/2000/01/rdf-schema#": "rdfs:",
  "http://www.w3.org/1999/02/22-rdf-syntax-ns#": "rdf:", "http://www.w3.org/2004/02/skos/core#": "skos:",
  "http://purl.org/dc/terms/": "dcterms:", "http://xmlns.com/foaf/0.1/": "foaf:",
  "http://www.w3.org/ns/prov#": "prov:", "http://purl.obolibrary.org/obo/": "obo:",
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
      const legendH = includeLegend ? 60 : 0;
      const prefixH = includePrefixes ? 50 : 0;
      const totalH = img.height + legendH + prefixH + 20;
      const canvas = document.createElement("canvas");
      canvas.width = Math.max(img.width, 600);
      canvas.height = totalH;
      const ctx = canvas.getContext("2d")!;
      ctx.fillStyle = "#ffffff";
      ctx.fillRect(0, 0, canvas.width, canvas.height);
      ctx.drawImage(img, 0, 0);

      let y = img.height + 10;

      if (includeLegend) {
        ctx.font = "bold 11px Inter, sans-serif";
        ctx.fillStyle = "#64748b";
        ctx.fillText("Legend:", 10, y + 12);
        const items = [
          { label: "Class", color: "#4f46e5", shape: "rect" },
          { label: "Individual", color: "#d97706", shape: "diamond" },
          { label: "Literal", color: "#16a34a", shape: "ellipse" },
        ];
        let lx = 70;
        ctx.font = "11px Inter, sans-serif";
        for (const it of items) {
          ctx.fillStyle = it.color;
          if (it.shape === "rect") { ctx.fillRect(lx, y + 3, 12, 12); }
          else if (it.shape === "diamond") { ctx.beginPath(); ctx.moveTo(lx + 6, y + 2); ctx.lineTo(lx + 12, y + 9); ctx.lineTo(lx + 6, y + 16); ctx.lineTo(lx, y + 9); ctx.fill(); }
          else { ctx.beginPath(); ctx.arc(lx + 6, y + 9, 6, 0, Math.PI * 2); ctx.fill(); }
          ctx.fillStyle = "#334155";
          ctx.fillText(it.label, lx + 16, y + 13);
          lx += ctx.measureText(it.label).width + 36;
        }
        const edgeItems = [
          { label: "SubClassOf", color: "#6366f1", dash: true },
          { label: "ObjProp", color: "#10b981", dash: false },
          { label: "DataProp", color: "#f59e0b", dash: true },
          { label: "rdf:type", color: "#94a3b8", dash: true },
        ];
        lx += 10;
        for (const it of edgeItems) {
          ctx.strokeStyle = it.color;
          ctx.lineWidth = 2;
          ctx.setLineDash(it.dash ? [4, 3] : []);
          ctx.beginPath();
          ctx.moveTo(lx, y + 9);
          ctx.lineTo(lx + 20, y + 9);
          ctx.stroke();
          ctx.setLineDash([]);
          ctx.fillStyle = "#334155";
          ctx.fillText(it.label, lx + 24, y + 13);
          lx += ctx.measureText(it.label).width + 44;
        }
        y += legendH;
      }

      if (includePrefixes) {
        ctx.font = "bold 10px Inter, sans-serif";
        ctx.fillStyle = "#64748b";
        ctx.fillText("Prefixes:", 10, y + 12);
        ctx.font = "10px Cascadia Code, monospace";
        ctx.fillStyle = "#475569";
        let px = 70;
        for (const [ns, prefix] of Object.entries(PREFIXES)) {
          const text = `${prefix} <${ns}>`;
          if (px + ctx.measureText(text).width > canvas.width - 10) { px = 70; y += 14; }
          ctx.fillText(text, px, y + 12);
          px += ctx.measureText(text).width + 16;
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
