# Collaboration

Two ways to work together. Pick by whether your team wants to run a server.

| | Live | Git |
|---|---|---|
| Needs a server | yes, one you run | no |
| See edits as they happen | yes | no |
| See other people's cursors | yes | no |
| Works offline | queues your edits, and catches up on theirs | yes |
| Setup | one service, one shared secret, a token each | none |

**OntoBoard hosts nothing.** There is no ontoboard.org to sign in to, no account we hold, and
no server we operate. Live collaboration means a server *your* team runs — on a lab machine, a
VM, wherever your ontology already lives. That is a deliberate choice: ontologies under
development are often unpublished, and a research group should not have to send theirs through
somebody else's service to work on them together.

If nobody wants to run one, git mode is not a downgraded fallback. It is how OBO ontologies are
actually built, and the ODK repository the plugin scaffolds is already set up for it.

---

## Git mode

Nothing to configure. Leave the collaboration fields empty and work as you would on any shared
repository: edit, commit, push, pull, resolve conflicts in the diff.

What you lose is only the live part — you will not see a colleague's cursor, and their edits
arrive when you pull rather than as they type. Everything else is unchanged, including the
canvas, the sidecar layout file and the ODK pipeline.

The sidecar (`<ontology>.ontoboard.json`) is designed for this: layout lives beside the
ontology, not inside it, so a merge conflict in a diagram never touches an axiom. Commit it if
you want to share arrangements, or add it to `.gitignore` if diagrams are personal. Either
works.

---

## Live mode

### 1. Run the server

