# Collaboration

Two ways to work together. Pick by whether your team wants to run a server.

| | Live | Git |
|---|---|---|
| Needs a server | yes, one you run | no |
| See edits and cursors as they happen | yes | no |
| Works offline | queues your edits, and catches up on theirs | yes |
| Setup | one service, one secret, a token each | none |

**OntoBoard hosts nothing.** Live collaboration means a server your team runs, so an unpublished
ontology never passes through somebody else's service. Git mode is not a fallback: it is how OBO
ontologies are built, and the repository *New ODK project...* writes is already a git repository.

## Git mode

Leave the collaboration fields empty and work as on any shared repository: edit, commit, push,
pull, resolve conflicts in the diff. The board's layout lives in `<ontology>.ontoboard.json`
beside the ontology, so a conflict in a diagram never touches an axiom. Commit it to share
arrangements, or ignore it if diagrams are personal.

## Live mode

### 1. Run the server

The service is in [`collab/`](https://github.com/ebrahimnorouzi/ontoboard/tree/main/collab/) and
needs only Node:

```bash
cd collab
npm install
export SECRET_KEY="$(openssl rand -hex 32)"     # do this once, keep it
BRIDGE_PORT=1235 node server.mjs
```

It refuses to start without `SECRET_KEY`. It opens two ports onto the same documents: 1234 speaks
Yjs, for a browser client, and **1235 speaks JSON over WebSocket - the port the Protégé plugin
uses**. The plugin refuses a `ws://…:1234` address and says which port to use. The service keeps
cursors and an operation log, not the ontology, so it needs no database.

### 2. Mint a token for each person

A token is a JWT signed with `SECRET_KEY`; its `sub` claim is the name shown beside the person's
cursor.

```bash
cd collab
npm run --silent mint-token -- "Ada Lovelace"              # 30 days
npm run --silent mint-token -- "Ada Lovelace" --days 7     # or as long as you want
```

The token goes to stdout; keep `--silent`, or npm's banner lands in the file with it. Give each
person their own: two people on one token appear as two cursors with one name.

### 3. Configure each person's plugin

The settings are at **OntoBoard → Share → Collaboration…**. Connect from the board's **⋮** menu,
**Collaborate**.

| Field | Value |
|---|---|
| Server address | `ws://your-server:1235` (or `wss://` behind TLS) |
| Board id | any string, the same for everyone - for example the ontology's short name |
| Access token | the token from step 2 |
| Your colour | your cursor and selection colour |

Your name comes from the token. The first person to use a board id creates the board. The token is
kept in memory unless you tick the box to store it; then it is written to Protégé's preferences
**as plain text**, in a place the dialog names, and unticking the box erases it.

### 4. Check it worked

The status bar shows `Collaborating as <you> on <board>`. A colleague sees your cursor with your
name on it, and a ring around the class you select.

## What travels, and what does not

Live mode shares operations, not the ontology file: the eighteen types in
`OntologyOperation.TYPES`.

**Travels:** classes created, renamed or deleted (a deleted class takes the axioms that mention
it, as Protégé's own delete does); individuals created and renamed; properties created and
deleted; subclass axioms; existential and universal restrictions; label and annotation changes;
the board's sticky notes and frames.

**Does not travel:** deleting an individual; equivalence and disjointness; property
characteristics and chains; `owl:hasKey`; negative property assertions; global domain and range;
datatype definitions; SWRL rules; imports; ontology annotations; moving a node after it was
created.

The status bar counts what was not shared - `N changes not shared`, with the reason in its
tooltip. Use git for those axioms.

## Who is editing what

A peer who changed a term in the last five minutes is shown as editing it: *"Bob is editing
Calzone"*. It is advisory; nothing locks the term. The badge goes when the peer disconnects or
five minutes after their last edit, and selecting a term does not count as editing it.

## Good to know

- An outage loses nothing. Up to 200 of your operations wait and are sent when the connection
  returns, and the server sends the whole session log when you join or rejoin.
- The plugin reconnects after an outage, waiting up to 30 seconds between tries, but not after the
  server refused it with a reason. The status bar says which happened.
- A change that arrives but cannot be read is counted in the status bar.
- A changed colour takes effect when you next connect.

## Behind TLS

Terminate TLS at a proxy, forward to 1235, and keep idle WebSockets open - nginx closes them after
60 seconds by default:

```nginx
location /collab-bridge {
    proxy_pass http://127.0.0.1:1235;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_read_timeout 3600s;
}
```

A connection that fails on `wss://` and works on `ws://` is worth reporting.

## If it does not work

1. **Is the port 1235?** Not 1234; a proxy in front may not refuse it.
2. **`invalid token: invalid signature`**: the token was signed with another `SECRET_KEY`.
3. **`invalid token: jwt expired`**: mint another and reconnect.
4. **Is everyone using the same board id?** A typo makes a second, empty board.
5. **Is the service up?** It prints `[bridge] <name> joined '<board>'` for each join, and
   `[collab] Refused a connection` with the reason.
6. **Cursors vanish while people work?** Something between you drops idle WebSocket frames,
   usually a proxy's idle timeout.
7. **Protégé's log**: `~/.Protege/logs/protege.log`, searching for `ontoboard`.

## For maintainers

| Piece | Where |
|---|---|
| Wire format | [`CollabMessages`](https://github.com/ebrahimnorouzi/ontoboard/blob/main/protege-plugin/src/main/java/de/fizkarlsruhe/ise/ontoboard/collab/CollabMessages.java) ↔ [`bridge.mjs`](https://github.com/ebrahimnorouzi/ontoboard/blob/main/collab/bridge.mjs) |
| Socket and reconnection | `CollabClient` |
| Loop guard, apply and publish | `CollabSession` |
| OWL ↔ operations | `OperationMapper` |
| Cursors | `PeerCursors`; painting in `PeerCursorLayer` |

[`bridge-interop.test.mjs`](https://github.com/ebrahimnorouzi/ontoboard/blob/main/collab/__tests__/bridge-interop.test.mjs)
holds both ends to one storage format. A new operation type goes into `collab/bridge.mjs` and
`OntologyOperation.TYPES` together, or one end refuses it. Both ports check the same credential,
through `hocuspocusAuth` beside `authenticate` in `bridge.mjs`.

See also [feature parity](feature-parity.md) and [limitations](limitations.md).
