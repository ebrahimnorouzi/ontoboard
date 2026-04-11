/**
 * Hook that syncs Tldraw editor state ↔ backend CanvasState.
 * Uses Tldraw v4 API (richText, getBindingsFromShape, etc.)
 */

import { useCallback, useState } from "react";
import { Editor, TLShapeId, createShapeId, toRichText, getArrowBindings } from "tldraw";
import { apiJson } from "../../api";

interface CanvasClass {
  id: string; iri: string; label: string;
  x: number; y: number; w: number; h: number; color: string;
}
interface CanvasProperty {
  id: string; iri: string; label: string;
  source_id: string; target_id: string; property_type: string;
}
interface CanvasIndividual {
  id: string; iri: string; label: string; class_iri: string;
  x: number; y: number;
}
interface CanvasState {
  classes: CanvasClass[];
  properties: CanvasProperty[];
  individuals: CanvasIndividual[];
}

// Map IRI → Tldraw shape ID
const iriToShapeId = new Map<string, TLShapeId>();

function makeShapeId(iri: string): TLShapeId {
  const safe = iri.replace(/[^a-zA-Z0-9]/g, "_").slice(-40);
  const id = createShapeId(safe);
  iriToShapeId.set(iri, id);
  return id;
}

export function useCanvasSync(boardId: string | undefined) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const loadCanvas = useCallback(async (editor: Editor) => {
    if (!boardId) return;
    setLoading(true);
    setError("");
    try {
      const state = await apiJson<CanvasState>(`/api/owl/${boardId}/load`);
      iriToShapeId.clear();

      // Create class shapes (geo rectangles)
      const classShapes = state.classes.map((cls) => ({
        id: makeShapeId(cls.iri),
        type: "geo" as const,
        x: cls.x,
        y: cls.y,
        props: {
          w: cls.w || 160,
          h: cls.h || 60,
          geo: "rectangle" as const,
          richText: toRichText(cls.label),
          color: "violet" as const,
          fill: "semi" as const,
          font: "sans" as const,
          size: "m" as const,
        },
        meta: { iri: cls.iri, entityType: "class" },
      }));

      // Create individual shapes (diamonds)
      const indShapes = state.individuals.map((ind, i) => ({
        id: makeShapeId(ind.iri),
        type: "geo" as const,
        x: ind.x || 80 + i * 200,
        y: ind.y || 400,
        props: {
          w: 140,
          h: 50,
          geo: "diamond" as const,
          richText: toRichText(ind.label),
          color: "orange" as const,
          fill: "semi" as const,
          font: "sans" as const,
          size: "s" as const,
        },
        meta: { iri: ind.iri, entityType: "individual", classIri: ind.class_iri },
      }));

      editor.createShapes([...classShapes, ...indShapes]);

      // Create arrow shapes for properties
      const arrowShapes = state.properties
        .filter((p) => iriToShapeId.has(p.source_id) && iriToShapeId.has(p.target_id))
        .map((prop) => ({
          id: makeShapeId(prop.id),
          type: "arrow" as const,
          props: {
            richText: toRichText(prop.label),
            color: prop.iri === "rdfs:subClassOf" ? ("light-blue" as const) : ("light-green" as const),
            arrowheadEnd: "arrow" as const,
            dash: prop.property_type === "annotation" ? ("dashed" as const) : ("draw" as const),
          },
          meta: { iri: prop.iri, entityType: "property", propertyType: prop.property_type },
        }));

      editor.createShapes(arrowShapes);

      // Bind arrows to source/target using v4 createBindings API
      const bindings: any[] = [];
      for (const prop of state.properties) {
        const arrowId = iriToShapeId.get(prop.id);
        const sourceId = iriToShapeId.get(prop.source_id);
        const targetId = iriToShapeId.get(prop.target_id);
        if (arrowId && sourceId && targetId) {
          bindings.push({
            type: "arrow",
            fromId: arrowId,
            toId: sourceId,
            props: { terminal: "start", isPrecise: false, isExact: false, normalizedAnchor: { x: 0.5, y: 0.5 } },
          });
          bindings.push({
            type: "arrow",
            fromId: arrowId,
            toId: targetId,
            props: { terminal: "end", isPrecise: false, isExact: false, normalizedAnchor: { x: 0.5, y: 0.5 } },
          });
        }
      }
      if (bindings.length > 0) {
        editor.createBindings(bindings);
      }

      // Zoom to fit
      editor.zoomToFit({ animation: { duration: 300 } });
    } catch (e: any) {
      setError(e.message || "Failed to load canvas");
    } finally {
      setLoading(false);
    }
  }, [boardId]);

  const saveCanvas = useCallback(async (editor: Editor) => {
    if (!boardId) return;
    setLoading(true);
    setError("");
    try {
      const shapes = editor.getCurrentPageShapes();

      const classes: CanvasClass[] = [];
      const individuals: CanvasIndividual[] = [];
      const shapeIdToIri = new Map<string, string>();

      // Extract classes and individuals from geo shapes
      for (const shape of shapes) {
        if (shape.type === "geo") {
          const meta = shape.meta || {};
          const iri = (meta.iri as string) || `http://example.org/new#${shape.id}`;
          shapeIdToIri.set(shape.id, iri);

          if (meta.entityType === "individual") {
            individuals.push({
              id: iri, iri,
              label: extractLabel(shape),
              class_iri: (meta.classIri as string) || "",
              x: shape.x, y: shape.y,
            });
          } else {
            // Default geo shapes are classes
            classes.push({
              id: iri, iri,
              label: extractLabel(shape),
              x: shape.x, y: shape.y,
              w: (shape.props as any).w || 160,
              h: (shape.props as any).h || 60,
              color: (shape.props as any).color || "violet",
            });
          }
        }
      }

      // Extract properties from arrow shapes using v4 getArrowBindings
      const properties: CanvasProperty[] = [];
      for (const shape of shapes) {
        if (shape.type === "arrow") {
          const meta = shape.meta || {};
          // Use v4 API to get arrow bindings
          const arrowBindings = getArrowBindings(editor, shape as any);
          const sourceId = arrowBindings.start?.toId;
          const targetId = arrowBindings.end?.toId;
          const sourceIri = sourceId ? shapeIdToIri.get(sourceId) : undefined;
          const targetIri = targetId ? shapeIdToIri.get(targetId) : undefined;

          if (sourceIri && targetIri) {
            properties.push({
              id: (meta.iri as string) || `http://example.org/new#prop_${shape.id}`,
              iri: (meta.iri as string) || `http://example.org/new#prop_${shape.id}`,
              label: extractLabel(shape),
              source_id: sourceIri,
              target_id: targetIri,
              property_type: (meta.propertyType as string) || "object",
            });
          }
        }
      }

      await apiJson(`/api/owl/${boardId}/save`, {
        method: "POST",
        body: JSON.stringify({ classes, properties, individuals }),
      });
      setError("");
    } catch (e: any) {
      setError(e.message || "Failed to save");
    } finally {
      setLoading(false);
    }
  }, [boardId]);

  return { loadCanvas, saveCanvas, loading, error };
}

/** Extract plaintext label from a shape's richText or text prop. */
function extractLabel(shape: any): string {
  const props = shape?.props || {};
  // v4 richText is a ProseMirror-like JSON structure
  if (props.richText && typeof props.richText === "object") {
    try {
      const content = props.richText.content;
      if (Array.isArray(content)) {
        return content
          .map((p: any) =>
            Array.isArray(p.content)
              ? p.content.map((t: any) => t.text || "").join("")
              : ""
          )
          .join("\n")
          .trim() || "Unnamed";
      }
    } catch {}
  }
  // Fallback for text prop (older shapes)
  if (props.text) return props.text;
  return "Unnamed";
}
