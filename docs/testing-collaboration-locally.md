# Testing collaboration on one machine

How to run two Protégé instances against a local server and exercise the whole feature. No Docker,
no backend, no database — the bridge runs on its own and tokens can be minted by hand.

---

## Before you start: the `mmo` project needs fixing

`C:\Users\eno\Desktop\oo\mmo` **cannot be used for collaboration as it stands.** Its ontology IRI
is a relative string:

```xml
<!-- src/ontology/mmo.owl:9 -->
<owl:Ontology rdf:about="o">
```

Protégé resolves that against the file's own location on save, which is why the edit file now says:

```xml
<!-- src/ontology/mmo-edit.owl:10 -->
<owl:Ontology rdf:about="file:/C:/Users/eno/Desktop/oo/mmo/src/ontology/o">
```

Every term minted from now on becomes `file:/C:/Users/eno/Desktop/oo/mmo/src/ontology/o#Whatever`
— a name that asserts a folder on one particular computer. Two people would mint *different* IRIs
for the same concept and never converge, which makes it precisely the wrong fixture for a
collaboration test. The wizard now rejects a relative base IRI for this reason, so a project
generated today cannot repeat it.

**The good news: there is nothing to migrate.** `mmo-edit.owl` declares three entities and all
three are external Dublin Core properties — no classes, no properties, no individuals in the
project's own namespace. So the fastest repair is to generate it again:

1. **Tools → New ODK Project…**
2. Ontology ID `mmo`, Title `Materials Manufacturing Ontology` (or whatever you meant)
3. **Base IRI `http://purl.obolibrary.org/obo/mmo.owl`** — or your own domain, e.g.
   `https://w3id.org/mmo`. It must be absolute; the wizard will now say so if it is not.
4. Choose a fresh folder, and delete the old one.

If you would rather keep the existing folder: open `mmo-edit.owl`, use the **Active ontology** tab
to set the ontology IRI to an absolute http IRI, save, then fix `uribase:` in `mmo-odk.yaml` and
the `Prefix(...)` lines in `mmo-idranges.owl` to match. There are no entities to rename.

The stale `o#apple` entry in the sidecar needs no attention — it is pruned automatically now, with
a line in the log saying so.

---

## 1. Start the bridge

Only the collaboration service is needed. It stores no ontology, so it needs no database.

```bash
cd collab
npm install                      # once

SECRET_KEY=local-test-secret BRIDGE_PORT=1235 COLLAB_PORT=1234 node server.mjs
```

You should see:

```
[collab] Hocuspocus server running on port 1234
[collab] JSON bridge for non-JS clients on port 1235
```

Leave it running. It prints a line each time somebody joins or leaves, which is the quickest way to
tell whether a connection got as far as authenticating.

## 2. Mint two tokens

The bridge only checks that a token is signed with `SECRET_KEY` and takes the user's name from its
`sub` claim. The backend is not involved, so two tokens can be made directly:

```bash
cd collab
node -e "console.log(require('jsonwebtoken').sign({sub:'alice'},'local-test-secret'))"
node -e "console.log(require('jsonwebtoken').sign({sub:'bob'},'local-test-secret'))"
```

Keep both. **The name beside a cursor comes from `sub`, not from anything you type in Protégé** —
the bridge sets it once at connect from the authenticated user and ignores what a client claims
afterwards. Two tokens with the same `sub` would give you two cursors both labelled the same.

For a longer session add an expiry: `sign({sub:'alice'}, secret, {expiresIn:'12h'})`.

## 3. Start two Protégé instances

Launch `Protege.exe` twice. Two instances run happily side by side, with two caveats:

- **They share `~/.Protege`.** Preferences and the log are common, so the second instance to save
  collaboration settings overwrites the first's stored copy. It does not matter while both are
  running — each holds its own settings in memory — but it means the colour you pick in the second
  window is the one that persists.
- **They share the log file.** `~/.Protege/logs/protege.log` interleaves both, so timestamps are
  how you tell them apart.

Open the same project in both: `mmo-edit.owl`. Both instances editing one file on disk is fine for
this test, because neither writes until you save — but **do not save from both**, or the second
save overwrites the first. The point of the test is that changes travel through the bridge, not
through the file.

## 4. Connect both

In each window: **Window → Tabs → OntoBoard → Collaborate…**

| Field | Window A | Window B |
|---|---|---|
| Server address | `ws://127.0.0.1:1235` | `ws://127.0.0.1:1235` |
| Board id | `mmo-test` | `mmo-test` |
| Access token | alice's token | bob's token |
| Your colour | pick one | pick a different one |

