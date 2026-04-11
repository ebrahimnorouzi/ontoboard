/**
 * OntologyCanvas — Cytoscape.js based ontology graph editor.
 *
 * Features:
 *   - dagre layout for SubClassOf trees
 *   - cose-bilkent force layout for large graphs
 *   - Prefix-aware labels (prov:Activity, foaf:Person)
 *   - Semantic edge styles by type
 *   - Entity editing: color, label rename, color picker
 *   - Edge drawing mode
 *   - Handles 400+ nodes smoothly
 *   - Auto-save via Zustand store
 */

import { useEffect, useRef, useState, useCallback } from "react";
import cytoscape from "cytoscape";
import dagre from "cytoscape-dagre";
import coseBilkent from "cytoscape-cose-bilkent";
import { useOntologyStore } from "../../store/ontologyStore";
import styles from "./OntologyCanvas.module.css";

cytoscape.use(dagre);
cytoscape.use(coseBilkent);

// ── Prefix compact display ────────────────────────────────────
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
  "http://www.w3.org/ns/dcat#": "dcat:",
};

function compact(iri: string): string {
  for (const [ns, p] of Object.entries(PREFIXES)) {
    if (iri.startsWith(ns)) return p + iri.slice(ns.length);
  }
  return iri.includes("#") ? iri.split("#").pop() || iri : iri.split("/").pop() || iri;
}

interface Props { boardId: string }

