import { useEffect, useState, useCallback } from "react";
import { apiJson } from "../api";

interface TaskOut {
  id: number; title: string; description: string;
  status: string; priority: string;
  assignee_username: string | null; assignee_id: number | null;
  created_by_username: string; entity_iri: string;
  github_issue_url: string; github_issue_number: number | null;
  comments_count: number; due_date: string | null;
  created_at: string; updated_at: string;
}

interface CommentOut {
  id: number; text: string; username: string; created_at: string;
}

export function useTasks(boardId: string | undefined) {
  const [tasks, setTasks] = useState<TaskOut[]>([]);
  const [loading, setLoading] = useState(true);

  const fetch = useCallback(async () => {
    if (!boardId) return;
    setLoading(true);
    try {
      const data = await apiJson<TaskOut[]>(`/api/tasks/${boardId}`);
      setTasks(data);
    } catch { setTasks([]); }
    finally { setLoading(false); }
  }, [boardId]);

  useEffect(() => { fetch(); }, [fetch]);

  const createTask = useCallback(async (body: { title: string; description?: string; priority?: string; assignee_username?: string; entity_iri?: string }) => {
    if (!boardId) return;
    await apiJson(`/api/tasks/${boardId}`, { method: "POST", body: JSON.stringify(body) });
    fetch();
  }, [boardId, fetch]);

  const updateTask = useCallback(async (taskId: number, body: Record<string, any>) => {
    if (!boardId) return;
    await apiJson(`/api/tasks/${boardId}/${taskId}`, { method: "PATCH", body: JSON.stringify(body) });
    fetch();
  }, [boardId, fetch]);

  const deleteTask = useCallback(async (taskId: number) => {
    if (!boardId) return;
    await apiJson(`/api/tasks/${boardId}/${taskId}`, { method: "DELETE" });
    fetch();
  }, [boardId, fetch]);

  const addComment = useCallback(async (taskId: number, text: string) => {
    if (!boardId) return;
    await apiJson(`/api/tasks/${boardId}/${taskId}/comments`, { method: "POST", body: JSON.stringify({ text }) });
  }, [boardId]);

  const getComments = useCallback(async (taskId: number): Promise<CommentOut[]> => {
    if (!boardId) return [];
    return apiJson<CommentOut[]>(`/api/tasks/${boardId}/${taskId}/comments`);
  }, [boardId]);

  return { tasks, loading, createTask, updateTask, deleteTask, addComment, getComments, refresh: fetch };
}

export type { TaskOut, CommentOut };
