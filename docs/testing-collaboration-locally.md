# Testing collaboration on one machine

Two Protégé instances against a local server - no Docker, no database. The steps are also run by
`CollabLiveTest` and `collab/__tests__/server-boot.test.mjs` on every build.

!!! note "The ontology needs an absolute IRI"
    An ontology IRI like `o` becomes a `file:` path when saved, and two people then mint different
    IRIs for one concept. The wizard refuses a relative base IRI; to repair an older project, set an
    absolute IRI in the **Active ontology** tab and make `uribase:` in `<id>-odk.yaml` and
    `<id>-idranges.owl` match, or make it again with **Tools → New ODK Project…**.

## 1. Start the bridge

```bash
cd collab
npm install                      # once
SECRET_KEY=local-test-secret BRIDGE_PORT=1235 COLLAB_PORT=1234 node server.mjs
```

It prints a line each time somebody joins or leaves.

## 2. Mint two tokens

```bash
SECRET_KEY=local-test-secret npm run --silent mint-token -- alice
SECRET_KEY=local-test-secret npm run --silent mint-token -- bob
```

The name beside a cursor comes from the token, not from Protégé. Tokens last 30 days; `--days 1`
is enough for a test.

## 3. Start two Protégé instances

Launch Protégé twice and open the same project in both. They share `~/.Protege` (preferences and
the log). **Do not save from both** - the test is that changes travel through the bridge.

## 4. Connect both

On the OntoBoard tab, press **Collaborate…**:

| Field | Window A | Window B |
|---|---|---|
| Server address | `ws://127.0.0.1:1235` | `ws://127.0.0.1:1235` |
| Board id | `pizza-test` | `pizza-test` |
| Access token | alice's | bob's |

Each status bar should read `Collaborating as alice on pizza-test` (or bob), and the bridge log
show two joins.

## 5. What to test

| Try this | Expect |
|---|---|
| Move the mouse or select a class in A | alice's cursor, or a ring round the class, on B |
| Close A, or stop the bridge | The cursor goes; both say `Disconnected …; retrying`, and reconnect |
| New class, rename, relation, subclass, new individual, delete a class, Undo in A | Reaches B, in Protégé's hierarchy too |
| Delete an individual, equivalence, disjointness, keys, domains and ranges | Does not travel; counted in the status bar as *changes not shared* |
| Drag a class from the hierarchy onto A's board | Stays on A: what a board shows is personal |
| Notes and frames on the board | Travel with the sidecar file through git, not live |
| Wrong token | `invalid token: …`, and no retry loop |
| Edit in A while the bridge is down | Arrives at B on reconnect (up to 200 queued) |
| Same board id, different ontologies | A warning naming the other person |

## 6. Reading the log

`~/.Protege/logs/protege.log`, searching for `OntoBoard`:

```
OntoBoard: dropped 1 canvas entry for entities no longer …     stale sidecar entry pruned
OntoBoard: … describes '…' but the open ontology is '…'        sidecar belongs to another ontology
OntoBoard: ignoring addStickyNote(…) - …                       an operation this version cannot apply
```

## 7. Tearing down

Ctrl+C the bridge; nothing persists on the server. In Protégé, **Collaborate…** becomes
**Disconnect**.
