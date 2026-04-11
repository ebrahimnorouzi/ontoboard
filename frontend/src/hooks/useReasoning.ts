import { useState, useCallback } from "react";
import { apiJson } from "../api";

interface Inference {
  inference_type: string;
  subject: string; subject_label: string;
  predicate: string;
  object: string; object_label: string;
}

interface ReasoningError {
  entity_iri: string; entity_label: string;
  axiom: string; message: string; severity: string;
}

interface FixSuggestion {
  error_index: number; description: string;
  action: string; target_axiom: string; target_entity: string;
}

interface ReasoningResult {
  success: boolean; consistent: boolean; reasoner: string;
  inferences: Inference[]; errors: ReasoningError[];
  fixes: FixSuggestion[]; logs: string; duration_seconds: number;
}

export function useReasoning(boardId: string | undefined) {
  const [result, setResult] = useState<ReasoningResult | null>(null);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState("");

  const runReasoning = useCallback(async (reasoner: string = "ELK") => {
    if (!boardId) return;
    setRunning(true); setError("");
    try {
      const r = await apiJson<ReasoningResult>(`/api/reasoning/${boardId}/run`, {
        method: "POST", body: JSON.stringify({ reasoner }),
      });
      setResult(r);
    } catch (e: any) { setError(e.message); }
    finally { setRunning(false); }
  }, [boardId]);

  const applyFix = useCallback(async (fix: FixSuggestion) => {
    if (!boardId) return;
    try {
      await apiJson(`/api/reasoning/${boardId}/apply-fix`, {
        method: "POST",
        body: JSON.stringify({ action: fix.action, target_entity: fix.target_entity, target_axiom: fix.target_axiom }),
      });
      // Re-run reasoning after fix
      await runReasoning(result?.reasoner || "ELK");
    } catch (e: any) { setError(e.message); }
  }, [boardId, result, runReasoning]);

  return { result, running, error, runReasoning, applyFix };
}

export type { ReasoningResult, Inference, ReasoningError, FixSuggestion };
