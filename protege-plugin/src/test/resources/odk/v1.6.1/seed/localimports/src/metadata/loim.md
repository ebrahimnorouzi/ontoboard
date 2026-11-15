---
layout: ontology_detail
id: loim
title: Local imports test ontology
jobs:
  - id: https://travis-ci.org/example-org/loim
    type: travis-ci
build:
  checkout: git clone https://github.com/example-org/loim.git
  system: git
  path: "."
contact:
  email: 
  label: 
  github: 
description: Local imports test ontology is an ontology...
domain: stuff
homepage: https://github.com/example-org/loim
products:
  - id: loim.owl
    name: "Local imports test ontology main release in OWL format"
  - id: loim.obo
    name: "Local imports test ontology additional release in OBO format"
  - id: loim.json
    name: "Local imports test ontology additional release in OBOJSon format"
  - id: loim/loim-base.owl
    name: "Local imports test ontology main release in OWL format"
  - id: loim/loim-base.obo
    name: "Local imports test ontology additional release in OBO format"
  - id: loim/loim-base.json
    name: "Local imports test ontology additional release in OBOJSon format"
dependencies:
- id: ro
- id: iao
- id: bfo
tracker: https://github.com/example-org/loim/issues
license:
  url: http://creativecommons.org/licenses/by/3.0/
  label: CC-BY
activity_status: active
---

Enter a detailed description of your ontology here. You can use arbitrary markdown and HTML.
You can also embed images too.

