# Migration fixtures

## eggboil-1.135.2

A project as OntoBoard 1.135.2 wrote one, kept so that *Update project files...* can be tested
on it after the code that wrote it (`OdkScaffold` and friends) is gone.

Written by `src/test/java/de/fizkarlsruhe/ise/ontoboard/odk/MigrationFixtureWriter.java` at
commit `fced00901469aeb31a98687d8f5ee615858cea86` (branch `work/1.153.0-odk-helper`, plugin
version 1.135.2), on 2026-11-11, JDK 11.0.19, Maven 3.8.7, with:

    cd protege-plugin
    mvn -o -Dtest=MigrationFixtureWriter -Dontoboard.writeFixture=<new folder> test

The writer went with the code it called, in 1.153.0 (`OdkScaffold`, `OdkRegenerator`,
`OdkProjectSettings`, `InProcessTargets`): it is in the history at the commit above, and this
folder cannot be written again from a later one. `RecordedProject` (in the tests) hands a copy
of it to a test.

Nothing in it is written by hand except what a person edits by hand. It holds:

- the project `OdkScaffold.create` writes: id `eggboil`, title "Test kitchen ontology",
  description "Application ontology for boiling eggs in a test kitchen", base IRI
  `https://example.org/eggboil/eggboil.owl`, CC BY 4.0. The scaffold's one ID range is allocated
  to `FirstEditor`, the name it uses when the account has none (the writer blanks `user.name`
  for the call, so the fixture does not carry whoever wrote it);
- three classes minted by `TermMinter` from that range, as the board mints them
  (`EGGBOIL_0001000` egg, `EGGBOIL_0001001` boiling run, `EGGBOIL_0001002` boiling record, the
  last a subclass of mini's dataset);
- `src/ontology/upstream/mini.owl`, ten labelled and defined classes and a `has part` property,
  IRI `https://example.org/mini.owl`;
- `imports/mini_*`: a BOT module of two of mini's terms, built as `OdkConversion.modules` builds
  one - term list with `# Source: https://example.org/mini.owl` and labels, catalog entry,
  imported, product declared in `import_group.products`;
- `imports/measurement_*`: a module cut from the library pattern
  `pmdco-measurement-and-setpoint` and saved as *Import terms...* saves a pattern's, its term
  list written by `ImportModules.writeTerms(root, "measurement", terms, source, pattern)`;
  imported, with a catalog entry, not declared;
- `imports/unused_*`: a module with a `# Source:` line that nothing imports (no catalog entry,
  not declared);
- `src/sparql/every_dataset_has_part.rq`, written as *Write a test from a question...* writes it;
- `profile.txt` with `missing_definition` relaxed from WARN to INFO, by hand;
- release 1.0.0 as *Release...* cuts it with its defaults: imports merged, ELK's inferences
  written, `Release.stamp`ed, under `releases/1.0.0/` and at the root;
- a paragraph added to `README.md` and a line `notes/` added to `.gitignore`, by hand.

Every date passed in is 2026-11-11. Written twice into two new folders, the two trees were
identical byte for byte. The files are committed byte for byte (`.gitattributes`:
`protege-plugin/src/test/resources/odk/** -text`), and added with `git add -f`, because the
project's own `.gitignore` would otherwise hide some of them.
