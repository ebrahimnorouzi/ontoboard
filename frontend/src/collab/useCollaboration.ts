/**
 * useCollaboration — connects to Hocuspocus via y-websocket.
 *
 * Provides a Yjs doc, awareness for cursors, and connection status.
 * Each board gets its own document namespace.
 */

import { useEffect, useRef, useState } from "react";
import * as Y from "yjs";
import { WebsocketProvider } from "y-websocket";
import { getToken } from "../api";

const COLLAB_URL = import.meta.env.VITE_COLLAB_URL || "ws://localhost:1234";

interface CollabUser {
  name: string;
  color: string;
  id?: number;
}

interface CollabState {
  doc: Y.Doc;
  provider: WebsocketProvider | null;
  connected: boolean;
  users: CollabUser[];
}

const COLORS = [
  "#6c5ce7", "#00cec9", "#fdcb6e", "#ff7675", "#a29bfe",
  "#55efc4", "#fab1a0", "#74b9ff", "#ffeaa7", "#81ecec",
];

export function useCollaboration(boardId: string | undefined, userName: string) {
  const docRef = useRef<Y.Doc>(new Y.Doc());
  const providerRef = useRef<WebsocketProvider | null>(null);
  const [connected, setConnected] = useState(false);
  const [users, setUsers] = useState<CollabUser[]>([]);

  useEffect(() => {
    if (!boardId) return;

    const doc = new Y.Doc();
    docRef.current = doc;

    const token = getToken() || "";
    const provider = new WebsocketProvider(COLLAB_URL, boardId, doc, {
      params: { token },
    });
    providerRef.current = provider;

    // Set local awareness (cursor info)
    const colorIndex = Math.abs(hashCode(userName)) % COLORS.length;
    provider.awareness.setLocalStateField("user", {
      name: userName,
      color: COLORS[colorIndex],
    });

    // Track connection status
    provider.on("status", ({ status }: { status: string }) => {
      setConnected(status === "connected");
    });

    // Track connected users via awareness
    const updateUsers = () => {
      const states = provider.awareness.getStates();
      const u: CollabUser[] = [];
      states.forEach((state, clientId) => {
        if (state.user && clientId !== doc.clientID) {
          u.push(state.user as CollabUser);
        }
      });
      setUsers(u);
    };

    provider.awareness.on("change", updateUsers);
    updateUsers();

    return () => {
      provider.awareness.off("change", updateUsers);
      provider.disconnect();
      provider.destroy();
      doc.destroy();
    };
  }, [boardId, userName]);

  return {
    doc: docRef.current,
    provider: providerRef.current,
    connected,
    users,
  };
}

function hashCode(str: string): number {
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = (hash << 5) - hash + str.charCodeAt(i);
    hash |= 0;
  }
  return hash;
}
