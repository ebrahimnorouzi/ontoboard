"""ODK's DOSDP pattern check (`dosdp validate -i`), its verdict on OntoBoard's own cases.

ODK's test runs `dosdp validate -i ../patterns/dosdp-patterns/` on a project with patterns. The
checker in the image is the GPL-3.0 Python package dosdp; OntoBoard's (odk/DosdpCheck) is
written from DOSDP's format and from what this records - the exit status on each case - and
nothing of the checker's code or schema text.

The cases are OntoBoard's: one rule each, kept and broken, the seed's example among them; and
folders, as ODK's recipe gives one. This writes dosdp/check/ (the cases, byte for byte) and
dosdp/verdicts.txt (`<input> <exit status>`, each input a path under check/: a file, a folder
with a slash or without, or one that is not there).

Used by generate.py (stage `dosdp`); see README.md.
"""
import os
import shutil

import odkimage

IRI = "http://purl.obolibrary.org/obo/myont/patterns/x.yaml"
HEAD = "pattern_name: x\npattern_iri: " + IRI + "\n"
X = "vars:\n  x: \"owl:Thing\"\n"
C = "annotationProperties:\n  c: rdfs:comment\n"

# The seed's example, as ODK v1.6.1's _dynamic_files renders it for a project myont.
SEED = """pattern_name: example
pattern_iri: http://purl.obolibrary.org/obo/myont/example.yaml
description: "This is a minimal example pattern."

classes:
  example: owl:Thing

relations:
  part_of: BFO:0000050

vars:
  example: "'example'"

name:
  text: "Part of %s"
  vars:
   - example

equivalentTo:
  text: "'part_of' some %s"
  vars:
    - example
"""

AXIOMS = HEAD + """classes:
  organ: UBERON:0000062
  cell: CL:0000000
relations:
  part_of: BFO:0000050
vars:
  whole: "'organ'"
  piece: "'cell'"
name:
  text: "%s of %s"
  vars: [piece, whole]
logical_axioms:
  - axiom_type: subClassOf
    text: "'part_of' some %s"
    vars: [whole]
  - axiom_type: equivalentTo
    text: "%s and ('part_of' some %s)"
    vars: [piece, whole]
"""

GCI = HEAD + """classes:
  thing: owl:Thing
relations:
  part_of: BFO:0000050
vars:
  a: "'thing'"
  b: "'thing'"
GCI:
  text: "%s and ('part_of' some %s)"
  vars: [a, b]
disjointWith:
  text: "%s"
  vars: [b]
"""

UNDECLARED = HEAD + X + "name:\n  text: \"%s\"\n  vars: [y]\n"

