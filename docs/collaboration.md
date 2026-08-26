# Collaboration

Two ways to work together. Pick by whether your team wants to run a server.

| | Live | Git |
|---|---|---|
| Needs a server | yes, one you run | no |
| See edits as they happen | yes | no |
| See other people's cursors | yes | no |
| Works offline | queues and catches up | yes |
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

The collaboration service is in [`collab/`](../collab/) and is already part of the compose file:

```bash
export SECRET_KEY="$(openssl rand -hex 32)"     # do this once, keep it
docker compose up -d collab
```

It opens **two** ports, and the difference matters:

| Port | Speaks | Used by |
|---|---|---|
| 1234 | Yjs / Hocuspocus (binary CRDT) | the web application |
| **1235** | **JSON over WebSocket** | **the Protégé plugin** |

Both serve the *same* documents — [`server.mjs`](../collab/server.mjs) attaches the bridge to
Hocuspocus through `openDirectConnection`, so a Protégé user and a browser user are in one
session rather than two that happen to look alike. Point the plugin at **1235**. Pointing it at
1234 fails in a confusing way, because a Yjs endpoint will accept the connection and then never
understand a word; the plugin refuses a `ws://…:1234` address for that reason.

Without Docker:

```bash
cd collab
npm install
SECRET_KEY=your-secret COLLAB_PORT=1234 BRIDGE_PORT=1235 node server.mjs
```

The service holds cursors and an operation log. It stores no ontology, so it needs no database
and no volume of its own.

### 2. Use one secret everywhere

`SECRET_KEY` signs the tokens, and the backend and the collaboration service **must** be given
the same value. If they differ, logging in works, the plugin connects, and then the bridge
rejects the token — because it was signed with a key the bridge does not have.

The plugin reports that as `invalid token: invalid signature`, which is accurate and still
sends people looking in the wrong place. Check the two environments agree before anything else:

```bash
docker compose exec backend printenv SECRET_KEY
docker compose exec collab  printenv SECRET_KEY
```

Both default to `change-me-in-production`, so a setup that never set it will appear to work.
Set it.

### 3. Get a token

Tokens come from the web application's login endpoint. There is no separate plugin credential.

```bash
curl -s -X POST http://your-server:8000/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"..."}'
```

```json
{ "access_token": "eyJhbGciOiJIUzI1NiIs..." }
```

**Tokens expire after 8 hours** by default (`ACCESS_TOKEN_EXPIRE_MINUTES`). A Protégé session
left open overnight will be refused the next morning, and the plugin will say so rather than
retry: it stops and shows `invalid token: jwt expired`. Get a new one and reconnect. Raise
`ACCESS_TOKEN_EXPIRE_MINUTES` on the backend if that is a nuisance for your team.

### 4. Configure each person's plugin

**OntoBoard tab → Collaborate…**

| Field | Value |
|---|---|
| Server address | `ws://your-server:1235` (or `wss://` behind TLS) |
| Board id | any string, the same for everyone — for example the ontology's short name |
| Access token | the `access_token` from step 3 |
| Your name | shown beside your cursor |
| Your colour | your cursor and selection colour |

The board id is not created anywhere in advance; the first person to use one makes it exist.
Everyone typing the same string is in the same session.

**About the token checkbox.** Unticked — the default — the token lives only in memory for that
Protégé session. Ticked, it is written to Protégé's preferences as plain text: the Windows
registry under `HKEY_CURRENT_USER\SOFTWARE\JavaSoft\Prefs`, or a file under `~/.java` on macOS
and Linux. That is readable by anything running as you. The dialog says the same thing, and the
default is off deliberately. Unticking it later erases what was stored, rather than leaving it
behind.

### 5. Check it worked

The toolbar shows `Collaborating as <you> on <board>`. Move your mouse over the canvas and a
colleague should see your cursor with your name on it. Select a class and they should see a ring
appear around it on their board.

---

## What travels, and what does not

Live mode shares an *operation log*, not the ontology file. Each edit becomes an operation, and
the vocabulary is the seventeen types the web application understands. OWL is much larger than
that, so some edits cannot be expressed.

**Travels:** classes and individuals created or deleted; subclass axioms; existential and
universal restrictions (the arrows the canvas draws); type assertions; `rdfs:label` changes;
canvas positions and colours for new nodes.

**Does not travel:** equivalence and disjointness, property characteristics, property chains,
`owl:hasKey`, negative property assertions, global domain and range, datatype definitions, SWRL
rules, imports, and ontology-level annotations.

The plugin does not hide this. Anything it cannot share is counted, and the toolbar shows
`N changes not shared` with the most recent reason in its tooltip — for example *"an
EquivalentClasses axiom, which the shared session has no way to express"*. If you are working on
axioms outside that list, **use git for those** and treat live mode as being for the shape of the
ontology rather than its full logic.

This is a limitation of the shared vocabulary, not of the plugin. Widening it means adding
operation types to `frontend/src/collab/useOperationSync.ts`, `collab/bridge.mjs` and
`OntologyOperation.TYPES` together — all three, or the new type is silently ignored at one end.

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
gave a reason — retrying a rejected token would just be a loop against your server. The toolbar
says which of the two happened.

**An outage does not lose your edits.** Up to 200 operations are held while disconnected and
sent when the connection returns, keeping their original timestamps so the merge engine still
orders them correctly. Beyond 200 the oldest are dropped, and the count is reported rather than
hidden.

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

1. **Is the port 1235?** Not 1234. The plugin refuses 1234 outright, but a proxy in front might
   not.
2. **Do the two `SECRET_KEY` values match?** See step 2 above. This is the most common cause of
   a token that looks valid and is not.
3. **Has the token expired?** Eight hours by default. The message says `jwt expired`.
4. **Is everyone using the same board id?** It is free-form text, so a typo makes a second,
   empty board rather than an error.
5. **Is the service actually up?** `docker compose logs collab` prints a line per join:
   `[bridge] alice joined 'my-board'`. No line means the connection never got that far.
6. **What does Protégé say?** `~/.Protege/logs/protege.log`, searching for `ontoboard`.

The plugin is built to fail with a reason rather than a spinner: every state — connecting,
connected, retrying in Ns, refused because X, N changes not shared — appears in the toolbar. If
you see a state that does not explain itself, that is a bug worth reporting.

---

## For maintainers

The plugin is a second client of the same session, not a separate system. The pieces:

| Piece | Where |
|---|---|
| Wire format | [`CollabMessages`](../protege-plugin/src/main/java/de/fizkarlsruhe/ise/ontoboard/collab/CollabMessages.java) ↔ [`bridge.mjs`](../collab/bridge.mjs) |
| Socket and reconnection | `CollabClient` |
| Loop guard, apply and publish | `CollabSession` |
| OWL ↔ operations | `OperationMapper` |
| Cursor rules | `PeerCursors`; painting in `PeerCursorLayer` |

The interop contract is asserted by
[`bridge-interop.test.mjs`](../collab/__tests__/bridge-interop.test.mjs), which reads and writes
the shared array exactly as `useOperationSync.ts` does. That file exists because the two ends
once disagreed about whether operations were stored as JSON strings or objects, and every unit
test on both sides passed while no operation could cross in either direction. If you change the
storage format, change it there too.

See also [feature parity](feature-parity.md) and [limitations](limitations.md).
