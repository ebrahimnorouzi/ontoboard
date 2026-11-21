# eggboil 1.1.0

Released on 2026-11-19 with OntoBoard 1.155.0, built by OntoBoard's own engine (ROBOT 1.9.8, inside Protégé).

## What it is

| | |
|---|---|
| Classes | 12 |
| Object properties | 3 |
| Data properties | 1 |
| Annotation properties | 5 |
| Individuals | 2 |
| Logical axioms | 40 |
| Expressivity | ALC |
| In OWL 2 DL | yes |

## How it was checked

- **Reasoner:** HermiT finds the ontology, with its imports, consistent, and every class can have members (under a second).
- **Quality report** (`src/ontology/reports/eggboil-edit.owl-obo-report.tsv`): 0 errors, 2 warnings, 1 info.
  - WARN missing_definition: 2
  - INFO lowercase_definition: 1
- **OWL 2 DL profile:** OWL 2 DL Profile Report: [Ontology and imports closure in profile]
- **ODK's checks:** passed - a release stops at the first that fails.

## What changed since 1.0.0

+2 classes, 1 obsoleted.

## What it imports

| Import | Version |
|---|---|
| http://purl.obolibrary.org/obo/eggboil/imports/ro_import.owl | http://purl.obolibrary.org/obo/eggboil/releases/2026-11-19/imports/ro_import.owl |