# name -> the file's text (str, written as UTF-8 with LF) or its bytes
PATTERNS = {
    "seed-example": SEED,
    # the fields: what a pattern has, and what each holds
    "pattern-only-name-and-iri": HEAD,
    "pattern-no-pattern-name": "pattern_iri: " + IRI + "\n" + X,
    "pattern-no-pattern-iri": "pattern_name: x\n" + X,
    "pattern-name-a-number": "pattern_name: 5\npattern_iri: " + IRI + "\n",
    "pattern-iri-not-an-iri": "pattern_name: x\npattern_iri: not an iri\n",
    "pattern-name-differs-from-file": "pattern_name: other\npattern_iri: " + IRI + "\n",
    "pattern-unknown-field": HEAD + "colour: red\n",
    "pattern-base-iri": HEAD + "base_IRI: http://purl.obolibrary.org/obo/\n",
    "pattern-status-draft": HEAD + "status: draft\n",
    "pattern-status-published": HEAD + "status: published\n",
    "pattern-contributors-a-string": HEAD + "contributors: https://orcid.org/0000-0000-0000-0000\n",
    "pattern-contributors-not-orcids": HEAD + "contributors:\n  - somebody\n",
    "pattern-tags-numbers": HEAD + "tags:\n  - 5\n",
    "pattern-examples-not-iris": HEAD + "examples:\n  - not an iri\n",
    "pattern-readable-identifiers": HEAD + "readable_identifiers:\n  - somethingelse\n",
    # the dictionaries
    "dictionary-classes-a-list": HEAD + "classes:\n  - owl:Thing\n",
    "dictionary-class-a-number": HEAD + "classes:\n  thing: 5\n",
    "dictionary-class-not-a-curie": HEAD + "classes:\n  a: not a curie\n",
    "dictionary-class-an-iri": HEAD + "classes:\n  a: http://example.org/a\n",
    "dictionary-classes-empty": HEAD + "classes: {}\n",
    "dictionary-classes-null": HEAD + "classes:\n",
    "dictionary-relations-a-list": HEAD + "relations:\n  - BFO:0000050\n",
    "dictionary-relation-a-list": HEAD + "relations:\n  r: [X:1]\n",
    "dictionary-annotation-properties-a-list": HEAD + "annotationProperties:\n  - rdfs:comment\n",
    "dictionary-annotation-property-a-number": HEAD + "annotationProperties:\n  c: 5\n"
    "annotations:\n  - annotationProperty: c\n    value: v\n",
    "dictionary-unused-class": HEAD + X + "classes:\n  never: X:1\nname:\n  text: \"%s\"\n"
    "  vars: [x]\n",
    "dictionary-unused-annotation-property": HEAD + C,
    "dictionary-int-key": HEAD + "classes:\n  5: X:1\n",
    # the vars, and which a block may name
    "vars-a-string": HEAD + "vars: \"owl:Thing\"\n",
    "vars-list-vars-a-list": HEAD + "list_vars:\n  - places\n",
    "vars-data-vars-a-list": HEAD + "data_vars:\n  - amount\n",
    "vars-data-list-vars-a-list": HEAD + "data_list_vars:\n  - notes\n",
    "vars-name-starts-with-a-digit": HEAD + "vars:\n  1x: \"owl:Thing\"\n",
    "vars-name-with-a-dash": HEAD + "vars:\n  a-b: \"owl:Thing\"\n",
    "vars-value-a-number": HEAD + "vars:\n  x: 5\nname:\n  text: \"%s\"\n  vars: [x]\n",
    "vars-range-not-manchester": HEAD + "vars:\n  x: \"((( broken\"\n",
    "vars-range-quoted-undeclared": HEAD + "vars:\n  x: \"'organ' or 'cell'\"\n"
    "name:\n  text: \"%s\"\n  vars: [x]\n",
    "vars-data-var-not-xsd": HEAD + "data_vars:\n  d: \"not a type\"\n",
    "vars-declared-twice": HEAD + X + "list_vars:\n  x: \"owl:Thing\"\n",
    "vars-unused": HEAD + "vars:\n  x: \"owl:Thing\"\n  y: \"owl:Thing\"\nname:\n  text: \"%s\"\n"
    "  vars: [x]\n",
    "vars-undeclared": UNDECLARED,
    "vars-undeclared-without-vars": HEAD + "name:\n  text: \"%s\"\n  vars: [x]\n",
    "vars-undeclared-with-empty-vars": HEAD + "vars: {}\nname:\n  text: \"%s\"\n  vars: [x]\n",
    "vars-list-var-alone": HEAD + "list_vars:\n  xs: \"'thing'\"\nclasses:\n  thing: owl:Thing\n"
    "name:\n  text: \"%s\"\n  vars: [xs]\n",
    "vars-list-var": HEAD + X + "list_vars:\n  xs: \"owl:Thing\"\nname:\n  text: \"%s\"\n"
    "  vars: [xs]\n",
    "vars-data-var": HEAD + X + "data_vars:\n  d: \"xsd:string\"\ncomment:\n  text: \"%s\"\n"
    "  vars: [d]\n",
    "vars-data-list-var": HEAD + X + "data_list_vars:\n  ds: \"xsd:string\"\n"
    "comment:\n  text: \"%s\"\n  vars: [ds]\n",
    "vars-internal-var": HEAD + X + "list_vars:\n  xs: \"owl:Thing\"\n"
    "internal_vars:\n  - var_name: iv\n    input: xs\n    apply:\n      sep: \" \"\n"
    "name:\n  text: \"%s\"\n  vars: [iv]\n",
    "vars-internal-var-input-undeclared": HEAD + X + "internal_vars:\n  - var_name: iv\n"
    "    input: nothere\n    apply:\n      sep: \" \"\n",
    "vars-internal-var-name-not-a-name": HEAD + X + "internal_vars:\n  - var_name: \"1 bad\"\n"
    "    input: x\n",
    "vars-internal-vars-a-map": HEAD + "internal_vars:\n  iv: x\n",
    "vars-internal-var-a-string": HEAD + X + "internal_vars:\n  - a\n",
    "vars-internal-var-without-a-name": HEAD + X + "internal_vars:\n  - input: x\n",
    "vars-internal-var-a-string-without-vars": HEAD + "internal_vars:\n  - a\n",
    "vars-undeclared-in-def": HEAD + X + "def:\n  text: \"%s\"\n  vars: [y]\n",
    "vars-undeclared-in-comment": HEAD + X + "comment:\n  text: \"%s\"\n  vars: [y]\n",
    "vars-undeclared-in-namespace": HEAD + X + "namespace:\n  text: \"%s\"\n  vars: [y]\n",
    "vars-undeclared-in-owl": HEAD + X + "subClassOf:\n  text: \"%s\"\n  vars: [y]\n",
    "vars-undeclared-in-logical-axioms": HEAD + X + "logical_axioms:\n  - axiom_type: subClassOf\n"
    "    text: \"%s\"\n    vars: [y]\n",
    "vars-undeclared-in-annotations": HEAD + X + C + "annotations:\n  - annotationProperty: c\n"
    "    text: \"%s\"\n    vars: [y]\n",
    "vars-undeclared-in-generated-synonyms": HEAD + X + "generated_synonyms:\n"
    "  - text: \"%s\"\n    vars: [y]\n",
    "vars-undeclared-in-a-clause": HEAD + X + "name:\n  multi_clause:\n    clauses:\n"
    "      - text: \"%s\"\n        vars: [y]\n",
    "vars-undeclared-in-a-sub-clause": HEAD + X + "name:\n  multi_clause:\n    clauses:\n"
    "      - text: \"%s\"\n        vars: [x]\n        sub_clauses:\n          - clauses:\n"
    "              - text: \"%s\"\n                vars: [y]\n",
    "vars-undeclared-in-a-nested-annotation": HEAD + X + C + "name:\n  text: \"%s\"\n"
    "  vars: [x]\n  annotations:\n    - annotationProperty: c\n      text: \"%s\"\n"
    "      vars: [y]\n",
    "vars-undeclared-in-an-iri-annotation": HEAD + X + "annotationProperties:\n  s: rdfs:seeAlso\n"
    "annotations:\n  - annotationProperty: s\n    var: y\n",
    "vars-undeclared-under-classes": HEAD + X + "classes:\n  a:\n    vars: [nothere]\n",
    "vars-undeclared-under-instance-graph": HEAD + X + "instance_graph:\n  nodes:\n    n:\n"
    "      vars: [nothere]\n  edges: []\n",
    "vars-empty-list": HEAD + X + "name:\n  text: \"%s\"\n  vars: []\n",
    "vars-more-slots-than-vars": HEAD + X + "name:\n  text: \"%s of %s\"\n  vars: [x]\n",
    "vars-more-vars-than-slots": HEAD + "vars:\n  x: \"owl:Thing\"\n  y: \"owl:Thing\"\n"
    "name:\n  text: \"%s\"\n  vars: [x, y]\n",
    # the printf blocks' shapes
    "block-text-without-vars": HEAD + "classes:\n  thing: owl:Thing\nname:\n  text: \"a fixed name\"\n",
    "block-slot-without-vars": HEAD + X + "name:\n  text: \"%s\"\n",
    "block-vars-without-text": HEAD + X + "name:\n  vars: [x]\n",
    "block-text-a-number": HEAD + X + "name:\n  text: 5\n  vars: [x]\n",
    "block-text-empty": HEAD + X + "name:\n  text: \"\"\n  vars: [x]\n",
    "block-vars-a-string": HEAD + X + "name:\n  text: \"%s\"\n  vars: x\n",
    "block-percent-d": HEAD + X + "name:\n  text: \"%d\"\n  vars: [x]\n",
    "block-literal-percent": HEAD + X + "name:\n  text: \"100%% %s\"\n  vars: [x]\n",
    "block-xrefs": HEAD + X + "name:\n  text: \"%s\"\n  vars: [x]\n  xrefs: \"X:1\"\n",
    "block-def-xrefs": HEAD + X + "def:\n  text: \"%s\"\n  vars: [x]\n  xrefs: \"X:1\"\n",
    "block-def-xrefs-a-list": HEAD + X + "def:\n  text: \"%s\"\n  vars: [x]\n"
    "  xrefs: [\"X:1\"]\n",
    "block-multi-clause": HEAD + X + "name:\n  multi_clause:\n    sep: \" \"\n    clauses:\n"
    "      - text: \"%s\"\n        vars: [x]\n",
    "block-multi-clause-without-sep": HEAD + X + "name:\n  multi_clause:\n    clauses:\n"
    "      - text: \"%s\"\n        vars: [x]\n",
    "block-multi-clause-without-clauses": HEAD + X + "name:\n  multi_clause:\n    clauses: []\n",
    "block-multi-clause-slots": HEAD + X + "name:\n  multi_clause:\n    sep: \" \"\n"
    "    clauses:\n      - text: \"%s %s\"\n        vars: [x]\n",
    "block-multi-clause-a-string": HEAD + "name:\n  multi_clause: \"x\"\n",
    "block-text-and-multi-clause": HEAD + X + "name:\n  text: \"%s\"\n  vars: [x]\n"
    "  multi_clause:\n    clauses: []\n",
    "block-synonym-as-text": HEAD + X + "exact_synonym:\n  text: \"%s part\"\n  vars: [x]\n",
    "block-synonym-value": HEAD + "exact_synonym:\n  value: \"a synonym\"\n",
    "block-synonym-value-and-xrefs": HEAD + "exact_synonym:\n  value: \"a synonym\"\n"
    "  xrefs: \"X:1\"\n",
    "block-generated-synonyms": HEAD + X + "generated_synonyms:\n  - text: \"%s thing\"\n"
    "    vars: [x]\n",
    "block-substitutions": HEAD + "substitutions:\n  - in: x\n    out: y\n    match: a\n    sub: b\n",
    "block-substitutions-incomplete": HEAD + "substitutions:\n  - in: x\n    out: y\n",
    "block-instance-graph": HEAD + "instance_graph:\n  nodes: {}\n  edges:\n    - edge: [a, b, c]\n",
    "block-instance-graph-short-edge": HEAD + "instance_graph:\n  nodes: {}\n  edges:\n"
    "    - edge: [a, b]\n",
    # the OWL blocks: their axiom types, and the names their text quotes
    "owl-logical-axioms": AXIOMS,
    "owl-gci-and-disjoint": GCI,
    "owl-no-quoted-names": HEAD + "vars:\n  x: \"owl:Thing\"\nname:\n  text: \"about %s\"\n"
    "  vars: [x]\n",
    "owl-axiom-type-unknown": HEAD + "classes:\n  thing: owl:Thing\nvars:\n  x: \"'thing'\"\n"
    "logical_axioms:\n  - axiom_type: sameAs\n    text: \"%s\"\n    vars: [x]\n",
    "owl-axiom-without-text": HEAD + X + "logical_axioms:\n  - axiom_type: subClassOf\n"
    "    vars: [x]\n",
    "owl-axiom-without-type": HEAD + X + "logical_axioms:\n  - text: \"%s\"\n    vars: [x]\n",
    "owl-logical-axioms-a-map": HEAD + X + "logical_axioms:\n  axiom_type: subClassOf\n"
    "  text: \"%s\"\n  vars: [x]\n",
    "owl-logical-axiom-multi-clause": HEAD + X + "logical_axioms:\n  - multi_clause:\n"
    "      clauses:\n        - text: \"%s\"\n          vars: [x]\n",
    "owl-text-without-a-slot": HEAD + X + "subClassOf:\n  text: \"owl:Thing\"\n  vars: [x]\n",
    "owl-vars-empty": HEAD + X + "subClassOf:\n  text: \"%s\"\n  vars: []\n",
    "owl-percent-d": HEAD + X + "subClassOf:\n  text: \"%d\"\n  vars: [x]\n",
    "owl-data-var": HEAD + X + "data_vars:\n  d: \"xsd:string\"\nsubClassOf:\n  text: \"%s\"\n"
    "  vars: [d]\n",
    "owl-list-var": HEAD + X + "list_vars:\n  xs: \"owl:Thing\"\nsubClassOf:\n  text: \"%s\"\n"
    "  vars: [xs]\n",
    "owl-quoted-relation-undeclared": HEAD + "classes:\n  thing: owl:Thing\nvars:\n"
    "  x: \"'thing'\"\nequivalentTo:\n  text: \"'has_part' some %s\"\n  vars: [x]\n",
    "owl-quoted-class-undeclared": HEAD + "relations:\n  part_of: BFO:0000050\n" + X
    + "subClassOf:\n  text: \"'part_of' some 'organ'\"\n  vars: []\n",
    "owl-quoted-object-property": HEAD + "objectProperties:\n  overlaps: RO:0002131\n" + X
    + "subClassOf:\n  text: \"'overlaps' some %s\"\n  vars: [x]\n",
    "owl-quoted-data-property": HEAD + "dataProperties:\n  weight: X:1\n" + X
    + "subClassOf:\n  text: \"'weight' some %s\"\n  vars: [x]\n",
    "owl-quoted-annotation-property": HEAD + "annotationProperties:\n  note: rdfs:comment\n" + X
    + "subClassOf:\n  text: \"'note' some %s\"\n  vars: [x]\n",
    "owl-quoted-in-equivalent-to": HEAD + X + "equivalentTo:\n  text: \"'part_of' some %s\"\n"
    "  vars: [x]\n",
    "owl-quoted-in-gci": HEAD + X + "GCI:\n  text: \"'part_of' some %s\"\n  vars: [x]\n",
    "owl-quoted-in-disjoint-with": HEAD + X + "disjointWith:\n  text: \"'part_of' some %s\"\n"
    "  vars: [x]\n",
    "owl-quoted-in-logical-axioms": HEAD + X + "logical_axioms:\n  - axiom_type: subClassOf\n"
    "    text: \"'part_of' some %s\"\n    vars: [x]\n",
    "owl-quoted-in-a-clause": HEAD + X + "subClassOf:\n  multi_clause:\n    sep: \" and \"\n"
    "    clauses:\n      - text: \"'part_of' some %s\"\n        vars: [x]\n",
    "owl-quoted-in-a-sub-clause": HEAD + X + "subClassOf:\n  multi_clause:\n    clauses:\n"
    "      - text: \"%s\"\n        vars: [x]\n        sub_clauses:\n          - clauses:\n"
    "              - text: \"'organ'\"\n                vars: []\n",
    "owl-quoted-in-its-annotation": HEAD + X + C + "subClassOf:\n  text: \"%s\"\n  vars: [x]\n"
    "  annotations:\n    - annotationProperty: c\n      text: \"'organ' %s\"\n      vars: [x]\n",
    "owl-quoted-in-a-name": HEAD + X + "name:\n  text: \"'organ' of %s\"\n  vars: [x]\n",
    "owl-quoted-with-a-space": HEAD + "classes:\n  anatomical entity: UBERON:0001062\n"
    "relations:\n  part of: BFO:0000050\nvars:\n  x: \"'anatomical entity'\"\n"
    "subClassOf:\n  text: \"'part of' some 'anatomical entity'\"\n  vars: []\n",
    "owl-quoted-one-of-two-undeclared": HEAD + "classes:\n  a: X:1\n" + X
    + "subClassOf:\n  text: \"'a' and 'b' and %s\"\n  vars: [x]\n",
    "owl-quoted-case-differs": HEAD + X + "classes:\n  Organ: X:1\n"
    "subClassOf:\n  text: \"'organ' and %s\"\n  vars: [x]\n",
    "owl-quoted-adjacent": HEAD + X + "classes:\n  a: X:1\n  b: X:2\n"
    "subClassOf:\n  text: \"'a''b' and %s\"\n  vars: [x]\n",
    "owl-quoted-empty": HEAD + X + "subClassOf:\n  text: \"'' and %s\"\n  vars: [x]\n",
    "owl-quoted-empty-then-a-name": HEAD + X + "classes:\n  a: X:1\n"
    "subClassOf:\n  text: \"'' and 'a' and %s\"\n  vars: [x]\n",
    "owl-quoted-slot": HEAD + X + "subClassOf:\n  text: \"'%s'\"\n  vars: [x]\n",
    "owl-odd-quotes": HEAD + "classes:\n  a: X:1\n" + X
    + "subClassOf:\n  text: \"'a' and 'b and %s\"\n  vars: [x]\n",
    "owl-odd-quotes-in-a-name": HEAD + X + "name:\n  text: \"%s's part\"\n  vars: [x]\n",
    "owl-odd-quotes-in-a-def": HEAD + X + "def:\n  text: \"A thing's %s.\"\n  vars: [x]\n",
    "owl-odd-quotes-in-an-annotation": HEAD + X + C + "annotations:\n"
    "  - annotationProperty: c\n    text: \"%s's note\"\n    vars: [x]\n",
    "owl-odd-quotes-in-its-annotation": HEAD + X + C + "subClassOf:\n  text: \"%s\"\n"
    "  vars: [x]\n  annotations:\n    - annotationProperty: c\n      text: \"%s's note\"\n"
    "      vars: [x]\n",
    # annotations, and the properties they name
    "annotations-value": HEAD + C + "annotations:\n  - annotationProperty: c\n    value: \"fixed\"\n",
    "annotations-iri-var": HEAD + X + "annotationProperties:\n  s: rdfs:seeAlso\n"
    "annotations:\n  - annotationProperty: s\n    var: x\n",
    "annotations-two-shapes": HEAD + C + "annotations:\n  - annotationProperty: c\n"
    "    value: \"fixed\"\n    text: \"t\"\n",
    "annotations-property-undeclared": HEAD + "annotations:\n  - annotationProperty: c\n"
    "    value: \"fixed\"\n",
    "annotations-property-undeclared-text": HEAD + X + C + "annotations:\n"
    "  - annotationProperty: nothere\n    text: \"%s\"\n    vars: [x]\n",
    "annotations-property-undeclared-iri": HEAD + X + C + "annotations:\n"
    "  - annotationProperty: nothere\n    var: x\n",
    "annotations-property-a-class": HEAD + "classes:\n  c: X:1\n"
    "annotations:\n  - annotationProperty: c\n    value: \"v\"\n",
    "annotations-nested-property-undeclared": HEAD + X + C + "annotations:\n"
    "  - annotationProperty: c\n    text: \"%s\"\n    vars: [x]\n    annotations:\n"
    "      - annotationProperty: nothere\n        value: w\n",
    "annotations-under-a-value": HEAD + X + C + "annotations:\n  - annotationProperty: c\n"
    "    value: v\n    annotations:\n      - annotationProperty: nothere\n        value: w\n",
    "annotations-multi-clause-only": HEAD + X + C + "annotations:\n  - multi_clause:\n"
    "      clauses:\n        - text: \"%s\"\n          vars: [x]\n",
    "annotations-in-a-block-property-undeclared": HEAD + X + "name:\n  text: \"%s\"\n"
    "  vars: [x]\n  annotations:\n    - annotationProperty: c\n      value: \"v\"\n",
    "annotations-in-an-owl-block-property-undeclared": HEAD + X + "subClassOf:\n"
    "  text: \"%s\"\n  vars: [x]\n  annotations:\n    - annotationProperty: c\n"
    "      value: \"v\"\n",
    # YAML as the checker reads it
    "yaml-not-yaml": HEAD + X + "name: [\n",
    "yaml-empty": "",
    "yaml-a-list": "- pattern_name: x\n",
    "yaml-tab": HEAD + "classes:\n\tthing: owl:Thing\n",
    "yaml-anchor": HEAD + "vars:\n  x: &r \"'thing'\"\n  y: *r\nclasses:\n  thing: owl:Thing\n",
    "yaml-merge-key": HEAD + "classes:\n  <<: {a: X:1}\n  b: X:2\n",
    "yaml-duplicate-key": HEAD + "description: one\ndescription: two\n",
    "yaml-two-documents": HEAD + "---\n" + HEAD,
    "yaml-quoted-keys": "\"pattern_name\": x\n'pattern_iri': y\n",
    # a colon in a word between [ ] or { }: YAML 1.2 keeps it in the word, 1.1 stops there
    "yaml-flow-curies": HEAD + "classes: {organ: UBERON:0000062, cell: CL:0000000}\n"
    "relations: {part_of: BFO:0000050}\nvars:\n  x: \"'organ'\"\n"
    "subClassOf:\n  text: \"'part_of' some %s\"\n  vars: [x]\n",
    "yaml-flow-url": HEAD + "contributors: [https://orcid.org/0000-0002-1825-0097]\n",
    "yaml-flow-words-with-a-colon": HEAD + "tags: [a:b c, d:e]\n",
    "yaml-flow-quote-and-colon": HEAD + "tags: [it's:here]\n",
    "yaml-flow-colon-then-space": HEAD + "tags: [a: b]\n",
    "yaml-flow-colon-at-the-end": HEAD + "tags: [a:]\n",
    "yaml-flow-time": HEAD + "examples: [2001-12-14 21:59:43]\n",
    "yaml-yes": HEAD + "description: yes\n",
    "yaml-on": HEAD + "description: on\n",
    "yaml-true": HEAD + "description: True\n",
    "yaml-null": HEAD + "description:\n",
    "yaml-tilde": HEAD + "description: ~\n",
    "yaml-number": HEAD + "description: 1.5\n",
    "yaml-hex": HEAD + "description: 0x10\n",
    "yaml-underscores": HEAD + "description: 1_000\n",
    "yaml-leading-zero": HEAD + "description: 0777\n",
    "yaml-inf": HEAD + "description: .inf\n",
    "yaml-sexagesimal": HEAD + "description: 1:30\n",
    "yaml-date": HEAD + "description: 2020-01-01\n",
    "yaml-time": HEAD + "description: 2001-12-14t21:59:43.10-05:00\n",
    "yaml-str-tag": HEAD + "description: !!str 2020-01-01\n",
    "yaml-byte-order-mark": "\ufeff" + HEAD,
    "yaml-crlf": (HEAD + X + "name:\n  text: \"%s\"\n  vars: [x]\n").replace("\n", "\r\n"),
    "yaml-latin-1": (HEAD + "description: \"caf\xe9\"\n").encode("latin-1"),
}

