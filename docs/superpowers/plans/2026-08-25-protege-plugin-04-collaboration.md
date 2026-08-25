# OntoBoard Protégé Plugin — Plan 4: Live Collaboration

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Protégé user and a browser user edit the same ontology together, seeing each other's cursors, edits and comments live.

**Spec:** [`2026-08-14-ontoboard-protege-plugin-design.md`](../specs/2026-08-14-ontoboard-protege-plugin-design.md) decisions D3a/D3b/D3c.

**Why:** the user asked directly — *"multiple user work on the same ontology, others could see their mouse is moving and they can see the changes, edits, comment"* — and chose "plugin joins the same live session" over delegating collaboration to the browser.

## Two collaboration modes, chosen by the user (2026-08-25)

The plugin does **not** host or ship a server. Teams run their own OntoBoard server, and each
user points the plugin at it. A team without a server is not shut out — they collaborate the
way the OBO community already does, through git.

| | **Live mode** | **Git mode** |
|---|---|---|
| Needs | an OntoBoard server the team runs | a git remote only |
| Setup | server URL, board, credentials in the plugin | clone / remote already configured |
| Same-time editing | yes | no |
| Remote cursors | yes | no |
| Live operation sync | yes | no |
| Comments | yes | via pull-request review |
| Sharing changes | continuous | commit, push, pull |
| Conflict handling | semantic merge engine | git merge, ROBOT diff |

**The plugin must be honest about which mode is active.** In Git mode the cursor overlay,
presence indicator and sync status must be *absent or visibly disabled* - never present and
silently doing nothing. A user who believes they are collaborating live when they are not
will lose work, and that failure is on the interface, not on them.

Mode is inferred from configuration, not asked as a separate question: server details
present and reachable means Live, otherwise Git. Nothing is required to get started, so the
plugin is fully usable with no configuration at all.

## The central constraint

The existing stack is **Yjs/Hocuspocus**, and Java has no mature Yjs client. Rather than port a CRDT to Java or bolt an immature JNI binding into a Protégé plugin, the plugin speaks a **plain JSON WebSocket** protocol, and a small bridge inside the existing `collab/` service translates that to and from the shared `Y.Doc`.

That choice matters beyond convenience:

- Web clients are **unchanged**. They keep using Yjs exactly as today.
- The semantic merge engine and conflict rules stay in **one** implementation rather than being reimplemented in Java, where they would inevitably drift.
- The plugin needs only a WebSocket and a JSON parser — both trivial and dependency-light inside an OSGi bundle already at 64 MB.

```
Protege plugin  ──JSON/WebSocket──▶  collab bridge  ──Yjs──▶  Y.Doc  ──Yjs──▶  browser
      ▲                                                                            │
      └──────────────────── same board, same operations ───────────────────────────┘
```

## What already exists — do not rebuild

- `collab/server.mjs` — Hocuspocus, JWT auth against the backend's `SECRET_KEY`, ephemeral per-board `Y.Doc`.
- `frontend/src/collab/` — `useOperationSync.ts` (the `OntologyOperation` protocol, 16 op types, dedup, 2-second conflict window), `mergeEngine.ts` (semantic merge rules), `ConflictBanner`, `CollabStatus`.
- `backend/` — JWT auth, boards, sharing, comments, tasks, notifications.

**Ontology data is *not* in Yjs.** `server.mjs` says so explicitly: Yjs carries awareness and the operation log; ontology state is persisted through the backend REST API. The plugin must follow the same split.

## Global Constraints

- **Java 8 API surface only** (`maven.compiler.release=8`). No `var`, records, `List.of`, no-arg `orElseThrow()`, text blocks.
- Package root `de.fizkarlsruhe.ise.ontoboard`; collaboration code in `.collab`.
- **Every incoming remote change is applied through `OWLModelManager.applyChanges(...)`** — never by mutating an `OWLOntology` directly. Protégé's undo and every other tab depend on it.
- **Never echo a remote operation back.** The web client guards with `isApplyingRemote`; the plugin needs the same guard or two clients will amplify each other indefinitely.
- **Collaboration must be optional and failure-tolerant.** With no server configured, or the server down, the plugin must work exactly as it does today. A broken WebSocket must never block editing or lose local work.
- `pom.xml` is guarded by `BundleConfigurationTest`; do not touch the OSGi instructions. Any new dependency must be embeddable and must not import `java.*`.
- Tests run **headless**: no `mxGraphComponent`, `mxGraphOutline`, or `SchemaCanvasView` in test code.

---

### Task 1: JSON bridge in the collab service

**Files:** modify `collab/server.mjs`; add `collab/bridge.mjs`; add `collab/__tests__/bridge.test.mjs`

Expose a second WebSocket endpoint for non-JS clients that mirrors the same `Y.Doc`.

- [ ] **Step 1: Protocol, written down before any code**

Document the message shapes in `collab/bridge.mjs`'s header. Client→server and server→client share one envelope:

