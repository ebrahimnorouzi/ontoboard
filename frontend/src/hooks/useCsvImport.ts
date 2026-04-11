import { useState, useCallback } from "react";
import { api, apiJson } from "../api";

interface ColumnInfo { name: string; inferred_type: string; unique_count: number; sample_values: string[] }
interface CsvAnalysis { filename: string; separator: string; row_count: number; columns: ColumnInfo[]; sample_rows: Record<string, string>[] }
interface ColumnMapping { column: string; target_iri: string; mapping_type: string; iri_role: string }
interface KgBuildResult { triples_count: number; individuals_count: number; output_path: string; preview: Record<string, string>[] }

export function useCsvImport(boardId: string | undefined) {
  const [analysis, setAnalysis] = useState<CsvAnalysis | null>(null);
  const [files, setFiles] = useState<string[]>([]);
  const [uploading, setUploading] = useState(false);
  const [building, setBuilding] = useState(false);
  const [buildResult, setBuildResult] = useState<KgBuildResult | null>(null);
  const [preview, setPreview] = useState<Record<string, string>[]>([]);
  const [error, setError] = useState("");

  const fetchFiles = useCallback(async () => {
    if (!boardId) return;
    try { setFiles(await apiJson<string[]>(`/api/csv/${boardId}/files`)); } catch {}
  }, [boardId]);

  const uploadFile = useCallback(async (file: File) => {
    if (!boardId) return;
    setUploading(true); setError("");
    try {
      const form = new FormData();
      form.append("file", file);
      const res = await api(`/api/csv/${boardId}/upload`, { method: "POST", body: form });
      if (!res.ok) throw new Error((await res.json()).detail || "Upload failed");
      const data = await res.json();
      setAnalysis(data);
      fetchFiles();
    } catch (e: any) { setError(e.message); }
    finally { setUploading(false); }
  }, [boardId, fetchFiles]);

  const runPreview = useCallback(async (csvFile: string, mappings: ColumnMapping[], iriStrategy: string, iriPattern: string | null, baseIri: string) => {
    if (!boardId) return;
    try {
      const res = await apiJson<{ triples: Record<string, string>[]; count: number }>(`/api/csv/${boardId}/preview`, {
        method: "POST",
        body: JSON.stringify({ csv_file: csvFile, mappings, iri_strategy: iriStrategy, iri_pattern: iriPattern, base_iri: baseIri, limit: 5 }),
      });
      setPreview(res.triples);
    } catch (e: any) { setError(e.message); }
  }, [boardId]);

  const buildKg = useCallback(async (csvFile: string, mappings: ColumnMapping[], iriStrategy: string, iriPattern: string | null, baseIri: string) => {
    if (!boardId) return;
    setBuilding(true); setError("");
    try {
      const res = await apiJson<KgBuildResult>(`/api/csv/${boardId}/build`, {
        method: "POST",
        body: JSON.stringify({ csv_file: csvFile, mappings, iri_strategy: iriStrategy, iri_pattern: iriPattern, base_iri: baseIri }),
      });
      setBuildResult(res);
    } catch (e: any) { setError(e.message); }
    finally { setBuilding(false); }
  }, [boardId]);

  return { analysis, files, uploading, uploadFile, fetchFiles, preview, runPreview, building, buildKg, buildResult, error };
}

export type { CsvAnalysis, ColumnInfo, ColumnMapping, KgBuildResult };
