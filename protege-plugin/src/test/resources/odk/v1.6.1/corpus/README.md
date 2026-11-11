# ODK's own sample configurations, as a differential corpus

`configs/` holds the 79 configurations ODK v1.6.1 keeps under `configs/`, `tests/` and
`examples/` (BSD-3-Clause, Gene Ontology Consortium; see the plugin's NOTICE), unchanged.
`manifests/<name>.sha256` lists every file `odk.py seed --skipgit` wrote for it in the ODK
image, with its SHA-256; `model/<name>.yaml` is `odk.py export-project`'s dump. A file that
starts `!refused` records that ODK itself refused the configuration, and why.

They exercise what OntoBoard's own configurations never do: components, DOSDP patterns,
SSSOM mappings, translations, subsets, custom artefacts, mirror types, plugins.
Made by `protege-plugin/tools/odk-goldens/generate.py corpus`. Regenerate, never edit.
