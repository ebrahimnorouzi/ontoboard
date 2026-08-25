/**
 * Interop tests: the bridge against a real Y.Doc and real sockets.
 *
 * bridge.test.mjs covers the pure decision points, and deliberately says socket plumbing is out
 * of scope. That left the one thing neither side can check alone completely unguarded, and it was
 * wrong: the bridge pushed operations into the shared array as objects, while
 * useOperationSync.ts writes `JSON.stringify(op)` and, when reading, skips any element that is
 * not a string. Protege and the browser could not exchange a single operation in either
 * direction, every unit test passed, and nothing logged a word about it.
 *
 * So these tests read and write the array exactly the way the web client does - see
 * readAsWebClient and pushAsWebClient, which are transcriptions of
 * frontend/src/collab/useOperationSync.ts, not paraphrases. A change to the storage convention
 * on either side now fails here instead of surfacing as silence in a running system.
 */
import { afterEach, describe, expect, it } from "vitest";
import * as Y from "yjs";
import jwt from "jsonwebtoken";
import WebSocket from "ws";
import { startBridge, encodeOperation, decodeOperation } from "../bridge.mjs";

const SECRET = "test-secret";

/**
 * Opened by a test and torn down after it, pass or fail. Servers and sockets are kept apart
 * because their close() signatures differ - WebSocketServer.close takes a callback and waits for
 * its clients, WebSocket.close does not - and treating them alike hangs the hook rather than
 * failing it, which reads as every test in the file timing out.
 */
const openSockets = [];
const openServers = [];

afterEach(async () => {
  for (const socket of openSockets.splice(0)) {
    try {
      socket.terminate();
    } catch {
      /* already gone */
    }
  }
  for (const wss of openServers.splice(0)) {
    for (const socket of wss.clients) {
      try {
        socket.terminate();
      } catch {
        /* already gone */
      }
    }
    await new Promise((resolve) => wss.close(resolve));
  }
});

// ---------- the web client's own storage convention, transcribed ----------

/** frontend/src/collab/useOperationSync.ts:288 - `yOps.push([JSON.stringify(op)])`. */
function pushAsWebClient(doc, op) {
  doc.getArray("ops").push([JSON.stringify(op)]);
}

/**
 * frontend/src/collab/useOperationSync.ts:160-168. The `typeof raw !== "string"` line is the
 * one that made bridge-written objects invisible, so it is reproduced rather than relaxed.
 */
function readAsWebClient(doc) {
  const yOps = doc.getArray("ops");
  const ops = [];
  for (let i = 0; i < yOps.length; i++) {
    const raw = yOps.get(i);
    if (typeof raw !== "string") continue;
    try {
      ops.push(JSON.parse(raw));
    } catch {
      /* ignore malformed, as the web client does */
    }
  }
  return ops;
}

// ---------- harness ----------

async function bridgeOn(doc) {
  const wss = startBridge({ port: 0, secret: SECRET, getDoc: async () => doc });
  openServers.push(wss);
  if (wss.address() === null) {
    await new Promise((resolve) => wss.once("listening", resolve));
  }
  return wss.address().port;
}

/** A client that records everything it is sent, so tests can await a condition. */
function client(port) {
  const socket = new WebSocket(`ws://127.0.0.1:${port}`);
  openSockets.push(socket);
  const received = [];
  let closed = null;
  socket.on("message", (raw) => {
    try {
      received.push(JSON.parse(String(raw)));
    } catch {
      received.push({ t: "unparseable", raw: String(raw) });
    }
  });
  socket.on("close", (code, reason) => {
    closed = { code, reason: String(reason) };
  });
  socket.on("error", () => {
    /* a refused or reset socket is asserted through `closed`, not thrown */
  });

  const ready = new Promise((resolve, reject) => {
    socket.once("open", resolve);
    socket.once("error", reject);
  });

  return {
    socket,
    received,
    isClosed: () => closed,
    async open() {
      await ready;
      return this;
    },
    send(message) {
      socket.send(JSON.stringify(message));
    },
    async hello(board, user, colour) {
      this.send({ t: "hello", board, token: jwt.sign({ sub: user }, SECRET), colour });
      return waitFor(() => received.find((m) => m.t === "welcome" || m.t === "error"),
          `welcome for ${user}`);
    },
    waitFor(predicate, what) {
      return waitFor(() => received.find(predicate), what);
    },
  };
}

