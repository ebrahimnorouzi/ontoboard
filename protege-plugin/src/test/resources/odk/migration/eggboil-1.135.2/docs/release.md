# Releasing Test kitchen ontology

A release is a frozen, numbered copy - 1.0.0, then 1.1.0 and so on - that other
ontologies can import and that will not change under them.

1. Make sure `test` passes.
2. **OntoBoard > Build and release > Release...**, which proposes the next number,
   stamps a version IRI of the form `https://example.org/eggboil/eggboil/1.0.0`, and writes
   `releases/1.0.0/eggboil.owl` and the published `eggboil.owl`. From the
   second release on, `releases/<version>/CHANGES.md` says what changed.
3. Commit both, tag the commit `v1.0.0`, and push.

A version IRI cannot be taken back once somebody has imported it, so the
release step refuses to overwrite a release that already exists.
