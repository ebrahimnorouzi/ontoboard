import { useState, useCallback } from "react";
import { apiJson, api } from "../api";

interface CheckResult {
  name: string;
  passed: boolean;
  message: string;
  severity: string;
}

interface PublishStatus {
  last_published: string | null;
  version: string | null;
  artifacts: string[];
  checks_passed: boolean | null;
}

interface PublishEvent {
  type: string;
  step: string;
  message: string;
  progress: number;
}

export function usePublish(boardId: string | undefined) {
  const [checks, setChecks] = useState<CheckResult[]>([]);
  const [checking, setChecking] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [events, setEvents] = useState<PublishEvent[]>([]);
  const [progress, setProgress] = useState(0);
  const [status, setStatus] = useState<PublishStatus | null>(null);
  const [error, setError] = useState("");

  const runChecks = useCallback(async () => {
    if (!boardId) return;
    setChecking(true);
    setError("");
    try {
      const result = await apiJson<CheckResult[]>(`/api/publish/${boardId}/check`, { method: "POST" });
      setChecks(result);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setChecking(false);
    }
  }, [boardId]);

  const runPublish = useCallback(async (steps: string[] = ["test", "prepare_release", "publish"]) => {
    if (!boardId) return;
    setPublishing(true);
    setEvents([]);
    setProgress(0);
    setError("");
    try {
      const res = await api(`/api/publish/${boardId}/run`, {
        method: "POST",
        body: JSON.stringify({ steps }),
      });
      if (!res.ok || !res.body) {
        setError(`Publish failed (${res.status})`);
        setPublishing(false);
        return;
      }
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        const text = decoder.decode(value);
        for (const line of text.split("\n\n").filter(Boolean)) {
          const content = line.replace(/^data: /, "");
          try {
            const evt: PublishEvent = JSON.parse(content);
            setEvents((prev) => [...prev, evt]);
            setProgress(evt.progress);
          } catch { /* skip non-JSON lines */ }
        }
      }
    } catch (e: any) {
      setError(e.message);
    } finally {
      setPublishing(false);
      fetchStatus();
    }
  }, [boardId]);

  const fetchStatus = useCallback(async () => {
    if (!boardId) return;
    try {
      const s = await apiJson<PublishStatus>(`/api/publish/${boardId}/status`);
      setStatus(s);
    } catch { /* ignore */ }
  }, [boardId]);

  return { checks, checking, runChecks, publishing, runPublish, events, progress, status, fetchStatus, error };
}

export type { CheckResult, PublishStatus, PublishEvent };
