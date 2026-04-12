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
import edgehandles from "cytoscape-edgehandles";
import { useOntologyStore } from "../../store/ontologyStore";
import { useCollaboration } from "../../collab/useCollaboration";
import { useAuth } from "../../auth";
import EditPopup, { EditData, NodeEditData, EdgeEditData } from "./EditPopup";
import StickyNoteComponent from "./StickyNote";
import Minimap from "./Minimap";
import styles from "./OntologyCanvas.module.css";

cytoscape.use(dagre);
cytoscape.use(coseBilkent);
cytoscape.use(edgehandles);

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
  const selected = store.selectedEntity?.iri ?? null;
  const [editLabel, setEditLabel] = useState("");
  const [editColor, setEditColor] = useState("#4f46e5");
  const [edgeType, setEdgeType] = useState<"object" | "subClassOf" | "data" | "annotation">("object");
  const [snapToGrid, setSnapToGrid] = useState(true);
  const snapRef = useRef(true);
  const GRID = 20;
  const [zoomLevel, setZoomLevel] = useState(100);
  const [viewport, setViewport] = useState({ zoom: 1, pan: { x: 0, y: 0 } });

  // ── Edge drawing via cytoscape-edgehandles ──
  const ehRef = useRef<any>(null);
  const edgeTypeRef = useRef(edgeType);
  const [edgeDrawing, setEdgeDrawing] = useState(false);
  edgeTypeRef.current = edgeType;
  const [editPopup, setEditPopup] = useState<EditData | null>(null);

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
        // Edgehandles styles
        { selector: ".eh-handle", style: {
          "background-color": "#ef4444", width: 10, height: 10, shape: "ellipse",
          "overlay-opacity": 0, "border-width": 2, "border-color": "#fff",
        }},
        { selector: ".eh-hover", style: {
          "background-color": "#ef4444",
        }},
        { selector: ".eh-source", style: {
          "border-color": "#ef4444", "border-width": 3,
        }},
        { selector: ".eh-target", style: {
          "border-color": "#10b981", "border-width": 3,
        }},
        { selector: ".eh-preview, .eh-ghost-edge", style: {
          "line-color": "#94a3b8", "target-arrow-color": "#94a3b8",
          "target-arrow-shape": "triangle", "curve-style": "bezier",
          width: 2, "line-style": "dashed",
        }},
      ],
      layout: { name: "preset" },
      wheelSensitivity: 0.3, minZoom: 0.05, maxZoom: 4,
      boxSelectionEnabled: true,
      selectionType: "additive",
    });
    cyRef.current = cy;

    // ── Edge handles (drag from node to create edge) ──────────
    const eh = (cy as any).edgehandles({
      snap: true,
      canConnect: (src: any, tgt: any) => src !== tgt,
      edgeParams: () => ({ data: { edgeType: "objectProperty", displayLabel: "relatedTo" } }),
      hoverDelay: 150,
      handleNodes: "node",
      handlePosition: () => "middle middle",
      handleInDrawMode: false,
      nodeLoopOffset: -50,
    });
    ehRef.current = eh;

    // When edge drawing completes, create the property in store
    cy.on("ehcomplete", (_e: any, src: any, tgt: any, addedEdge: any) => {
      addedEdge.remove(); // remove the temporary edge added by edgehandles
      const ts = Date.now();
      const et = edgeTypeRef.current;
      const iri = et === "subClassOf" ? "rdfs:subClassOf" : `http://example.org/new#prop_${ts}`;
      const label = et === "subClassOf" ? "subClassOf" : "relatedTo";
      store.addProperty({
        id: `edge_${ts}`, iri, label,
        source_id: src.id(), target_id: tgt.id(),
        property_type: et === "subClassOf" ? "annotation" : et,
      });
    });

    // ── Node tap — select ────────────────────────────────────
    cy.on("tap", "node", (evt) => {
      const nodeId = evt.target.id();
      const nodeLabel = evt.target.data("label") || "";
      const nodeType = evt.target.data("entityType") || "class";
      setEditLabel(nodeLabel);
      store.selectEntity({ iri: nodeId, type: nodeType, label: nodeLabel });
    });

    // Pane tap — deselect
    cy.on("tap", (e) => {
      if (e.target === cy) {
        store.selectEntity(null);
        setEditPopup(null);
      }
    });

    // ── Double-click: edit node / edge / create new node ─────
    cy.on("dbltap", "node", (evt) => {
      const n = evt.target;
      const rp = n.renderedPosition();
      const entityType = n.data("entityType") || "class";
      setEditPopup({
        type: "node", id: n.id(), label: n.data("label") || "",
        entityType, shape: n.style("shape") || "roundrectangle",
        color: n.style("border-color") || "#4f46e5",
        fontSize: parseInt(n.style("font-size")) || 11,
        screenX: rp.x + 20, screenY: rp.y - 10,
      });
    });

    cy.on("dbltap", "edge", (evt) => {
      const e = evt.target;
      const mid = e.midpoint();
      const zoom = cy.zoom();
      const pan = cy.pan();
      setEditPopup({
        type: "edge", id: e.id(), label: e.data("displayLabel") || "",
        edgeType: e.data("edgeType") || "objectProperty",
        color: e.style("line-color") || "#10b981",
        lineStyle: (e.style("line-style") || "solid") as "solid" | "dashed" | "dotted",
        screenX: mid.x * zoom + pan.x + 20, screenY: mid.y * zoom + pan.y - 10,
      });
    });

    cy.on("dbltap", (evt) => {
      if (evt.target === cy && evt.position) {
        // Double-click empty canvas → create new class
        const pos = evt.position;
        const snap = snapRef.current;
        const x = snap ? Math.round(pos.x / GRID) * GRID : pos.x;
        const y = snap ? Math.round(pos.y / GRID) * GRID : pos.y;
        const ts = Date.now();
        const iri = `http://example.org/new#Class_${ts}`;
        store.addClass({ id: iri, iri, label: "NewClass", x, y, w: 160, h: 60, color: "#4f46e5" });
        // Open edit popup for the new node
        const rZoom = cy.zoom();
        const rPan = cy.pan();
        setEditPopup({
          type: "node", id: iri, label: "NewClass",
          entityType: "class", shape: "roundrectangle",
          color: "#4f46e5", fontSize: 11,
          screenX: x * rZoom + rPan.x + 20, screenY: y * rZoom + rPan.y - 10,
        });
      }
    });

    // Drag → snap to grid + update position
    cy.on("dragfree", "node", (e) => {
      const p = e.target.position();
      const snap = snapRef.current;
      const sx = snap ? Math.round(p.x / GRID) * GRID : p.x;
      const sy = snap ? Math.round(p.y / GRID) * GRID : p.y;
      if (snap) e.target.position({ x: sx, y: sy });
      store.updateClass(e.target.id(), { x: sx, y: sy });
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

    // Track zoom level and viewport for sticky notes
    const updateViewport = () => {
      setZoomLevel(Math.round(cy.zoom() * 100));
      setViewport({ zoom: cy.zoom(), pan: cy.pan() });
    };
    cy.on("zoom pan", updateViewport);

    return () => { eh.destroy(); cy.destroy(); cyRef.current = null; };
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

  // ── Selection sync: highlight + center node when selected from tree ──
  useEffect(() => {
    const cy = cyRef.current;
    if (!cy) return;
    cy.elements().unselect();
    if (selected) {
      const node = cy.getElementById(selected);
      if (node.length) {
        node.select();
        setEditLabel(node.data("label") || "");
        cy.animate({ center: { eles: node }, zoom: cy.zoom() }, { duration: 300 });
      }
    }
  }, [selected]);

  // ── Keyboard shortcuts ────────────────────────────────────
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      // Don't capture when typing in an input
      const tag = (e.target as HTMLElement).tagName;
      if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") return;

      const ctrl = e.ctrlKey || e.metaKey;

      if ((e.key === "Delete" || e.key === "Backspace") && selected) {
        e.preventDefault();
        store.removeClass(selected);
        store.setIndividuals(store.individuals.filter((i) => i.iri !== selected));
        store.selectEntity(null);
      } else if (ctrl && e.key === "z" && !e.shiftKey) {
        e.preventDefault();
        store.undo();
      } else if (ctrl && (e.key === "Z" || e.key === "y" || (e.key === "z" && e.shiftKey))) {
        e.preventDefault();
        store.redo();
      } else if (ctrl && e.key === "c" && selected) {
        // Copy selected node IRI to internal clipboard
        (window as any).__ontoboard_clipboard = selected;
      } else if (ctrl && e.key === "v") {
        const clipIri = (window as any).__ontoboard_clipboard;
        if (!clipIri) return;
        const cls = store.classes.find((c) => c.iri === clipIri);
        if (cls) {
          const ts = Date.now();
          const iri = `${clipIri}_copy_${ts}`;
          store.addClass({ ...cls, id: iri, iri, label: cls.label + " (copy)", x: cls.x + 40, y: cls.y + 40 });
        }
      } else if (e.key === "Escape") {
        store.selectEntity(null);
        setEditPopup(null);
        if (edgeDrawing) {
          setEdgeDrawing(false);
          ehRef.current?.disableDrawMode();
        }
      } else if (e.key === "f" && !ctrl) {
        e.preventDefault();
        cyRef.current?.fit(undefined, 40);
      } else if (ctrl && e.key === "a") {
        e.preventDefault();
        cyRef.current?.elements().select();
      }
    };
    document.addEventListener("keydown", handler);
    return () => document.removeEventListener("keydown", handler);
  }, [selected, store, edgeDrawing]);

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
    cyRef.current?.getElementById(selected).data("displayLabel", editLabel);
  };

  // ── EditPopup handlers ────────────────────────────────────
  const handlePopupSave = useCallback((data: EditData) => {
    const cy = cyRef.current;
    if (!cy) { setEditPopup(null); return; }

    if (data.type === "node") {
      const nd = data as NodeEditData;
      const node = cy.getElementById(nd.id);
      if (node.length) {
        node.data("displayLabel", nd.label);
        node.data("label", nd.label);
        node.data("entityType", nd.entityType);
        node.style({ shape: nd.shape, "border-color": nd.color, "background-color": nd.color + "15", "font-size": `${nd.fontSize}px` });
      }
      store.updateClass(nd.id, { label: nd.label, color: nd.color });
      store.setIndividuals(store.individuals.map((i) => i.iri === nd.id ? { ...i, label: nd.label } : i));
    } else {
      const ed = data as EdgeEditData;
      const edge = cy.getElementById(ed.id);
      if (edge.length) {
        edge.data("displayLabel", ed.label);
        edge.data("edgeType", ed.edgeType);
        edge.style({ "line-color": ed.color, "target-arrow-color": ed.color, "line-style": ed.lineStyle });
      }
      // Update property in store
      const prop = store.properties.find((p) => p.id === ed.id);
      if (prop) {
        const isSub = ed.edgeType === "subClassOf";
        store.setProperties(store.properties.map((p) => p.id === ed.id ? {
          ...p, label: ed.label, iri: isSub ? "rdfs:subClassOf" : prop.iri,
          property_type: isSub ? "annotation" : ed.edgeType === "dataProperty" ? "data" : ed.edgeType === "annotationProperty" ? "annotation" : "object",
        } : p));
      }
    }
    setEditPopup(null);
  }, [store]);

  const handlePopupDelete = useCallback((id: string, type: "node" | "edge") => {
    if (type === "node") {
      store.removeClass(id);
      store.setIndividuals(store.individuals.filter((i) => i.iri !== id));
      store.selectEntity(null);
    } else {
      store.removeProperty(id);
    }
    setEditPopup(null);
  }, [store]);

  return (
    <div className={styles.container}>
      {/* ── Floating Toolbar ──────────────────────────────── */}
      <div className={styles.toolbar}>
        {/* Node tools */}
        <div className={styles.group}>
          <button className={styles.btn} title="Add Class" onClick={() => {
            const iri = `http://example.org/new#Class_${Date.now()}`;
            store.addClass({ id: iri, iri, label: "NewClass", x: 200 + Math.random() * 400, y: 100 + Math.random() * 300, w: 160, h: 60, color: "#4f46e5" });
          }}>+ Class</button>
          <button className={styles.btn} title="Add Individual" onClick={() => {
            const iri = `http://example.org/new#Ind_${Date.now()}`;
            store.setIndividuals([...store.individuals, { id: iri, iri, label: "NewIndividual", class_iri: "", x: 300, y: 400 }]);
          }}>+ Individual</button>
          <button className={styles.btn} title="Add Sticky Note" onClick={() => {
            const cy = cyRef.current;
            const center = cy ? { x: -cy.pan().x / cy.zoom() + cy.width() / cy.zoom() / 2, y: -cy.pan().y / cy.zoom() + cy.height() / cy.zoom() / 2 } : { x: 300, y: 300 };
            store.addStickyNote({
              id: `sticky_${Date.now()}`, text: "", x: center.x - 100, y: center.y - 75,
              w: 200, h: 150, color: "#fef3c7", fontSize: 14,
            });
          }}>+ Sticky</button>
        </div>

        <div className={styles.separator} />

        {/* Edge tools */}
        <div className={styles.group}>
          <select className={styles.edgeSelect} value={edgeType} onChange={(e) => setEdgeType(e.target.value as any)}>
            <option value="object">Object Prop</option>
            <option value="subClassOf">SubClassOf</option>
            <option value="data">Data Prop</option>
            <option value="annotation">Annotation</option>
          </select>
          <button className={`${styles.btn} ${edgeDrawing ? styles.active : ""}`}
                  onClick={() => { const v = !edgeDrawing; setEdgeDrawing(v); v ? ehRef.current?.enableDrawMode() : ehRef.current?.disableDrawMode(); }}>
            {edgeDrawing ? "\u2716 Stop" : "\u2194 Edge"}
          </button>
        </div>

        <div className={styles.separator} />

        {/* Layout + view */}
        <div className={styles.group}>
          {(["dagre", "cose-bilkent", "grid"] as const).map((l) => (
            <button key={l} className={`${styles.btn} ${layoutName === l ? styles.active : ""}`}
                    onClick={() => { setLayoutName(l); doLayout(l); }}
                    title={l === "dagre" ? "Tree layout" : l === "cose-bilkent" ? "Force layout" : "Grid layout"}>
              {l === "dagre" ? "Tree" : l === "cose-bilkent" ? "Force" : "Grid"}
            </button>
          ))}
          <button className={styles.btn} onClick={() => cyRef.current?.fit(undefined, 40)} title="Fit to view">Fit</button>
          <button className={`${styles.btn} ${snapToGrid ? styles.active : ""}`}
                  onClick={() => { const v = !snapToGrid; setSnapToGrid(v); snapRef.current = v; }}
                  title="Snap to grid">Snap</button>
        </div>

        <div className={styles.separator} />

        {/* Zoom controls */}
        <div className={styles.group}>
          <button className={styles.btn} title="Zoom out"
                  onClick={() => { const cy = cyRef.current; if (cy) cy.zoom({ level: cy.zoom() * 0.8, renderedPosition: { x: cy.width() / 2, y: cy.height() / 2 } }); }}>
            &minus;
          </button>
          <span className={styles.zoomLabel}>{zoomLevel}%</span>
          <button className={styles.btn} title="Zoom in"
                  onClick={() => { const cy = cyRef.current; if (cy) cy.zoom({ level: cy.zoom() * 1.25, renderedPosition: { x: cy.width() / 2, y: cy.height() / 2 } }); }}>
            +
          </button>
        </div>

        {/* Save indicator */}
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
            store.selectEntity(null);
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

      {/* ── Minimap ────────────────────────────────────────��── */}
      <Minimap cyRef={cyRef} />

      {/* ── Remote cursors overlay ─────────────────────────── */}
      <div ref={cursorsRef} className={styles.cursorsLayer} />

      {/* ── Sticky notes overlay ────────────────────────────── */}
      {store.stickyNotes.map((note) => (
        <StickyNoteComponent key={note.id} note={note}
          zoom={viewport.zoom} pan={viewport.pan}
          onUpdate={store.updateStickyNote} onDelete={store.removeStickyNote} />
      ))}

      {/* ── Cytoscape canvas ───────────────────────────────── */}
      <div ref={containerRef} className={styles.canvas} />

      {/* ── Edit popup (double-click) ─────────────────────── */}
      {editPopup && (
        <EditPopup data={editPopup} onSave={handlePopupSave}
                   onDelete={handlePopupDelete} onCancel={() => setEditPopup(null)} />
      )}
    </div>
  );
}
