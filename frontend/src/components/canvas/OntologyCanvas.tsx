/**
 * OntologyCanvas — Cytoscape.js ontology editor with Miro-like UX.
 *
 * Features: diff-based sync, drag-to-create edges, literals, right-click
 * context menu, label+prefix display, rdf:type, graph export, minimap.
 */

import { useEffect, useRef, useState, useCallback } from "react";
import cytoscape from "cytoscape";
import dagre from "cytoscape-dagre";
import coseBilkent from "cytoscape-cose-bilkent";
import edgehandles from "cytoscape-edgehandles";
import { useOntologyStore, scheduleAutoSave, type PrefixColor } from "../../store/ontologyStore";
import { apiJson } from "../../api";
import type { RemoteCursor, EntityLock } from "../../collab/useCollaboration";
import EditPopup, { EditData, NodeEditData, EdgeEditData } from "./EditPopup";
import StickyNoteComponent from "./StickyNote";
import CanvasFrameComponent from "./CanvasFrame";
import Minimap from "./Minimap";
import ContextMenu, { ContextMenuData } from "./ContextMenu";
import ExportDialog from "./ExportDialog";
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
  // Standard semantic web prefixes
  for (const [ns, p] of Object.entries(PREFIXES)) if (iri.startsWith(ns)) return p + iri.slice(ns.length);
  // Board-local entities: resolve http://example.org/{ont}# as ont:localname
  const exMatch = iri.match(/^https?:\/\/example\.org\/([^#/]+)#(.+)$/);
  if (exMatch) {
    const [, ns, local] = exMatch;
    return `${ns}:${local}`;
  }
  return iri.includes("#") ? iri.split("#").pop() || iri : iri.split("/").pop() || iri;
}

/** Built-in labels for standard OWL/RDFS/RDF semantic entities */
const BUILTIN_LABELS: Record<string, string> = {
  "http://www.w3.org/2002/07/owl#Thing": "owl:Thing",
  "http://www.w3.org/2002/07/owl#Nothing": "owl:Nothing",
  "http://www.w3.org/2002/07/owl#Class": "owl:Class",
  "http://www.w3.org/2002/07/owl#NamedIndividual": "owl:NamedIndividual",
  "http://www.w3.org/2002/07/owl#ObjectProperty": "owl:ObjectProperty",
  "http://www.w3.org/2002/07/owl#DatatypeProperty": "owl:DatatypeProperty",
  "http://www.w3.org/2002/07/owl#AnnotationProperty": "owl:AnnotationProperty",
  "http://www.w3.org/2002/07/owl#FunctionalProperty": "owl:FunctionalProperty",
  "http://www.w3.org/2002/07/owl#InverseFunctionalProperty": "owl:InverseFunctionalProperty",
  "http://www.w3.org/2002/07/owl#TransitiveProperty": "owl:TransitiveProperty",
  "http://www.w3.org/2002/07/owl#SymmetricProperty": "owl:SymmetricProperty",
  "http://www.w3.org/2002/07/owl#Ontology": "owl:Ontology",
  "http://www.w3.org/2002/07/owl#topObjectProperty": "owl:topObjectProperty",
  "http://www.w3.org/2002/07/owl#topDataProperty": "owl:topDataProperty",
  "http://www.w3.org/2000/01/rdf-schema#Class": "rdfs:Class",
  "http://www.w3.org/2000/01/rdf-schema#Resource": "rdfs:Resource",
  "http://www.w3.org/2000/01/rdf-schema#Literal": "rdfs:Literal",
  "http://www.w3.org/2000/01/rdf-schema#Datatype": "rdfs:Datatype",
  "http://www.w3.org/2000/01/rdf-schema#subClassOf": "rdfs:subClassOf",
  "http://www.w3.org/2000/01/rdf-schema#subPropertyOf": "rdfs:subPropertyOf",
  "http://www.w3.org/2000/01/rdf-schema#domain": "rdfs:domain",
  "http://www.w3.org/2000/01/rdf-schema#range": "rdfs:range",
  "http://www.w3.org/2000/01/rdf-schema#label": "rdfs:label",
  "http://www.w3.org/2000/01/rdf-schema#comment": "rdfs:comment",
  "http://www.w3.org/2000/01/rdf-schema#seeAlso": "rdfs:seeAlso",
  "http://www.w3.org/2000/01/rdf-schema#isDefinedBy": "rdfs:isDefinedBy",
  "http://www.w3.org/1999/02/22-rdf-syntax-ns#type": "rdf:type",
  "http://www.w3.org/1999/02/22-rdf-syntax-ns#Property": "rdf:Property",
};

/** Build two-line display label: "Label\nprefix:localname" */
function displayLabel(label: string, iri: string): string {
  const builtinLabel = BUILTIN_LABELS[iri];
  if (builtinLabel) {
    return label && label !== builtinLabel ? `${label}\n${builtinLabel}` : builtinLabel;
  }
  const short = compact(iri);
  return label && short !== label ? `${label}\n${short}` : label || short;
}

/** Predefined colors for ODP patterns */
const PATTERN_COLORS: Record<string, string> = {
  "part-of": "#8b5cf6",
  "quality-pattern": "#06b6d4",
  "participation": "#f97316",
  "classification": "#ec4899",
  "information-entity": "#14b8a6",
};
let patternColorIdx = 0;
const PATTERN_COLOR_PALETTE = ["#7c3aed", "#0891b2", "#ea580c", "#db2777", "#0d9488", "#4f46e5", "#059669", "#d97706", "#dc2626", "#7c2d12"];
function getPatternColor(patternId: string): string {
  if (PATTERN_COLORS[patternId]) return PATTERN_COLORS[patternId];
  const color = PATTERN_COLOR_PALETTE[patternColorIdx % PATTERN_COLOR_PALETTE.length];
  PATTERN_COLORS[patternId] = color;
  patternColorIdx++;
  return color;
}

/** Nicer collapsible legend overlay */
function CanvasLegend({ prefixColors, patternMap }: { prefixColors: PrefixColor[]; patternMap: Record<string, string> }) {
  const [collapsed, setCollapsed] = useState(false);

  // Collect unique patterns present in the graph
  const patternIds = [...new Set(Object.values(patternMap))];

  return (
    <div className={styles.legend} style={collapsed ? { padding: "4px 10px" } : undefined}>
      <div className={styles.legendHeader} onClick={() => setCollapsed(!collapsed)}>
        <span className={styles.legendTitle}>Legend</span>
        <span className={styles.legendToggle}>{collapsed ? "\u25B6" : "\u25BC"}</span>
      </div>
      {!collapsed && (
        <div className={styles.legendBody}>
          <div className={styles.legendSection}>
            <span className={styles.legendSectionTitle}>Nodes</span>
            <div className={styles.legendItem}>
              <span className={styles.legendSwatch} style={{ background: "#eef2ff", border: "2px solid #4f46e5", borderRadius: "3px" }} />
              <span>Class</span>
            </div>
            <div className={styles.legendItem}>
              <span className={styles.legendDiamond} style={{ background: "#d97706" }} />
              <span>Individual</span>
            </div>
            <div className={styles.legendItem}>
              <span className={styles.legendCircle} style={{ background: "#dcfce7", border: "1.5px dashed #16a34a" }} />
              <span>Literal</span>
            </div>
          </div>
          <div className={styles.legendSection}>
            <span className={styles.legendSectionTitle}>Edges</span>
            <div className={styles.legendItem}>
              <span className={styles.legendLine} style={{ borderColor: "#6366f1", borderStyle: "dashed" }} />
              <span>SubClassOf</span>
            </div>
            <div className={styles.legendItem}>
              <span className={styles.legendLine} style={{ borderColor: "#10b981" }} />
              <span>Object Property</span>
            </div>
            <div className={styles.legendItem}>
              <span className={styles.legendLine} style={{ borderColor: "#f59e0b", borderStyle: "dashed" }} />
              <span>Data Property</span>
            </div>
            <div className={styles.legendItem}>
              <span className={styles.legendLine} style={{ borderColor: "#9ca3af", borderStyle: "dashed" }} />
              <span>rdf:type</span>
            </div>
          </div>
          {prefixColors.length > 0 && (
            <div className={styles.legendSection}>
              <span className={styles.legendSectionTitle}>Prefix Colors</span>
              {prefixColors.map((pc) => (
                <div key={pc.prefix} className={styles.legendItem}>
                  <span className={styles.legendSwatch} style={{ background: pc.color + "20", border: `2px solid ${pc.color}`, borderRadius: "3px" }} />
                  <span>{pc.prefix}:</span>
                </div>
              ))}
            </div>
          )}
          {patternIds.length > 0 && (
            <div className={styles.legendSection}>
              <span className={styles.legendSectionTitle}>Patterns</span>
              {patternIds.map((pid) => (
                <div key={pid} className={styles.legendItem}>
                  <span className={styles.legendSwatch} style={{ background: getPatternColor(pid) + "20", border: `2px solid ${getPatternColor(pid)}`, borderRadius: "3px" }} />
                  <span>{pid}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

interface Props {
  boardId: string;
  onOpenComments?: (entityIri?: string) => void;
  remoteCursors?: RemoteCursor[];
  broadcastCursor?: (x: number, y: number, clicking?: boolean, action?: string, selectedEntity?: string | null) => void;
  entityLocks?: EntityLock[];
  lockEntity?: (entityIri: string | null) => void;
  getEntityLock?: (entityIri: string) => EntityLock | null;
}

export default function OntologyCanvas({ boardId, onOpenComments, remoteCursors = [], broadcastCursor, entityLocks = [], lockEntity, getEntityLock }: Props) {
  const cyRef = useRef<cytoscape.Core | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const cursorsRef = useRef<HTMLDivElement>(null);
  const store = useOntologyStore();

  const [layoutName, setLayoutName] = useState("dagre");
  const selected = store.selectedEntity?.iri ?? null;
  const [editLabel, setEditLabel] = useState("");
  const [editColor, setEditColor] = useState("#4f46e5");
  const [edgeType, setEdgeType] = useState<"object" | "subClassOf" | "data" | "annotation" | "rdfType">("object");
  const [snapToGrid, setSnapToGrid] = useState(true);
  const snapRef = useRef(true);
  const GRID = 20;
  const [zoomLevel, setZoomLevel] = useState(100);
  const [viewport, setViewport] = useState({ zoom: 1, pan: { x: 0, y: 0 } });

  // Edge drawing
  const ehRef = useRef<any>(null);
  const edgeTypeRef = useRef(edgeType);
  edgeTypeRef.current = edgeType;

  // Popups and menus
  const [editPopup, setEditPopup] = useState<EditData | null>(null);
  const [contextMenu, setContextMenu] = useState<ContextMenuData | null>(null);
  const [showExport, setShowExport] = useState(false);

  // Search
  const [showSearch, setShowSearch] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  const [searchResults, setSearchResults] = useState<{ id: string; label: string; type: string }[]>([]);
  const [searchIdx, setSearchIdx] = useState(0);
  const searchInputRef = useRef<HTMLInputElement>(null);
  const initialFitDoneRef = useRef(false);

  // ── Cytoscape init ──────────────────────────────────────────
  useEffect(() => {
    if (!containerRef.current) return;
    const cy = cytoscape({
      container: containerRef.current,
      style: [
        // Class nodes — two-line label
        { selector: "node[entityType='class']", style: {
          "background-color": "#eef2ff", "border-color": "#4f46e5", "border-width": 2,
          label: "data(displayLabel)", "text-valign": "center", "text-halign": "center",
          "font-size": "11px", "font-family": "Inter, sans-serif", "font-weight": 600,
          color: "#312e81", shape: "roundrectangle", width: "label", height: "label", padding: "10px",
          "text-wrap": "wrap", "text-max-width": "180px",
        }},
        // Individual nodes
        { selector: "node[entityType='individual']", style: {
          "background-color": "#fef3c7", "border-color": "#d97706", "border-width": 2,
          label: "data(displayLabel)", "text-valign": "center", "text-halign": "center",
          "font-size": "10px", color: "#78350f", shape: "ellipse",
          width: "label", height: "label", padding: "10px",
          "text-wrap": "wrap", "text-max-width": "140px",
        }},
        // Literal nodes — green dashed ellipse
        { selector: "node[entityType='literal']", style: {
          "background-color": "#dcfce7", "border-color": "#16a34a", "border-width": 1.5,
          "border-style": "dashed", label: "data(displayLabel)",
          "text-valign": "center", "text-halign": "center",
          "font-size": "10px", "font-style": "italic", color: "#166534",
          shape: "ellipse", width: "label", height: "label", padding: "8px",
          "text-wrap": "wrap", "text-max-width": "120px",
        }},
        { selector: "node:selected", style: { "border-color": "#0ea5e9", "border-width": 3 }},
        // Edges
        { selector: "edge[edgeType='subClassOf']", style: {
          "line-color": "#6366f1", "target-arrow-color": "#6366f1",
          "target-arrow-shape": "triangle-backcurve", "curve-style": "bezier",
          width: 2, "line-style": "dashed", "line-dash-pattern": [8, 4], "arrow-scale": 1.2,
          label: "data(displayLabel)", "font-size": "8px", color: "#4338ca",
          "text-rotation": "autorotate", "text-background-color": "#fff",
          "text-background-opacity": 0.9, "text-background-padding": "2px",
        }},
        { selector: "edge[edgeType='objectProperty']", style: {
          "line-color": "#10b981", "target-arrow-color": "#10b981",
          "target-arrow-shape": "triangle", "curve-style": "bezier", width: 2,
          label: "data(displayLabel)", "font-size": "9px", color: "#064e3b",
          "text-rotation": "autorotate", "text-background-color": "#fff",
          "text-background-opacity": 0.9, "text-background-padding": "2px",
        }},
        { selector: "edge[edgeType='rdfType']", style: {
          "line-color": "#9ca3af", "target-arrow-color": "#9ca3af",
          "target-arrow-shape": "triangle", "curve-style": "bezier",
          width: 1.5, "line-style": "dashed", "line-dash-pattern": [4, 2],
          label: "data(displayLabel)", "font-size": "8px", color: "#6b7280",
          "text-rotation": "autorotate", "text-background-color": "#fff",
          "text-background-opacity": 0.9, "text-background-padding": "2px",
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
        // Edgehandles
        { selector: ".eh-handle", style: {
          "background-color": "#ef4444", width: 14, height: 14, shape: "ellipse",
          "overlay-opacity": 0, "border-width": 2, "border-color": "#fff",
        }},
        { selector: ".eh-hover", style: { "background-color": "#ef4444" }},
        { selector: ".eh-source", style: { "border-color": "#ef4444", "border-width": 3 }},
        { selector: ".eh-target", style: { "border-color": "#10b981", "border-width": 3 }},
        { selector: ".eh-preview, .eh-ghost-edge", style: {
          "line-color": "#94a3b8", "target-arrow-color": "#94a3b8",
          "target-arrow-shape": "triangle", "curve-style": "bezier",
          width: 2, "line-style": "dashed",
        }},
      ],
      layout: { name: "preset" },
      wheelSensitivity: 0.3, minZoom: 0.05, maxZoom: 4,
      boxSelectionEnabled: true, selectionType: "additive",
    });
    cyRef.current = cy;

    // ── Edge handles — always-on (no toggle needed) ─────────
    const eh = (cy as any).edgehandles({
      snap: true,
      canConnect: (src: any, tgt: any) => src !== tgt,
      edgeParams: () => ({ data: { edgeType: "objectProperty", displayLabel: "relatedTo" } }),
      hoverDelay: 100,
      handleNodes: "node[entityType!='literal']",
      handlePosition: () => "middle middle",
      handleInDrawMode: false,
      nodeLoopOffset: -50,
    });
    ehRef.current = eh;
    // Edge handles use the red dot on each node — drag from the dot to create edges.
    // Draw mode is OFF so normal node drag and double-click work correctly.

    // Edge complete → create property in store
    // Auto-detect data property when target is a literal node
    cy.on("ehcomplete", (_e: any, src: any, tgt: any, addedEdge: any) => {
      addedEdge.remove();
      const ts = Date.now();
      let et = edgeTypeRef.current;
      const targetIsLiteral = tgt.data("entityType") === "literal";
      // If target is a literal, force data property type
      if (targetIsLiteral && et !== "annotation") {
        et = "data";
      }
      const isSubClass = et === "subClassOf";
      const isRdfType = et === "rdfType";
      const iri = isSubClass ? "rdfs:subClassOf" : isRdfType ? "rdf:type" : `http://example.org/new#prop_${ts}`;
      const label = isSubClass ? "rdfs:subClassOf" : isRdfType ? "rdf:type" : targetIsLiteral ? "hasValue" : "relatedTo";
      const propType = isSubClass ? "annotation" : isRdfType ? "annotation" : et === "data" ? "data" : et === "annotation" ? "annotation" : "object";
      store.addProperty({
        id: `edge_${ts}`, iri, label,
        source_id: src.id(), target_id: tgt.id(),
        property_type: propType,
      });
    });

    // ── Node tap — select + lock ──────────────────────────────
    cy.on("tap", "node", (evt) => {
      const n = evt.target;
      const nodeIri = n.id();
      // Check if locked by another user
      const lock = getEntityLock?.(nodeIri);
      if (lock) {
        // Show a brief toast or just let the user know via overlay
        console.info(`[collab] Entity locked by ${lock.userName}`);
      }
      setEditLabel(n.data("label") || "");
      store.selectEntity({ iri: nodeIri, type: n.data("entityType") || "class", label: n.data("label") || "" });
      lockEntity?.(nodeIri); // Broadcast that we're editing this entity
      setContextMenu(null);
      if (evt.position) broadcastCursor?.(evt.position.x, evt.position.y, false, "selected " + n.data("label"), nodeIri);
    });

    // Pane tap — deselect + unlock
    cy.on("tap", (e) => {
      if (e.target === cy) {
        store.selectEntity(null); setEditPopup(null); setContextMenu(null);
        lockEntity?.(null); // Release entity lock
        if (e.position) broadcastCursor?.(e.position.x, e.position.y, false, "idle", null);
      }
    });

    // ── Double-click: edit node / edge / create new node ─────
    cy.on("dbltap", "node", (evt) => {
      const n = evt.target;
      const rp = n.renderedPosition();
      const entityType = n.data("entityType") || "class";
      if (entityType === "literal") {
        // Edit literal value
        setEditPopup({
          type: "node", id: n.id(), label: n.data("label") || "",
          entityType: "literal", shape: "ellipse",
          color: "#16a34a", fontSize: 10,
          screenX: rp.x + 20, screenY: rp.y - 10,
        });
      } else {
        setEditPopup({
          type: "node", id: n.id(), label: n.data("label") || "",
          entityType, shape: n.style("shape") || "roundrectangle",
          color: n.style("border-color") || "#4f46e5",
          fontSize: parseInt(n.style("font-size")) || 11,
          screenX: rp.x + 20, screenY: rp.y - 10,
        });
      }
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
        const pos = evt.position;
        const snap = snapRef.current;
        const x = snap ? Math.round(pos.x / GRID) * GRID : pos.x;
        const y = snap ? Math.round(pos.y / GRID) * GRID : pos.y;
        const ts = Date.now();
        const iri = `http://example.org/new#Class_${ts}`;
        store.addClass({ id: iri, iri, label: "NewClass", x, y, w: 160, h: 60, color: "#4f46e5" });
        const rZoom = cy.zoom(); const rPan = cy.pan();
        setEditPopup({
          type: "node", id: iri, label: "NewClass",
          entityType: "class", shape: "roundrectangle", color: "#4f46e5", fontSize: 11,
          screenX: x * rZoom + rPan.x + 20, screenY: y * rZoom + rPan.y - 10,
        });
      }
    });

    // ── Right-click context menu (with provenance lookup) ────
    const getProvenance = (entityId: string) => {
      const s = useOntologyStore.getState();
      const cls = s.classes.find((c) => c.iri === entityId);
      if (cls) return { created_by: cls.created_by, created_at: cls.created_at, modified_by: cls.modified_by, modified_at: cls.modified_at };
      const ind = s.individuals.find((i) => i.iri === entityId);
      if (ind) return { created_by: ind.created_by, created_at: ind.created_at, modified_by: ind.modified_by, modified_at: ind.modified_at };
      const prop = s.properties.find((p) => p.id === entityId);
      if (prop) return { created_by: prop.created_by, created_at: prop.created_at, modified_by: prop.modified_by, modified_at: prop.modified_at };
      return undefined;
    };
    cy.on("cxttap", "node", (evt) => {
      evt.originalEvent.preventDefault();
      const n = evt.target;
      const rp = n.renderedPosition();
      setContextMenu({ x: rp.x, y: rp.y, target: "node", targetId: n.id(), targetType: n.data("entityType"), provenance: getProvenance(n.id()) });
    });
    cy.on("cxttap", "edge", (evt) => {
      evt.originalEvent.preventDefault();
      const e = evt.target;
      const mid = e.midpoint();
      const zoom = cy.zoom(); const pan = cy.pan();
      setContextMenu({ x: mid.x * zoom + pan.x, y: mid.y * zoom + pan.y, target: "edge", targetId: e.id(), targetType: e.data("edgeType"), provenance: getProvenance(e.id()) });
    });
    cy.on("cxttap", (evt) => {
      if (evt.target === cy) {
        evt.originalEvent.preventDefault();
        const rEvt = evt.originalEvent as MouseEvent;
        const rect = containerRef.current?.getBoundingClientRect();
        const gx = rect ? rEvt.clientX - rect.left : rEvt.clientX;
        const gy = rect ? rEvt.clientY - rect.top : rEvt.clientY;
        setContextMenu({ x: gx, y: gy, target: "canvas", graphPosition: evt.position });
      }
    });

    // ── Drag → snap + update position (classes AND individuals) ──
    cy.on("drag", "node", (e) => {
      const n = e.target;
      if (e.position) broadcastCursor?.(e.position.x, e.position.y, true, "dragging " + n.data("label"), n.id());
    });
    cy.on("dragfree", "node", (e) => {
      const n = e.target;
      const p = n.position();
      const snap = snapRef.current;
      const sx = snap ? Math.round(p.x / GRID) * GRID : p.x;
      const sy = snap ? Math.round(p.y / GRID) * GRID : p.y;
      if (snap) n.position({ x: sx, y: sy });
      const entityType = n.data("entityType");
      if (entityType === "individual") {
        store.updateIndividual(n.id(), { x: sx, y: sy });
      } else if (entityType === "literal") {
        store.updateLiteral(n.id(), { x: sx, y: sy });
      } else {
        store.updateClass(n.id(), { x: sx, y: sy });
      }
      if (e.position) broadcastCursor?.(e.position.x, e.position.y, false, "idle", null);
    });

    // ── Cursor broadcasting ──────────────────────────────────
    cy.on("mousemove", (e) => { if (e.position) broadcastCursor?.(e.position.x, e.position.y, false); });
    cy.on("mousedown", (e) => { if (e.position) broadcastCursor?.(e.position.x, e.position.y, true); });
    cy.on("mouseup", (e) => { if (e.position) broadcastCursor?.(e.position.x, e.position.y, false); });

    // ── Track viewport ───────────────────────────────────────
    const updateViewport = () => {
      setZoomLevel(Math.round(cy.zoom() * 100));
      setViewport({ zoom: cy.zoom(), pan: cy.pan() });
    };
    cy.on("zoom pan", updateViewport);

    return () => { eh.destroy(); cy.destroy(); cyRef.current = null; };
  }, []);

  // ── Diff-based sync: store → Cytoscape (no full rebuild) ──────
  useEffect(() => {
    const cy = cyRef.current;
    if (!cy) return;

    const expectedNodes = new Map<string, { label: string; displayLabel: string; entityType: string; x: number; y: number }>();
    for (const c of store.classes) expectedNodes.set(c.iri, { label: c.label, displayLabel: displayLabel(c.label, c.iri), entityType: "class", x: c.x || 0, y: c.y || 0 });
    for (const i of store.individuals) expectedNodes.set(i.iri, { label: i.label, displayLabel: displayLabel(i.label, i.iri), entityType: "individual", x: i.x || 0, y: i.y || 0 });
    for (const l of store.literals) expectedNodes.set(l.id, { label: l.value, displayLabel: l.value || "(empty)", entityType: "literal", x: l.x || 0, y: l.y || 0 });

    const expectedEdges = new Map<string, { source: string; target: string; displayLabel: string; edgeType: string }>();
    let skippedEdges = 0;
    for (const p of store.properties) {
      if (!expectedNodes.has(p.source_id) && !expectedNodes.has(p.target_id)) {
        skippedEdges++;
        continue;
      }
      const isSub = p.iri === "rdfs:subClassOf";
      const isType = p.iri === "rdf:type";
      const isData = p.property_type === "data";
      const isAnn = p.property_type === "annotation" && !isSub;
      const edgeType = isSub ? "subClassOf" : isType ? "rdfType" : isData ? "dataProperty" : isAnn ? "annotationProperty" : "objectProperty";
      expectedEdges.set(p.id, { source: p.source_id, target: p.target_id, displayLabel: isSub ? "rdfs:subClassOf" : isType ? "rdf:type" : compact(p.iri), edgeType });
    }

    // Build prefix color lookup: namespace → color
    const prefixColorLookup = new Map<string, string>();
    for (const pc of store.prefixColors) {
      prefixColorLookup.set(pc.namespace, pc.color);
    }

    console.debug("[canvas sync]", store.classes.length, "classes,", store.properties.length, "props,", expectedEdges.size, "edges,", skippedEdges, "skipped");

    cy.batch(() => {
      // Remove nodes not in store
      cy.nodes().forEach((n) => {
        if (!expectedNodes.has(n.id())) n.remove();
      });
      // Add or update nodes
      for (const [id, data] of expectedNodes) {
        const existing = cy.getElementById(id);
        if (existing.length) {
          // Update data if changed
          if (existing.data("displayLabel") !== data.displayLabel) existing.data("displayLabel", data.displayLabel);
          if (existing.data("label") !== data.label) existing.data("label", data.label);
          if (existing.data("entityType") !== data.entityType) existing.data("entityType", data.entityType);
        } else {
          cy.add({ group: "nodes", data: { id, label: data.label, displayLabel: data.displayLabel, entityType: data.entityType }, position: { x: data.x, y: data.y } });
        }

        // Apply prefix color or pattern color to class nodes
        if (data.entityType === "class") {
          const node = cy.getElementById(id);
          if (!node.length) continue;
          let customColor: string | null = null;

          // Check pattern map first
          const patternId = store.patternMap[id];
          if (patternId) {
            customColor = getPatternColor(patternId);
          }

          // Check prefix color (overrides pattern if set)
          for (const [ns, color] of prefixColorLookup) {
            if (id.startsWith(ns)) {
              customColor = color;
              break;
            }
          }

          if (customColor) {
            node.style({ "border-color": customColor, "background-color": customColor + "15", color: customColor });
          }
        }
      }
      // Show entity locks from other users
      for (const lock of entityLocks) {
        const lockedNode = cy.getElementById(lock.entityIri);
        if (lockedNode.length) {
          lockedNode.style({
            "overlay-color": lock.color,
            "overlay-opacity": 0.12,
            "overlay-padding": 8,
          });
        }
      }

      // Build set of inferred edge IDs
      const inferredEdgeIds = new Set<string>();
      if (store.showInferences) {
        for (const inf of store.inferences) {
          if (!inf.subject || !inf.object) continue;
          const infId = `inferred_${inf.subject}_${inf.predicate}_${inf.object}`;
          // Only add inferred edges if both nodes exist on canvas
          if (expectedNodes.has(inf.subject) && expectedNodes.has(inf.object)) {
            inferredEdgeIds.add(infId);
            expectedEdges.set(infId, {
              source: inf.subject,
              target: inf.object,
              displayLabel: inf.inference_type || compact(inf.predicate),
              edgeType: "inferred",
            });
          }
        }
      }

      // Remove edges not in store (including stale inferred edges)
      cy.edges().filter((e) => !e.hasClass("eh-ghost-edge") && !e.hasClass("eh-preview")).forEach((e) => {
        if (!expectedEdges.has(e.id())) e.remove();
      });
      // Add or update edges
      for (const [id, data] of expectedEdges) {
        if (!cy.getElementById(data.source).length || !cy.getElementById(data.target).length) continue;
        const existing = cy.getElementById(id);
        if (existing.length) {
          if (existing.data("displayLabel") !== data.displayLabel) existing.data("displayLabel", data.displayLabel);
          if (existing.data("edgeType") !== data.edgeType) existing.data("edgeType", data.edgeType);
        } else {
          cy.add({ group: "edges", data: { id, source: data.source, target: data.target, displayLabel: data.displayLabel, edgeType: data.edgeType } });
        }
        // Style inferred edges distinctly
        if (inferredEdgeIds.has(id)) {
          const edge = cy.getElementById(id);
          if (edge.length) {
            edge.style({
              "line-color": "#a78bfa",
              "target-arrow-color": "#a78bfa",
              "line-style": "dashed",
              "line-dash-pattern": [6, 4],
              opacity: 0.75,
            });
          }
        }
      }
    });

    // Auto-layout only if all positions are 0
    if (store.classes.length > 0 && store.classes.every((c) => c.x === 0 && c.y === 0) && cy.nodes().length > 0) {
      doLayout(layoutName);
    }

    // Auto-fit viewport on initial load so entities are visible
    if (!initialFitDoneRef.current && cy.nodes().length > 0) {
      initialFitDoneRef.current = true;
      requestAnimationFrame(() => {
        cy.fit(undefined, 50);
      });
    }

    // Restore selection
    if (selected) {
      const node = cy.getElementById(selected);
      if (node.length && !node.selected()) node.select();
    }
  }, [store.classes, store.properties, store.individuals, store.literals, store.prefixColors, store.patternMap, store.showInferences, store.inferences, entityLocks]);

  useEffect(() => { store.loadFromBackend(boardId); }, [boardId]);

  // ── Selection sync ─────────────────────────────────────────
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

  // ── Keyboard shortcuts ─────────────────────────────────────
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement).tagName;
      if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") return;
      const ctrl = e.ctrlKey || e.metaKey;

      if ((e.key === "Delete" || e.key === "Backspace") && selected) {
        e.preventDefault();
        store.removeClass(selected);
        store.setIndividuals(store.individuals.filter((i) => i.iri !== selected));
        store.removeLiteral(selected);
        store.selectEntity(null);
      } else if (ctrl && e.key === "z" && !e.shiftKey) {
        e.preventDefault(); store.undo();
      } else if (ctrl && (e.key === "Z" || e.key === "y" || (e.key === "z" && e.shiftKey))) {
        e.preventDefault(); store.redo();
      } else if (ctrl && e.key === "c" && selected) {
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
        if (showSearch) { setShowSearch(false); setSearchQuery(""); setSearchResults([]); }
        else { store.selectEntity(null); setEditPopup(null); setContextMenu(null); }
      } else if (ctrl && e.key === "f") {
        e.preventDefault();
        setShowSearch(true);
        setTimeout(() => searchInputRef.current?.focus(), 50);
      } else if (e.key === "f" && !ctrl) {
        e.preventDefault(); cyRef.current?.fit(undefined, 40);
      } else if (ctrl && e.key === "a") {
        e.preventDefault(); cyRef.current?.elements().select();
      }
    };
    document.addEventListener("keydown", handler);
    return () => document.removeEventListener("keydown", handler);
  }, [selected, store]);

  // ── Remote cursors ─────────────────────────────────────────
  useEffect(() => {
    if (!cursorsRef.current || !cyRef.current) return;
    const cy = cyRef.current;
    const container = cursorsRef.current;
    container.innerHTML = "";
    for (const cursor of remoteCursors) {
      const rendered = cy.pan(); const zoom = cy.zoom();
      const screenX = cursor.x * zoom + rendered.x;
      const screenY = cursor.y * zoom + rendered.y;
      const el = document.createElement("div");
      el.className = `${styles.remoteCursor}${cursor.action !== "idle" ? ` ${styles.cursorActive}` : ""}`;
      el.style.left = `${screenX}px`; el.style.top = `${screenY}px`;
      const actionHtml = cursor.action && cursor.action !== "idle" ? `<span class="${styles.cursorAction}" style="background:${cursor.color}">${cursor.action}</span>` : "";
      el.innerHTML = `<svg width="16" height="20" viewBox="0 0 16 20" style="position:absolute;top:-2px;left:-2px;"><path d="M0 0L16 12L8 12L4 20Z" fill="${cursor.color}" stroke="#fff" stroke-width="1"/></svg><span class="${styles.cursorLabel}" style="background:${cursor.color}">${cursor.name}</span>${actionHtml}`;
      container.appendChild(el);

      // Highlight selected entity node for this remote user
      if (cursor.selectedEntity) {
        const node = cy.getElementById(cursor.selectedEntity);
        if (node.length) {
          node.style("overlay-color", cursor.color);
          node.style("overlay-opacity", 0.15);
          node.style("overlay-padding", 6);
        }
      }
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
    store.updateIndividual(selected, { label: editLabel });
    const cy = cyRef.current;
    if (cy) {
      const node = cy.getElementById(selected);
      if (node.length) node.data("displayLabel", displayLabel(editLabel, selected));
    }
  };

  // ── EditPopup handlers ─────────────────────────────────────
  const handlePopupSave = useCallback((data: EditData) => {
    const cy = cyRef.current;
    if (!cy) { setEditPopup(null); return; }
    if (data.type === "node") {
      const nd = data as NodeEditData;
      const originalId = editPopup?.id || nd.id;
      const iriChanged = nd.id !== originalId;

      if (iriChanged && nd.entityType !== "literal") {
        // IRI was changed — remove old, add new, update edge references
        const cls = store.classes.find((c) => c.iri === originalId);
        if (cls) {
          store.removeClass(originalId);
          store.addClass({ ...cls, id: nd.id, iri: nd.id, label: nd.label, color: nd.color });
          // Update edges referencing this node
          store.setProperties(store.properties.map((p) => ({
            ...p,
            source_id: p.source_id === originalId ? nd.id : p.source_id,
            target_id: p.target_id === originalId ? nd.id : p.target_id,
          })));
          store.selectEntity({ iri: nd.id, type: "class", label: nd.label });
        }
      } else {
        const node = cy.getElementById(nd.id);
        if (node.length) {
          node.data("displayLabel", displayLabel(nd.label, nd.id));
          node.data("label", nd.label);
          node.data("entityType", nd.entityType);
          node.style({ shape: nd.shape, "border-color": nd.color, "background-color": nd.color + "15", "font-size": `${nd.fontSize}px` });
        }
        store.updateClass(nd.id, { label: nd.label, color: nd.color });
        store.updateIndividual(nd.id, { label: nd.label });
        // If it's a literal, update value
        if (nd.entityType === "literal") store.updateLiteral(nd.id, { value: nd.label });
      }
    } else {
      const ed = data as EdgeEditData;
      const edge = cy.getElementById(ed.id);
      if (edge.length) {
        edge.data("displayLabel", ed.label);
        edge.data("edgeType", ed.edgeType);
        // Clear any manual style overrides so the selector-based defaults apply
        edge.removeStyle();
      }
      const prop = store.properties.find((p) => p.id === ed.id);
      if (prop) {
        const isSub = ed.edgeType === "subClassOf";
        const isType = ed.edgeType === "rdfType";
        store.setProperties(store.properties.map((p) => p.id === ed.id ? {
          ...p, label: ed.label,
          iri: isSub ? "rdfs:subClassOf" : isType ? "rdf:type" : prop.iri,
          property_type: isSub ? "annotation" : isType ? "annotation" : ed.edgeType === "dataProperty" ? "data" : ed.edgeType === "annotationProperty" ? "annotation" : "object",
        } : p));
      }
    }
    setEditPopup(null);
  }, [store]);

  const handlePopupDelete = useCallback((id: string, type: "node" | "edge") => {
    if (type === "node") {
      store.removeClass(id);
      store.setIndividuals(store.individuals.filter((i) => i.iri !== id));
      store.removeLiteral(id);
      store.selectEntity(null);
    } else {
      store.removeProperty(id);
    }
    setEditPopup(null);
  }, [store]);

  // ── Context menu handler ───────────────────────────────────
  const handleContextAction = useCallback((action: string) => {
    const cy = cyRef.current;
    if (!cy) { setContextMenu(null); return; }
    const ts = Date.now();
    const gp = contextMenu?.graphPosition;
    const x = gp ? (snapRef.current ? Math.round(gp.x / GRID) * GRID : gp.x) : 300;
    const y = gp ? (snapRef.current ? Math.round(gp.y / GRID) * GRID : gp.y) : 300;

    switch (action) {
      case "add-class": {
        const iri = `http://example.org/new#Class_${ts}`;
        store.addClass({ id: iri, iri, label: "NewClass", x, y, w: 160, h: 60, color: "#4f46e5" });
        break;
      }
      case "add-individual": {
        const iri = `http://example.org/new#Ind_${ts}`;
        store.addIndividual({ id: iri, iri, label: "NewIndividual", class_iri: "", x, y });
        break;
      }
      case "add-literal": {
        const id = `lit_${ts}`;
        store.addLiteral({ id, value: "value", datatype: "xsd:string", language: "", x, y });
        break;
      }
      case "add-sticky": {
        store.addStickyNote({ id: `sticky_${ts}`, text: "", x: x - 100, y: y - 75, w: 200, h: 150, color: "#fef3c7", fontSize: 14 });
        break;
      }
      case "add-frame": {
        store.addFrame({ id: `frame_${ts}`, label: "Frame", x: x - 200, y: y - 150, w: 400, h: 300, color: "rgba(79, 70, 229, 0.05)", borderColor: "#c7d2fe" });
        break;
      }
      case "paste": {
        const clipIri = (window as any).__ontoboard_clipboard;
        if (clipIri) {
          const cls = store.classes.find((c) => c.iri === clipIri);
          if (cls) {
            const iri = `${clipIri}_copy_${ts}`;
            store.addClass({ ...cls, id: iri, iri, label: cls.label + " (copy)", x, y });
          }
        }
        break;
      }
      case "edit": {
        const nodeId = contextMenu?.targetId;
        if (nodeId) {
          const n = cy.getElementById(nodeId);
          if (n.length) {
            const rp = n.renderedPosition();
            setEditPopup({
              type: "node", id: n.id(), label: n.data("label") || "",
              entityType: n.data("entityType") || "class",
              shape: n.style("shape") || "roundrectangle",
              color: n.style("border-color") || "#4f46e5",
              fontSize: parseInt(n.style("font-size")) || 11,
              screenX: rp.x + 20, screenY: rp.y - 10,
            });
          }
        }
        break;
      }
      case "connect": {
        const nodeId = contextMenu?.targetId;
        if (nodeId) ehRef.current?.start(cy.getElementById(nodeId));
        break;
      }
      case "add-subclass": {
        const parentId = contextMenu?.targetId;
        if (parentId) {
          const childIri = `http://example.org/new#Class_${ts}`;
          store.addClass({ id: childIri, iri: childIri, label: "SubClass", x: x + 40, y: y + 80, w: 160, h: 60, color: "#4f46e5" });
          store.addSubClassOf(childIri, parentId);
        }
        break;
      }
      case "add-rdftype": {
        const indId = contextMenu?.targetId;
        if (indId) {
          // Find first class to connect to
          const cls = store.classes[0];
          if (cls) {
            store.addProperty({ id: `rdftype_${ts}`, iri: "rdf:type", label: "rdf:type", source_id: indId, target_id: cls.iri, property_type: "annotation" });
          }
        }
        break;
      }
      case "duplicate": {
        const nodeId = contextMenu?.targetId;
        if (nodeId) {
          const cls = store.classes.find((c) => c.iri === nodeId);
          if (cls) {
            const iri = `${nodeId}_copy_${ts}`;
            store.addClass({ ...cls, id: iri, iri, label: cls.label + " (copy)", x: cls.x + 40, y: cls.y + 40 });
          }
        }
        break;
      }
      case "copy-iri": {
        const nodeId = contextMenu?.targetId;
        if (nodeId) navigator.clipboard?.writeText(nodeId);
        break;
      }
      case "open-iri": {
        const nodeId = contextMenu?.targetId;
        if (nodeId && nodeId.startsWith("http")) window.open(nodeId, "_blank");
        break;
      }
      case "edit-iri": {
        const nodeId = contextMenu?.targetId;
        if (nodeId) {
          const newIri = prompt("Edit IRI:", nodeId);
          if (newIri && newIri !== nodeId) {
            const cls = store.classes.find((c) => c.iri === nodeId);
            if (cls) {
              store.removeClass(nodeId);
              store.addClass({ ...cls, id: newIri, iri: newIri });
              // Update edges referencing this node
              store.setProperties(store.properties.map((p) => ({
                ...p,
                source_id: p.source_id === nodeId ? newIri : p.source_id,
                target_id: p.target_id === nodeId ? newIri : p.target_id,
              })));
              store.selectEntity({ iri: newIri, type: "class", label: cls.label });
            }
          }
        }
        break;
      }
      case "add-superclass": {
        const childId = contextMenu?.targetId;
        if (childId) {
          const parentIri = `http://example.org/new#Class_${ts}`;
          store.addClass({ id: parentIri, iri: parentIri, label: "SuperClass", x: x - 40, y: y - 100, w: 160, h: 60, color: "#4f46e5" });
          store.addSubClassOf(childId, parentIri);
        }
        break;
      }
      case "add-sibling": {
        const nodeId = contextMenu?.targetId;
        if (nodeId) {
          // Find parent of this class
          const parentEdge = store.properties.find((p) => p.iri === "rdfs:subClassOf" && p.source_id === nodeId);
          const siblingIri = `http://example.org/new#Class_${ts}`;
          store.addClass({ id: siblingIri, iri: siblingIri, label: "SiblingClass", x: x + 200, y: y, w: 160, h: 60, color: "#4f46e5" });
          if (parentEdge) {
            store.addSubClassOf(siblingIri, parentEdge.target_id);
          }
        }
        break;
      }
      case "axiom-subclassof":
      case "axiom-equivalent":
      case "axiom-disjoint":
      case "view-axioms": {
        // Select the entity and switch to axiom tab
        const nodeId = contextMenu?.targetId;
        if (nodeId) {
          const cls = store.classes.find((c) => c.iri === nodeId);
          if (cls) store.selectEntity({ iri: nodeId, type: "class", label: cls.label });
        }
        break;
      }
      case "comment": {
        const nodeId = contextMenu?.targetId;
        if (nodeId && onOpenComments) {
          // Select the entity and open comments panel
          const node = cyRef.current?.getElementById(nodeId);
          if (node?.length) {
            store.selectEntity({ iri: nodeId, label: node.data("label") || nodeId, type: node.data("entityType") || "class" });
          }
          onOpenComments(nodeId);
        }
        break;
      }
      case "board-comment": {
        if (onOpenComments) {
          onOpenComments();
        }
        break;
      }
      case "delete": {
        const nodeId = contextMenu?.targetId;
        if (nodeId) {
          store.removeClass(nodeId);
          store.setIndividuals(store.individuals.filter((i) => i.iri !== nodeId));
          store.removeLiteral(nodeId);
          store.selectEntity(null);
        }
        break;
      }
      case "edit-edge": {
        const edgeId = contextMenu?.targetId;
        if (edgeId) {
          const e = cy.getElementById(edgeId);
          if (e.length) {
            const mid = e.midpoint();
            const zoom = cy.zoom(); const pan = cy.pan();
            setEditPopup({
              type: "edge", id: e.id(), label: e.data("displayLabel") || "",
              edgeType: e.data("edgeType") || "objectProperty",
              color: e.style("line-color") || "#10b981",
              lineStyle: (e.style("line-style") || "solid") as any,
              screenX: mid.x * zoom + pan.x + 20, screenY: mid.y * zoom + pan.y - 10,
            });
          }
        }
        break;
      }
      case "reverse": {
        const edgeId = contextMenu?.targetId;
        if (edgeId) {
          const prop = store.properties.find((p) => p.id === edgeId);
          if (prop) {
            store.setProperties(store.properties.map((p) => p.id === edgeId ? { ...p, source_id: p.target_id, target_id: p.source_id } : p));
          }
        }
        break;
      }
      case "delete-edge": {
        const edgeId = contextMenu?.targetId;
        if (edgeId) store.removeProperty(edgeId);
        break;
      }
      default: {
        // change-{edgeType} actions
        if (action.startsWith("change-")) {
          const newType = action.replace("change-", "");
          const edgeId = contextMenu?.targetId;
          if (edgeId) {
            const prop = store.properties.find((p) => p.id === edgeId);
            if (prop) {
              const isSub = newType === "subClassOf";
              const isType = newType === "rdfType";
              store.setProperties(store.properties.map((p) => p.id === edgeId ? {
                ...p,
                iri: isSub ? "rdfs:subClassOf" : isType ? "rdf:type" : prop.iri,
                property_type: isSub ? "annotation" : isType ? "annotation" : newType === "dataProperty" ? "data" : newType === "annotationProperty" ? "annotation" : "object",
              } : p));
            }
          }
        }
      }
    }
    setContextMenu(null);
  }, [contextMenu, store]);

  // ── Pattern drag-and-drop onto canvas ──────────────────────
  const handleCanvasDrop = useCallback(async (e: React.DragEvent) => {
    e.preventDefault();
    const patternId = e.dataTransfer.getData("application/x-odp-pattern");
    if (!patternId) return;
    const cy = cyRef.current;
    if (!cy) return;
    const rect = containerRef.current?.getBoundingClientRect();
    if (!rect) return;
    // Convert screen coordinates to graph coordinates
    const zoom = cy.zoom();
    const pan = cy.pan();
    const graphX = (e.clientX - rect.left - pan.x) / zoom;
    const graphY = (e.clientY - rect.top - pan.y) / zoom;
    // Apply the pattern at the drop position
    try {
      const result = await apiJson<{ classes: any[]; properties: any[]; pattern_id?: string }>(
        `/api/patterns/${boardId}/apply/${patternId}`,
        {
          method: "POST",
          body: JSON.stringify({
            base_iri: "http://example.org/ontology",
            x: graphX,
            y: graphY,
          }),
        }
      );
      const patternColor = getPatternColor(patternId);
      // Batch all additions into a single store update to avoid partial state
      const currentState = useOntologyStore.getState();
      const newClasses = [...currentState.classes];
      const newProperties = [...currentState.properties];
      const newPatternMap = { ...currentState.patternMap };

      for (const cls of result.classes || []) {
        if (!newClasses.some((c) => c.iri === cls.iri)) {
          newClasses.push({ ...cls, color: patternColor });
        }
        newPatternMap[cls.iri] = patternId;
      }
      for (const prop of (result as any).properties || []) {
        if (!newProperties.some((p) => p.id === prop.id)) {
          newProperties.push(prop);
        }
      }

      useOntologyStore.setState({
        classes: newClasses,
        properties: newProperties,
        patternMap: newPatternMap,
        dirty: true,
      });
      scheduleAutoSave();
    } catch (err) {
      console.error("Failed to apply pattern via drag-drop:", err);
    }
  }, [boardId, store]);

  const handleCanvasDragOver = useCallback((e: React.DragEvent) => {
    if (e.dataTransfer.types.includes("application/x-odp-pattern")) {
      e.preventDefault();
      e.dataTransfer.dropEffect = "copy";
    }
  }, []);

  // ── Fuzzy search across all entities ──────────────────────
  const executeSearch = useCallback((query: string) => {
    setSearchQuery(query);
    if (!query.trim()) { setSearchResults([]); return; }
    const q = query.toLowerCase();
    const results: { id: string; label: string; type: string; score: number }[] = [];
    // Fuzzy match: check if all chars of query appear in order
    const fuzzyMatch = (text: string, pattern: string): number => {
      const t = text.toLowerCase();
      if (t.includes(pattern)) return 100; // exact substring
      let pi = 0;
      for (let i = 0; i < t.length && pi < pattern.length; i++) {
        if (t[i] === pattern[pi]) pi++;
      }
      return pi === pattern.length ? 50 : 0;
    };
    for (const c of store.classes) {
      const labelScore = fuzzyMatch(c.label, q);
      const iriScore = fuzzyMatch(c.iri, q) * 0.5;
      const score = Math.max(labelScore, iriScore);
      if (score > 0) results.push({ id: c.iri, label: c.label, type: "class", score });
    }
    for (const i of store.individuals) {
      const score = Math.max(fuzzyMatch(i.label, q), fuzzyMatch(i.iri, q) * 0.5);
      if (score > 0) results.push({ id: i.iri, label: i.label, type: "individual", score });
    }
    for (const p of store.properties) {
      const score = Math.max(fuzzyMatch(p.label, q), fuzzyMatch(p.iri, q) * 0.5);
      if (score > 0) results.push({ id: p.id, label: p.label, type: "property", score });
    }
    results.sort((a, b) => b.score - a.score);
    setSearchResults(results.slice(0, 20));
    setSearchIdx(0);
    // Select and center first result
    if (results.length > 0) {
      navigateToResult(results[0]);
    }
  }, [store.classes, store.individuals, store.properties]);

  const navigateToResult = useCallback((result: { id: string; label: string; type: string }) => {
    const cy = cyRef.current;
    if (!cy) return;
    const node = cy.getElementById(result.id);
    if (node.length) {
      cy.elements().unselect();
      node.select();
      cy.animate({ center: { eles: node }, zoom: cy.zoom() }, { duration: 300 });
      store.selectEntity({ iri: result.id, type: result.type, label: result.label });
    }
  }, [store]);

  return (
    <div className={styles.container} onDrop={handleCanvasDrop} onDragOver={handleCanvasDragOver}>
      {/* ── Floating Toolbar ──────────────────────────────── */}
      <div className={styles.toolbar}>
        <div className={styles.group}>
          <button className={styles.btn} title="Add Class" onClick={() => {
            const iri = `http://example.org/new#Class_${Date.now()}`;
            store.addClass({ id: iri, iri, label: "NewClass", x: 200 + Math.random() * 400, y: 100 + Math.random() * 300, w: 160, h: 60, color: "#4f46e5" });
          }}>+ Class</button>
          <button className={styles.btn} title="Add Individual" onClick={() => {
            const iri = `http://example.org/new#Ind_${Date.now()}`;
            store.addIndividual({ id: iri, iri, label: "NewIndividual", class_iri: "", x: 300, y: 400 });
          }}>+ Individual</button>
          <button className={styles.btn} title="Add Literal value node" onClick={() => {
            const id = `lit_${Date.now()}`;
            store.addLiteral({ id, value: "value", datatype: "xsd:string", language: "", x: 400, y: 400 });
          }}>+ Literal</button>
          <button className={styles.btn} title="Add Sticky Note" onClick={() => {
            const cy = cyRef.current;
            const center = cy ? { x: -cy.pan().x / cy.zoom() + cy.width() / cy.zoom() / 2, y: -cy.pan().y / cy.zoom() + cy.height() / cy.zoom() / 2 } : { x: 300, y: 300 };
            store.addStickyNote({ id: `sticky_${Date.now()}`, text: "", x: center.x - 100, y: center.y - 75, w: 200, h: 150, color: "#fef3c7", fontSize: 14 });
          }}>+ Sticky</button>
          <button className={styles.btn} title="Draw Frame" onClick={() => {
            const cy = cyRef.current;
            const center = cy ? { x: -cy.pan().x / cy.zoom() + cy.width() / cy.zoom() / 2, y: -cy.pan().y / cy.zoom() + cy.height() / cy.zoom() / 2 } : { x: 300, y: 300 };
            store.addFrame({ id: `frame_${Date.now()}`, label: "Frame", x: center.x - 200, y: center.y - 150, w: 400, h: 300, color: "rgba(79, 70, 229, 0.05)", borderColor: "#c7d2fe" });
          }}>+ Frame</button>
        </div>

        <div className={styles.separator} />

        <div className={styles.group}>
          <select className={styles.edgeSelect} value={edgeType} onChange={(e) => setEdgeType(e.target.value as any)}>
            <option value="object">Object Prop</option>
            <option value="subClassOf">SubClassOf</option>
            <option value="data">Data Prop</option>
            <option value="rdfType">rdf:type</option>
            <option value="annotation">Annotation</option>
          </select>
        </div>

        <div className={styles.separator} />

        <div className={styles.group}>
          <select className={styles.layoutSelect} value={layoutName} onChange={(e) => { const l = e.target.value; setLayoutName(l); doLayout(l); }}>
            <option value="dagre">Tree Layout</option>
            <option value="cose-bilkent">Force Layout</option>
            <option value="grid">Grid Layout</option>
          </select>
          <button className={styles.btn} onClick={() => cyRef.current?.fit(undefined, 40)} title="Fit to view">Fit</button>
          <button className={`${styles.btn} ${snapToGrid ? styles.active : ""}`}
                  onClick={() => { const v = !snapToGrid; setSnapToGrid(v); snapRef.current = v; }}
                  title="Snap to grid">Snap</button>
        </div>

        <div className={styles.separator} />

        <div className={styles.group}>
          <button className={styles.btn} title="Zoom out"
                  onClick={() => { const cy = cyRef.current; if (cy) cy.zoom({ level: cy.zoom() * 0.8, renderedPosition: { x: cy.width() / 2, y: cy.height() / 2 } }); }}>&minus;</button>
          <span className={styles.zoomLabel}>{zoomLevel}%</span>
          <button className={styles.btn} title="Zoom in"
                  onClick={() => { const cy = cyRef.current; if (cy) cy.zoom({ level: cy.zoom() * 1.25, renderedPosition: { x: cy.width() / 2, y: cy.height() / 2 } }); }}>+</button>
        </div>

        <div className={styles.separator} />

        <button className={styles.btn} title="Export graph image" onClick={() => setShowExport(true)}>Export</button>

        <div className={styles.separator} />

        <button className={styles.btn} title="Search (Ctrl+F)" onClick={() => { setShowSearch(!showSearch); setTimeout(() => searchInputRef.current?.focus(), 50); }}>
          &#128269;
        </button>

        <span className={styles.save}>
          {store.saving ? "Saving..." : store.lastSaved > 0 ? "\u2713" : ""}
        </span>
      </div>

      {/* ── Search bar ──────────────────────────────────────── */}
      {showSearch && (
        <div className={styles.searchBar}>
          <input
            ref={searchInputRef}
            className={styles.searchInput}
            placeholder="Search entities... (fuzzy)"
            value={searchQuery}
            onChange={(e) => executeSearch(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && searchResults.length > 0) {
                const nextIdx = (searchIdx + 1) % searchResults.length;
                setSearchIdx(nextIdx);
                navigateToResult(searchResults[nextIdx]);
              }
              if (e.key === "Escape") { setShowSearch(false); setSearchQuery(""); setSearchResults([]); }
            }}
          />
          {searchResults.length > 0 && (
            <span className={styles.searchCount}>{searchIdx + 1}/{searchResults.length}</span>
          )}
          <button className={styles.searchNav} disabled={searchResults.length === 0}
                  onClick={() => { const i = (searchIdx - 1 + searchResults.length) % searchResults.length; setSearchIdx(i); navigateToResult(searchResults[i]); }}
                  title="Previous">&#9650;</button>
          <button className={styles.searchNav} disabled={searchResults.length === 0}
                  onClick={() => { const i = (searchIdx + 1) % searchResults.length; setSearchIdx(i); navigateToResult(searchResults[i]); }}
                  title="Next">&#9660;</button>
          <button className={styles.searchClose} onClick={() => { setShowSearch(false); setSearchQuery(""); setSearchResults([]); }}>&times;</button>
          {searchResults.length > 0 && (
            <div className={styles.searchDropdown}>
              {searchResults.map((r, i) => (
                <div key={r.id} className={`${styles.searchResult} ${i === searchIdx ? styles.searchResultActive : ""}`}
                     onClick={() => { setSearchIdx(i); navigateToResult(r); }}>
                  <span className={styles.searchResultType}>{r.type === "class" ? "C" : r.type === "individual" ? "I" : "P"}</span>
                  <span className={styles.searchResultLabel}>{r.label}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

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
            store.removeLiteral(selected);
            store.selectEntity(null);
          }}>Delete</button>
        </div>
      )}

      {/* ── Legend ──────────────────────────────────────────── */}
      <CanvasLegend prefixColors={store.prefixColors} patternMap={store.patternMap} />

      <Minimap cyRef={cyRef} />
      <div ref={cursorsRef} className={styles.cursorsLayer} />

      {store.frames.map((frame) => (
        <CanvasFrameComponent key={frame.id} frame={frame}
          zoom={viewport.zoom} pan={viewport.pan}
          onUpdate={store.updateFrame} onDelete={store.removeFrame} />
      ))}

      {store.stickyNotes.map((note) => (
        <StickyNoteComponent key={note.id} note={note}
          zoom={viewport.zoom} pan={viewport.pan}
          onUpdate={store.updateStickyNote} onDelete={store.removeStickyNote} />
      ))}

      <div ref={containerRef} className={styles.canvas} />

      {editPopup && (
        <EditPopup data={editPopup} onSave={handlePopupSave}
                   onDelete={handlePopupDelete} onCancel={() => setEditPopup(null)} />
      )}

      {contextMenu && (
        <ContextMenu data={contextMenu} onAction={handleContextAction}
                     onClose={() => setContextMenu(null)} />
      )}

      {showExport && (
        <ExportDialog cyRef={cyRef} onClose={() => setShowExport(false)} />
      )}
    </div>
  );
}
