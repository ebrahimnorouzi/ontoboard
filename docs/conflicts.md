<!--
  Written for: the maintainer deciding what to build next, and a reviewer asking why an ontology
  editor does not "just merge". Points only.
-->

# Conflicts and concurrent editing

## What OntoBoard does today

- **Unit of synchronisation is one OWL axiom change**, encoded as one of 18 typed JSON
  operations. Not characters, not files. (`OperationMapper`, `OntologyOperation.TYPES`)
- **Transport is an append-only shared log** (a Yjs array over a WebSocket bridge). The bridge
  relays and authenticates; it does not arbitrate.
- **Resolution is last-writer-wins by arrival order.** No lock, no version check, no server
  authority, no vector clocks.
- **Deliberately no entity locking.** A plugin learns of an edit only after Protégé applied it,
  so a lock it could not enforce would be a promise it could not keep.
- **Git conflicts are refused, not committed.** Since 1.123.0 an unmerged file (`UU`, `AA`, `DD`,
  `AU`, `UA`, `DU`, `UD`) is its own state, and a commit while one exists is declined. Before
  that, `git add -A` + `commit` recorded the conflict markers and reported success — which
  destroys the ontology, because an `.owl` full of markers does not parse.
- **Axiom-level diff exists and is descriptive**: `AxiomDiff` and `ReleaseDiff` with six typed
  change kinds. They render a report; they do not produce applicable changes.

## Why this is harder than merging text or rows

- **No canonical serialisation.** Reorder the `owl:Class` elements or relabel `_:b0`, and you get
  a ~100% text diff and a zero-axiom diff.
- **Same logic, different syntax.** `A ⊑ (B ⊓ C)` versus `A ⊑ B` plus `A ⊑ C`: diff3 reports a
  conflict, a reasoner reports no difference.
- **Different regions, contradictory result.** One side adds `DisjointClasses(B, C)`, the other
  adds `A ⊑ C`, with `A ⊑ B` already present. Clean merge, and `A` is now unsatisfiable. This is
  database *write skew*: each side is valid against its own snapshot; the union is not.
- **Entailment has no locality.** The two axioms that contradict can be 10,000 lines apart, in
  different files, or one of them inferred — so the hunk abstraction that makes diff3 work does
  not transfer.
- **Imports.** One side bumps `owl:imports` to a version where upstream made two classes
  disjoint. Both diffs are one line, neither touches the axiom that breaks, and the unsatisfiable
  class is in a file nobody here edited.
- **Blank nodes.** Graphs must be standardised apart before union; deciding isomorphism with
  blank nodes is GI-complete.
- **Only a reasoner can say a merge is good**, and it answers "class X is unsatisfiable" rather
  than "line 412 conflicts" — so the verdict cannot be shown in a diff gutter.

## The established methods, and what each would buy

| Method | Guarantee | Cost here |
|---|---|---|
| Two-phase locking | Serializability | Needs a lock *before* the edit — impossible in a Protégé plugin |
| Optimistic concurrency control | Serializability, no blocking | Reachable: version counter + refuse a non-fast-forward publish |
| MVCC / snapshot isolation | Readers never block writers | Not serializable — write skew is exactly the ontology problem |
| Operational transformation (ShareDB, ot-text) | Identical character sequence | Says nothing about meaning; two edits to one axiom interleave into a third |
| CRDTs (Yjs, Automerge) | Strong eventual consistency | Agreement on state ≠ correctness; LWW silently drops an author |
| Three-way merge / diff3 | Non-overlapping hunks merge | "Separate regions never conflict" is formally **not** guaranteed |
| Git merge driver | Hook for an OWL-aware merge | The right place for one; `union` takes both sides with no markers |
| Axiom-level diff (ROBOT diff, Bubastis) | Ignores serialisation noise | Lower level than how curators think about a change |
| Structural + logical diff (OWLDiff, ECCO) | Catches entailment differences | Full semantic diff is tractable only for EL |
| SHACL / reasoner on the merged result | The only "is this good?" | A reasoner run per merge |

Closest prior art: **ContentCVS** (Jiménez-Ruiz et al., DKE 2011) — a Protégé 4 plugin for
concurrent OWL development built on structural *and* semantic difference, with debugging and
repair. **OntoEditor** (ESWC 2024) takes the other route: character-level OT, syntax validation
only, and a hand-driven side-by-side diff for git history.

## What a Protégé plugin can and cannot do

- **Cannot**: take a lock before an edit, veto an edit, pre-validate, or transform an incoming
  operation. `ontologiesChanged` is past tense, returns `void`, and has no veto.
- **Can**: read every change as typed OWL API objects, keep an op log and a version counter, do
  an optimistic check at publish time, apply remote changes with `applyChanges`, run a reasoner
  over the result, and compensate by applying the inverse change.
- **Good news about the unit**: axiom add/add is set union, which is commutative — so the
  transform function OT needs never arises. Only two real conflict classes remain: *remove versus
  concurrent re-add*, and *two individually-fine adds whose union is inconsistent*.

## What is still missing, cheapest first

1. **The editing claim never fires.** `editedTerm` expects an IRI but gets the operation's random
   UUID, so the advisory "someone is editing this" badge is dead. (~half a day)
2. **PR mergeability.** Add `mergeable,mergeStateStatus` to the PR listing so a curator learns of
   a conflict without leaving Protégé. (under an hour)
3. **Tell the sender when an operation is dropped.** `skipped()` logs at INFO; there is no NACK
   in the protocol. (2–3 days)
4. **Role enforcement.** `role` is parsed and discarded — anyone who can connect can write.
   (1–2 days)
5. **A read-only conflict report.** Reuse the six `ReleaseDiff` change kinds to name the collision
   in curator language ("both sides redefined X"). Needs a base version and axioms rather than
   strings. (2–3 days — best value per unit of work)
6. **Applicable diff objects.** Neither diff returns `List<OWLOntologyChange>`; everything below
   depends on that. (2–3 days)
7. **A base version per session**, so a three-way merge is possible at all. (1–2 weeks)
