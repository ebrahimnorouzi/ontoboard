/**
 * The Hocuspocus port's authentication, which used to admit everybody.
 *
 * Two ports share one Y.Doc: Hocuspocus on 1234 for browser clients, and the JSON bridge on
 * 1235 for the Protege plugin, attached to the same document through `openDirectConnection`.
 * The bridge checked its token and refused anonymous peers - its own comment says why - and the
 * Hocuspocus handler returned an "anonymous" "viewer" for both a missing token and an invalid
 * one. Hocuspocus authenticates a connection when the handler returns, so every connection was
 * accepted, and the role was never enforced anywhere.
 *
 * The effect was that the bridge's check could be stepped around by connecting to the other
 * port: an unauthenticated socket joined the document the authenticated Protege peers were
 * editing.
 *
 * The existing server-boot test did assert "refuses an unauthenticated peer", and passed before
 * the fix - because it speaks to the bridge. Nothing covered the other port. That is the shape
 * of this whole bug: a guard tested on the half that had it.
 */
import { describe, expect, it } from "vitest";
import jwt from "jsonwebtoken";
import { hocuspocusAuth } from "../bridge.mjs";

const SECRET = "hocuspocus-auth-test-secret";

describe("hocuspocusAuth", () => {
  it("refuses a connection with no token by throwing, not by returning a viewer", async () => {
    const authenticate = hocuspocusAuth(SECRET);

    await expect(authenticate({ documentName: "board-1" })).rejects.toThrow("no token supplied");
  });

  it("refuses an empty token", async () => {
    const authenticate = hocuspocusAuth(SECRET);

    await expect(authenticate({ token: "", documentName: "board-1" })).rejects.toThrow();
  });

  it("refuses a token signed with the wrong secret", async () => {
    const authenticate = hocuspocusAuth(SECRET);
    const forged = jwt.sign({ sub: "mallory" }, "not-the-secret");

    await expect(authenticate({ token: forged, documentName: "board-1" }))
      .rejects.toThrow(/invalid token/);
  });

  it("refuses a token that has expired", async () => {
    const authenticate = hocuspocusAuth(SECRET);
    const stale = jwt.sign({ sub: "alice" }, SECRET, { expiresIn: -10 });

    await expect(authenticate({ token: stale, documentName: "board-1" }))
      .rejects.toThrow(/invalid token/);
  });

  it("admits a valid token, naming the user the token names", async () => {
    const authenticate = hocuspocusAuth(SECRET);
    const token = jwt.sign({ sub: "alice", role: "user" }, SECRET);

    const context = await authenticate({ token, documentName: "board-1" });

    expect(context.user.name).toBe("alice");
    expect(context.user.role).toBe("user");
  });

  it("defaults the role rather than leaving it undefined", async () => {
    const authenticate = hocuspocusAuth(SECRET);
    const token = jwt.sign({ sub: "alice" }, SECRET);

    expect((await authenticate({ token, documentName: "b" })).user.role).toBe("user");
  });

  it("reports each refusal, so a rejected peer is diagnosable from the server's output", async () => {
    const said = [];
    const authenticate = hocuspocusAuth(SECRET, (message) => said.push(message));

    await expect(authenticate({ documentName: "board-9" })).rejects.toThrow();

    expect(said).toHaveLength(1);
    expect(said[0]).toContain("board-9");
    expect(said[0]).toContain("no token supplied");
  });

  it("says nothing when the token is good", async () => {
    const said = [];
    const authenticate = hocuspocusAuth(SECRET, (message) => said.push(message));

    await authenticate({ token: jwt.sign({ sub: "alice" }, SECRET), documentName: "b" });

    expect(said).toEqual([]);
  });

  it("agrees with the bridge, which is the point of sharing the verifier", async () => {
    const { authenticate } = await import("../bridge.mjs");
    const forged = jwt.sign({ sub: "mallory" }, "not-the-secret");

    // The bridge says no...
    expect(authenticate(forged, SECRET).ok).toBe(false);
    // ...and so does the port that shares its document.
    await expect(hocuspocusAuth(SECRET)({ token: forged })).rejects.toThrow();
  });
});
