/**
 * OntologyCanvas — Tldraw-based infinite canvas for ontology editing.
 *
 * Shapes are mapped:
 *   - Geo rectangles (violet)  → owl:Class
 *   - Geo diamonds (orange)    → owl:NamedIndividual
 *   - Arrows (green)           → owl:ObjectProperty
 *   - Arrows (blue, dashed)    → rdfs:subClassOf
 *   - Notes (yellow)           → comments/annotations
 *
 * Users can also use Tldraw's built-in arrow tool to connect shapes.
 * Connected arrows become owl:ObjectProperty on save.
 */

import { useCallback, useRef, useState } from "react";
import { Tldraw, Editor, createShapeId, toRichText } from "tldraw";
import "tldraw/tldraw.css";
import { useCanvasSync } from "./useCanvasSync";
import styles from "./OntologyCanvas.module.css";

interface Props {
  boardId: string;
  onSelectEntity?: (entity: { iri: string; type: string; label: string } | null) => void;
}

export default function OntologyCanvas({ boardId, onSelectEntity }: Props) {
  const editorRef = useRef<Editor | null>(null);
  const { loadCanvas, saveCanvas, loading, error } = useCanvasSync(boardId);
  const [loaded, setLoaded] = useState(false);
  const [saved, setSaved] = useState(false);

  // onMount MUST be synchronous — Tldraw expects void | (() => void)
  const handleMount = useCallback((editor: Editor) => {
    editorRef.current = editor;

    // Auto-assign ontology metadata to new shapes
    const unsubCreate = editor.sideEffects.registerAfterCreateHandler("shape", (shape) => {
      if (shape.type === "geo" && !shape.meta?.entityType) {
        // Auto-tag new geo shapes as classes
        const geo = (shape.props as any).geo;
        const entityType = geo === "diamond" ? "individual" : "class";
        const iri = `http://example.org/new#${entityType}_${Date.now()}`;
        editor.updateShape({
          id: shape.id,
          type: shape.type,
          meta: { iri, entityType },
        });
      }
      if (shape.type === "arrow" && !shape.meta?.entityType) {
        // Auto-tag new arrows as object properties
        const iri = `http://example.org/new#prop_${Date.now()}`;
        editor.updateShape({
          id: shape.id,
          type: shape.type,
          meta: { iri, entityType: "property", propertyType: "object" },
        });
      }
    });

    // Listen for selection changes
    const unsubSelect = editor.sideEffects.registerAfterChangeHandler(
      "instance_page_state",
      () => {
        if (!onSelectEntity) return;
        const selectedIds = editor.getSelectedShapeIds();
        if (selectedIds.length === 1) {
          const shape = editor.getShape(selectedIds[0]);
          if (shape?.meta?.iri) {
            onSelectEntity({
              iri: shape.meta.iri as string,
              type: (shape.meta.entityType as string) || "class",
              label: extractLabel(shape),
            });
          }
        } else {
          onSelectEntity(null);
        }
      },
    );

    // Load canvas state from backend
    loadCanvas(editor).then(() => setLoaded(true));

    return () => {
      unsubCreate();
      unsubSelect();
    };
  }, [loadCanvas, onSelectEntity]);

  const handleAddClass = () => {
    const editor = editorRef.current;
    if (!editor) return;
    const center = editor.getViewportScreenCenter();
    const point = editor.screenToPage(center);
    const newIri = `http://example.org/new#Class_${Date.now()}`;
    editor.createShape({
      id: createShapeId(),
      type: "geo",
      x: point.x - 80,
      y: point.y - 30,
      props: {
        w: 160, h: 60, geo: "rectangle",
        richText: toRichText("NewClass"), color: "violet", fill: "semi",
        font: "sans", size: "m",
      },
      meta: { iri: newIri, entityType: "class" },
    });
  };

  const handleAddIndividual = () => {
    const editor = editorRef.current;
    if (!editor) return;
    const center = editor.getViewportScreenCenter();
    const point = editor.screenToPage(center);
    const newIri = `http://example.org/new#Ind_${Date.now()}`;
    editor.createShape({
      id: createShapeId(),
      type: "geo",
      x: point.x - 70,
      y: point.y - 25,
      props: {
        w: 140, h: 50, geo: "diamond",
        richText: toRichText("NewIndividual"), color: "orange", fill: "semi",
        font: "sans", size: "s",
      },
      meta: { iri: newIri, entityType: "individual", classIri: "" },
    });
  };

  const handleAddNote = () => {
    const editor = editorRef.current;
    if (!editor) return;
    const center = editor.getViewportScreenCenter();
    const point = editor.screenToPage(center);
    editor.createShape({
      id: createShapeId(),
      type: "note",
      x: point.x - 50,
      y: point.y - 50,
      props: {
        richText: toRichText("Add a note..."),
        color: "yellow",
        size: "m",
        font: "sans",
      },
    });
  };

  const handleSave = async () => {
    if (!editorRef.current) return;
    await saveCanvas(editorRef.current);
    setSaved(true);
    setTimeout(() => setSaved(false), 2000);
  };

  const handleLoad = () => {
    const editor = editorRef.current;
    if (!editor) return;
    const allShapeIds = editor.getCurrentPageShapeIds();
    if (allShapeIds.size > 0) {
      editor.deleteShapes([...allShapeIds]);
    }
    loadCanvas(editor);
  };

  return (
    <div className={styles.container}>
      {/* Overlay toolbar */}
      <div className={styles.toolbar}>
        <button className={styles.btn} onClick={handleAddClass} title="Add owl:Class (rectangle)">
          + Class
        </button>
        <button className={styles.btn} onClick={handleAddIndividual} title="Add Individual (diamond)">
          + Individual
        </button>
        <button className={styles.btn} onClick={handleAddNote} title="Add a note">
          + Note
        </button>
        <span className={styles.separator} />
        <span className={styles.hint}>Use arrow tool to connect classes</span>
        <span className={styles.separator} />
        <button className={styles.btn} onClick={handleLoad} title="Reload from OWL file">
          Reload
        </button>
        <button className={`${styles.btn} ${styles.saveBtn}`} onClick={handleSave} title="Save to OWL file">
          {loading ? "Saving..." : saved ? "Saved!" : "Save"}
        </button>
        {error && <span className={styles.error}>{error}</span>}
      </div>

      {/* Tldraw canvas */}
      <div className={styles.canvas}>
        <Tldraw onMount={handleMount} />
      </div>
    </div>
  );
}

/** Extract plaintext from a shape's richText or text prop. */
function extractLabel(shape: any): string {
  const props = shape?.props || {};
  if (props.richText && typeof props.richText === "object" && props.richText.content) {
    return props.richText.content
      ?.map((p: any) => p.content?.map((t: any) => t.text || "").join("") || "")
      .join("\n")
      .trim() || "";
  }
  if (props.text) return props.text;
  return "";
}
