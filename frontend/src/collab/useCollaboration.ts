/**
 * useCollaboration — Yjs awareness for real-time cursor sharing.
 *
 * Each user broadcasts:
 *   - user.name, user.color (identity)
 *   - cursor.x, cursor.y (canvas position)
 *   - cursor.clicking (boolean)
 *
 * Other clients render these as colored avatar bubbles on the canvas.
 */

import { useEffect, useRef, useState, useCallback } from "react";
import * as Y from "yjs";
import { WebsocketProvider } from "y-websocket";
import { getToken } from "../api";

const COLLAB_URL = import.meta.env.VITE_COLLAB_URL || "ws://localhost:1234";

export interface RemoteCursor {
  name: string;
  color: string;
  x: number;
  y: number;
  clicking: boolean;
  clientId: number;
}

const COLORS = [
  "#4f46e5", "#0ea5e9", "#10b981", "#f59e0b", "#ef4444",
  "#8b5cf6", "#ec4899", "#14b8a6", "#f97316", "#6366f1",
];

export function useCollaboration(boardId: string | undefined, userName: string) {
  const providerRef = useRef<WebsocketProvider | null>(null);
  const docRef = useRef<Y.Doc | null>(null);
  const [connected, setConnected] = useState(false);
  const [users, setUsers] = useState<{ name: string; color: string }[]>([]);
  const [remoteCursors, setRemoteCursors] = useState<RemoteCursor[]>([]);

  useEffect(() => {
    if (!boardId) return;

    const doc = new Y.Doc();
    docRef.current = doc;
    const token = getToken() || "";
    const provider = new WebsocketProvider(COLLAB_URL, boardId, doc, { params: { token } });
    providerRef.current = provider;

    const colorIndex = Math.abs(hashCode(userName)) % COLORS.length;
    const myColor = COLORS[colorIndex];

    provider.awareness.setLocalStateField("user", { name: userName, color: myColor });

    provider.on("status", ({ status }: { status: string }) => {
      setConnected(status === "connected");
    });

    const updateAwareness = () => {
      const states = provider.awareness.getStates();
      const userList: { name: string; color: string }[] = [];
      const cursors: RemoteCursor[] = [];

      states.forEach((state, clientId) => {
        if (clientId === doc.clientID) return;
        if (state.user) {
          userList.push(state.user as any);
          if (state.cursor) {
            cursors.push({
              name: state.user.name,
              color: state.user.color,
              x: state.cursor.x,
              y: state.cursor.y,
              clicking: state.cursor.clicking || false,
              clientId,
            });
          }
        }
      });
      setUsers(userList);
      setRemoteCursors(cursors);
    };

    provider.awareness.on("change", updateAwareness);
    updateAwareness();

    return () => {
      provider.awareness.off("change", updateAwareness);
      provider.disconnect();
      provider.destroy();
      doc.destroy();
    };
  }, [boardId, userName]);

  // Broadcast local cursor position
  const broadcastCursor = useCallback((x: number, y: number, clicking: boolean = false) => {
    providerRef.current?.awareness.setLocalStateField("cursor", { x, y, clicking });
  }, []);

  return { connected, users, remoteCursors, broadcastCursor };
}

function hashCode(str: string): number {
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = (hash << 5) - hash + str.charCodeAt(i);
    hash |= 0;
  }
  return hash;
}