# Folders, as ODK's recipe gives one: path under check/ -> what is in it (name -> text)
FOLDERS = {
    "folder-kept": {"seed-example.yaml": SEED, "axioms.yaml": AXIOMS, "gci.yml": GCI,
                    "external.txt": ""},
    "folder-one-broken": {"seed-example.yaml": SEED, "undeclared.yaml": UNDECLARED},
    "folder-yml-broken": {"seed-example.yaml": SEED, "undeclared.yml": UNDECLARED},
    "folder-without-patterns": {"README.txt": "No patterns here yet.\n"},
    "folder-not-searched-below": {"fine.yaml": HEAD, "inner/broken.yaml": HEAD + "colour: red\n"},
    "folder-other-files": {"fine.yaml": HEAD, "notes.txt": HEAD + "colour: red\n",
                           "data.tsv": HEAD + "colour: red\n", "broken.YAML": HEAD + "colour: red\n",
                           "broken.json": HEAD + "colour: red\n"},
    "folder-with-a-yaml-folder": {"fine.yaml": HEAD, "a-folder.yaml/README.txt": "a folder\n"},
}

# Files given by themselves that are not .yaml or .yml
OTHERS = {
    "file-not-yaml-broken.txt": HEAD + "colour: red\n",
    "file-not-yaml-fine.txt": HEAD,
}

