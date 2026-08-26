/**
 * JSON WebSocket bridge onto the same Yjs documents Hocuspocus serves.
 *
 * Web clients speak Yjs. The Protégé plugin is Java, where no mature Yjs client exists, so
 * rather than port a CRDT or embed a JNI binding into an OSGi plugin, non-JS clients speak
 * plain JSON here and this module mirrors it into the shared Y.Doc.
 *
 * That keeps two things true that matter more than the convenience:
 *   - web clients are unchanged, and
 *   - the semantic merge rules keep a single implementation in mergeEngine.ts rather than
 *     being reimplemented in Java, where they would drift.
 *
 * As with server.mjs, this carries awareness and the operation log only. Ontology state is
 * persisted through the backend REST API, never through Yjs.
 *
 * Protocol — one envelope in both directions:
 *
 *   -> { t: "hello",    board: "b1", token: "<jwt>" }
 *   <- { t: "welcome",  user: "alice", peers: [...] }
 *   <> { t: "op",       op: { id, type, timestamp, userId, data } }
 *   <> { t: "presence", user, colour, x, y, selection }
 *   <- { t: "peers",    peers: [ { user, colour, x, y, selection } ] }
 *   <- { t: "error",    message: "..." }
 *
 * `op.type` must be one of the 17 in frontend/src/collab/useOperationSync.ts. A type the web
 * client cannot interpret is a silent no-op on the other side, which is far harder to
 * diagnose than a rejection, so unknown types are refused here.
 */

import { WebSocketServer } from "ws";
import jwt from "jsonwebtoken";

/** Mirrors OntologyOpType in frontend/src/collab/useOperationSync.ts. Keep in step. */
export const OPERATION_TYPES = new Set([
  "addClass", "updateClass", "removeClass",
  "addProperty", "removeProperty", "addSubClassOf",
  "addIndividual", "updateIndividual",
  "addLiteral", "updateLiteral", "removeLiteral",
  "addStickyNote", "updateStickyNote", "removeStickyNote",
  "addFrame", "updateFrame", "removeFrame",
]);

/** How long a peer's cursor survives without an update, in milliseconds. */
export const PRESENCE_TTL_MS = 10_000;

/**
 * How many of a connection's own operation ids are remembered, to avoid echoing them back.
 *
 * <p>Only has to cover the round trip from this socket into the shared document and back out
 * through the observer, which is immediate. A few hundred is generous, and the bound matters
 * because a long editing session on one connection would otherwise grow a set forever.
 */
export const SENT_ID_MEMORY = 500;

/**
 * Validates an incoming client message.
 *
 * Exported so the rules are testable without sockets — every rejection below corresponds to
 * something that would otherwise corrupt the shared document or mislead another client.
 *
 * @returns {{ok: true, message: object} | {ok: false, reason: string}}
 */
export function parseClientMessage(raw) {
  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return { ok: false, reason: "not valid JSON" };
  }
  if (!parsed || typeof parsed !== "object") {
    return { ok: false, reason: "expected a JSON object" };
  }
  const { t } = parsed;
  if (t === "hello") {
    if (!parsed.board || typeof parsed.board !== "string") {
      return { ok: false, reason: "hello needs a board" };
    }
    return { ok: true, message: parsed };
  }
  if (t === "op") {
    const op = parsed.op;
    if (!op || typeof op !== "object") {
      return { ok: false, reason: "op needs an op object" };
    }
    if (!op.id || typeof op.id !== "string") {
      // Without an id there is no deduplication, so the operation would echo forever.
      return { ok: false, reason: "op needs a string id" };
    }
    if (!OPERATION_TYPES.has(op.type)) {
      return { ok: false, reason: `unknown op type '${op.type}'` };
    }
    return { ok: true, message: parsed };
  }
  if (t === "presence") {
    if (typeof parsed.x !== "number" || typeof parsed.y !== "number") {
      return { ok: false, reason: "presence needs numeric x and y" };
    }
    return { ok: true, message: parsed };
  }
  return { ok: false, reason: `unknown message type '${t}'` };
}

