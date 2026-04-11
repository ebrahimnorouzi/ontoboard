/**
 * Hocuspocus collaboration server with JWT auth and file persistence.
 *
 * Each board gets its own Yjs document, identified by the board_id.
 * Auth tokens are validated against the same SECRET_KEY used by the backend.
 */

import { Server } from "@hocuspocus/server";
import jwt from "jsonwebtoken";
import fs from "fs";
import path from "path";

const PORT = parseInt(process.env.COLLAB_PORT || "1234", 10);
const SECRET_KEY = process.env.SECRET_KEY || "change-me-in-production";
const DATA_DIR = process.env.DATA_DIR || "/app/data";

const server = new Server({
  port: PORT,

  async onAuthenticate({ token, documentName }) {
    // Allow unauthenticated connections for public boards (read-only awareness)
    if (!token) {
      return { user: { name: "anonymous", role: "viewer" } };
    }

    try {
      const payload = jwt.verify(token, SECRET_KEY);
      console.log(`[collab] Authenticated user '${payload.sub}' for document '${documentName}'`);
      return {
        user: {
          name: payload.sub,
          role: payload.role || "user",
          id: payload.user_id,
        },
      };
    } catch (err) {
      console.warn(`[collab] Invalid token for document '${documentName}':`, err.message);
      // Still allow connection but mark as anonymous
      return { user: { name: "anonymous", role: "viewer" } };
    }
  },

  async onLoadDocument({ documentName, document }) {
    // Try to load persisted Yjs state from disk
    const statePath = _statePath(documentName);
    if (fs.existsSync(statePath)) {
      try {
        const data = fs.readFileSync(statePath);
        const Y = await import("yjs");
        Y.applyUpdate(document, new Uint8Array(data));
        console.log(`[collab] Loaded persisted state for '${documentName}'`);
      } catch (err) {
        console.warn(`[collab] Failed to load state for '${documentName}':`, err.message);
      }
    }
  },

  async onStoreDocument({ documentName, document }) {
    // Persist Yjs state to disk
    const statePath = _statePath(documentName);
    try {
      const dir = path.dirname(statePath);
      fs.mkdirSync(dir, { recursive: true });
      const Y = await import("yjs");
      const state = Y.encodeStateAsUpdate(document);
      fs.writeFileSync(statePath, Buffer.from(state));
    } catch (err) {
      console.error(`[collab] Failed to persist state for '${documentName}':`, err.message);
    }
  },

  onConnect({ documentName, context }) {
    const userName = context?.user?.name || "unknown";
    console.log(`[collab] ${userName} connected to '${documentName}'`);
  },

  onDisconnect({ documentName, context }) {
    const userName = context?.user?.name || "unknown";
    console.log(`[collab] ${userName} disconnected from '${documentName}'`);
  },
});

function _statePath(documentName) {
  // Store Yjs state alongside the board data
  return path.join(DATA_DIR, documentName, "collab-state.bin");
}

server.listen();
console.log(`[collab] Hocuspocus server running on port ${PORT}`);
console.log(`[collab] Data dir: ${DATA_DIR}`);