# What is asked of the checker: every case, each folder with a slash, one without, and
# a folder and a file that are not there.
INPUTS = (["%s.yaml" % name for name in PATTERNS] + sorted(OTHERS)
          + ["%s/" % name for name in FOLDERS]
          + ["folder-kept", "missing/", "missing.yaml"])

DRIVER = r"""#!/bin/sh
# dosdp validate -i on each input under /work/check, as ODK's test runs it.
while IFS= read -r input; do
  dosdp validate -i "/work/check/$input" > /dev/null 2>&1
  echo "$input $?"
done < /work/inputs.txt > /work/verdicts.txt
echo "ODK_VERSION=$ODK_VERSION" > /work/versions.txt
pip show dosdp 2>/dev/null | sed -n 's/^Version: /dosdp /p' >> /work/versions.txt
"""


def data(text):
    return text if isinstance(text, bytes) else text.encode("utf-8")


def lay_out(check):
    for name, text in PATTERNS.items():
        odkimage.write_bytes(os.path.join(check, name + ".yaml"), data(text))
    for name, text in OTHERS.items():
        odkimage.write_bytes(os.path.join(check, name), data(text))
    for folder, files in FOLDERS.items():
        for name, text in files.items():
            odkimage.write_bytes(os.path.join(check, folder, *name.split("/")), data(text))