/**
 * Verifies a token the same way server.mjs does.
 *
 * Unlike the Hocuspocus path this does NOT fall back to anonymous: a plugin that believes it
 * is authenticated while sending unattributed operations is worse than one that fails
 * loudly, because its edits land in the shared document under the wrong name.
 *
 * @returns {{ok: true, user: string} | {ok: false, reason: string}}
 */
export function authenticate(token, secret) {
  if (!token) {
    return { ok: false, reason: "no token supplied" };
  }
  try {
    const payload = jwt.verify(token, secret);
    return { ok: true, user: payload.sub, role: payload.role || "user" };
  } catch (err) {
    return { ok: false, reason: `invalid token: ${err.message}` };
  }
}

/** Drops peers that have gone quiet, so a crashed client's cursor does not linger. */
export function livePeers(peers, now = Date.now(), ttl = PRESENCE_TTL_MS) {
  const alive = [];
  for (const [, peer] of peers) {
    if (now - peer.seenAt <= ttl) {
      alive.push({
        user: peer.user,
        colour: peer.colour,
        x: peer.x,
        y: peer.y,
        selection: peer.selection,
      });
    }
  }
  alive.sort((a, b) => a.user.localeCompare(b.user));
  return alive;
}

/**
 * How an operation is stored in the shared Y.Array.
 *
 * useOperationSync.ts pushes `JSON.stringify(op)` and, when reading, skips any element that is
 * not a string (`if (typeof raw !== "string") continue`). That convention is the contract, and
 * the bridge has to honour it rather than push a bare object: an object lands in the array, web
 * clients skip it, and nothing surfaces anywhere. No exception, no log line - the operation
 * simply never happened as far as the browser is concerned.
 *
 * Exported so the round trip can be asserted against a real Y.Doc, which is the only way this
 * class of mismatch shows up. Before this existed the bridge pushed objects and forwarded raw
 * array contents, so Protege and the browser could not exchange a single operation in either
 * direction, and every unit test still passed.
 */
export function encodeOperation(op) {
  return JSON.stringify(op);
}

/**
 * Reads one element of the shared Y.Array back into an operation object.
 *
 * @returns the operation, or null if the element is not a well-formed operation - a malformed
 *          entry from an older or buggy client must not stop the ones after it being delivered.
 */
export function decodeOperation(content) {
  if (content && typeof content === "object") {
    // Tolerated, not produced: an object here means something wrote the array directly.
    return content;
  }
  if (typeof content !== "string") {
    return null;
  }
  try {
    const parsed = JSON.parse(content);
    return parsed && typeof parsed === "object" ? parsed : null;
  } catch {
    return null;
  }
}

/**
 * Starts the bridge.
 *
 * @param {object} options
 * @param {number} options.port
 * @param {string} options.secret            JWT secret, shared with the backend
 * @param {(board: string) => Promise<object>} options.getDoc  resolves a board id to its
 *        Y.Doc. May be async: Hocuspocus loads documents asynchronously, and the bridge must
 *        attach to the same document instance web clients use, not a parallel copy.
 */
