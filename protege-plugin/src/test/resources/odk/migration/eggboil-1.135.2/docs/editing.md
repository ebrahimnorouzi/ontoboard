# Editing Test kitchen ontology

## With Protege and OntoBoard

1. Open `src/ontology/eggboil-edit.owl`.
2. Work on the OntoBoard tab, or in Protege's own views.
3. Run **OntoBoard > Build and release > Build...** and choose `test` before you
   commit. It runs the same checks the repository's CI runs.

## Identifiers

New terms are minted from the range allocated to you in
`src/ontology/eggboil-idranges.owl`. Give each editor their own range -
**OntoBoard > Project > ID ranges...** - so two people never mint the same
identifier for different things.

## What the checks mean

| Target | What it does |
|---|---|
| `reason` | Runs the reasoner and writes its conclusions into `eggboil.owl` |
| `report` | ROBOT's quality report, failing on anything at ERROR |
| `sparql_test` | The checks in `src/sparql/` |
| `validate_profile` | That the ontology is still OWL 2 DL |
| `test` | All four |
