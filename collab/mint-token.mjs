#!/usr/bin/env node
/**
 * Mints one access token for one person.
 *
 *   SECRET_KEY=... node mint-token.mjs "Ada Lovelace"
 *   SECRET_KEY=... npm run mint-token -- "Ada Lovelace" --days 30
 *
 * Why this exists as a file rather than as a line in the documentation. There is no login service
 * and there is not going to be one: a token is a JWT your own server's SECRET_KEY signs, and the
 * documentation gave a `node -e "require('jsonwebtoken').sign(...)"` one-liner for it. That works,
 * and it was still the wrong answer. It is 90 characters of quoting that a Windows shell mangles
 * differently from a POSIX one, it silently mints an unusable token if SECRET_KEY is empty, and
 * nothing about it tells the operator which claim the plugin reads or when the thing expires.
 *
 * Meanwhile the plugin's own dialog said the token came "from the web application", which was
 * retired six releases earlier - so the only field a user cannot possibly guess pointed at nothing
 * at all. A command that can be named in a dialog is the missing piece between "I am running a
 * server" and "my colleagues can connect".
 *
 * The `sub` claim is the only one that matters: the bridge reads it and that is the name that
 * appears on the cursor. `role` is carried because the protocol has a slot for it and defaults to
 * "user"; nothing enforces it yet, which is said out loud rather than implied.
 */
import jwt from "jsonwebtoken";

const USAGE = [
  "Usage:  SECRET_KEY=<secret> node mint-token.mjs <name> [--days N] [--role user|viewer]",
  "",
  "  <name>    the person this token is for. It becomes their cursor name, so give each",
  "            person their own - two people sharing a token are two cursors with one name.",
  "  --days N  how long it lasts. Default 30. The plugin stops rather than retrying when a",
  "            token expires, and says 'invalid token: jwt expired'.",
  "  --role R  carried in the token and not yet enforced anywhere. Default 'user'.",
].join("\n");

/** Reads the arguments, or explains what is wrong with them. */
export function parseArguments(argv) {
  const positional = [];
  const options = { days: 30, role: "user" };
  for (let at = 0; at < argv.length; at++) {
    const argument = argv[at];
    if (argument === "--days" || argument === "--role") {
      const value = argv[++at];
      if (value === undefined) {
        return { ok: false, reason: `${argument} needs a value` };
      }
      if (argument === "--days") {
        const days = Number(value);
        // Rejected rather than coerced: Number("") is 0 and Number("thirty") is NaN, and a token
        // that expires immediately or never is worse than a message.
        //
        // A whole number, and bounded, because `${days}d` is handed to jsonwebtoken's duration
        // parser as text. Number(1e21) stringifies to "1e+21", so --days 1e21 passed this guard,
        // printed "valid for 1e+21 day(s)" and then died inside jwt.sign with a stack trace - the
        // script announcing success on the line before it crashed. A fraction is no better: "1.5d"
        // is not a duration either. 36500 days is a hundred years, which is already far longer than
        // any credential should live.
        if (!Number.isInteger(days) || days <= 0 || days > 36500) {
          return {
            ok: false,
            reason: `--days must be a whole number of days between 1 and 36500, not '${value}'`,
          };
        }
        options.days = days;
      } else {
        options.role = value;
      }
      continue;
    }
    if (argument.startsWith("--")) {
      return { ok: false, reason: `unknown option '${argument}'` };
    }
    positional.push(argument);
  }
  if (positional.length === 0) {
    return { ok: false, reason: "no name given" };
  }
  // Joined, so a name typed without quotes still works. Somebody minting a token for a colleague
  // should not lose a surname to a shell.
  return { ok: true, name: positional.join(" "), ...options };
}

/** The token itself, kept separate from the argument handling so both are testable. */
export function mint(name, secret, { days = 30, role = "user" } = {}) {
  return jwt.sign({ sub: name, role }, secret, { expiresIn: `${days}d` });
}

/** Run directly, not imported by the tests. */
if (process.argv[1] && process.argv[1].endsWith("mint-token.mjs")) {
  const secret = process.env.SECRET_KEY;
  if (!secret) {
    console.error(
      "[collab] SECRET_KEY is not set. It is the same secret the server verifies with, so a\n" +
        "         token minted without it cannot connect to anything.\n\n" +
        USAGE,
    );
    process.exit(2);
  }
  const parsed = parseArguments(process.argv.slice(2));
  if (!parsed.ok) {
    console.error(`[collab] ${parsed.reason}.\n\n${USAGE}`);
    process.exit(2);
  }
  // The token on stdout and everything else on stderr, so `> token.txt` gives a usable file.
  console.error(
    `[collab] ${parsed.name}, role ${parsed.role}, valid for ${parsed.days} day(s). ` +
      "Paste this into the plugin's Access token field:",
  );
  console.log(mint(parsed.name, secret, parsed));
}
