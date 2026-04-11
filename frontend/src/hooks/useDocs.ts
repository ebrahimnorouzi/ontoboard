import { useState, useCallback, useEffect } from "react";
import { api, apiJson } from "../api";

interface DocsStatus {
  generated: boolean; last_built: string | null;
  page_count: number; output_dir: string; index_url: string | null;
}

interface BuildEvent { step: string; message: string; progress: number }

export function useDocs(boardId: string | undefined) {
  const [status, setStatus] = useState<DocsStatus | null>(null);
  const [building, setBuilding] = useState(false);
  const [events, setEvents] = useState<BuildEvent[]>([]);
  const [progress, setProgress] = useState(0);
  const [files, setFiles] = useState<string[]>([]);
  const [viewContent, setViewContent] = useState<string | null>(null);
  const [error, setError] = useState("");

  const fetchStatus = useCallback(async () => {
    if (!boardId) return;
    try { setStatus(await apiJson<DocsStatus>(`/api/docs/${boardId}/status`)); } catch {}
  }, [boardId]);

  const fetchFiles = useCallback(async () => {
    if (!boardId) return;
    try { setFiles(await apiJson<string[]>(`/api/docs/${boardId}/files`)); } catch {}
  }, [boardId]);

  useEffect(() => { fetchStatus(); fetchFiles(); }, [fetchStatus, fetchFiles]);

  const build = useCallback(async () => {
    if (!boardId) return;
    setBuilding(true); setEvents([]); setProgress(0); setError("");
    try {
      const res = await api(`/api/docs/${boardId}/build`, { method: "POST" });
      if (!res.ok || !res.body) { setError("Build failed"); setBuilding(false); return; }
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        for (const line of decoder.decode(value).split("\n\n").filter(Boolean)) {
          const content = line.replace(/^data: /, "");
          try {
            const evt: BuildEvent = JSON.parse(content);
            setEvents((prev) => [...prev, evt]);
            setProgress(evt.progress);
          } catch {}
        }
      }
    } catch (e: any) { setError(e.message); }
    finally { setBuilding(false); fetchStatus(); fetchFiles(); }
  }, [boardId, fetchStatus, fetchFiles]);

  const viewFile = useCallback(async (filePath: string) => {
    if (!boardId) return;
    try {
      const res = await api(`/api/docs/${boardId}/serve/${filePath}`);
      if (res.ok) setViewContent(await res.text());
    } catch {}
  }, [boardId]);

  return { status, building, build, events, progress, files, viewContent, viewFile, error };
}
