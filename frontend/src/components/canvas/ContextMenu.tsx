/**
 * ContextMenu — right-click menu for canvas, nodes, and edges.
 * Shows provenance info, axiom shortcuts, and entity operations.
 */
import { useEffect, useRef } from "react";
import styles from "./ContextMenu.module.css";

export interface ContextMenuData {
  x: number;
  y: number;
  target: "canvas" | "node" | "edge";
  targetId?: string;
  targetType?: string;
  graphPosition?: { x: number; y: number };
  provenance?: {
    created_by?: string;
    created_at?: string;
    modified_by?: string;
    modified_at?: string;
  };
}

interface Props {
  data: ContextMenuData;
  onAction: (action: string) => void;
  onClose: () => void;
}

function formatDate(iso?: string): string {
  if (!iso) return "";
  try {
    const d = new Date(iso);
    return d.toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" }) + " " +
           d.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
  } catch { return iso; }
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

  const item = (label: string, action: string, icon?: string) => (
    <button className={styles.item} onClick={() => onAction(action)}>
      {icon && <span className={styles.icon}>{icon}</span>}
      {label}
    </button>
  );

  const sectionLabel = (label: string) => (
    <div className={styles.sectionLabel}>{label}</div>
  );

  const prov = data.provenance;
  const hasProv = prov && (prov.created_by || prov.modified_by);

  return (
    <div ref={ref} className={styles.menu} style={{ left: data.x, top: data.y }}>
      {data.target === "canvas" && (
        <>
          {sectionLabel("Add Entity")}
          {item("Class", "add-class", "\u25A1")}
          {item("Individual", "add-individual", "\u25C7")}
          {item("Literal", "add-literal", "\u25CB")}
          {item("Sticky Note", "add-sticky", "\u25A0")}
          <div className={styles.divider} />
          {item("Paste", "paste", "\u2398")}
        </>
      )}
      {data.target === "node" && (
        <>
          {/* Provenance info — shown at top if available */}
          {hasProv && (
            <>
              <div className={styles.provSection}>
                {prov.created_by && (
                  <div className={styles.provRow}>
                    <span className={styles.provLabel}>Created by</span>
                    <span className={styles.provValue}>{prov.created_by}</span>
                  </div>
                )}
                {prov.created_at && (
                  <div className={styles.provRow}>
                    <span className={styles.provLabel}>Created</span>
                    <span className={styles.provValue}>{formatDate(prov.created_at)}</span>
                  </div>
                )}
                {prov.modified_by && prov.modified_by !== prov.created_by && (
                  <div className={styles.provRow}>
                    <span className={styles.provLabel}>Modified by</span>
                    <span className={styles.provValue}>{prov.modified_by}</span>
                  </div>
                )}
                {prov.modified_at && prov.modified_at !== prov.created_at && (
                  <div className={styles.provRow}>
                    <span className={styles.provLabel}>Modified</span>
                    <span className={styles.provValue}>{formatDate(prov.modified_at)}</span>
                  </div>
                )}
              </div>
              <div className={styles.divider} />
            </>
          )}
          {sectionLabel("Edit")}
          {item("Edit Properties...", "edit", "\u270E")}
          {item("Edit IRI...", "edit-iri", "\u2709")}
          {item("Connect to...", "connect", "\u2192")}
          <div className={styles.divider} />
          {sectionLabel("Structure")}
          {data.targetType === "class" && item("Add SubClass", "add-subclass", "\u2514")}
          {data.targetType === "class" && item("Add SuperClass", "add-superclass", "\u250C")}
          {data.targetType === "class" && item("Add Sibling Class", "add-sibling", "\u251C")}
          {data.targetType === "individual" && item("Set rdf:type", "add-rdftype", "\u2261")}
          <div className={styles.divider} />
          {sectionLabel("Axioms")}
          {item("Add SubClassOf Axiom", "axiom-subclassof", "\u2286")}
          {item("Add EquivalentTo Axiom", "axiom-equivalent", "\u2261")}
          {item("Add DisjointWith Axiom", "axiom-disjoint", "\u2260")}
          {item("View in Axiom Editor", "view-axioms", "\u2263")}
          <div className={styles.divider} />
          {item("Open IRI in Browser", "open-iri", "\u2197")}
          {item("Duplicate", "duplicate", "\u2750")}
          {item("Copy IRI", "copy-iri", "\u2398")}
          <div className={styles.divider} />
          <button className={`${styles.item} ${styles.danger}`} onClick={() => onAction("delete")}>
            <span className={styles.icon}>{"\u2716"}</span> Delete
          </button>
        </>
      )}
      {data.target === "edge" && (
        <>
          {hasProv && (
            <>
              <div className={styles.provSection}>
                {prov.created_by && (
                  <div className={styles.provRow}>
                    <span className={styles.provLabel}>Created by</span>
                    <span className={styles.provValue}>{prov.created_by}</span>
                  </div>
                )}
                {prov.created_at && (
                  <div className={styles.provRow}>
                    <span className={styles.provLabel}>Created</span>
                    <span className={styles.provValue}>{formatDate(prov.created_at)}</span>
                  </div>
                )}
              </div>
              <div className={styles.divider} />
            </>
          )}
          {item("Edit...", "edit-edge", "\u270E")}
          <div className={styles.divider} />
          {sectionLabel("Change Type")}
          {item("SubClassOf", "change-subClassOf")}
          {item("Object Property", "change-objectProperty")}
          {item("Data Property", "change-dataProperty")}
          {item("rdf:type", "change-rdfType")}
          {item("Annotation", "change-annotationProperty")}
          <div className={styles.divider} />
          {item("Reverse Direction", "reverse", "\u21C4")}
          <button className={`${styles.item} ${styles.danger}`} onClick={() => onAction("delete-edge")}>
            <span className={styles.icon}>{"\u2716"}</span> Delete
          </button>
        </>
      )}
    </div>
  );
}