/** Resolves with the first truthy value `probe` returns, or fails with a useful message. */
async function waitFor(probe, what, timeoutMs = 2000) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const found = probe();
    if (found) return found;
    if (Date.now() > deadline) {
      throw new Error(`timed out after ${timeoutMs}ms waiting for ${what}`);
    }
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
}

function operation(type, userId, data) {
  return { id: `op-${type}-${userId}`, type, timestamp: 1000, userId, data };
}

// ---------- storage convention ----------

describe("the shared-array encoding", () => {
  it("stores an operation the way the web client reads it", () => {
    const doc = new Y.Doc();
    doc.getArray("ops").push([encodeOperation(operation("addClass", "alice",
        { iri: "http://example.org/o#Person" }))]);

    const seen = readAsWebClient(doc);
    expect(seen).toHaveLength(1);
    expect(seen[0].type).toBe("addClass");
    expect(seen[0].data.iri).toBe("http://example.org/o#Person");
  });

  it("proves a bare object is invisible to the web client, which is the bug this guards", () => {
    const doc = new Y.Doc();
    doc.getArray("ops").push([operation("addClass", "alice", { iri: "x" })]);

    expect(doc.getArray("ops").length).toBe(1);
    expect(readAsWebClient(doc)).toHaveLength(0);
  });

  it("reads back what the web client wrote", () => {
    const doc = new Y.Doc();
    pushAsWebClient(doc, operation("removeClass", "bob", { iri: "http://example.org/o#Robot" }));

    const decoded = decodeOperation(doc.getArray("ops").get(0));
    expect(decoded.type).toBe("removeClass");
    expect(decoded.userId).toBe("bob");
  });

  it("returns null for junk rather than throwing, so one bad entry cannot stop the rest", () => {
    expect(decodeOperation("not json")).toBeNull();
    expect(decodeOperation("")).toBeNull();
    expect(decodeOperation("42")).toBeNull();
    expect(decodeOperation('"a string"')).toBeNull();
    expect(decodeOperation(null)).toBeNull();
    expect(decodeOperation(undefined)).toBeNull();
  });

  it("tolerates an object written directly, so an older client is read rather than dropped", () => {
    expect(decodeOperation(operation("addClass", "amy", { iri: "y" })).userId).toBe("amy");
  });
});

// ---------- plugin -> browser ----------

