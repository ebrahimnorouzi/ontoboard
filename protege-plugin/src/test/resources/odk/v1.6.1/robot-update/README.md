# robot query --update, as ODK v1.6.1's ROBOT runs it

Made by `protege-plugin/tools/odk-goldens/generate.py robot-update` in ODK's image (ROBOT 1.9.10).
Regenerate, never edit what it writes: `command.txt`, `robot.ofn`, `robot.txt`. Held against by
`OdkRobotQueryTest.anUpdateChangesTheOntologyAsRobotDoes`.

| case | what ROBOT is given |
|---|---|
| `preprocess-module` | ODK's seed's `preprocess-module.ru` on a piece of RO with a version IRI |
| `postprocess-module` | ODK's seed's `postprocess-module.ru` on the same, its `dc:source` already there |
| `inject-subset-declaration` | nfdicore's query on subsets in OBO's, nfdicore's and another namespace, and one that is a literal |
| `inject-synonymtype-declaration` | nfdicore's query on synonym types given as annotations of synonyms |
| `insert-data` | an `INSERT DATA`, one label in French (the image runs Java with `file.encoding=UTF-8`) |
| `delete-where` | a `DELETE WHERE` that takes every `rdfs:comment`, the ontology's own too |
| `chain` | nfdicore's import rule in short: an update, `extract --method BOT`, then three updates in one query |

## Where the queries come from

- `preprocess-module.ru`, `postprocess-module.ru`: ODK v1.6.1's seed, byte for byte as `odk.py seed`
  writes them (`seed/eggboil/src/sparql/`). BSD-3-Clause, Copyright (c) 2015, Gene Ontology
  Consortium: `protege-plugin/src/main/odk/v1.6.1/LICENSE-ODK.txt`.
- `inject-subset-declaration.ru`, `inject-synonymtype-declaration.ru`: nfdicore,
  https://github.com/ISE-FIZKarlsruhe/nfdicore, `src/sparql/` at commit
  549a451cc67d200127c5fdb4a8531a86996af664 (2026-04-09), byte for byte. CC0 1.0 Universal
  (nfdicore's `LICENSE`).
- `insert-data.ru`, `delete-where.ru`, every `input.ofn` and `chain/terms.txt`: written for these
  tests.
