/**
 * Minting a token, which is the only way anyone gets into a session.
 *
 * There is no login service, so a token is a JWT the operator signs with the server's SECRET_KEY.
 * This was documented as a `node -e "require('jsonwebtoken').sign(...)"` one-liner and nothing
 * else - while the plugin's own dialog told the user the token came "from the web application",
 * which had been retired six releases earlier. So the one field nobody can guess pointed at a
 * product that no longer existed, and the actual procedure lived in a line of shell nobody could
 * be named in a dialog.
 *
 * The test that matters most is the last one: a token this script mints is one the bridge's own
 * verifier accepts. The two halves are in separate files and nothing else would notice them
 * drifting - which is exactly how the server came to admit anonymous connections.
 */
import { describe, expect, it } from "vitest";
import { authenticate } from "../bridge.mjs";
import { mint, parseArguments } from "../mint-token.mjs";

describe("parseArguments", () => {
  it("takes a name", () => {
    expect(parseArguments(["Ada"])).toMatchObject({ ok: true, name: "Ada", days: 30, role: "user" });
  });

  it("joins an unquoted name, so a surname is not lost to the shell", () => {
    expect(parseArguments(["Ada", "Lovelace"]).name).toBe("Ada Lovelace");
  });

  it("refuses to mint for nobody", () => {
    expect(parseArguments([])).toMatchObject({ ok: false });
    expect(parseArguments(["--days", "7"])).toMatchObject({ ok: false });
  });

  it("reads the lifetime and the role", () => {
    expect(parseArguments(["Ada", "--days", "7", "--role", "viewer"])).toMatchObject({
      ok: true,
      days: 7,
      role: "viewer",
    });
  });

  /**
   * Rejected rather than coerced, and bounded. Number("") is 0 and Number("thirty") is NaN, and a
   * token that expires immediately or never is worse than a message - the first looks like a server
   * fault and the second is a credential with no end.
   *
   * `1e21` is the one that mattered: Number("1e21") is finite and positive, so it passed the
   * original guard, the script printed "valid for 1e+21 day(s)" - and then died inside jwt.sign,
   * because `${days}d` stringifies to "1e+21d", which is not a duration. The script announced
   * success on the line before it crashed. A fraction fails the same way: "1.5d" is not a duration.
   */
  it("refuses a lifetime that is not a whole, bounded number of days", () => {
    for (const bad of ["thirty", "", "0", "-5", "1e21", "1.5", "Infinity", "36501"]) {
      expect(parseArguments(["Ada", "--days", bad]), bad).toMatchObject({ ok: false });
    }
    for (const good of ["1", "30", "36500"]) {
      expect(parseArguments(["Ada", "--days", good]), good).toMatchObject({ ok: true });
    }
  });

  /** And what survives the guard is a duration jsonwebtoken actually accepts. */
  it("every accepted lifetime mints without throwing", () => {
    for (const days of [1, 7, 30, 365, 36500]) {
      expect(() => mint("Ada", "s", { days })).not.toThrow();
    }
  });

  it("refuses an option it does not know, rather than treating it as a name", () => {
    expect(parseArguments(["Ada", "--forever"])).toMatchObject({ ok: false });
    expect(parseArguments(["Ada", "--days"])).toMatchObject({ ok: false });
  });
});

describe("mint", () => {
  it("puts the name in sub, which is the claim the bridge reads", () => {
    const payload = JSON.parse(
      Buffer.from(mint("Ada Lovelace", "s").split(".")[1], "base64").toString("utf8"),
    );

    expect(payload.sub).toBe("Ada Lovelace");
    expect(payload.role).toBe("user");
    expect(payload.exp - payload.iat).toBe(30 * 24 * 60 * 60);
  });

  it("honours the lifetime asked for", () => {
    const payload = JSON.parse(
      Buffer.from(mint("Ada", "s", { days: 7 }).split(".")[1], "base64").toString("utf8"),
    );

    expect(payload.exp - payload.iat).toBe(7 * 24 * 60 * 60);
  });

  /** The claim this whole script exists to make. */
  it("mints something the bridge accepts", () => {
    const token = mint("Ada Lovelace", "shared-secret", { days: 1 });

    expect(authenticate(token, "shared-secret")).toEqual({
      ok: true,
      user: "Ada Lovelace",
      role: "user",
    });
  });

  it("and the role survives the round trip", () => {
    expect(authenticate(mint("Bob", "shared-secret", { role: "viewer" }), "shared-secret")).toEqual({
      ok: true,
      user: "Bob",
      role: "viewer",
    });
  });

  /** A token minted against the wrong secret is the most common real failure. */
  it("is refused by a server holding a different secret", () => {
    const refused = authenticate(mint("Ada", "one-secret"), "another-secret");

    expect(refused.ok).toBe(false);
    expect(refused.reason).toContain("invalid signature");
  });
});