export function startBridge({ port, secret, getDoc }) {
  const wss = new WebSocketServer({ port });
  /** board -> Map<socket, presence> */
  const boards = new Map();

  const presenceFor = (board) => {
    if (!boards.has(board)) {
      boards.set(board, new Map());
    }
    return boards.get(board);
  };

  const broadcastPeers = (board) => {
    const peers = presenceFor(board);
    const payload = JSON.stringify({ t: "peers", peers: livePeers(peers) });
    for (const socket of peers.keys()) {
      if (socket.readyState === socket.OPEN) {
        socket.send(payload);
      }
    }
  };

  wss.on("connection", (socket) => {
    let board = null;
    let user = null;
    let ops = null;
    let observer = null;
    /** Ids this connection sent, so the observer does not hand them straight back. */
    const sentFromHere = new Set();

    const fail = (message) => {
      try {
        socket.send(JSON.stringify({ t: "error", message }));
      } finally {
        socket.close();
      }
    };

    socket.on("message", async (raw) => {
      const parsed = parseClientMessage(String(raw));
      if (!parsed.ok) {
        fail(parsed.reason);
        return;
      }
      const message = parsed.message;

      if (message.t === "hello") {
        const auth = authenticate(message.token, secret);
        if (!auth.ok) {
          fail(auth.reason);
          return;
        }
        board = message.board;
        user = auth.user;

        let doc;
        try {
          doc = await getDoc(board);
        } catch (err) {
          fail(`could not open board '${board}': ${err.message}`);
          return;
        }
        ops = doc.getArray("ops");

        // Forward operations appended by anyone else.
        //
        // Filtered by the ids THIS connection sent, not by the authenticated user. Echo is a
        // property of a connection - do not send a socket back what that socket just said - and
        // filtering by user was a stricter thing that happened to imply it. The difference is not
        // academic: two Protege windows signed in as one account could not see each other at all,
        // which broke both the obvious way to try the feature out and the real case of one person
        // working across a desktop and a laptop.
        //
        // Loop safety is unchanged. A socket still never receives its own operations, so nothing
        // can amplify, and the plugin keeps its own id ledger as a second guard.
        observer = (event) => {
          const added = [];
          for (const item of event.changes.added) {
            for (const content of item.content.getContent()) {
              added.push(content);
            }
          }
          for (const content of added) {
            // Elements arrive as the JSON strings useOperationSync.ts writes, so they have to be
            // parsed before the id can be read off them. Forwarding the raw string instead would
            // send `{t:"op", op:"{...}"}`, which the Java client drops as a non-object.
            const op = decodeOperation(content);
            if (op && !sentFromHere.has(op.id) && socket.readyState === socket.OPEN) {
              socket.send(JSON.stringify({ t: "op", op }));
            }
          }
        };
        ops.observe(observer);

        presenceFor(board).set(socket, {
          user,
          colour: message.colour || "#4A90D9",
          x: 0,
          y: 0,
          selection: null,
          seenAt: Date.now(),
        });

        socket.send(JSON.stringify({
          t: "welcome",
          user,
          peers: livePeers(presenceFor(board)),
        }));
        broadcastPeers(board);
        console.log(`[bridge] ${user} joined '${board}'`);
        return;
      }

      if (!board) {
        fail("say hello before anything else");
        return;
      }

      if (message.t === "op") {
        // Recorded before the push, because the observer fires synchronously inside it.
        sentFromHere.add(message.op.id);
        if (sentFromHere.size > SENT_ID_MEMORY) {
          sentFromHere.delete(sentFromHere.values().next().value);
        }
        // Stamp the authenticated user rather than trusting the client's claim, and store it the
        // way web clients read it - see encodeOperation.
        ops.push([encodeOperation({ ...message.op, userId: user })]);
        return;
      }

      if (message.t === "presence") {
        const peer = presenceFor(board).get(socket);
        if (peer) {
          peer.x = message.x;
          peer.y = message.y;
          peer.selection = message.selection || null;
          peer.seenAt = Date.now();
        }
        broadcastPeers(board);
      }
    });

    socket.on("close", () => {
      if (board) {
        presenceFor(board).delete(socket);
        if (ops && observer) {
          ops.unobserve(observer);
        }
        broadcastPeers(board);
        console.log(`[bridge] ${user || "unknown"} left '${board}'`);
      }
    });

    // A dead socket must never take the service down with it.
    socket.on("error", (err) => {
      console.warn(`[bridge] socket error (suppressed): ${err.message}`);
    });
  });

  console.log(`[bridge] JSON bridge listening on port ${port}`);
  return wss;
}
