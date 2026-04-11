import { useEffect, useState, useCallback } from "react";
import { apiJson } from "../api";

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

export function useTreeData(boardId: string | undefined) {
  const [tree, setTree] = useState<TreeNode[]>([]);
  const [activeTab, setActiveTab] = useState<TreeTab>("classes");
  const [loading, setLoading] = useState(false);
  const [search, setSearch] = useState("");

  const fetchTree = useCallback(async () => {
    if (!boardId) return;
    setLoading(true);
    try {
      if (activeTab === "individuals") {
        const groups = await apiJson<Record<string, TreeNode[]>>(`/api/tree/${boardId}/individuals`);
        const nodes: TreeNode[] = Object.entries(groups).map(([cls, inds]) => ({
          iri: cls, label: cls, entity_type: "class_group",
          children: inds, annotation_count: 0,
        }));
        setTree(nodes);
      } else {
        const data = await apiJson<TreeNode[]>(`/api/tree/${boardId}/${activeTab}`);
        setTree(data);
      }
    } catch {
      setTree([]);
    } finally {
      setLoading(false);
    }
  }, [boardId, activeTab]);

  useEffect(() => { fetchTree(); }, [fetchTree]);

  return { tree, activeTab, setActiveTab, loading, search, setSearch, refresh: fetchTree };
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
