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
