/**
 * useCollaboration — Real-time collaboration via Yjs awareness.
 *
 * Provides:
 * 1. Cursor sharing (position, click state, action label)
 * 2. Entity locking (who's editing what — prevents concurrent edits)
 * 3. Save notifications (instant reload when another user saves)
 * 4. User presence (online users list)
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
  action: string;
  selectedEntity: string | null;
}

/** Entity currently being edited by a remote user. */
export interface EntityLock {
  entityIri: string;
  userName: string;
  color: string;
  since: number; // timestamp
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
  const [entityLocks, setEntityLocks] = useState<EntityLock[]>([]);
  const [remoteSaveVersion, setRemoteSaveVersion] = useState(0);
  const [doc, setDoc] = useState<Y.Doc | null>(null);

  useEffect(() => {
    if (!boardId) return;

    const ydoc = new Y.Doc();
    docRef.current = ydoc;
    setDoc(ydoc);

    const token = getToken() || "";
    const provider = new WebsocketProvider(COLLAB_URL, boardId, ydoc, { params: { token } });
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
      const locks: EntityLock[] = [];

      states.forEach((state, clientId) => {
        if (clientId === ydoc.clientID) return;
        if (state.user) {
          userList.push(state.user as any);

          // Cursors
          if (state.cursor) {
            cursors.push({
              name: state.user.name,
              color: state.user.color,
              x: state.cursor.x,
              y: state.cursor.y,
              clicking: state.cursor.clicking || false,
              clientId,
              action: state.cursor.action || "idle",
              selectedEntity: state.cursor.selectedEntity || null,
            });
          }

          // Entity locks
          if (state.editing) {
            locks.push({
              entityIri: state.editing.entityIri,
              userName: state.user.name,
              color: state.user.color,
              since: state.editing.since || 0,
            });
          }

          // Save notifications
          if (state.saveVersion && state.saveVersion > remoteSaveVersion) {
            setRemoteSaveVersion(state.saveVersion);
          }
        }
      });
      setUsers(userList);
      setRemoteCursors(cursors);
      setEntityLocks(locks);
    };

    provider.awareness.on("change", updateAwareness);
    updateAwareness();

    return () => {
      provider.awareness.off("change", updateAwareness);
      provider.disconnect();
      provider.destroy();
      ydoc.destroy();
      docRef.current = null;
      setDoc(null);
    };
  }, [boardId, userName]);

  /** Broadcast cursor position. */
  const broadcastCursor = useCallback((x: number, y: number, clicking: boolean = false, action: string = "idle", selectedEntity: string | null = null) => {
    providerRef.current?.awareness.setLocalStateField("cursor", { x, y, clicking, action, selectedEntity });
  }, []);

  /** Lock an entity (broadcast that you're editing it). Call with null to release. */
  const lockEntity = useCallback((entityIri: string | null) => {
    if (entityIri) {
      providerRef.current?.awareness.setLocalStateField("editing", { entityIri, since: Date.now() });
    } else {
      providerRef.current?.awareness.setLocalStateField("editing", null);
    }
  }, []);

  /** Check if an entity is locked by another user. Returns the lock or null. */
  const getEntityLock = useCallback((entityIri: string): EntityLock | null => {
    return entityLocks.find((l) => l.entityIri === entityIri) || null;
  }, [entityLocks]);

  /** Broadcast that you just saved (so other clients reload). */
  const broadcastSave = useCallback(() => {
    providerRef.current?.awareness.setLocalStateField("saveVersion", Date.now());
  }, []);

  return {
    connected,
    users,
    remoteCursors,
    broadcastCursor,
    doc,
    // Locking
    entityLocks,
    lockEntity,
    getEntityLock,
    // Save sync
    remoteSaveVersion,
    broadcastSave,
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
