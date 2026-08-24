/**
 * Tests for the pure decision points in bridge.mjs — validation, auth and presence expiry.
 * Socket plumbing is not covered here; these are the rules that, if wrong, corrupt the
 * shared document or mislead another client.
 */
import { describe, it, expect } from "vitest";
import jwt from "jsonwebtoken";
import {
  parseClientMessage, authenticate, livePeers, OPERATION_TYPES, PRESENCE_TTL_MS,
} from "../bridge.mjs";

const SECRET = "test-secret";

describe("parseClientMessage", () => {
  it("accepts a well-formed hello", () => {
    const r = parseClientMessage(JSON.stringify({ t: "hello", board: "b1", token: "x" }));
    expect(r.ok).toBe(true);
  });

  it("rejects malformed JSON rather than throwing", () => {
    expect(parseClientMessage("{not json").ok).toBe(false);
  });

  it("rejects a hello with no board", () => {
    expect(parseClientMessage(JSON.stringify({ t: "hello" })).ok).toBe(false);
  });

  it("rejects an unknown message type", () => {
    expect(parseClientMessage(JSON.stringify({ t: "mystery" })).ok).toBe(false);
  });

  it("accepts every operation type the web client emits", () => {
    for (const type of OPERATION_TYPES) {
      const r = parseClientMessage(JSON.stringify({
        t: "op", op: { id: "1", type, timestamp: 0, userId: "u", data: {} },
      }));
      expect(r.ok, `rejected known type ${type}`).toBe(true);
    }
  });

  /** A type the web client cannot interpret is a silent no-op there, so refuse it here. */
  it("rejects an operation type the web client would ignore", () => {
    const r = parseClientMessage(JSON.stringify({
      t: "op", op: { id: "1", type: "addWidget", timestamp: 0, userId: "u", data: {} },
    }));
    expect(r.ok).toBe(false);
    expect(r.reason).toContain("addWidget");
  });

  /** Without an id there is no dedup, so the operation would echo between clients forever. */
  it("rejects an operation with no id", () => {
    const r = parseClientMessage(JSON.stringify({
      t: "op", op: { type: "addClass", timestamp: 0, userId: "u", data: {} },
    }));
    expect(r.ok).toBe(false);
  });

  it("rejects presence without numeric coordinates", () => {
    expect(parseClientMessage(JSON.stringify({ t: "presence", x: "1", y: 2 })).ok).toBe(false);
    expect(parseClientMessage(JSON.stringify({ t: "presence", x: 1, y: 2 })).ok).toBe(true);
  });
});

describe("authenticate", () => {
  it("accepts a token signed with the shared secret", () => {
    const token = jwt.sign({ sub: "alice" }, SECRET);
    const r = authenticate(token, SECRET);
    expect(r.ok).toBe(true);
    expect(r.user).toBe("alice");
  });

  it("rejects a token signed with a different secret", () => {
    expect(authenticate(jwt.sign({ sub: "mallory" }, "other"), SECRET).ok).toBe(false);
  });

  /**
   * Hocuspocus downgrades a missing token to anonymous. The bridge must not: a client that
   * believes it is authenticated while sending unattributed operations puts edits into the
   * shared document under the wrong name.
   */
  it("refuses a missing token instead of falling back to anonymous", () => {
    const r = authenticate(undefined, SECRET);
    expect(r.ok).toBe(false);
    expect(r.user).toBeUndefined();
  });
});

describe("livePeers", () => {
  const peers = () => new Map([
    ["s1", { user: "bob", colour: "#111111", x: 1, y: 2, selection: null, seenAt: 1000 }],
    ["s2", { user: "amy", colour: "#222222", x: 3, y: 4, selection: "iri", seenAt: 1000 }],
  ]);

  it("returns peers sorted so the list does not reshuffle between updates", () => {
    expect(livePeers(peers(), 1000).map((p) => p.user)).toEqual(["amy", "bob"]);
  });

  it("drops peers that have gone quiet, so a crashed client's cursor does not linger", () => {
    expect(livePeers(peers(), 1000 + PRESENCE_TTL_MS + 1)).toEqual([]);
  });

  it("keeps a peer that is exactly at the deadline", () => {
    expect(livePeers(peers(), 1000 + PRESENCE_TTL_MS)).toHaveLength(2);
  });

  it("never leaks the internal seenAt timestamp to clients", () => {
    for (const peer of livePeers(peers(), 1000)) {
      expect(peer.seenAt).toBeUndefined();
    }
  });
});
