/**
 * Hocuspocus collaboration server — awareness only (cursors, locking, save broadcast).
 *
 * Each board gets its own Yjs document, identified by the board_id. Documents are NOT persisted:
 * they hold awareness state and the operation log for a live session, and nothing else. The
 * ontology itself lives in each editor's own files, which is where the plugin saves it.
 *
 * Two ports, one document. Hocuspocus on COLLAB_PORT speaks the Yjs protocol for browser
 * clients; the JSON bridge on BRIDGE_PORT speaks to the Protege plugin, which has no Yjs
 * implementation. The bridge attaches to the SAME Y.Doc through `openDirectConnection`, so both
 * kinds of client are in one session.
 *
 * Because they share the document, they must demand the same credential — see onAuthenticate.
 */

import { Server } from "@hocuspocus/server";
import { startBridge, hocuspocusAuth } from "./bridge.mjs";

const PORT = parseInt(process.env.COLLAB_PORT || "1234", 10);
const BRIDGE_PORT = parseInt(process.env.BRIDGE_PORT || "1235", 10);

/**
 * No default. A collaboration server whose signing key is a string published in its own
 * repository is one anybody can mint a token for, and the previous default —
 * "change-me-in-production" — was exactly that, applied silently whenever the variable was
 * unset. Nothing printed a warning, so a server started without it looked identical to one
 * started correctly.
 */
const SECRET_KEY = process.env.SECRET_KEY;
if (!SECRET_KEY) {
  console.error(
    "[collab] SECRET_KEY is not set. It signs and verifies every token, so the server will " +
      "not start without one.\n" +
      "         Pick a long random string, give it to whoever mints tokens, and start again:\n" +
      "           SECRET_KEY=<your secret> node server.mjs",
  );
  process.exit(2);
}

const server = new Server({
  port: PORT,

  /**
   * Rejects anything without a valid token, by throwing.
   *
   * Returning a value from onAuthenticate authenticates the connection — that is the Hocuspocus
   * contract, and the only way to refuse one is to throw. This used to `return` an "anonymous"
   * "viewer" both when no token was supplied and when verification failed, so every connection
   * was accepted. The role was never enforced anywhere, so "viewer" bought nothing.
   *
   * That mattered because of `openDirectConnection` below: the JSON bridge, which does check its
   * token, attaches to the same Y.Doc. So an unauthenticated socket on this port joined the
   * document the authenticated Protege peers were editing, and the bridge's check could be
   * stepped around by connecting to the other port instead.
   *
   * The decision lives next to the bridge's own in bridge.mjs, so the two cannot drift apart
   * again, and is unit-tested there without booting a server.
   */
  onAuthenticate: hocuspocusAuth(SECRET_KEY, (message) => console.warn(message)),

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

// JSON bridge for non-JS clients (the Protege plugin). It attaches to the SAME Y.Doc that
// Hocuspocus serves, via openDirectConnection, so a Protege user and a browser user are in
// one session rather than two that happen to look alike.
startBridge({
  port: BRIDGE_PORT,
  secret: SECRET_KEY,
  getDoc: async (board) => {
    // server.hocuspocus, not server. `openDirectConnection` is a method of the Hocuspocus class;
    // `Server` is a thin wrapper that holds one as `this.hocuspocus` and does not re-export it.
    // Calling it on the wrapper threw "server.openDirectConnection is not a function" for every
    // board, the bridge reported that back as "could not open board" and closed the socket, and so
    // no Protege peer could ever join a session - the feature was inert from the first release that
    // shipped this line. Nothing caught it because the bridge's own tests inject their own getDoc
    // and never load server.mjs.
    const connection = await server.hocuspocus.openDirectConnection(board);
    return connection.document;
  },
});

console.log(`[collab] Hocuspocus server running on port ${PORT}`);
console.log(`[collab] Mode: awareness only (cursors, locking, save broadcast)`);
console.log(`[collab] JSON bridge for non-JS clients on port ${BRIDGE_PORT}`);
