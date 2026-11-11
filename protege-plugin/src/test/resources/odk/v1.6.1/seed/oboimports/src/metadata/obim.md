---
layout: ontology_detail
id: obim
title: OBO imports test ontology
jobs:
  - id: https://travis-ci.org/example-org/obim
    type: travis-ci
build:
  checkout: git clone https://github.com/example-org/obim.git
  system: git
  path: "."
contact:
  email: 
  label: 
  github: 
description: OBO imports test ontology is an ontology...
domain: stuff
homepage: https://github.com/example-org/obim
products:
  - id: obim.owl
    name: "OBO imports test ontology main release in OWL format"
  - id: obim.obo
    name: "OBO imports test ontology additional release in OBO format"
  - id: obim.json
    name: "OBO imports test ontology additional release in OBOJSon format"
  - id: obim/obim-base.owl
    name: "OBO imports test ontology main release in OWL format"
  - id: obim/obim-base.obo
    name: "OBO imports test ontology additional release in OBO format"
  - id: obim/obim-base.json
    name: "OBO imports test ontology additional release in OBOJSon format"
dependencies:
- id: ro
- id: iao
- id: bfo
- id: pato
tracker: https://github.com/example-org/obim/issues
license:
  url: http://creativecommons.org/licenses/by/3.0/
  label: CC-BY
activity_status: active
---

Enter a detailed description of your ontology here. You can use arbitrary markdown and HTML.
You can also embed images too.