describe("an operation sent by the plugin", () => {
  it("reaches a web client reading the shared document", async () => {
    const doc = new Y.Doc();
    const port = await bridgeOn(doc);
    const plugin = await client(port).open();
    await plugin.hello("b1", "alice");

    plugin.send({ t: "op", op: operation("addSubClassOf", "alice",
        { childIri: "http://example.org/o#Dog", parentIri: "http://example.org/o#Animal" }) });

    const seen = await waitFor(() => {
      const ops = readAsWebClient(doc);
      return ops.length ? ops : null;
    }, "the operation to appear in the document as the web client reads it");

    expect(seen[0].type).toBe("addSubClassOf");
    expect(seen[0].data.childIri).toBe("http://example.org/o#Dog");
  });

  it("is attributed to the authenticated user, not to whoever the client claimed", async () => {
    const doc = new Y.Doc();
    const port = await bridgeOn(doc);
    const plugin = await client(port).open();
    await plugin.hello("b1", "alice");

    plugin.send({ t: "op", op: operation("addClass", "pretending-to-be-admin", { iri: "x" }) });

    const seen = await waitFor(() => {
      const ops = readAsWebClient(doc);
      return ops.length ? ops : null;
    }, "the operation");
    expect(seen[0].userId).toBe("alice");
  });

  it("is not echoed back to the sender", async () => {
    const doc = new Y.Doc();
    const port = await bridgeOn(doc);
    const plugin = await client(port).open();
    await plugin.hello("b1", "alice");

    plugin.send({ t: "op", op: operation("addClass", "alice", { iri: "x" }) });
    await waitFor(() => (readAsWebClient(doc).length ? true : null), "the operation to land");
    await new Promise((resolve) => setTimeout(resolve, 100));

    expect(plugin.received.filter((m) => m.t === "op")).toHaveLength(0);
  });

  it("reaches a second plugin client signed in as a different user", async () => {
    const doc = new Y.Doc();
    const port = await bridgeOn(doc);
    const alice = await client(port).open();
    const bob = await client(port).open();
    await alice.hello("b1", "alice");
    await bob.hello("b1", "bob");

    alice.send({ t: "op", op: operation("addIndividual", "alice",
        { iri: "http://example.org/o#rex", class_iri: "http://example.org/o#Dog" }) });

    const relayed = await bob.waitFor((m) => m.t === "op", "bob to receive alice's operation");
    expect(typeof relayed.op).toBe("object");
    expect(relayed.op.type).toBe("addIndividual");
    expect(relayed.op.userId).toBe("alice");
    expect(relayed.op.data.class_iri).toBe("http://example.org/o#Dog");
  });
});

// ---------- browser -> plugin ----------

describe("an operation written by a web client", () => {
  it("arrives at the plugin as an object, not as a JSON string", async () => {
    const doc = new Y.Doc();
    const port = await bridgeOn(doc);
    const plugin = await client(port).open();
    await plugin.hello("b1", "alice");

    pushAsWebClient(doc, operation("updateClass", "bob",
        { iri: "http://example.org/o#Person", updates: { label: "Human" } }));

    const relayed = await plugin.waitFor((m) => m.t === "op", "the web client's operation");
    // A raw string here is the failure mode: CollabMessages.decode requires an object and
    // would return Kind.UNKNOWN, dropping the operation without a word.
    expect(typeof relayed.op).toBe("object");
    expect(relayed.op.type).toBe("updateClass");
    expect(relayed.op.data.updates.label).toBe("Human");
  });

  it("is withheld when it is the plugin user's own work coming back round", async () => {
    const doc = new Y.Doc();
    const port = await bridgeOn(doc);
    const plugin = await client(port).open();
    await plugin.hello("b1", "alice");

    pushAsWebClient(doc, operation("addClass", "alice", { iri: "x" }));
    await new Promise((resolve) => setTimeout(resolve, 150));

    expect(plugin.received.filter((m) => m.t === "op")).toHaveLength(0);
  });

  it("still arrives when an earlier array element is malformed", async () => {
    const doc = new Y.Doc();
    const port = await bridgeOn(doc);
    const plugin = await client(port).open();
    await plugin.hello("b1", "alice");

    doc.getArray("ops").push(["}{ not json"]);
    pushAsWebClient(doc, operation("removeProperty", "bob", { id: "p1" }));

    const relayed = await plugin.waitFor((m) => m.t === "op", "the operation after the junk");
    expect(relayed.op.type).toBe("removeProperty");
  });
});

// ---------- lifecycle ----------

