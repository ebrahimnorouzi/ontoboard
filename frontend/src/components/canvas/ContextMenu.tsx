/**
 * ContextMenu — right-click menu for canvas, nodes, and edges.
 */
import { useEffect, useRef } from "react";
import styles from "./ContextMenu.module.css";

export interface ContextMenuData {
  x: number;
  y: number;
  target: "canvas" | "node" | "edge";
  targetId?: string;
  targetType?: string; // entityType for nodes, edgeType for edges
  graphPosition?: { x: number; y: number };
}

interface Props {
  data: ContextMenuData;
  onAction: (action: string) => void;
  onClose: () => void;
}

export default function ContextMenu({ data, onAction, onClose }: Props) {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) onClose();
    };
    const keyHandler = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    document.addEventListener("mousedown", handler);
    document.addEventListener("keydown", keyHandler);
    return () => { document.removeEventListener("mousedown", handler); document.removeEventListener("keydown", keyHandler); };
  }, [onClose]);

  const item = (label: string, action: string) => (
    <button className={styles.item} onClick={() => onAction(action)}>{label}</button>
  );

  return (
    <div ref={ref} className={styles.menu} style={{ left: data.x, top: data.y }}>
      {data.target === "canvas" && (
        <>
          {item("+ Add Class", "add-class")}
          {item("+ Add Individual", "add-individual")}
          {item("+ Add Literal", "add-literal")}
          {item("+ Add Sticky Note", "add-sticky")}
          <div className={styles.divider} />
          {item("Paste", "paste")}
        </>
      )}
      {data.target === "node" && (
        <>
          {item("Edit...", "edit")}
          {item("Connect to...", "connect")}
          <div className={styles.divider} />
          {data.targetType === "class" && item("+ Add SubClass", "add-subclass")}
          {data.targetType === "individual" && item("+ Set rdf:type", "add-rdftype")}
          {item("Duplicate", "duplicate")}
          {item("Copy IRI", "copy-iri")}
          <div className={styles.divider} />
          {item("Delete", "delete")}
        </>
      )}
      {data.target === "edge" && (
        <>
          {item("Edit...", "edit-edge")}
          <div className={styles.divider} />
          {item("SubClassOf", "change-subClassOf")}
          {item("Object Property", "change-objectProperty")}
          {item("Data Property", "change-dataProperty")}
          {item("rdf:type", "change-rdfType")}
          {item("Annotation", "change-annotationProperty")}
          <div className={styles.divider} />
          {item("Reverse Direction", "reverse")}
          {item("Delete", "delete-edge")}
        </>
      )}
    </div>
  );
}
