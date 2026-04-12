import { useEffect, useState, useCallback, useMemo } from "react";
import { apiJson } from "../api";
import { useOntologyStore } from "../store/ontologyStore";

interface TreeNode {
  iri: string;
  label: string;
  entity_type: string;
  children: TreeNode[];
  annotation_count: number;
}

interface AnnotationValue {
  property_iri: string;
  property_label: string;
  value: string;
  language: string | null;
  datatype: string | null;
}

interface UsageInfo {
  subject_iri: string;
  subject_label: string;
  predicate: string;
  role: string;
}

interface EntityDetail {
  iri: string;
  label: string;
  entity_type: string;
  annotations: AnnotationValue[];
  axiom_count: number;
  usages: UsageInfo[];
}

type TreeTab = "classes" | "object-properties" | "data-properties" | "annotation-properties" | "individuals";

/** Top-level OWL super entities (not shown on graph, but act as tree roots) */
const OWL_THING = "http://www.w3.org/2002/07/owl#Thing";
const OWL_TOP_OBJECT_PROPERTY = "http://www.w3.org/2002/07/owl#topObjectProperty";
const OWL_TOP_DATA_PROPERTY = "http://www.w3.org/2002/07/owl#topDataProperty";

/** Standard annotation properties that should always be available */
const DEFAULT_ANNOTATION_PROPERTIES: TreeNode[] = [
  { iri: "http://www.w3.org/2000/01/rdf-schema#label", label: "rdfs:label", entity_type: "annotation-property", children: [], annotation_count: 0 },
  { iri: "http://www.w3.org/2000/01/rdf-schema#comment", label: "rdfs:comment", entity_type: "annotation-property", children: [], annotation_count: 0 },
  { iri: "http://www.w3.org/2000/01/rdf-schema#seeAlso", label: "rdfs:seeAlso", entity_type: "annotation-property", children: [], annotation_count: 0 },
  { iri: "http://www.w3.org/2000/01/rdf-schema#isDefinedBy", label: "rdfs:isDefinedBy", entity_type: "annotation-property", children: [], annotation_count: 0 },
  { iri: "http://www.w3.org/2002/07/owl#versionInfo", label: "owl:versionInfo", entity_type: "annotation-property", children: [], annotation_count: 0 },
  { iri: "http://www.w3.org/2002/07/owl#deprecated", label: "owl:deprecated", entity_type: "annotation-property", children: [], annotation_count: 0 },
  { iri: "http://www.w3.org/2002/07/owl#priorVersion", label: "owl:priorVersion", entity_type: "annotation-property", children: [], annotation_count: 0 },
  { iri: "http://www.w3.org/2002/07/owl#backwardCompatibleWith", label: "owl:backwardCompatibleWith", entity_type: "annotation-property", children: [], annotation_count: 0 },
  { iri: "http://www.w3.org/2002/07/owl#incompatibleWith", label: "owl:incompatibleWith", entity_type: "annotation-property", children: [], annotation_count: 0 },
  { iri: "http://purl.org/dc/terms/title", label: "dcterms:title", entity_type: "annotation-property", children: [], annotation_count: 0 },
  { iri: "http://purl.org/dc/terms/description", label: "dcterms:description", entity_type: "annotation-property", children: [], annotation_count: 0 },
  { iri: "http://purl.org/dc/terms/creator", label: "dcterms:creator", entity_type: "annotation-property", children: [], annotation_count: 0 },
  { iri: "http://purl.org/dc/terms/contributor", label: "dcterms:contributor", entity_type: "annotation-property", children: [], annotation_count: 0 },
  { iri: "http://purl.org/dc/terms/license", label: "dcterms:license", entity_type: "annotation-property", children: [], annotation_count: 0 },
  { iri: "http://www.w3.org/2004/02/skos/core#prefLabel", label: "skos:prefLabel", entity_type: "annotation-property", children: [], annotation_count: 0 },
  { iri: "http://www.w3.org/2004/02/skos/core#altLabel", label: "skos:altLabel", entity_type: "annotation-property", children: [], annotation_count: 0 },
  { iri: "http://www.w3.org/2004/02/skos/core#definition", label: "skos:definition", entity_type: "annotation-property", children: [], annotation_count: 0 },
];

/**
 * Build tree data directly from the Zustand store for instant synchronization.
 * Falls back to backend API for entity details.
 */
export function useTreeData(boardId: string | undefined) {
  const [activeTab, setActiveTab] = useState<TreeTab>("classes");
  const [search, setSearch] = useState("");

  const classes = useOntologyStore((s) => s.classes);
  const properties = useOntologyStore((s) => s.properties);
  const individuals = useOntologyStore((s) => s.individuals);

  const tree = useMemo(() => {
    switch (activeTab) {
      case "classes":
        return buildClassTree(classes, properties);
      case "object-properties":
        return buildPropertyTree(properties, "object");
      case "data-properties":
        return buildPropertyTree(properties, "data");
      case "annotation-properties":
        return buildAnnotationPropertyTree(properties);
      case "individuals":
        return buildIndividualTree(individuals, classes);
      default:
        return [];
    }
  }, [activeTab, classes, properties, individuals]);

  const refresh = useCallback(() => {
    // No-op: tree is now derived from store and updates reactively
  }, []);

  return { tree, activeTab, setActiveTab, loading: false, search, setSearch, refresh };
}