describe("the connection lifecycle", () => {
  it("names the authenticated user in the welcome", async () => {
    const port = await bridgeOn(new Y.Doc());
    const plugin = await client(port).open();

    const welcome = await plugin.hello("b1", "alice");
    expect(welcome.t).toBe("welcome");
    expect(welcome.user).toBe("alice");
    expect(Array.isArray(welcome.peers)).toBe(true);
  });

  it("refuses an operation sent before hello, and says so", async () => {
    const port = await bridgeOn(new Y.Doc());
    const plugin = await client(port).open();

    plugin.send({ t: "op", op: operation("addClass", "alice", { iri: "x" }) });

    const error = await plugin.waitFor((m) => m.t === "error", "an error");
    expect(error.message).toContain("hello");
    await waitFor(() => plugin.isClosed(), "the socket to be closed");
  });

  it("refuses a token signed with the wrong secret and explains why", async () => {
    const port = await bridgeOn(new Y.Doc());
    const plugin = await client(port).open();

    plugin.send({ t: "hello", board: "b1", token: jwt.sign({ sub: "alice" }, "wrong-secret") });

    const error = await plugin.waitFor((m) => m.t === "error", "an error");
    expect(error.message).toContain("invalid token");
    await waitFor(() => plugin.isClosed(), "the socket to be closed");
  });

  it("closes the socket on any malformed frame, so a client must reconnect", async () => {
    const port = await bridgeOn(new Y.Doc());
    const plugin = await client(port).open();
    await plugin.hello("b1", "alice");

    plugin.socket.send("this is not json");

    await waitFor(() => plugin.isClosed(), "the socket to be closed after a bad frame");
  });
});

// ---------- presence ----------

describe("presence", () => {
  it("broadcasts both peers to both of them, with their colours", async () => {
    const port = await bridgeOn(new Y.Doc());
    const alice = await client(port).open();
    const bob = await client(port).open();
    await alice.hello("b1", "alice", "#AA0000");
    await bob.hello("b1", "bob", "#0000BB");

    alice.send({ t: "presence", user: "alice", colour: "#AA0000", x: 12.5, y: -3, selection: null });

    const peers = await bob.waitFor(
        (m) => m.t === "peers" && m.peers.length === 2 && m.peers.some((p) => p.x === 12.5),
        "a peers list carrying alice's cursor");

    const byUser = {};
    for (const peer of peers.peers) byUser[peer.user] = peer;
    expect(byUser.alice.x).toBe(12.5);
    expect(byUser.alice.y).toBe(-3);
    expect(byUser.alice.colour).toBe("#AA0000");
    expect(byUser.bob.colour).toBe("#0000BB");
  });

  it("carries the selected IRI so a viewer can see what someone is on", async () => {
    const port = await bridgeOn(new Y.Doc());
    const alice = await client(port).open();
    const bob = await client(port).open();
    await alice.hello("b1", "alice");
    await bob.hello("b1", "bob");

    alice.send({ t: "presence", user: "alice", x: 0, y: 0,
        selection: "http://example.org/o#Person" });

    const peers = await bob.waitFor(
        (m) => m.t === "peers" && m.peers.some((p) => p.selection), "a selection");
    expect(peers.peers.find((p) => p.user === "alice").selection)
        .toBe("http://example.org/o#Person");
  });

  it("keeps boards apart, so a peer on another board is never shown", async () => {
    const port = await bridgeOn(new Y.Doc());
    const alice = await client(port).open();
    const stranger = await client(port).open();
    await alice.hello("board-1", "alice");
    await stranger.hello("board-2", "stranger");

    stranger.send({ t: "presence", user: "stranger", x: 5, y: 5, selection: null });
    await new Promise((resolve) => setTimeout(resolve, 150));

    for (const message of alice.received.filter((m) => m.t === "peers")) {
      expect(message.peers.map((p) => p.user)).not.toContain("stranger");
    }
  });

  it("rejects non-numeric coordinates by closing, since a NaN cursor cannot be drawn", async () => {
    const port = await bridgeOn(new Y.Doc());
    const plugin = await client(port).open();
    await plugin.hello("b1", "alice");

    plugin.send({ t: "presence", user: "alice", x: "12", y: 0, selection: null });

    const error = await plugin.waitFor((m) => m.t === "error", "an error");
    expect(error.message).toContain("numeric");
  });
});
