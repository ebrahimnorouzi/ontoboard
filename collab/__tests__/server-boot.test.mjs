/**
 * The one test that boots server.mjs.
 *
 * bridge.test.mjs covers the bridge's decision points and bridge-interop.test.mjs drives it over
 * real sockets against a real Y.Doc - but both supply their own `getDoc`. That is the seam where
 * server.mjs lives, and nothing crossed it, so the line that wires the bridge to Hocuspocus was
 * never executed by a test.
 *
 * It was wrong. `openDirectConnection` is a method of the `Hocuspocus` class; `Server` is a wrapper
 * that holds one as `this.hocuspocus` and does not re-export it. So `server.openDirectConnection`
 * was undefined, `getDoc` threw for every board, the bridge answered "could not open board" and
 * closed the socket - and no Protege peer could join a session at all, in any release that shipped
 * that line, while all 41 tests here passed and docs/collaboration.md described the feature as
 * working.
 *
 * The lesson is narrow and worth keeping: a test that injects the dependency cannot check the
 * wiring. This one runs the real entry point, the way `node server.mjs` does, and speaks to it
 * over a socket.
 */
import { afterEach, describe, expect, it } from "vitest";
import { spawn } from "node:child_process";
import { createServer } from "node:net";
import { fileURLToPath } from "node:url";
import path from "node:path";
import jwt from "jsonwebtoken";
import WebSocket from "ws";

const SECRET = "server-boot-test-secret";
const COLLAB_DIR = path.dirname(path.dirname(fileURLToPath(import.meta.url)));

const running = [];
const openSockets = [];

afterEach(async () => {
  for (const socket of openSockets.splice(0)) {
    try {
      socket.terminate();
    } catch {
      /* already gone */
    }
  }
  for (const child of running.splice(0)) {
    child.kill("SIGKILL");
  }
});

/** A port the OS says is free, so a developer's own running server is never mistaken for ours. */
function freePort() {
  return new Promise((resolve, reject) => {
    const probe = createServer();
    probe.on("error", reject);
    probe.listen(0, "127.0.0.1", () => {
      const { port } = probe.address();
      probe.close(() => resolve(port));
    });
  });
}

