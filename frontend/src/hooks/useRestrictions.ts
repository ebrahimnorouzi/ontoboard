import { useState, useEffect, useCallback } from "react";
import { apiJson } from "../api";

interface RestrictionInfo {
  restriction_type: string; on_property: string; on_property_label: string;
  filler: string; filler_label: string; cardinality: number | null;
  qualified_class: string | null; manchester: string;
}

export function useRestrictions(boardId: string | undefined, entityIri: string | undefined) {
  const [restrictions, setRestrictions] = useState<RestrictionInfo[]>([]);
  const [loading, setLoading] = useState(false);

  const fetch = useCallback(async () => {
    if (!boardId || !entityIri) { setRestrictions([]); return; }
    setLoading(true);
    try {
      const encoded = encodeURIComponent(entityIri);
      const data = await apiJson<RestrictionInfo[]>(`/api/restrictions/${boardId}/entity/${encoded}`);
      setRestrictions(data);
    } catch { setRestrictions([]); }
    finally { setLoading(false); }
  }, [boardId, entityIri]);

  useEffect(() => { fetch(); }, [fetch]);

  const addRestriction = useCallback(async (body: any) => {
    if (!boardId || !entityIri) return;
    const encoded = encodeURIComponent(entityIri);
    await apiJson(`/api/restrictions/${boardId}/entity/${encoded}`, {
      method: "POST", body: JSON.stringify(body),
    });
    fetch();
  }, [boardId, entityIri, fetch]);

  const removeRestriction = useCallback(async (body: any) => {
    if (!boardId || !entityIri) return;
    const encoded = encodeURIComponent(entityIri);
    await apiJson(`/api/restrictions/${boardId}/entity/${encoded}`, {
      method: "DELETE", body: JSON.stringify(body),
    });
    fetch();
  }, [boardId, entityIri, fetch]);

  return { restrictions, loading, addRestriction, removeRestriction, refresh: fetch };
}

export type { RestrictionInfo };