```json
{ "t": "hello",    "board": "b1", "token": "<jwt>" }
{ "t": "op",       "op": { "id":"…", "type":"addClass", "timestamp":0, "userId":"…", "data":{} } }
{ "t": "presence", "user": "alice", "colour": "#4A90D9", "x": 120.0, "y": 40.0, "selection": "http://…" }
{ "t": "peers",    "peers": [ { "user":"bob", "colour":"#7B61A8", "x":0, "y":0 } ] }
{ "t": "error",    "message": "…" }
```

`op.type` must be exactly one of the 16 in `frontend/src/collab/useOperationSync.ts`. Do not invent new ones here — a type the web client cannot interpret is a silent no-op on the other side.

- [ ] **Step 2: Auth**

Reuse `server.mjs`'s `jwt.verify(token, SECRET_KEY)`. Reject with `{t:"error"}` and close on an invalid token — do **not** silently downgrade to anonymous as the Hocuspocus path does, because a plugin that believes it is authenticated while sending unattributed operations is worse than one that fails loudly.

- [ ] **Step 3: Bridge operations both ways**

On `op` from a plugin client: append to that board's `Y.Array("ops")` so every web client receives it through the existing path. On a `Y.Array("ops")` observer event: forward new entries to plugin clients, **skipping ones that client sent** (match on `op.id`, which the web client already uses for dedup).

- [ ] **Step 4: Bridge presence**

Map `presence` messages onto Yjs awareness so browser users see the Protégé cursor with the same mechanism they already use, and forward awareness changes back as `peers`.

- [ ] **Step 5: Tests**

Cover: a valid token connects and an invalid one is rejected and closed; an op from the bridge reaches `Y.Array("ops")`; an op appended to the array reaches the bridge client; a client does not receive its own op back; a malformed message produces `error` rather than crashing the server.

- [ ] **Step 6: Commit**

---

### Task 2: Java WebSocket client and settings

**Files:** create `.collab/CollabClient.java`, `.collab/CollabSettings.java`, `.collab/PeerPresence.java`; tests for each

- [ ] **Step 1: Choose and verify the WebSocket library**

`org.java-websocket:Java-WebSocket` is pure Java, dependency-light and Java 8 compatible. **Verify with `javap` before writing against it**, and confirm it adds no `java.*` imports to the bundle manifest — `BundleConfigurationTest` will fail the build if it does, which is the desired behaviour.

- [ ] **Step 2: Settings**

Server URL, board id and token, stored in Protégé's preferences (the same mechanism the sidecar decision rejected for *layout*, but correct here: credentials are machine-local and must never land in a file beside the ontology). Empty settings mean collaboration is off.

- [ ] **Step 3: Client with a guard flag**

`connect`, `disconnect`, `sendOperation`, `sendPresence`, and listener callbacks. Carry an `applyingRemote` flag with exactly the semantics of the web client's `isApplyingRemote`, and unit-test that an operation received while applying is not echoed.

- [ ] **Step 4: Reconnect and offline behaviour**

Exponential backoff, capped. **Test that every public method is safe when disconnected** — collaboration is optional and must never break local editing.

- [ ] **Step 5: Commit**

---

### Task 3: Remote cursors on the canvas

The thing the user asked for first, and the clearest proof the pipe works end to end.

**Files:** create `.canvas/PeerCursorOverlay.java`; modify `views/SchemaCanvasView.java`

- [ ] **Step 1** Send local presence on mouse move over the canvas, throttled to ~20/second — cursors need to feel live, but a message per pixel would flood the bridge.
- [ ] **Step 2** Paint remote cursors as a Swing overlay on the graph component, each with the peer's name and colour. Draw them **above** the graph without inserting cells, so a remote cursor can never be mistaken for an ontology entity or be caught by `capturePositions()`.
- [ ] **Step 3** Drop a peer after a few seconds of silence, so a crashed client's cursor does not linger for ever.
- [ ] **Step 4** Test the throttle and the stale-peer expiry headlessly; state plainly that the painting itself is not covered.
- [ ] **Step 5: Commit**

---

### Task 4: Operations both ways

The hard part: `OWLOntologyChange` ↔ `OntologyOperation`.

**Files:** create `.collab/OperationMapper.java`; modify `views/SchemaCanvasView.java`

- [ ] **Step 1: Failing tests first, for the mapping in both directions.** `AddAxiom(SubClassOf(A,B))` becomes `addSubClassOf` with the same payload shape the web client emits; the reverse reconstructs the identical axiom. Read `useOperationSync.ts` for each `data` shape rather than guessing — a mismatched field name produces an operation the other client silently ignores, which is far harder to debug than a crash.
- [ ] **Step 2** Map the axiom-bearing operations first: `addClass`, `removeClass`, `addSubClassOf`, `addProperty`, `addIndividual`. Canvas-only ones (`addStickyNote`, `addFrame`, `updateClass` position) carry no OWL and map to sidecar state.
- [ ] **Step 3** Send on local change: hook the existing `OWLOntologyChangeListener`, skipping anything applied while `applyingRemote` is set.
- [ ] **Step 4** Apply on remote receipt through `OWLModelManager.applyChanges(...)` with the guard set, then refresh.
- [ ] **Step 5** Test a full round trip: local change → operation → mapped back → identical axiom set.
- [ ] **Step 6: Commit**