def run(tool, image, resources, work):
    """Makes dosdp/check/ and dosdp/verdicts.txt; returns (said, versions)."""
    if os.path.isdir(work):
        shutil.rmtree(work)
    lay_out(os.path.join(work, "check"))
    odkimage.write_text(os.path.join(work, "inputs.txt"), "".join(i + "\n" for i in INPUTS))
    odkimage.write_text(os.path.join(work, "driver.sh"), DRIVER)
    odkimage.in_image(tool, image, ["sh", "/work/driver.sh"], mounts=[(work, "/work", "rw")])

    root = os.path.join(resources, "dosdp")
    if os.path.isdir(root):
        shutil.rmtree(root)
    lay_out(os.path.join(root, "check"))
    with open(os.path.join(work, "verdicts.txt"), "rb") as made:
        verdicts = made.read().decode("utf-8").replace("\r\n", "\n")
    lines = [line for line in verdicts.split("\n") if line]
    if [line.rsplit(" ", 1)[0] for line in lines] != INPUTS:
        raise SystemExit("the checker's verdicts are not one per input:\n" + verdicts)
    odkimage.write_text(os.path.join(root, "verdicts.txt"), "".join(l + "\n" for l in lines))
    failed = sum(1 for line in lines if not line.endswith(" 0"))
    said = ["%d inputs, %d refused" % (len(lines), failed)]
    with open(os.path.join(work, "versions.txt"), "rb") as made:
        versions = made.read().decode("utf-8").splitlines()
    return said, versions
