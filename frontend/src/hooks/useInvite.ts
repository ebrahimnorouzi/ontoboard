import { useState, useCallback } from "react";
import { apiJson, api } from "../api";

interface InviteLink {
  id: number;
  token: string;
  role: string;
  created_by: number;
  created_at: string;
  expires_at: string | null;
  max_uses: number;
  use_count: number;
  is_active: boolean;
}

interface InviteCreateResponse {
  token: string;
  url: string;
  role: string;
  expires_at: string | null;
}

interface InviteInfo {
  board_name: string;
  role: string;
  created_by: string;
  expires_at: string | null;
  valid: boolean;
}

interface InviteAcceptResponse {
  board_id: string;
  board_name: string;
  role: string;
}

interface BoardMember {
  user_id: number;
  username: string;
  role: string;
  added_at: string;
}

export function useInvite(boardId: string | undefined) {
  const [links, setLinks] = useState<InviteLink[]>([]);
  const [members, setMembers] = useState<BoardMember[]>([]);
  const [loading, setLoading] = useState(false);

  const fetchLinks = useCallback(async () => {
    if (!boardId) return;
    setLoading(true);
    try {
      const data = await apiJson<InviteLink[]>(`/api/invite/${boardId}/links`);
      setLinks(data);
    } catch {
      setLinks([]);
    } finally {
      setLoading(false);
    }
  }, [boardId]);

  const fetchMembers = useCallback(async () => {
    if (!boardId) return;
    try {
      const data = await apiJson<BoardMember[]>(`/api/boards/${boardId}/members`);
      setMembers(data);
    } catch {
      setMembers([]);
    }
  }, [boardId]);

  const createLink = useCallback(
    async (role: string, expiresHours: number | null, maxUses: number): Promise<InviteCreateResponse> => {
      return apiJson<InviteCreateResponse>(`/api/invite/${boardId}/create`, {
        method: "POST",
        body: JSON.stringify({
          role,
          expires_hours: expiresHours,
          max_uses: maxUses,
        }),
      });
    },
    [boardId]
  );

  const revokeLink = useCallback(
    async (token: string) => {
      await apiJson(`/api/invite/${boardId}/${token}`, { method: "DELETE" });
      fetchLinks();
    },
    [boardId, fetchLinks]
  );

  const shareByUsername = useCallback(
    async (username: string, role: string) => {
      await apiJson(`/api/boards/${boardId}/share`, {
        method: "POST",
        body: JSON.stringify({ username, role }),
      });
      fetchMembers();
    },
    [boardId, fetchMembers]
  );

  const removeMember = useCallback(
    async (username: string) => {
      await apiJson(`/api/boards/${boardId}/share/${username}`, { method: "DELETE" });
      fetchMembers();
    },
    [boardId, fetchMembers]
  );

  return {
    links,
    members,
    loading,
    fetchLinks,
    fetchMembers,
    createLink,
    revokeLink,
    shareByUsername,
    removeMember,
  };
}

export function useInviteAccept() {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const getInfo = useCallback(async (token: string): Promise<InviteInfo | null> => {
    try {
      const res = await api(`/api/invite/info/${token}`);
      if (!res.ok) return null;
      return res.json();
    } catch {
      return null;
    }
  }, []);

  const accept = useCallback(async (token: string): Promise<InviteAcceptResponse | null> => {
    setLoading(true);
    setError(null);
    try {
      const data = await apiJson<InviteAcceptResponse>(`/api/invite/accept/${token}`, {
        method: "POST",
      });
      return data;
    } catch (err: any) {
      setError(err.message || "Failed to accept invite");
      return null;
    } finally {
      setLoading(false);
    }
  }, []);

  return { getInfo, accept, loading, error };
}

export type { InviteLink, InviteInfo, InviteAcceptResponse, BoardMember };
