/**
 * OntologyCanvas — Cytoscape.js + real-time cursor sharing.
 *
 * - Edge drawing: click source node → click target node (uses refs, not state)
 * - Remote cursors: colored bubbles with username labels
 * - Entity editing: inline rename, color picker, type selector
 * - Layout algorithms: dagre (tree), cose-bilkent (force), grid
 */

import { useEffect, useRef, useState, useCallback } from "react";
import cytoscape from "cytoscape";
import dagre from "cytoscape-dagre";
import coseBilkent from "cytoscape-cose-bilkent";
import { useOntologyStore } from "../../store/ontologyStore";
import { useCollaboration, RemoteCursor } from "../../collab/useCollaboration";
import { useAuth } from "../../auth";
import styles from "./OntologyCanvas.module.css";

cytoscape.use(dagre);
cytoscape.use(coseBilkent);

const PREFIXES: Record<string, string> = {
  "http://www.w3.org/2002/07/owl#": "owl:", "http://www.w3.org/2000/01/rdf-schema#": "rdfs:",
  "http://www.w3.org/1999/02/22-rdf-syntax-ns#": "rdf:", "http://www.w3.org/2004/02/skos/core#": "skos:",
  "http://purl.org/dc/terms/": "dcterms:", "http://purl.org/dc/elements/1.1/": "dc:",
  "http://xmlns.com/foaf/0.1/": "foaf:", "http://www.w3.org/ns/prov#": "prov:",
  "http://purl.obolibrary.org/obo/": "obo:", "http://schema.org/": "schema:",
};
function compact(iri: string): string {
  for (const [ns, p] of Object.entries(PREFIXES)) if (iri.startsWith(ns)) return p + iri.slice(ns.length);
  return iri.includes("#") ? iri.split("#").pop() || iri : iri.split("/").pop() || iri;
}

interface Props { boardId: string }