The collaboration service is in [`collab/`](https://github.com/ebrahimnorouzi/ontoboard/tree/main/collab/). It is a small Node server and needs
nothing but Node to run:

```bash
cd collab
npm install
export SECRET_KEY="$(openssl rand -hex 32)"     # do this once, keep it
BRIDGE_PORT=1235 node server.mjs
```

One person runs it - on a laptop for a working session, or on a host the team can reach - and
everyone points their plugin at it. It is the only part of OntoBoard that is not inside
Protégé.

It opens **two** ports, and the difference matters:

| Port | Speaks | Used by |
|---|---|---|
| 1234 | Yjs / Hocuspocus (binary CRDT) | a browser client, if you write one |
| **1235** | **JSON over WebSocket** | **the Protégé plugin** |

Both serve the *same* documents — [`server.mjs`](https://github.com/ebrahimnorouzi/ontoboard/blob/main/collab/server.mjs) attaches the bridge to
Hocuspocus through `openDirectConnection`, so a Protégé user and a browser user are in one
session rather than two that happen to look alike. Point the plugin at **1235**. Pointing it at
1234 fails in a confusing way, because a Yjs endpoint accepts the connection and then never
understands a word - the plugin would sit on "Connecting…" indefinitely with nothing in any log to
explain it. So the plugin refuses a `ws://…:1234` address outright and says which port to use
instead. That refusal was documented here from the start and only became real in 1.51.0; before
that, an address on 1234 was accepted and hung.

The service holds cursors and an operation log. It stores no ontology, so it needs no
database, no volume and nothing else running beside it.

### 2. Keep the secret

`SECRET_KEY` signs every token and verifies every connection. One service reads it — the one
you just started — so there is nothing to keep in step, but whoever mints tokens must use the
same string.

**Since 1.86.0 the server refuses to start without one.** It used to fall back to
`change-me-in-production`, a string published in this repository, silently: a server started
without the variable looked exactly like one started correctly, and anybody who had read the
source could mint a token for it.

If a token is signed with a different secret the plugin reports `invalid token: invalid
signature`, which is accurate and still sends people looking in the wrong place. It almost
always means the token was minted against a different value than the running server has.

### 3. Mint a token

There is no login service. A token is a JWT signed with your `SECRET_KEY`, and its `sub` claim
is the name that appears beside the person's cursor — so mint one per person:

```bash
cd collab
npm run --silent mint-token -- "Ada Lovelace"              # 30 days
npm run --silent mint-token -- "Ada Lovelace" --days 7     # or as long as you want
```

The token goes to stdout and everything else to stderr, so `> ada.txt` gives a file you can send
— **with `--silent`**, which is why it is there. Without it `npm run` writes four lines of its own
banner to stdout and they land in the file ahead of the token. `node mint-token.mjs "Ada Lovelace"`
is the same thing without npm in the way.
Send each person their own: two people sharing a token appear as two cursors with one name.

> **New in 1.93.0.** This used to be a `node -e "require('jsonwebtoken').sign(…)"` one-liner here
> and nothing else — 90 characters of quoting that a Windows shell mangles differently from a POSIX
> one, which mints an unusable token in silence if `SECRET_KEY` happens to be empty. Worse, the
> plugin's own dialog said the token came "from the web application", retired six releases earlier,
> so the one field nobody can guess pointed at a product that no longer existed. The dialog now
> names this command, and a test checks that what it mints is what the bridge accepts.

**Choose the expiry deliberately.** `--days` is yours to set; there is no server-side default any
more. A Protégé session left open past it is refused the next morning, and the plugin stops rather
than retrying — retrying a rejected token would only loop against your server. It shows
`invalid token: jwt expired`; mint a new one and reconnect.

### 4. Configure each person's plugin

Settings live at **OntoBoard → Collaboration…** in Protégé's menu bar. Connecting is
separate: open the OntoBoard tab and choose **Collaborate** from the **⋮** menu at the right of
the board's toolbar. It is in that menu rather than on the bar because the bar did not fit —
measured at 1261px wanted against 857 available, at which the button was laid out past the
right edge and never painted at all.

| Field | Value |
|---|---|
| Server address | `ws://your-server:1235` (or `wss://` behind TLS) |
| Board id | any string, the same for everyone — for example the ontology's short name |
| Access token | the token you minted in step 3 |
| Your colour | your cursor and selection colour |

There is no "your name" field. The name comes from the token's `sub` claim, because the bridge
stamps every operation with the authenticated user rather than trusting what a client says it
is called — a box that could not change anything would be a box that lied.

The board id is not created anywhere in advance; the first person to use one makes it exist.
Everyone typing the same string is in the same session.

**About the token checkbox.** Unticked — the default — the token lives only in memory for that
Protégé session. Ticked, it is written to Protégé's preferences **as plain text**, readable by
anything running as you:

| | |
|---|---|
| Windows | the registry, under `HKEY_CURRENT_USER\SOFTWARE\JavaSoft\Prefs` |
| macOS | a plist under `~/Library/Preferences` |
| Linux | a file under `~/.java/.userPrefs` |

The dialog names the location for the machine it is running on, which is the difference between
consenting to store a credential and clicking past a checkbox. Unticking it later erases what
was stored rather than leaving it behind.

### 5. Check it worked

The status bar shows `Collaborating as <you> on <board>`. Move your mouse over the canvas and a
colleague should see your cursor with your name on it. Select a class and they should see a ring
appear around it on their board.

---

## What travels, and what does not

Live mode shares an *operation log*, not the ontology file. Each edit becomes an operation, and
the vocabulary is the eighteen types in `OntologyOperation.TYPES`. OWL is much larger than that,
so some edits cannot be expressed.

**Travels:** classes created, renamed or deleted; individuals created and renamed; properties
created and deleted; subclass axioms; existential and universal restrictions (the arrows the
canvas draws); `rdfs:label` changes; annotations — editorial notes, definitions, provenance — as
`updateAnnotation`; literals; and the board's own sticky notes and frames.

**Does not travel:** deleting an individual — there is no `removeIndividual` in the vocabulary,
so the local delete happens and the peer keeps the individual. Nor do equivalence and
disjointness, property characteristics, property chains, `owl:hasKey`, negative property
assertions, global domain and range, datatype definitions, SWRL rules, imports, or
ontology-level annotations. **Moving a node** does not travel either: positions are sent for a
node when it is created, and a later drag is local.

The plugin does not hide this. Anything it cannot share is counted, and the status bar shows
`N changes not shared` on the right with the most recent reason in its tooltip — for example *"an
EquivalentClasses axiom, which the shared session has no way to express"*. If you are working on
axioms outside that list, **use git for those** and treat live mode as being for the shape of the
ontology rather than its full logic.

> **Two things 1.93.0 fixed about that notice.** It was written into the same label as the
> connection status, so the next `Connected` erased it — and an automatic reconnect produces one, so
> a green light could sit over a session in which three of your axioms had gone nowhere. It now has
> its own place in the status bar, which nothing else writes, and it lasts the whole session. And
> moving a node was in the "does not travel" list above while the product said nothing at all: an
> unshareable axiom was counted, but arranging thirty classes produced no count and no message.
> Dragging a node while connected now says so once, in the board's own status line.

This is a limitation of the shared vocabulary, not of the plugin. Widening it means adding the
type to `collab/bridge.mjs` and `OntologyOperation.TYPES` together — both, or the new type is
refused at one end and the edit never arrives.

---

## Who is editing what

**Since 1.104.0.** A peer who has changed an axiom on a term within the last five minutes is
shown as editing it — *"Bob is editing Calzone"* — so you find out before you start on the
same term, not after.

**It is advisory, and that is not a shortcoming to be fixed later.** Protégé owns the edit
model: a change happens through its views, its undo stack and its keyboard, and OntoBoard hears
about it afterwards. A lock this plugin could not enforce would be worse than none, because
somebody would rely on it. Reporting in time is the whole of what is deliverable here, and it
prevents the collision the only way anything in this position can.

Three properties worth knowing, because they are what make it trustworthy:

- **Observed, not declared.** The claim is made by actually editing, not by announcing an
  intention. Nothing to switch on, nothing to remember to release.
- **It dies with its holder.** It rides the presence heartbeat, which expires after ten
  seconds, so a peer who closes Protégé, loses the network or crashes stops claiming anything.
  That is structural rather than handled — a lock outliving its holder is the failure that
  makes locking hated, and here it cannot happen.
- **It expires in place.** Five minutes after the last edit, so a window left open over lunch
  stops speaking for somebody. The frame is rebuilt on every beat rather than composed once,
  which is what makes that true at the holder rather than only at the viewer.

**Selection is not editing.** What a peer has clicked already travelled and is already drawn as
a ring round the node. Treating that as a claim would make every click look like an edit, which
would make the whole signal worthless.

**An older bridge shows no badges rather than wrong ones.** The claim travels as one extra field
on the presence message; a bridge that does not know about it drops it, and absence is treated as
*no information* rather than as "nobody is editing".

---

## Things that will confuse you once

**Your name comes from the token, not from the plugin.** The bridge reads it from the token's
`sub` claim at connect and ignores anything a client claims afterwards, so two people sharing one
account appear as two cursors with the same name. Use an account each — and note that the colour
is read once at connect too, so changing it means reconnecting.

Two windows on one account *do* see each other's edits: echo is filtered per connection, not per
account, so one person working across a desktop and a laptop works as expected.

**A cursor that vanishes is not a disconnection.** The bridge expires a peer after ten seconds
of silence. The plugin re-sends your position every three seconds even when you are not moving,
so this should not happen; if cursors do disappear while people are clearly still working, look
for something dropping WebSocket frames — an idle-timeout on a reverse proxy is the usual
culprit.

**Any malformed message closes the connection.** The bridge answers a frame it cannot parse by
saying why and hanging up. The plugin reconnects automatically for anything that looks like an
outage, with the delay doubling up to thirty seconds, and does *not* reconnect when the server
gave a reason — retrying a rejected token would just be a loop against your server. The status bar
says which of the two happened.

**An outage does not lose your edits, in either direction — since 1.88.0.** Up to 200 of your own
operations are held while disconnected and sent when the connection returns, keeping their
original timestamps so the merge engine still orders them correctly. Beyond 200 the oldest are
dropped, and the count is reported rather than hidden.

Coming back the other way, **the bridge sends you the whole session log when you join or
rejoin**, so you also receive what happened while you were away. Before 1.88.0 only the first
half was true: your edits queued, and the other direction did not exist — a peer that dropped off
received only what happened after it returned, and a peer joining a session in progress received
nothing at all, with nothing on screen to say its board was incomplete.

The log is sent uncapped. The document is ephemeral — it holds one session's operations and dies
with the server — so a cap would reintroduce the same quiet loss for the oldest edits. Duplicates
are expected and harmless: you are sent your own earlier operations too, and the plugin
deduplicates by operation id across reconnects.

**If an incoming change cannot be read, you are told.** A frame that arrives shaped like an
operation but will not decode is dropped — there is nothing to apply — and the status bar says so,
with a count. It used to share a branch with "a message type this version does not understand",
which is deliberately ignored for forward compatibility, and was logged where nobody would see
it. The two now differ: an unknown type stays silent, a lost edit does not.

**Deleting a class deletes more than the class.** A remote `removeClass` retracts the
declaration and every axiom mentioning it, which is what Protégé's own delete does. Leaving
those behind would strand axioms pointing at an undeclared class and leave the two copies
disagreeing about more than the one entity. Protégé's undo will put it back.

---

## Behind TLS

`wss://` works. One caveat worth knowing before you rely on it: the bundle imports
`javax.net.ssl` as optional, because a mandatory import for a package Protégé's OSGi container
might not export would stop the whole plugin loading. The trade is that if a container does not
export it, `wss://` fails when you connect rather than when you install. `ws://` is unaffected.
Nothing seen so far on Protégé 5.5 or 5.6 hits this, but a connection that fails on `wss://`
and works on `ws://` is worth reporting rather than working around.

Terminate TLS at your proxy and forward to 1235, and make sure the proxy does
not close idle WebSocket connections — nginx defaults to 60 seconds, which is shorter than a
pause for thought:

```nginx
location /collab-bridge {
    proxy_pass http://127.0.0.1:1235;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_read_timeout 3600s;
}
```

---

## If it does not work

Work down this list; it is ordered by how often each one is the answer.

1. **Is the port 1235?** Not 1234. The plugin refuses 1234 outright (since 1.51.0), but a proxy in front might
   not.
2. **Was the token signed with the running server's `SECRET_KEY`?** This is the most common
   cause of a token that looks valid and is not. The message is `invalid token: invalid
   signature`.
3. **Has the token expired?** Whatever `--days` you gave when you minted it, 30 by default. The
   message says `jwt expired`; mint another.
4. **Is everyone using the same board id?** It is free-form text, so a typo makes a second,
   empty board rather than an error.
5. **Is the service actually up?** The terminal running `node server.mjs` prints a line per
   join: `[bridge] alice joined 'my-board'`. No line means the connection never got that far.
   A refused one prints `[collab] Refused a connection` with the reason.
6. **What does Protégé say?** `~/.Protege/logs/protege.log`, searching for `ontoboard`.

The plugin is built to fail with a reason rather than a spinner: every state — connecting,
connected, retrying in Ns, refused because X, N changes not shared — appears in the status bar
along the bottom of the board, with the unshared count kept in its own corner so a reconnect
cannot wipe it. If
you see a state that does not explain itself, that is a bug worth reporting.

---

## For maintainers

The plugin is a second client of the same session, not a separate system. The pieces:

| Piece | Where |
|---|---|
| Wire format | [`CollabMessages`](https://github.com/ebrahimnorouzi/ontoboard/blob/main/protege-plugin/src/main/java/de/fizkarlsruhe/ise/ontoboard/collab/CollabMessages.java) ↔ [`bridge.mjs`](https://github.com/ebrahimnorouzi/ontoboard/blob/main/collab/bridge.mjs) |
| Socket and reconnection | `CollabClient` |
| Loop guard, apply and publish | `CollabSession` |
| OWL ↔ operations | `OperationMapper` |
| Cursor rules | `PeerCursors`; painting in `PeerCursorLayer` |

The interop contract is asserted by
[`bridge-interop.test.mjs`](https://github.com/ebrahimnorouzi/ontoboard/blob/main/collab/__tests__/bridge-interop.test.mjs), which reads and writes
the shared array the way a Yjs client does. That file exists because the two ends once
disagreed about whether operations were stored as JSON strings or objects, and every unit test
on both sides passed while no operation could cross in either direction. If you change the
storage format, change it there too.

**Both ports must demand the same credential.** They share one `Y.Doc` — the bridge attaches to
Hocuspocus through `openDirectConnection` — so a port that admits anonymous clients is a way
around the port that does not. Until 1.86.0 the Hocuspocus handler returned an "anonymous"
"viewer" for a missing *or invalid* token, and Hocuspocus authenticates a connection whenever
that handler returns rather than throws, so it accepted everyone. The two decisions now share
one function, `hocuspocusAuth` beside `authenticate` in `bridge.mjs`, so they cannot drift
apart again.

See also [feature parity](feature-parity.md) and [limitations](limitations.md).