Leave *Remember the token* unticked — it is a credential, and for a test there is no reason to
write it to the registry.

Both toolbars should read `Collaborating as alice on mmo-test` and `Collaborating as bob on
mmo-test`. The bridge log should show two joins.

**One account is fine.** Two windows using alice's token for both now see each other — echo is
filtered per connection rather than per account. You will just have two cursors labelled `alice`,
which makes the test harder to read, so two names are better.

---

## 5. What to test, and what to expect

Work down this list. The right-hand column is honest about what is not built yet — a blank result
there is not a bug.

### Cursors and presence

| Try this | Expect |
|---|---|
| Move the mouse over A's canvas | A cursor labelled `alice` moves on B's canvas |
| Stop moving for 30 seconds | The cursor stays — a heartbeat re-sends it every 3s |
| Select a class in A's tree | A ring in alice's colour appears round that node on B |
| Close A | alice's cursor disappears from B within ten seconds |
| Stop the bridge | Both toolbars say `Disconnected …; retrying in Ns`, cursors vanish |
| Restart the bridge | Both reconnect on their own |

### Editing

Add a class in A and watch B. Everything here goes through Protégé's `OWLModelManager`, so it also
appears in B's class hierarchy, not just on the canvas.

| Edit in A | Reaches B | Notes |
|---|---|---|
| New class (double-click the canvas) | yes | With its position |
| New class (Protégé's own **+** button) | yes | Published from the change listener, so any editor counts |
| Rename via `rdfs:label` | yes | |
| Drag a class from the hierarchy onto the canvas | **no** | Canvas membership is per-person by design; the entity itself already exists on both |
| Draw a relation (existential) | yes | The reading is preserved — `only` stays `only` |
| Subclass axiom | yes | |
| New individual, and its type | yes | |
| Delete a class | yes | Also retracts axioms mentioning it, as Protégé's own delete does |
| Delete an individual | **no** | The shared vocabulary has no `removeIndividual`; the toolbar will say `1 change not shared` |
| Equivalent classes, disjointness, `owl:hasKey`, domain/range, property characteristics | **no** | Counted in the toolbar with the reason in its tooltip |
| Undo (Ctrl+Z) in A | yes | Undo produces real changes, so they publish like any other |

Watch the toolbar. `N changes not shared` is the feature working correctly, not failing — hover it
for which axiom kind it was.

### Not built yet

These are in the plan, not in the build. Testing them will find nothing:

- **Comments** — no comment UI, and no comment operation in the shared vocabulary.
- **Sticky notes and frames** — the sidecar reserves fields for them; nothing draws them.
- **Provenance** — no `dcterms:contributor` or `dcterms:created` is written for anything you add.
- **Editorial notes** — no `IAO:0000116` editing beyond Protégé's own annotation editor.
- **Release / published version** — `make reason` regenerates `mmo.owl`, but nothing in the plugin
  runs it, and no `owl:versionIRI` is stamped.

See the [requirements analysis](superpowers/specs/2026-08-26-provenance-and-release-requirements.md)
for what each of those needs.

### Resilience worth checking

| Try this | Expect |
|---|---|
| Wrong token | `invalid token: …` and **no** retry loop — check the bridge log shows one connection |
| `ws://127.0.0.1:1234` | Refused before a socket opens, with a message about 1235 |
| Kill the bridge, edit in A, restart the bridge | A's edits arrive at B when it reconnects — up to 200 are queued |
| Different board ids | Nothing crosses; no error either, because a board id is free text |

---

## 6. Reading the log

`~/.Protege/logs/protege.log`, searching for `OntoBoard`. Lines worth knowing:

```
OntoBoard: adopted the layout shipped with this version (…)   tab layout updated after an upgrade
OntoBoard: dropped 1 canvas entry for entities no longer …     stale sidecar entry pruned
OntoBoard: … describes '…' but the open ontology is '…'        sidecar belongs to another ontology
OntoBoard: ignoring addStickyNote(…) - …                       an operation this version cannot apply
```

The bridge's own log is on its terminal: `[bridge] alice joined 'mmo-test'`.

---

## 7. Tearing down

Ctrl+C the bridge. Nothing persists server-side — the operation log lives in memory only, so the
next run starts clean. In Protégé, **Collaborate…** becomes **Disconnect**.

If you ticked *Remember the token*, clear it by opening the dialog and unticking — that erases what
was written rather than just not writing again.