export default function OntologyCanvas({ boardId }: Props) {
  const cyRef = useRef<cytoscape.Core | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const cursorsRef = useRef<HTMLDivElement>(null);
  const store = useOntologyStore();
  const { user } = useAuth();
  const { remoteCursors, broadcastCursor } = useCollaboration(
    boardId, user?.display_name || user?.username || "anonymous"
  );

  const [layoutName, setLayoutName] = useState("dagre");
  const [selected, setSelected] = useState<string | null>(null);
  const [editLabel, setEditLabel] = useState("");
  const [editColor, setEditColor] = useState("#4f46e5");
  const [edgeType, setEdgeType] = useState<"object" | "subClassOf" | "data" | "annotation">("object");

  // ── Edge drawing with REFS (not state — avoids stale closures) ──
  const edgeModeRef = useRef(false);
  const edgeSourceRef = useRef<string | null>(null);
  const [edgeModeUI, setEdgeModeUI] = useState(false);
  const [edgeSourceUI, setEdgeSourceUI] = useState<string | null>(null);

  const toggleEdgeMode = () => {
    edgeModeRef.current = !edgeModeRef.current;
    edgeSourceRef.current = null;
    setEdgeModeUI(edgeModeRef.current);
    setEdgeSourceUI(null);
  };

  // ── Cytoscape init ──────────────────────────────────────────
  useEffect(() => {
    if (!containerRef.current) return;
    const cy = cytoscape({
      container: containerRef.current,
      style: [
        { selector: "node[entityType='class']", style: {
          "background-color": "#eef2ff", "border-color": "#4f46e5", "border-width": 2,
          label: "data(displayLabel)", "text-valign": "center", "text-halign": "center",
          "font-size": "11px", "font-family": "Inter, sans-serif", "font-weight": 600,
          color: "#312e81", shape: "roundrectangle", width: "label", height: 36, padding: "12px",
          "text-wrap": "ellipsis", "text-max-width": "160px",
        }},
        { selector: "node[entityType='individual']", style: {
          "background-color": "#fef3c7", "border-color": "#d97706", "border-width": 2,
          label: "data(displayLabel)", "text-valign": "center", "text-halign": "center",
          "font-size": "10px", color: "#78350f", shape: "diamond", width: 50, height: 50,
        }},
        // Highlighted source node during edge drawing
        { selector: "node.edge-source", style: {
          "border-color": "#ef4444", "border-width": 4,
          "overlay-opacity": 0.12, "overlay-color": "#ef4444",
        }},
        { selector: "node:selected", style: {
          "border-color": "#0ea5e9", "border-width": 3,
        }},
        { selector: "edge[edgeType='subClassOf']", style: {
          "line-color": "#6366f1", "target-arrow-color": "#6366f1",
          "target-arrow-shape": "triangle-backcurve", "curve-style": "bezier",
          width: 2, "line-style": "dashed", "line-dash-pattern": [8, 4], "arrow-scale": 1.2,
        }},
        { selector: "edge[edgeType='objectProperty']", style: {
          "line-color": "#10b981", "target-arrow-color": "#10b981",
          "target-arrow-shape": "triangle", "curve-style": "bezier", width: 2,
          label: "data(displayLabel)", "font-size": "9px", color: "#064e3b",
          "text-rotation": "autorotate", "text-background-color": "#fff",
          "text-background-opacity": 0.9, "text-background-padding": "2px",
        }},
        { selector: "edge[edgeType='rdfType']", style: {
          "line-color": "#94a3b8", "target-arrow-color": "#94a3b8",
          "target-arrow-shape": "triangle", "curve-style": "bezier",
          width: 1.5, "line-style": "dotted",
        }},
        { selector: "edge[edgeType='dataProperty']", style: {
          "line-color": "#f59e0b", "target-arrow-color": "#f59e0b",
          "target-arrow-shape": "triangle", "curve-style": "bezier",
          width: 1.5, "line-style": "dashed", label: "data(displayLabel)",
          "font-size": "8px", color: "#92400e", "text-rotation": "autorotate",
        }},
        { selector: "edge[edgeType='annotationProperty']", style: {
          "line-color": "#a855f7", "target-arrow-color": "#a855f7",
          "target-arrow-shape": "triangle", "curve-style": "bezier",
          width: 1, "line-style": "dotted",
        }},
      ],
      layout: { name: "preset" },
      wheelSensitivity: 0.3, minZoom: 0.05, maxZoom: 4,
    });
    cyRef.current = cy;

    // ── Node tap (edge drawing uses refs) ─────────────────────
    cy.on("tap", "node", (evt) => {
      const nodeId = evt.target.id();
      const nodeLabel = evt.target.data("label") || "";
      const nodeType = evt.target.data("entityType") || "class";

      if (edgeModeRef.current) {
        if (edgeSourceRef.current) {
          // Complete edge
          const ts = Date.now();
          const iri = edgeType === "subClassOf" ? "rdfs:subClassOf" : `http://example.org/new#prop_${ts}`;
          const label = edgeType === "subClassOf" ? "subClassOf" : "relatedTo";
          store.addProperty({
            id: `edge_${ts}`, iri, label,
            source_id: edgeSourceRef.current, target_id: nodeId,
            property_type: edgeType === "subClassOf" ? "annotation" : edgeType,
          });
          // Remove highlight
          cy.getElementById(edgeSourceRef.current).removeClass("edge-source");
          edgeSourceRef.current = null;
          setEdgeSourceUI(null);
        } else {
          // Select source
          edgeSourceRef.current = nodeId;
          setEdgeSourceUI(nodeId);
          evt.target.addClass("edge-source");
        }
      } else {
        setSelected(nodeId);
        setEditLabel(nodeLabel);
        store.selectEntity({ iri: nodeId, type: nodeType, label: nodeLabel });
      }
    });

    // Pane tap — deselect
    cy.on("tap", (e) => {
      if (e.target === cy) {
        setSelected(null);
        store.selectEntity(null);
        if (edgeModeRef.current && edgeSourceRef.current) {
          cy.getElementById(edgeSourceRef.current).removeClass("edge-source");
          edgeSourceRef.current = null;
          setEdgeSourceUI(null);
        }
      }
    });

    // Drag → update position
    cy.on("dragfree", "node", (e) => {
      const p = e.target.position();
      store.updateClass(e.target.id(), { x: p.x, y: p.y });
    });

    // ── Mouse move → broadcast cursor ─────────────────────────
    cy.on("mousemove", (e) => {
      if (e.position) {
        broadcastCursor(e.position.x, e.position.y, false);
      }
    });
    cy.on("mousedown", (e) => {
      if (e.position) broadcastCursor(e.position.x, e.position.y, true);
    });
    cy.on("mouseup", (e) => {
      if (e.position) broadcastCursor(e.position.x, e.position.y, false);
    });

    return () => { cy.destroy(); cyRef.current = null; };
  }, []);

  // ── Sync store → Cytoscape ──────────────────────────────────
  useEffect(() => {
    const cy = cyRef.current;
    if (!cy) return;
    cy.batch(() => {
      cy.elements().remove();
      for (const c of store.classes) {
        cy.add({ group: "nodes", data: { id: c.iri, label: c.label,
          displayLabel: c.label || compact(c.iri), entityType: "class" },
          position: { x: c.x || 0, y: c.y || 0 } });
      }
      for (const i of store.individuals) {
        cy.add({ group: "nodes", data: { id: i.iri, label: i.label,
          displayLabel: i.label || compact(i.iri), entityType: "individual" },
          position: { x: i.x || 0, y: i.y || 0 } });
      }
      for (const p of store.properties) {
        if (!cy.getElementById(p.source_id).length || !cy.getElementById(p.target_id).length) continue;
        const isSub = p.iri === "rdfs:subClassOf";
        const isType = p.iri === "rdf:type";
        const isData = p.property_type === "data";
        const isAnn = p.property_type === "annotation" && !isSub;
        cy.add({ group: "edges", data: { id: p.id, source: p.source_id, target: p.target_id,
          displayLabel: isSub ? "" : compact(p.iri),
          edgeType: isSub ? "subClassOf" : isType ? "rdfType" : isData ? "dataProperty" : isAnn ? "annotationProperty" : "objectProperty" } });
      }
    });
    if (store.classes.some((c) => c.x === 0 && c.y === 0) && cy.nodes().length > 0) doLayout(layoutName);
  }, [store.classes, store.properties, store.individuals]);

  useEffect(() => { store.loadFromBackend(boardId); }, [boardId]);

  // ── Render remote cursors as overlay ────────────────────────
  useEffect(() => {
    if (!cursorsRef.current || !cyRef.current) return;
    const cy = cyRef.current;
    const container = cursorsRef.current;
    container.innerHTML = "";

    for (const cursor of remoteCursors) {
      // Convert graph position to rendered position
      const rendered = cy.pan();
      const zoom = cy.zoom();
      const screenX = cursor.x * zoom + rendered.x;
      const screenY = cursor.y * zoom + rendered.y;

      const el = document.createElement("div");
      el.className = styles.remoteCursor;
      el.style.left = `${screenX}px`;
      el.style.top = `${screenY}px`;
      el.style.borderColor = cursor.color;
      if (cursor.clicking) el.style.transform = "scale(1.3)";

      // Arrow SVG
      el.innerHTML = `
        <svg width="16" height="20" viewBox="0 0 16 20" style="position:absolute;top:-2px;left:-2px;">
          <path d="M0 0L16 12L8 12L4 20Z" fill="${cursor.color}" stroke="#fff" stroke-width="1"/>
        </svg>
        <span class="${styles.cursorLabel}" style="background:${cursor.color}">${cursor.name}</span>
      `;
      container.appendChild(el);
    }
  }, [remoteCursors]);

  const doLayout = useCallback((name: string) => {
    const cy = cyRef.current;
    if (!cy || !cy.nodes().length) return;
    const opts: any = {
      dagre: { name: "dagre", rankDir: "TB", rankSep: 80, nodeSep: 40, padding: 30 },
      "cose-bilkent": { name: "cose-bilkent", idealEdgeLength: 100, nodeRepulsion: 6000, padding: 20, animate: false },
      grid: { name: "grid", padding: 20, rows: Math.ceil(Math.sqrt(cy.nodes().length)) },
    };
    cy.layout(opts[name] || opts.dagre).run();
    cy.fit(undefined, 40);
  }, []);

  const rename = () => {
    if (!selected || !editLabel) return;
    store.updateClass(selected, { label: editLabel });
    store.setIndividuals(store.individuals.map((i) => i.iri === selected ? { ...i, label: editLabel } : i));
    // Update Cytoscape node label
    cyRef.current?.getElementById(selected).data("displayLabel", editLabel);
  };

  return (
    <div className={styles.container}>
      {/* ── Toolbar ─────────────────────────────────────────── */}
      <div className={styles.toolbar}>
        <div className={styles.group}>
          <button className={styles.btn} onClick={() => {
            const iri = `http://example.org/new#Class_${Date.now()}`;
            store.addClass({ id: iri, iri, label: "NewClass", x: 200 + Math.random() * 400, y: 100 + Math.random() * 300, w: 160, h: 60, color: "#4f46e5" });
          }}>+ Class</button>
          <button className={styles.btn} onClick={() => {
            const iri = `http://example.org/new#Ind_${Date.now()}`;
            store.setIndividuals([...store.individuals, { id: iri, iri, label: "NewIndividual", class_iri: "", x: 300, y: 400 }]);
          }}>+ Individual</button>
        </div>

        <div className={styles.group}>
          <select className={styles.edgeSelect} value={edgeType} onChange={(e) => setEdgeType(e.target.value as any)}>
            <option value="object">Object Property</option>
            <option value="subClassOf">SubClassOf</option>
            <option value="data">Data Property</option>
            <option value="annotation">Annotation</option>
          </select>
          <button className={`${styles.btn} ${edgeModeUI ? styles.active : ""}`} onClick={toggleEdgeMode}>
            {edgeModeUI ? (edgeSourceUI ? `\u2192 Click target` : `\u2022 Click source`) : "\u2194 Draw Edge"}
          </button>
        </div>

        <div className={styles.group}>
          <span className={styles.layoutLabel}>Layout:</span>
          {(["dagre", "cose-bilkent", "grid"] as const).map((l) => (
            <button key={l} className={`${styles.btn} ${layoutName === l ? styles.active : ""}`}
                    onClick={() => { setLayoutName(l); doLayout(l); }}>
              {l === "dagre" ? "Tree" : l === "cose-bilkent" ? "Force" : "Grid"}
            </button>
          ))}
          <button className={styles.btn} onClick={() => cyRef.current?.fit(undefined, 40)}>Fit</button>
        </div>

        <span className={styles.save}>
          {store.saving ? "Saving..." : store.lastSaved > 0 ? "\u2713" : ""}
        </span>
      </div>

      {/* ── Entity editor bar ──────────────────────────────── */}
      {selected && (
        <div className={styles.editor}>
          <input className={styles.nameInput} value={editLabel}
                 onChange={(e) => setEditLabel(e.target.value)}
                 onBlur={rename} onKeyDown={(e) => e.key === "Enter" && rename()} />
          <input type="color" className={styles.colorPick} value={editColor} onChange={(e) => {
            setEditColor(e.target.value);
            cyRef.current?.getElementById(selected).style("border-color", e.target.value);
            cyRef.current?.getElementById(selected).style("background-color", e.target.value + "15");
          }} />
          <span className={styles.iri}>{compact(selected)}</span>
          <button className={styles.btnSmall} onClick={() => {
            store.removeClass(selected);
            store.setIndividuals(store.individuals.filter((i) => i.iri !== selected));
            setSelected(null);
          }}>Delete</button>
        </div>
      )}

      {/* ── Legend ──────────────────────────────────────────── */}
      <div className={styles.legend}>
        <span><i className={styles.dot} style={{ background: "#4f46e5" }} /> Class</span>
        <span><i className={styles.dot} style={{ background: "#d97706" }} /> Individual</span>
        <span><i className={styles.line} style={{ borderColor: "#6366f1", borderStyle: "dashed" }} /> SubClassOf</span>
        <span><i className={styles.line} style={{ borderColor: "#10b981" }} /> ObjProp</span>
        <span><i className={styles.line} style={{ borderColor: "#f59e0b", borderStyle: "dashed" }} /> DataProp</span>
        <span><i className={styles.line} style={{ borderColor: "#a855f7", borderStyle: "dotted" }} /> AnnotProp</span>
      </div>

      {/* ── Remote cursors overlay ─────────────────────────── */}
      <div ref={cursorsRef} className={styles.cursorsLayer} />

      {/* ── Cytoscape canvas ───────────────────────────────── */}
      <div ref={containerRef} className={styles.canvas} />
    </div>
  );
}
