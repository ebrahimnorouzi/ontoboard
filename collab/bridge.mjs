/**
 * JSON WebSocket bridge onto the same Yjs documents Hocuspocus serves.
 *
 * A Yjs client speaks a binary CRDT protocol. The Protégé plugin is Java, where no mature Yjs
 * client exists, so rather than port a CRDT or embed a JNI binding into an OSGi plugin, non-JS
 * clients speak plain JSON here and this module mirrors it into the shared Y.Doc - the same
 * document Hocuspocus serves, so a plugin peer and any Yjs peer are in one session.
 *
 * This carries awareness and the operation log only. No ontology is stored anywhere in this
 * service: each editor's copy lives in their own files, and the log is how an edit reaches the
 * other people in the session.
 *
 * Protocol — one envelope in both directions:
 *
 *   -> { t: "hello",    board: "b1", token: "<jwt>", ontology?: "http://..." }
 *   <- { t: "welcome",  user: "alice", peers: [...] }
 *   <> { t: "op",       op: { id, type, timestamp, userId, data } }
 *   <> { t: "presence", user, colour, x, y, selection }
 *   <- { t: "peers",    peers: [ { user, colour, x, y, selection, ontology } ] }
 *   <- { t: "error",    message: "..." }
 *
 * `op.type` must be one of the 18 in OPERATION_TYPES below, which the plugin mirrors in
 * OntologyOperation.TYPES. A type the other end cannot interpret is a silent no-op there,
 * which is far harder to diagnose than a rejection, so unknown types are refused here.
 */

import { WebSocketServer } from "ws";
import jwt from "jsonwebtoken";

/** Mirrors OntologyOperation.TYPES in the plugin. Keep the two in step. */
export const OPERATION_TYPES = new Set([
  "addClass", "updateClass", "removeClass",
  "addProperty", "removeProperty", "addSubClassOf",
  "addIndividual", "updateIndividual",
  "addLiteral", "updateLiteral", "removeLiteral",
  "addStickyNote", "updateStickyNote", "removeStickyNote",
  "addFrame", "updateFrame", "removeFrame",
  // Editorial notes and every other annotation but rdfs:label. Listed here because this set is
  // a gate, not a description: an operation whose type is not in it is refused outright, so
  // without this entry a note written in one Protege never reaches another one.
  "updateAnnotation",
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
    // Optional, and relayed rather than checked. The bridge has no opinion about which ontology
    // anybody is editing - it cannot, it never sees one - but it is the only thing that can put
    // two peers in touch with each other's answer. Two people who both override the derived
    // board id to the same wrong value are invisible to every local check; this is what makes
    // them visible to each other.
    if (parsed.ontology !== undefined && typeof parsed.ontology !== "string") {
      return { ok: false, reason: "hello ontology must be a string when present" };
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

/**
 * The same decision, shaped the way Hocuspocus wants it.
 *
 * Hocuspocus authenticates a connection when `onAuthenticate` RETURNS, and refuses it only when
 * the function THROWS. The server's handler used to return an "anonymous" "viewer" both when no
 * token was supplied and when verification failed, so it accepted every connection; the role was
 * never enforced anywhere, so "viewer" bought nothing.
 *
 * It is here, beside `authenticate`, because the two must agree. They share a document: the
 * bridge attaches to the same Y.Doc that Hocuspocus serves, so a port that admits anonymous
 * clients is a way around the port that does not, and the comment above explaining why the
 * bridge refuses anonymous peers was describing a guard the other half gave away.
 *
 * @param {string} secret
 * @param {(message: string) => void} [log] told about each refusal
 * @returns {(context: {token?: string, documentName?: string}) => Promise<object>}
 */
export function hocuspocusAuth(secret, log) {
  return async ({ token, documentName }) => {
    const auth = authenticate(token, secret);
    if (!auth.ok) {
      if (log) {
        log(`[collab] Refused a connection to '${documentName}': ${auth.reason}`);
      }
      throw new Error(auth.reason);
    }
    return { user: { name: auth.user, role: auth.role } };
  };
}

/**
 * Drops peers that have gone quiet, so a crashed client's cursor does not linger.
 *
 * `ontology` is part of the payload, not decoration. The plugin sends it in `hello` and reads it
 * back off every peer to answer the one question it cannot answer locally: is everybody on this
 * board editing the same file as me? Two peers whose board id collides - a shared default, or the
 * same override typed twice - otherwise apply each other's axioms to unrelated ontologies with
 * nothing said. `CollabSession.peerOntologyWarning` exists for exactly that, and until this field
 * was included it could never fire, because every peer's ontology arrived empty. The connection
 * record has carried it all along; this function simply left it out, and no test on either side
 * mentioned it.
 */
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
        ontology: peer.ontology || "",
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
 * @param {string} options.secret            JWT secret; the same one that signed the tokens
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
          // Carried so peers can tell each other what they are editing. Empty when the client
          // did not say - an older plugin, or an ontology with no IRI of its own.
          ontology: typeof message.ontology === "string" ? message.ontology : "",
          seenAt: Date.now(),
        });

        socket.send(JSON.stringify({
          t: "welcome",
          user,
          peers: livePeers(presenceFor(board)),
        }));

        // Everything already in the log, after the welcome.
        //
        // This is what "an outage does not lose your edits" was only half of. The plugin queues
        // its OWN edits while disconnected and sends them on reconnect, so that direction
        // survived; the other direction did not exist. A peer that dropped off and came back
        // received only what happened after it returned, and a peer joining a session in
        // progress received nothing at all - the board it drew was missing every axiom agreed
        // before it arrived, with nothing on screen to say so.
        //
        // The Java client's own javadoc claimed this already worked: "Redelivery is normal after
        // a reconnect - the shared log is replayed". Its dedupe ledger was written for a replay
        // that never came.
        //
        // Uncapped, deliberately. The document is ephemeral - it holds one session's operations
        // and dies with the server - and a cap would reintroduce exactly the quiet loss this
        // fixes, just for the oldest edits instead of all of them. A session long enough for the
        // replay to be slow is a visible performance symptom, which is a better failure than
        // silently missing axioms.
        //
        // Sent after the observer is attached, so an operation pushed during the replay is
        // duplicated rather than dropped. The client deduplicates by operation id, so a repeat
        // costs nothing and a gap would be unrecoverable.
        let replayed = 0;
        for (const content of ops.toArray()) {
          const op = decodeOperation(content);
          if (op && socket.readyState === socket.OPEN) {
            socket.send(JSON.stringify({ t: "op", op }));
            replayed++;
          }
        }

        broadcastPeers(board);
        console.log(`[bridge] ${user} joined '${board}'`
            + (replayed > 0 ? `, caught up on ${replayed} operation(s)` : ""));
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
