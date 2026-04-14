/**
 * Hocuspocus collaboration server — awareness only (cursors, locking, save broadcast).
 *
 * Each board gets its own Yjs document, identified by the board_id.
 * Auth tokens are validated against the same SECRET_KEY used by the backend.
 *
 * NOTE: Yjs documents are NOT persisted to disk — they are ephemeral.
 * Ontology data is stored via the backend REST API, not Yjs.
 * Only awareness state (cursors, who's editing what) flows through here.
 */

import { Server } from "@hocuspocus/server";
import jwt from "jsonwebtoken";

const PORT = parseInt(process.env.COLLAB_PORT || "1234", 10);
const SECRET_KEY = process.env.SECRET_KEY || "change-me-in-production";

const server = new Server({
  port: PORT,

  async onAuthenticate({ token, documentName }) {
    if (!token) {
      return { user: { name: "anonymous", role: "viewer" } };
    }

    try {
      const payload = jwt.verify(token, SECRET_KEY);
      console.log(`[collab] Authenticated '${payload.sub}' for '${documentName}'`);
      return {
        user: {
          name: payload.sub,
          role: payload.role || "user",
          id: payload.user_id,
        },
      };
    } catch (err) {
      console.warn(`[collab] Invalid token for '${documentName}':`, err.message);
      return { user: { name: "anonymous", role: "viewer" } };
    }
  },

  // No state persistence — awareness is ephemeral
  async onLoadDocument({ documentName }) {
    console.log(`[collab] Document '${documentName}' created (ephemeral)`);
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

// Global error handler — prevent crashes from bad client data
process.on("uncaughtException", (err) => {
  console.error(`[collab] Uncaught exception (suppressed):`, err.message);
});

process.on("unhandledRejection", (reason) => {
  console.error(`[collab] Unhandled rejection (suppressed):`, reason);
});

server.listen();
console.log(`[collab] Hocuspocus server running on port ${PORT}`);
console.log(`[collab] Mode: awareness only (cursors, locking, save broadcast)`);
