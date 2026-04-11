/**
 * OntologyCanvas — React Flow ontology graph editor.
 *
 * Epic 4 Features:
 *   - Double-click to rename nodes inline
 *   - Smart rdf:type detection (Individual→Class = rdf:type)
 *   - Visual distinction: solid green (objProp), dashed blue (subClassOf), dotted gray (rdf:type)
 *   - Connection handles visible on hover
 *   - White background
 */

import { useCallback, useEffect, useMemo, useState } from "react";
import {
  ReactFlow, Background, Controls, MiniMap,
  useNodesState, useEdgesState,
  Connection, Edge, Node, Handle, Position, MarkerType, BackgroundVariant,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { useOntologyStore } from "../../store/ontologyStore";
import styles from "./OntologyCanvas.module.css";

// ── ClassNode with inline rename ──────────────────────────────
function ClassNode({ id, data, selected }: any) {
  const [editing, setEditing] = useState(false);
  const [label, setLabel] = useState(data.label || "");
  const updateClass = useOntologyStore((s) => s.updateClass);

  const handleDoubleClick = () => { setEditing(true); setLabel(data.label); };
  const handleBlur = () => { setEditing(false); if (label !== data.label) updateClass(id, { label }); };
  const handleKey = (e: any) => { if (e.key === "Enter") { e.target.blur(); } };

  return (
    <div className={`${styles.classNode} ${selected ? styles.selected : ""}`}
         style={data.color ? { borderColor: data.color } : {}}
         onDoubleClick={handleDoubleClick}
         title={`${data.iri}\nrdfs:label: ${data.label}`}>
      <Handle type="target" position={Position.Top} className={styles.handle} id="top" />
      <Handle type="target" position={Position.Left} className={styles.handle} id="left" />
      {editing ? (
        <input className={styles.nodeInput} value={label} onChange={(e) => setLabel(e.target.value)}
               onBlur={handleBlur} onKeyDown={handleKey} autoFocus />
      ) : (
        <>
          <div className={styles.nodeLabel}>{data.label}</div>
          <div className={styles.nodeIri}>{data.iri?.split("#").pop() || data.iri?.split("/").pop() || ""}</div>
        </>
      )}
      <Handle type="source" position={Position.Bottom} className={styles.handle} id="bottom" />
      <Handle type="source" position={Position.Right} className={styles.handle} id="right" />
    </div>
  );
}

// ── IndividualNode with inline rename ─────────────────────────
function IndividualNode({ id, data, selected }: any) {
  const [editing, setEditing] = useState(false);
  const [label, setLabel] = useState(data.label || "");
  const store = useOntologyStore();

  const handleDoubleClick = () => { setEditing(true); setLabel(data.label); };
  const handleBlur = () => {
    setEditing(false);
    const inds = store.individuals.map((i) => i.iri === id ? { ...i, label } : i);
    store.setIndividuals(inds);
  };

  return (
    <div className={`${styles.individualNode} ${selected ? styles.selected : ""}`}
         onDoubleClick={handleDoubleClick}>
      <Handle type="target" position={Position.Top} className={styles.handle} id="top" />
      <Handle type="target" position={Position.Left} className={styles.handle} id="left" />
      {editing ? (
        <input className={styles.nodeInput} value={label} onChange={(e) => setLabel(e.target.value)}
               onBlur={handleBlur} onKeyDown={(e) => e.key === "Enter" && (e.target as any).blur()} autoFocus />
      ) : (
        <div className={styles.nodeLabel}>{data.label}</div>
      )}
      <Handle type="source" position={Position.Bottom} className={styles.handle} id="bottom" />
      <Handle type="source" position={Position.Right} className={styles.handle} id="right" />
    </div>
  );
}

// ── LiteralNode (xsd:string, xsd:int, etc.) ──────────────────
function LiteralNode({ data }: any) {
  return (
    <div className={styles.literalNode}>
      <Handle type="target" position={Position.Top} className={styles.handle} id="top" />
      <div className={styles.literalValue}>"{data.label}"</div>
      <div className={styles.literalType}>{data.datatype || "xsd:string"}</div>
    </div>
  );
}

const nodeTypes = {
  classNode: ClassNode,
  individualNode: IndividualNode,
  literalNode: LiteralNode,
};

interface Props { boardId: string }

export default function OntologyCanvas({ boardId }: Props) {
  const store = useOntologyStore();
  const { classes, properties, individuals, selectEntity, addClass, addProperty } = store;

  const flowNodes: Node[] = useMemo(() => [
    ...classes.map((cls) => ({
      id: cls.iri, type: "classNode" as const,
      position: { x: cls.x || 0, y: cls.y || 0 },
      data: { label: cls.label, iri: cls.iri, entityType: "class", color: cls.color },
    })),
    ...individuals.map((ind, i) => ({
      id: ind.iri, type: "individualNode" as const,
      position: { x: ind.x || 100 + i * 200, y: ind.y || 400 },
      data: { label: ind.label, iri: ind.iri, entityType: "individual" },
    })),
  ], [classes, individuals]);

  const flowEdges: Edge[] = useMemo(() => properties.map((p) => {
    const isSub = p.iri === "rdfs:subClassOf";
    const isType = p.iri === "rdf:type";
    const isData = p.property_type === "data";
    const isAnnotation = p.property_type === "annotation" && !isSub;
    return {
      id: p.id, source: p.source_id, target: p.target_id,
      label: p.label, animated: false,
      style: {
        stroke: isSub ? "#3498db" : isType ? "#95a5a6" : isData ? "#e67e22" : isAnnotation ? "#9b59b6" : "#27ae60",
        strokeDasharray: isSub ? "8 4" : isType ? "4 4" : isAnnotation ? "4 2" : undefined,
        strokeWidth: isSub ? 2 : 1.5,
      },
      markerEnd: {
        type: MarkerType.ArrowClosed,
        color: isSub ? "#3498db" : isType ? "#95a5a6" : isData ? "#e67e22" : "#27ae60",
      },
    };
  }), [properties]);

  const [nodes, setNodes, onNodesChange] = useNodesState(flowNodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState(flowEdges);

  useEffect(() => { setNodes(flowNodes); }, [flowNodes, setNodes]);
  useEffect(() => { setEdges(flowEdges); }, [flowEdges, setEdges]);
  useEffect(() => { store.loadFromBackend(boardId); }, [boardId]);

  // Smart edge detection: Individual→Class = rdf:type
  const onConnect = useCallback((conn: Connection) => {
    if (!conn.source || !conn.target) return;
    const sourceNode = store.individuals.find((i) => i.iri === conn.source);
    const targetNode = store.classes.find((c) => c.iri === conn.target);

    if (sourceNode && targetNode) {
      // Individual→Class = rdf:type
      addProperty({
        id: `rdf_type_${conn.source}_${conn.target}`,
        iri: "rdf:type", label: "rdf:type",
        source_id: conn.source, target_id: conn.target,
        property_type: "annotation",
      });
    } else {
      // Default: object property
      const ts = Date.now();
      addProperty({
        id: `http://example.org/new#prop_${ts}`,
        iri: `http://example.org/new#prop_${ts}`,
        label: "relatedTo",
        source_id: conn.source, target_id: conn.target,
        property_type: "object",
      });
    }
  }, [store.individuals, store.classes, addProperty]);

  const onNodeDragStop = useCallback((_: any, node: Node) => {
    store.updateClass(node.id, { x: node.position.x, y: node.position.y });
  }, [store]);

  const onNodeClick = useCallback((_: any, node: Node) => {
    selectEntity({
      iri: node.id,
      type: (node.data?.entityType as string) || "class",
      label: (node.data?.label as string) || "",
    });
  }, [selectEntity]);

  const onNodesDelete = useCallback((deleted: Node[]) => {
    const classIds = deleted.filter((n) => n.type === "classNode").map((n) => n.id);
    const indIds = deleted.filter((n) => n.type === "individualNode").map((n) => n.id);
    classIds.forEach((id) => store.removeClass(id));
    if (indIds.length > 0) {
      store.setIndividuals(store.individuals.filter((i) => !indIds.includes(i.iri)));
    }
  }, [store]);

  const onEdgesDelete = useCallback((deleted: Edge[]) => {
    deleted.forEach((edge) => store.removeProperty(edge.id));
  }, [store]);

  return (
    <div className={styles.container}>
      <div className={styles.toolbar}>
        <button className={styles.btn} onClick={() => {
          const iri = `http://example.org/new#Class_${Date.now()}`;
          addClass({ id: iri, iri, label: "NewClass", x: 200 + Math.random() * 300, y: 100 + Math.random() * 200, w: 160, h: 60, color: "#6c5ce7" });
        }}>+ Class</button>
        <button className={styles.btn} onClick={() => {
          const iri = `http://example.org/new#Ind_${Date.now()}`;
          store.setIndividuals([...individuals, { id: iri, iri, label: "NewIndividual", class_iri: "", x: 200 + Math.random() * 300, y: 400 }]);
        }}>+ Individual</button>
        <span className={styles.separator} />
        <span className={styles.hint}>Drag handles to connect &middot; Dbl-click to rename &middot; Ind→Class = rdf:type</span>
        <span className={styles.separator} />
        <span className={styles.saveIndicator}>
          {store.saving ? "Saving..." : store.lastSaved > 0 ? "\u2713 Saved" : ""}
        </span>
      </div>
      <div className={styles.canvas}>
        <ReactFlow
          nodes={nodes} edges={edges}
          onNodesChange={onNodesChange} onEdgesChange={onEdgesChange}
          onNodesDelete={onNodesDelete} onEdgesDelete={onEdgesDelete}
          onConnect={onConnect} onNodeDragStop={onNodeDragStop}
          onNodeClick={onNodeClick} onPaneClick={() => selectEntity(null)}
          nodeTypes={nodeTypes} fitView snapToGrid snapGrid={[16, 16]}
          defaultEdgeOptions={{ type: "default", markerEnd: { type: MarkerType.ArrowClosed } }}
        >
          <Background variant={BackgroundVariant.Dots} gap={20} size={1} color="#e0e0e0" />
          <Controls />
          <MiniMap
            nodeColor={(n) => n.type === "classNode" ? "#6c5ce7" : n.type === "individualNode" ? "#fdcb6e" : "#55efc4"}
            style={{ background: "#f8f9fa" }}
          />
        </ReactFlow>
      </div>
    </div>
  );
}
