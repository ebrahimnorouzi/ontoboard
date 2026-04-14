import { useState, useCallback } from "react";
import { api, apiJson } from "../api";

// ── Types ────────────────────────────────────────────────────

interface ColumnInfo {
  name: string;
  sample_values: string[];
  suggested_type: string;
}

interface CsvAnalysis {
  filename: string;
  columns: ColumnInfo[];
  row_count: number;
  delimiter: string;
  sample_rows: Record<string, string>[];
}

interface OntologyEntity {
  iri: string;
  label: string;
}

interface OntologyEntities {
  classes: OntologyEntity[];
  object_properties: OntologyEntity[];
  data_properties: OntologyEntity[];
  annotation_properties: OntologyEntity[];
}

interface ColumnMapping {
  column_name: string;
  directive_type: string;
  property_iri: string;
  split_char: string;
}

interface TemplateResult {
  template_path: string;
  template_name: string;
  preview_rows: string[][];
  total_data_rows: number;
}

interface BuildResult {
  success: boolean;
  output_path: string;
  triples_count: number;
  error: string | null;
}

interface MergeResult {
  success: boolean;
  output_path: string;
  triples_count: number;
  error: string | null;
}

interface KgFileInfo {
  name: string;
  size: number;
  path: string;
}

interface KgFiles {
  uploads: KgFileInfo[];
  templates: KgFileInfo[];
  output: KgFileInfo[];
}

// ── Hook ─────────────────────────────────────────────────────

export function useCsvImport(boardId: string | undefined) {
  const [analysis, setAnalysis] = useState<CsvAnalysis | null>(null);
  const [entities, setEntities] = useState<OntologyEntities | null>(null);
  const [kgFiles, setKgFiles] = useState<KgFiles>({ uploads: [], templates: [], output: [] });
  const [templateResult, setTemplateResult] = useState<TemplateResult | null>(null);
  const [buildResult, setBuildResult] = useState<BuildResult | null>(null);
  const [mergeResult, setMergeResult] = useState<MergeResult | null>(null);
  const [uploading, setUploading] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [building, setBuilding] = useState(false);
  const [merging, setMerging] = useState(false);
  const [error, setError] = useState("");

  const clearError = useCallback(() => setError(""), []);

  // Upload CSV/TSV
  const uploadFile = useCallback(async (file: File) => {
    if (!boardId) return;
    setUploading(true);
    setError("");
    try {
      const form = new FormData();
      form.append("file", file);
      const res = await api(`/api/csv/${boardId}/upload`, { method: "POST", body: form });
      if (!res.ok) throw new Error((await res.json()).detail || "Upload failed");
      const data: CsvAnalysis = await res.json();
      setAnalysis(data);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Upload failed");
    } finally {
      setUploading(false);
    }
  }, [boardId]);

  // Fetch ontology entities
  const fetchEntities = useCallback(async () => {
    if (!boardId) return;
    try {
      const data = await apiJson<OntologyEntities>(`/api/csv/${boardId}/entities`);
      setEntities(data);
    } catch {
      // Ontology may not exist yet — that's ok
    }
  }, [boardId]);

  // Fetch KG file list
  const fetchFiles = useCallback(async () => {
    if (!boardId) return;
    try {
      const data = await apiJson<KgFiles>(`/api/csv/${boardId}/files`);
      setKgFiles(data);
    } catch {
      // Directory may not exist yet
    }
  }, [boardId]);

  // Generate ROBOT template
  const generateTemplate = useCallback(async (
    filename: string,
    columnMappings: ColumnMapping[],
    iriStrategy: string,
    baseIri: string,
    templateName?: string,
  ) => {
    if (!boardId) return;
    setGenerating(true);
    setError("");
    try {
      const data = await apiJson<TemplateResult>(`/api/csv/${boardId}/template`, {
        method: "POST",
        body: JSON.stringify({
          filename,
          column_mappings: columnMappings,
          iri_strategy: iriStrategy,
          base_iri: baseIri,
          template_name: templateName || null,
        }),
      });
      setTemplateResult(data);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Template generation failed");
    } finally {
      setGenerating(false);
    }
  }, [boardId]);

  // Build KG from template
  const buildKg = useCallback(async (templatePath: string) => {
    if (!boardId) return;
    setBuilding(true);
    setError("");
    try {
      const data = await apiJson<BuildResult>(`/api/csv/${boardId}/build`, {
        method: "POST",
        body: JSON.stringify({ template_path: templatePath }),
      });
      setBuildResult(data);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Build failed");
    } finally {
      setBuilding(false);
    }
  }, [boardId]);

  // Merge KG files
  const mergeKgs = useCallback(async (filePaths: string[]) => {
    if (!boardId) return;
    setMerging(true);
    setError("");
    try {
      const data = await apiJson<MergeResult>(`/api/csv/${boardId}/merge`, {
        method: "POST",
        body: JSON.stringify({ file_paths: filePaths }),
      });
      setMergeResult(data);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Merge failed");
    } finally {
      setMerging(false);
    }
  }, [boardId]);

  // Download URL helper
  const getDownloadUrl = useCallback((filePath: string) => {
    if (!boardId) return "";
    return `/api/csv/${boardId}/download/${filePath}`;
  }, [boardId]);

  return {
    // State
    analysis, entities, kgFiles,
    templateResult, buildResult, mergeResult,
    uploading, generating, building, merging, error,
    // Actions
    uploadFile, fetchEntities, fetchFiles,
    generateTemplate, buildKg, mergeKgs,
    getDownloadUrl, clearError,
    setAnalysis, setTemplateResult, setBuildResult, setMergeResult,
  };
}

export type {
  CsvAnalysis, ColumnInfo, ColumnMapping,
  OntologyEntity, OntologyEntities,
  TemplateResult, BuildResult, MergeResult,
  KgFileInfo, KgFiles,
};