export default function OntologyCanvas({ boardId }: Props) {
  const cyRef = useRef<cytoscape.Core | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const store = useOntologyStore();
  const [layoutName, setLayoutName] = useState("dagre");
  const [edgeMode, setEdgeMode] = useState(false);
  const [edgeSource, setEdgeSource] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [editLabel, setEditLabel] = useState("");
  const [editColor, setEditColor] = useState("#4f46e5");

  // ── Init Cytoscape ──────────────────────────────────────────
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
        { selector: "node:selected", style: {
          "border-color": "#0ea5e9", "border-width": 3,
          "overlay-opacity": 0.06, "overlay-color": "#0ea5e9",
        }},
        { selector: "edge[edgeType='subClassOf']", style: {
          "line-color": "#6366f1", "target-arrow-color": "#6366f1",
          "target-arrow-shape": "triangle-backcurve", "curve-style": "bezier",
          width: 2, "line-style": "dashed", "line-dash-pattern": [8, 4],
          "arrow-scale": 1.2,
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
          "font-size": "8px", color: "#92400e",
        }},
        { selector: "edge[edgeType='annotationProperty']", style: {
          "line-color": "#a855f7", "target-arrow-color": "#a855f7",
          "target-arrow-shape": "triangle", "curve-style": "bezier",
          width: 1, "line-style": "dotted", label: "data(displayLabel)",
          "font-size": "8px", color: "#7c3aed",
        }},
      ],
      layout: { name: "preset" },
      wheelSensitivity: 0.3, minZoom: 0.05, maxZoom: 4,
    });
    cyRef.current = cy;

    cy.on("tap", "node", (evt) => {
      const n = evt.target;
      if (edgeMode && edgeSource) {
        store.addProperty({ id: `prop_${Date.now()}`, iri: `http://example.org/new#prop_${Date.now()}`,
          label: "relatedTo", source_id: edgeSource, target_id: n.id(), property_type: "object" });
        setEdgeSource(null); setEdgeMode(false);
      } else if (edgeMode) {
        setEdgeSource(n.id());
      } else {
        setSelected(n.id()); setEditLabel(n.data("label") || "");
        store.selectEntity({ iri: n.id(), type: n.data("entityType"), label: n.data("label") });
      }
    });
    cy.on("tap", (e) => { if (e.target === cy) { setSelected(null); store.selectEntity(null); } });
    cy.on("dragfree", "node", (e) => {
      const p = e.target.position();
      store.updateClass(e.target.id(), { x: p.x, y: p.y });
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
          displayLabel: c.label || compact(c.iri), entityType: "class", iri: c.iri },
          position: { x: c.x || 0, y: c.y || 0 } });
      }
      for (const i of store.individuals) {
        cy.add({ group: "nodes", data: { id: i.iri, label: i.label,
          displayLabel: i.label || compact(i.iri), entityType: "individual", iri: i.iri },
          position: { x: i.x || 0, y: i.y || 0 } });
      }
      for (const p of store.properties) {
        if (!cy.getElementById(p.source_id).length || !cy.getElementById(p.target_id).length) continue;
        const isSub = p.iri === "rdfs:subClassOf";
        const isType = p.iri === "rdf:type";
        const isData = p.property_type === "data";
        const isAnn = p.property_type === "annotation" && !isSub;
        cy.add({ group: "edges", data: { id: p.id, source: p.source_id, target: p.target_id,
          label: p.label, displayLabel: isSub ? "" : compact(p.iri),
          edgeType: isSub ? "subClassOf" : isType ? "rdfType" : isData ? "dataProperty" : isAnn ? "annotationProperty" : "objectProperty" } });
      }
    });
    if (store.classes.some((c) => c.x === 0 && c.y === 0) && cy.nodes().length > 0) {
      doLayout(layoutName);
    }
  }, [store.classes, store.properties, store.individuals]);

  useEffect(() => { store.loadFromBackend(boardId); }, [boardId]);

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
  };

  return (
    <div className={styles.container}>
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
          <button className={`${styles.btn} ${edgeMode ? styles.active : ""}`}
                  onClick={() => { setEdgeMode(!edgeMode); setEdgeSource(null); }}>
            {edgeMode ? (edgeSource ? "\u2192 Click target" : "\u2022 Click source") : "\u2194 Draw Edge"}
          </button>
        </div>
        <div className={styles.group}>
          <span className={styles.layoutLabel}>Layout:</span>
          {["dagre", "cose-bilkent", "grid"].map((l) => (
            <button key={l} className={`${styles.btn} ${layoutName === l ? styles.active : ""}`}
                    onClick={() => { setLayoutName(l); doLayout(l); }}>
              {l === "dagre" ? "Tree" : l === "cose-bilkent" ? "Force" : "Grid"}
            </button>
          ))}
          <button className={styles.btn} onClick={() => cyRef.current?.fit(undefined, 40)}>Fit</button>
        </div>
        <span className={styles.save}>
          {store.saving ? "Saving..." : store.lastSaved > 0 ? "\u2713 Saved" : ""}
        </span>
      </div>

      {selected && (
        <div className={styles.editor}>
          <input className={styles.nameInput} value={editLabel} onChange={(e) => setEditLabel(e.target.value)}
                 onBlur={rename} onKeyDown={(e) => e.key === "Enter" && rename()} />
          <input type="color" className={styles.colorPick} value={editColor} onChange={(e) => {
            setEditColor(e.target.value);
            cyRef.current?.getElementById(selected).style("border-color", e.target.value);
          }} />
          <span className={styles.iri}>{compact(selected)}</span>
        </div>
      )}

      <div className={styles.legend}>
        <span><i className={styles.dot} style={{ background: "#4f46e5" }} /> Class</span>
        <span><i className={styles.dot} style={{ background: "#d97706" }} /> Individual</span>
        <span><i className={styles.line} style={{ borderColor: "#6366f1", borderStyle: "dashed" }} /> SubClassOf</span>
        <span><i className={styles.line} style={{ borderColor: "#10b981" }} /> ObjProp</span>
        <span><i className={styles.line} style={{ borderColor: "#f59e0b", borderStyle: "dashed" }} /> DataProp</span>
        <span><i className={styles.line} style={{ borderColor: "#a855f7", borderStyle: "dotted" }} /> AnnotProp</span>
      </div>

      <div ref={containerRef} className={styles.canvas} />
    </div>
  );
}