/** Starts the real entry point and resolves once its bridge says it is listening. */
async function startRealServer() {
  const collabPort = await freePort();
  const bridgePort = await freePort();
  const child = spawn(process.execPath, ["server.mjs"], {
    cwd: COLLAB_DIR,
    env: {
      ...process.env,
      SECRET_KEY: SECRET,
      COLLAB_PORT: String(collabPort),
      BRIDGE_PORT: String(bridgePort),
    },
  });
  running.push(child);

  let output = "";
  child.stdout.on("data", (chunk) => {
    output += String(chunk);
  });
  child.stderr.on("data", (chunk) => {
    output += String(chunk);
  });

  const deadline = Date.now() + 20_000;
  while (Date.now() < deadline) {
    if (output.includes(`JSON bridge listening on port ${bridgePort}`)) {
      return { bridgePort, output: () => output };
    }
    if (child.exitCode !== null) {
      throw new Error(`server.mjs exited with ${child.exitCode}:\n${output}`);
    }
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  throw new Error(`server.mjs never listened:\n${output}`);
}

function connect(bridgePort) {
  const socket = new WebSocket(`ws://127.0.0.1:${bridgePort}`);
  openSockets.push(socket);
  return socket;
}

/** Sends hello and resolves with every frame received until `stop` says enough. */
function speak(socket, hello, stop) {
  return new Promise((resolve, reject) => {
    const frames = [];
    const timer = setTimeout(
      () => reject(new Error(`timed out; frames so far: ${JSON.stringify(frames)}`)),
      15_000,
    );
    socket.on("open", () => socket.send(JSON.stringify(hello)));
    socket.on("error", reject);
    socket.on("message", (raw) => {
      frames.push(JSON.parse(String(raw)));
      if (stop(frames)) {
        clearTimeout(timer);
        resolve(frames);
      }
    });
  });
}

const tokenFor = (sub) => jwt.sign({ sub, role: "user" }, SECRET);

describe("server.mjs", () => {
  it("opens a board for a plugin peer instead of refusing every one of them", async () => {
    const { bridgePort, output } = await startRealServer();
    const socket = connect(bridgePort);

    const frames = await speak(
      socket,
      { t: "hello", board: "boot-1", token: tokenFor("alice"), ontology: "http://example.org/x" },
      (seen) => seen.some((f) => f.t === "welcome" || f.t === "error"),
    );

    const errors = frames.filter((f) => f.t === "error");
    expect(errors, `the bridge refused the peer. Server said:\n${output()}`).toEqual([]);
    expect(frames.find((f) => f.t === "welcome")?.user).toBe("alice");
  });

  it("relays an operation from one plugin peer to another through the shared document", async () => {
    const { bridgePort, output } = await startRealServer();

    const alice = connect(bridgePort);
    const aliceWelcome = speak(alice, {
      t: "hello", board: "boot-2", token: tokenFor("alice"), ontology: "http://example.org/x",
    }, (seen) => seen.some((f) => f.t === "welcome"));
    await aliceWelcome;

    const bob = connect(bridgePort);
    const bobFrames = speak(bob, {
      t: "hello", board: "boot-2", token: tokenFor("bob"), ontology: "http://example.org/x",
    }, (seen) => seen.some((f) => f.t === "op"));

    // Sent after bob has been given a moment to attach his observer; the bridge only forwards what
    // arrives after that, which is the same race a second Protege window is in.
    await new Promise((resolve) => setTimeout(resolve, 300));
    alice.send(JSON.stringify({
      t: "op",
      op: { id: "op-1", type: "addClass", timestamp: 1, userId: "alice", data: { iri: "x#Pizza" } },
    }));

    const frames = await bobFrames;
    const relayed = frames.find((f) => f.t === "op");
    expect(relayed, `bob never received alice's operation. Server said:\n${output()}`)
      .toBeTruthy();
    expect(relayed.op.id).toBe("op-1");
    expect(relayed.op.type).toBe("addClass");
    // Stamped by the server, not trusted from the client.
    expect(relayed.op.userId).toBe("alice");
  });

  it("tells each peer which ontology the others are editing", async () => {
    // The field that makes CollabSession.peerOntologyWarning possible. It was stored on the
    // connection and left out of the payload, so the warning could never fire - two people on a
    // colliding board id applied each other's axioms to unrelated ontologies in silence.
    const { bridgePort, output } = await startRealServer();

    const alice = connect(bridgePort);
    await speak(alice, {
      t: "hello", board: "boot-3", token: tokenFor("alice"),
      ontology: "http://example.org/pizza",
    }, (seen) => seen.some((f) => f.t === "welcome"));

    const alicePeers = new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("alice was never told about bob")), 15_000);
      alice.on("message", (raw) => {
        const frame = JSON.parse(String(raw));
        if (frame.t === "peers" && frame.peers.some((p) => p.user === "bob")) {
          clearTimeout(timer);
          resolve(frame.peers);
        }
      });
    });

    const bob = connect(bridgePort);
    await speak(bob, {
      t: "hello", board: "boot-3", token: tokenFor("bob"),
      ontology: "http://example.org/entirely-different",
    }, (seen) => seen.some((f) => f.t === "welcome"));

    const peers = await alicePeers;
    const bobPeer = peers.find((p) => p.user === "bob");
    expect(bobPeer, `bob absent from alice's peers. Server said:\n${output()}`).toBeTruthy();
    expect(
      bobPeer.ontology,
      "without this the plugin cannot warn that two peers are on different ontologies",
    ).toBe("http://example.org/entirely-different");
  });

  it("will not start without a SECRET_KEY, instead of signing with a published one", async () => {
    // It used to default to "change-me-in-production", a string in this repository, applied
    // silently whenever the variable was unset - so a server started without it looked exactly
    // like one started correctly, and anybody could mint a token for it.
    const collabPort = await freePort();
    const bridgePort = await freePort();
    const withoutSecret = { ...process.env };
    delete withoutSecret.SECRET_KEY;

    const child = spawn(process.execPath, ["server.mjs"], {
      cwd: COLLAB_DIR,
      env: { ...withoutSecret, COLLAB_PORT: String(collabPort), BRIDGE_PORT: String(bridgePort) },
    });
    running.push(child);

    let output = "";
    child.stdout.on("data", (chunk) => (output += String(chunk)));
    child.stderr.on("data", (chunk) => (output += String(chunk)));

    const code = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`it kept running:\n${output}`)), 20_000);
      child.on("exit", (exitCode) => {
        clearTimeout(timer);
        resolve(exitCode);
      });
    });

    expect(code, `expected a refusal to start. It said:\n${output}`).toBe(2);
    expect(output).toContain("SECRET_KEY is not set");
    // The message has to say what to do, or it is just a stop.
    expect(output).toContain("SECRET_KEY=");
    expect(output).not.toContain("change-me-in-production");
  });

  it("refuses an unauthenticated peer rather than letting it edit as nobody", async () => {
    const { bridgePort } = await startRealServer();
    const socket = connect(bridgePort);

    const frames = await speak(
      socket,
      { t: "hello", board: "boot-4" },
      (seen) => seen.some((f) => f.t === "welcome" || f.t === "error"),
    );

    expect(frames.find((f) => f.t === "error")?.message).toContain("no token");
    expect(frames.some((f) => f.t === "welcome")).toBe(false);
  });
});
