<!--
  Written for: the maintainer deciding what to build next, and a reviewer asking why an ontology
  editor does not "just merge". Points only.
-->

# Conflicts and concurrent editing

## What OntoBoard does today

- **Live sessions synchronise axiom changes**, as 18 typed operations over a shared log; the last
  change to arrive wins. A `viewer` token receives but cannot write. A change that cannot be
  applied is reported to the person whose copy is behind.
- **No locks.** A plugin learns of an edit only after Protégé applied it. Instead, a peer editing
  a term is shown with a badge.
- **Git conflicts are refused, not committed**, and the refusal names the terms the two sides
  disagree on - read from the three versions git keeps of the file, since a `.owl` full of
  conflict markers does not parse. A pull request that cannot be merged is marked as such.
- **Axiom-level diffs** (`AxiomDiff`, `ReleaseDiff`) describe changes; they do not produce changes
  that can be applied.

## Why ontologies are harder to merge than text

- **No canonical serialisation:** reordering a file gives a large text diff and no axiom diff.
- **Same logic, different syntax:** `A ⊑ (B ⊓ C)` against `A ⊑ B` plus `A ⊑ C`.
- **Different regions, contradictory result:** one side adds `DisjointClasses(B, C)`, the other
  `A ⊑ C`, with `A ⊑ B` already there - a clean merge, and `A` is unsatisfiable.
- **Imports and blank nodes:** the axiom that breaks may be in a file nobody here edited.
- **Only a reasoner can say a merge is good**, and it names a class, not a line.

| Method | What it gives | Here |
|---|---|---|
| Locking | Serializability | Impossible: a plugin cannot lock before an edit |
| Optimistic concurrency | Serializability, no blocking | Reachable: a version counter, refuse a stale publish |
| CRDTs, operational transformation | The same state everywhere | Agreement is not correctness |
| Three-way merge, a git merge driver | Merged hunks | Separate regions can still contradict |
| A reasoner on the merged result | The only "is this good?" | A reasoner run per merge |

Closest prior art: **ContentCVS** (Jiménez-Ruiz et al., DKE 2011), a Protégé plugin built on
structural and semantic difference; **OntoEditor** (ESWC 2024), character-level and syntax-only.

## Still missing

1. **Telling the sender when an operation is dropped** - the receiver is told; the sender needs a
   message the protocol lacks.
2. **Applicable diffs** (`List<OWLOntologyChange>`), so a decision can be applied once made.
3. **A base version per live session**, so a three-way merge is possible at all.
