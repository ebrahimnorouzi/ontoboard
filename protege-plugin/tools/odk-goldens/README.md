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