---

### Task 5: Git mode

For teams with no server. Not a lesser path - it is how most published ontologies are
actually built, and it is what the ODK release workflow assumes.

**Files:** create `.git/GitClient.java`, `.git/GitPanel.java`; modify `plugin.xml`

- [ ] **Step 1** JGit is already on the classpath (Protege ships it - do not add a second
  copy). Wrap the operations a modeller needs: current branch and dirty state, create
  branch, stage the ontology and its sidecar, commit, pull with rebase, push.
- [ ] **Step 2** Show status in the panel: branch, ahead/behind, changed files. A modeller
  needs to know whether their work is shared, not to learn git plumbing.
- [ ] **Step 3** `ROBOT diff` between the working copy and `HEAD`, so a review shows
  *axiom* changes rather than an OWL/XML text diff nobody can read. This is the piece that
  makes git mode genuinely usable for ontologies, and it needs ROBOT - so state plainly that
  it requires Protege 5.6.x (spec D3c).
- [ ] **Step 4** Never auto-commit and never auto-push. Both are explicit actions with the
  diff visible first.
- [ ] **Step 5** Test the plumbing against a temporary repository created in the test:
  branch, commit, dirty detection. Push and pull need a remote and are stated as untested.
- [ ] **Step 6: Commit**

---

### Task 6: Comments

**Files:** create `.collab/CommentsClient.java`, `views/CommentsView.java`; modify `plugin.xml`

- [ ] **Step 1** REST client against the backend's existing comment endpoints — board-level and entity-level, with threading and `@mentions` as the web app already supports.
- [ ] **Step 2** A `ViewComponent` listing comments for the selected entity, following the selection through `OWLSelectionModel` exactly as the canvas already does.
- [ ] **Step 3** **Register its package in `plugin.xml` and add the explicit `Import-Package` clause.** A class named only in `plugin.xml` is invisible to bnd — this already cost one release (1.0.2 shipped a tab that appeared in the menu and threw `ClassNotFoundException` when clicked), and `everyThirdPartyClassInPluginXmlHasItsPackageImported` guards it.
- [ ] **Step 4: Commit**

---

### Task 7: Setup documentation

The feature is unusable without it. A team has to stand up a server, mint credentials and
configure each client, and none of that is guessable.

**Files:** create `docs/collaboration.md`; modify `README.md`, `docs/getting-started.md`

- [ ] **Step 1** `docs/collaboration.md` covering, for **Live mode**: what to run
  (`./run.sh` brings up backend, collab and the bridge), which ports must be reachable
  (backend 8000, bridge 1235) and that the bridge is the plugin's endpoint while 1234 stays
  the browser's, how a user obtains a token, where to enter it in the plugin, and how to
  verify it worked.
- [ ] **Step 2** The same for **Git mode**: no server, configure a remote, the
  commit/pull/push loop, and that cursors and live sync are deliberately unavailable.
- [ ] **Step 3** A troubleshooting section written from the actual failure modes: bridge
  unreachable, token expired, board id wrong, `SECRET_KEY` mismatched between backend and
  collab (the same secret signs and verifies - if they differ every token is rejected).
- [ ] **Step 4** Say plainly that the server is the team's to run and secure. The plugin
  ships no server and no default credentials, and `SECRET_KEY` must be changed from its
  development default before anyone exposes a server beyond localhost.
- [ ] **Step 5: Commit**

---

## Definition of Done

- With no server configured the plugin behaves exactly as it does today.
- With a server configured, a Protégé user and a browser user on the same board see each other's cursors, and each other's class/subclass/property edits, within a second.
- Remote changes arrive through `OWLModelManager`, so Protégé's undo and other tabs stay correct.
- No operation is ever echoed back to its sender.
- Killing the collab server mid-session loses no local work and does not block editing.
- Comments on an entity are visible in both clients.
- With **no** server configured, the plugin works fully in Git mode and no collaboration UI
  claims otherwise.
- A user can follow `docs/collaboration.md` and get either mode working without reading code.

## Risks

| Risk | Handling |
|---|---|
| `data` payload shapes drift from the web client | Tests read the shapes from `useOperationSync.ts`; a mismatch is a silent no-op, so it must be caught by test rather than by use |
| Two clients amplify each other | The `applyingRemote` guard, unit-tested, mirroring the web client's `isApplyingRemote` |
| A WebSocket library that imports `java.*` | `BundleConfigurationTest` fails the build; verify with `javap` before adopting |
| Ontology state and the operation log diverge | Ontology data stays in the backend REST API, as `server.mjs` already documents; Yjs carries only awareness and operations |
| Bundle grows past what Protégé loads comfortably | Measure after Task 2; the WebSocket library must be small |
