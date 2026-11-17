# ODK goldens

A project OntoBoard creates must be what ODK's own `odk.py seed` writes, byte for byte, and what
it runs must be what ODK's targets do. These scripts make, in the pinned ODK image, the files the
tests compare with. The tests themselves need no Docker.

| Script | What it makes | Needs |
|---|---|---|
| `extract_templates.py` | `src/main/odk/v1.6.1/`: ODK's template folder as the image holds it, an index of every file's SHA-256 and permission bits, ODK's licence, and which image it was | the image |
| `generate.py seed` | `src/test/resources/odk/v1.6.1/seed/<name>/` and `model/<name>.yaml` for every config in `configs/` | the image |
| `generate.py corpus --odk-checkout <clone>` | `corpus/`: ODK's own 79 sample configs, each one's seed as a manifest of hashes, and its filled-in model | the image, a clone of ODK holding the tag |
| `generate.py lexer` | `lexer/`: how Jinja2 3.1.6 itself cuts each template | Python with Jinja2 3.1.6 |
| `generate.py make` | `make-n/` and `closure/`: GNU make's own dry runs of every seeded project, and what it considers for each | the image |
| `generate.py equivalence` | `equivalence/egg/<target>/`: what ODK's `make test_fast` and `make prepare_release_fast` print and write for a small project with work in it (`seed/eggboil` with `equivalence/egg/overlay` over it), and `equivalence/egg/query/`: what `robot query` gives in the forms ODK's other targets use | the image |
| `generate.py update` | `update/<case>/`: what ODK's own `odk.py update` writes - run twice - for the egg project as it is (`egg-unchanged`), with work in it (`egg-changed`: an import declared, a README of their own, own ignore rules and catalog entries, an import nobody declared, ODK's files edited, two files deleted) and with an import that cannot be loaded (`egg-unloadable`: ODK's update stops at its last step) | the image |
| `generate.py robot-update` | `robot-update/<case>/`: what ROBOT writes for `robot query --update` - ODK's seed's `preprocess-module.ru` and `postprocess-module.ru`, nfdicore's `inject-subset-declaration.ru` and `inject-synonymtype-declaration.ru`, an `INSERT DATA`, a `DELETE WHERE`, and nfdicore's chain of an update, an extract and three updates in one query - each run in a copy of the case's folder, as `command.txt` says; `robot.ofn` is what ROBOT wrote and `robot.txt` what it printed. The inputs import nothing | the image |
| `generate.py sed` | `sed/<case>/`: what GNU sed's `sed -i 's/RE/REPL/[g]' FILE` leaves of a file - nfdicore's two substitutions (`s/obofoundry/obolibrary/g`, `s/Declaration(AnnotationProperty(rdf:type))//g`), a group and `\1`, `&`, `^` and `$`, a bracket class, `\{2\}`, another delimiter, no `g`, CRLF lines with no line break at the end, an empty match, UTF-8, BRE's literal `+?(){}\|`, an escaped delimiter, a leading `*` - each run in the case's own folder as `sed -i "$(cat script.txt)" file.txt`; `after.txt` is the file afterwards and `sed.txt` what sed printed | the image |
| `generate.py own` | `equivalence/own/<target>/`: what ODK's make prints and writes for `test_fast`, `no-mirror-refresh-imports` and `prepare_release_fast` of a project with rules of its own in nfdicore's shape (`seed/oboimports` with `equivalence/own/overlay` over it: a module cut by MIREOT between ODK's seed's updates and nfdicore's, then sed over it; a module cut by BOT; the base built the project's way; small mirrors, so nothing is downloaded) | the image |
| `generate.py dosdp` | `src/main/resources/odk/dosdp-tools-0.19.3.sha256`: the SHA-256 of each `lib/*.jar` of dosdp-tools as the image has it in `/tools/dosdptools/lib`, which OntoBoard holds a download of the release against; and `dosdp/check/` with `dosdp/verdicts.txt`: OntoBoard's own DOSDP pattern cases (`dosdp_check_goldens.py`: the fields and what each holds, the vars and which a block may name, the blocks' shapes, the names an OWL text quotes, annotation properties, YAML as the checker reads it, files and folders as ODK's recipe gives them) and the exit status of ODK's `dosdp validate -i` on each. That checker is GPL-3.0; only its verdicts are recorded. And `equivalence/dosdp/<target>/` (`dosdp_equivalence_goldens.py`): what ODK's `make test` and `make prepare_release` with `IMP=false MIR=false` print and write for ODK's own test project with patterns (`tests__test-patterns`, seeded in the image into `equivalence/dosdp/project`) with `equivalence/dosdp/overlay` over it - labelled classes, two rows of the seed's pattern, a second pattern of OntoBoard's in the manual pipeline | the image |
| `generate.py imports` | `equivalence/loim/<target>/`: what ODK's `make refresh-imports`, `make test`, `make prepare_release` and `make refresh-ro` print and write for a project that uses a term of each of its three imports (`seed/localimports` with `equivalence/loim/overlay` over it), and `make no-mirror-refresh-imports` and `make no-mirror-refresh-ro` for the same project where ODK's own refresh left it, with one more term asked for in a term list (`equivalence/loim/more`); that start is checked to be what the tests put together from the goldens. The ontologies it mirrors are the small ones in `equivalence/loim/upstream`, given to make as `OBOBASE=file:///work/in/upstream`: ODK's rule fetches with `curl -L $(OBOBASE)/<id>.owl`, and curl reads a `file:` address, so nothing comes from the network. Two things differ from one run to the next and are not held against anything: the speeds in curl's progress meter, and the order of the rows of `tmp/pre_seed.txt` | the image |

Run them from the repository root with Python 3.8 or later; `generate.py all --odk-checkout <clone>`
does everything and takes about a quarter of an hour on Windows (the bind mount is slow).

The image is found by what it is, not by its tag: `obolibrary/odkfull:v1.6.1`, or any local
`obolibrary/odkfull` tag whose own `ODK_VERSION` is `v1.6.1`. Nothing is pulled, and nothing here
downloads an ontology: `seed --skipgit` renders templates and stops.

## Rules

- Regenerate, never edit. `.gitattributes` marks both folders `-text`; a golden whose line endings
  git converted is a broken golden.
- To add a case, add `configs/<name>.yaml` and run `generate.py seed`.
- To move to another ODK: change `PIN` in `odkimage.py` and `OdkPin.java`, run
  `extract_templates.py` and `generate.py all`, and let the tests say what the interpreter or the
  config model has to learn.
