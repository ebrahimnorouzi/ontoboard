import { useEffect, useState, useCallback } from "react";
import { apiJson } from "../api";

interface EntityName { iri: string; label: string; type: string }

export function useAxiomEditor(boardId: string | undefined, entityIri: string | undefined) {
  const [manchester, setManchester] = useState("");
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [errors, setErrors] = useState<{ line: number; column: number; message: string }[]>([]);
  const [saveResult, setSaveResult] = useState<{ success: boolean; message: string } | null>(null);
  const [entityNames, setEntityNames] = useState<EntityName[]>([]);

  // Load Manchester text when entity changes
  useEffect(() => {
    if (!boardId || !entityIri) {
      setManchester("");
      return;
    }
    setLoading(true);
    setSaveResult(null);
    const encoded = encodeURIComponent(entityIri);
    apiJson<{ manchester: string }>(`/api/axiom/${boardId}/manchester/${encoded}`)
      .then((data) => setManchester(data.manchester))
      .catch(() => setManchester("# Failed to load axioms"))
      .finally(() => setLoading(false));
  }, [boardId, entityIri]);

  // Load entity names for autocomplete
  useEffect(() => {
    if (!boardId) return;
    apiJson<EntityName[]>(`/api/axiom/${boardId}/entity-names`)
      .then(setEntityNames)
      .catch(() => {});
  }, [boardId]);

  // Validate on change (debounced)
  const validate = useCallback(async (text: string) => {
    if (!boardId) return;
    try {
      const result = await apiJson<{ line: number; column: number; message: string }[]>(
        `/api/axiom/${boardId}/validate`,
        { method: "POST", body: JSON.stringify({ manchester_text: text }) },
      );
      setErrors(result);
    } catch {
      // ignore validation errors during typing
    }
  }, [boardId]);

  // Save
  const save = useCallback(async () => {
    if (!boardId || !entityIri) return;
    setSaving(true);
    setSaveResult(null);
    try {
      const encoded = encodeURIComponent(entityIri);
      const result = await apiJson<{ success: boolean; applied: number; errors: any[]; warnings: string[] }>(
        `/api/axiom/${boardId}/axioms/${encoded}`,
        { method: "PUT", body: JSON.stringify({ manchester_text: manchester }) },
      );
      if (result.success) {
        setSaveResult({ success: true, message: `Applied ${result.applied} axioms` });
      } else {
        setSaveResult({ success: false, message: result.errors.map((e: any) => e.message).join("; ") });
      }
    } catch (e: any) {
      setSaveResult({ success: false, message: e.message });
    } finally {
      setSaving(false);
    }
  }, [boardId, entityIri, manchester]);

  return { manchester, setManchester, loading, saving, errors, saveResult, save, validate, entityNames };
}