/** Build a hierarchical class tree using subClassOf edges, rooted under owl:Thing */
function buildClassTree(
  classes: { iri: string; label: string }[],
  properties: { iri: string; source_id: string; target_id: string }[],
): TreeNode[] {
  const subClassEdges = properties.filter((p) => p.iri === "rdfs:subClassOf");
  // Map child → parent IRIs
  const childToParent = new Map<string, string>();
  for (const e of subClassEdges) {
    childToParent.set(e.source_id, e.target_id);
  }
  // Map parent → children
  const parentToChildren = new Map<string, string[]>();
  for (const e of subClassEdges) {
    const arr = parentToChildren.get(e.target_id) || [];
    arr.push(e.source_id);
    parentToChildren.set(e.target_id, arr);
  }

  const classMap = new Map(classes.map((c) => [c.iri, c]));

  function buildNode(iri: string): TreeNode {
    const cls = classMap.get(iri);
    const childIris = parentToChildren.get(iri) || [];
    return {
      iri,
      label: cls?.label || iri.split(/[#/]/).pop() || iri,
      entity_type: "class",
      children: childIris.map(buildNode),
      annotation_count: 0,
    };
  }

  // Root classes = those that have no parent (or parent is owl:Thing)
  const rootIris = classes
    .filter((c) => !childToParent.has(c.iri) || childToParent.get(c.iri) === OWL_THING)
    .map((c) => c.iri);

  const rootNodes = rootIris.map(buildNode);

  // Wrap under owl:Thing
  return [{
    iri: OWL_THING,
    label: "owl:Thing",
    entity_type: "class",
    children: rootNodes,
    annotation_count: 0,
  }];
}

/** Build a flat property list rooted under owl:topObjectProperty / owl:topDataProperty */
function buildPropertyTree(
  properties: { id: string; iri: string; label: string; property_type: string }[],
  propType: "object" | "data",
): TreeNode[] {
  const rootIri = propType === "object" ? OWL_TOP_OBJECT_PROPERTY : OWL_TOP_DATA_PROPERTY;
  const rootLabel = propType === "object" ? "owl:topObjectProperty" : "owl:topDataProperty";
  const entityType = propType === "object" ? "object-property" : "data-property";

  // Only show user-defined properties (flat, no hierarchy)
  const seen = new Set<string>();
  const children: TreeNode[] = [];

  // Add user-defined properties
  for (const p of properties) {
    if (p.property_type !== propType) continue;
    if (seen.has(p.iri)) continue;
    seen.add(p.iri);
    children.push({
      iri: p.iri,
      label: p.label || p.iri.split(/[#/]/).pop() || p.iri,
      entity_type: entityType,
      children: [],
      annotation_count: 0,
    });
  }

  return [{
    iri: rootIri,
    label: rootLabel,
    entity_type: entityType,
    children,
    annotation_count: 0,
  }];
}

/** Build annotation properties tree with defaults always present */
function buildAnnotationPropertyTree(
  properties: { id: string; iri: string; label: string; property_type: string }[],
): TreeNode[] {
  const defaultIris = new Set(DEFAULT_ANNOTATION_PROPERTIES.map((d) => d.iri));
  const result = [...DEFAULT_ANNOTATION_PROPERTIES];

  // Add any user-defined annotation properties that aren't in defaults
  const seen = new Set<string>(defaultIris);
  for (const p of properties) {
    if (p.property_type !== "annotation") continue;
    // Skip subClassOf and rdf:type — they are relationship types, not annotation properties
    if (p.iri === "rdfs:subClassOf" || p.iri === "rdf:type") continue;
    if (seen.has(p.iri)) continue;
    seen.add(p.iri);
    result.push({
      iri: p.iri,
      label: p.label || p.iri.split(/[#/]/).pop() || p.iri,
      entity_type: "annotation-property",
      children: [],
      annotation_count: 0,
    });
  }

  return result;
}

/** Build individual tree grouped by class */
function buildIndividualTree(
  individuals: { iri: string; label: string; class_iri: string }[],
  classes: { iri: string; label: string }[],
): TreeNode[] {
  const classMap = new Map(classes.map((c) => [c.iri, c.label]));
  const groups = new Map<string, TreeNode[]>();

  for (const ind of individuals) {
    const groupKey = ind.class_iri || "(untyped)";
    const arr = groups.get(groupKey) || [];
    arr.push({
      iri: ind.iri,
      label: ind.label || ind.iri.split(/[#/]/).pop() || ind.iri,
      entity_type: "individual",
      children: [],
      annotation_count: 0,
    });
    groups.set(groupKey, arr);
  }

  return Array.from(groups.entries()).map(([cls, inds]) => ({
    iri: cls,
    label: classMap.get(cls) || cls.split(/[#/]/).pop() || cls,
    entity_type: "class_group",
    children: inds,
    annotation_count: 0,
  }));
}

export function useEntityDetail(boardId: string | undefined, entityIri: string | undefined) {
  const [detail, setDetail] = useState<EntityDetail | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!boardId || !entityIri) { setDetail(null); return; }
    setLoading(true);
    const encoded = encodeURIComponent(entityIri);
    apiJson<EntityDetail>(`/api/tree/${boardId}/entity/${encoded}`)
      .then(setDetail)
      .catch(() => setDetail(null))
      .finally(() => setLoading(false));
  }, [boardId, entityIri]);

  return { detail, loading };
}

export type { TreeNode, EntityDetail, AnnotationValue, UsageInfo, TreeTab };
