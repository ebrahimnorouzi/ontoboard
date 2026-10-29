<!--
  Written for: somebody reading a pattern page and wondering what they are looking at, and
  somebody about to add or improve a worked example.
-->

# The patterns, their graphs and their examples

Every pattern page in the [library](patterns/index.md) now shows two graphs and offers two
downloads: the pattern, which is a schema, and a worked example, which is data that instantiates
it. This page says how they are produced, why the graph opens the way it does, and what is known
to be wrong with some of the patterns.

## Why the graph opens with the literals hidden

A pattern's graph opens at **level of detail 3**. The levels are cumulative:

| | Shows | |
|---|---|---|
| L0 | classes only, no edges | |
| L1 | + the subclass hierarchy | |
| L2 | + individuals, property nodes, object properties | |
| **L3** | **+ OWL restrictions and SHACL constraints** | **the default here** |
| L4 | + datatype properties and literal nodes | |
| L5 | the same as L4 — nothing has floor 5 | |
| L6 | + inferred triples | |

L3 is the highest level that still hides every literal while keeping all of the structure a
pattern is about. The difference is not cosmetic: `eep.ttl`, the largest pattern, is 772 nodes
and 1,340 edges at L6, of which **558 nodes are literals** — annotation text, version strings,
cardinality numbers. At L3 it is 214 nodes. Before this, the page opened at L6 and a reader met a
wall of text nodes with the model buried inside it.

Raise it with the **LOD** control in the graph's own toolbar whenever you want the annotation
values or the reasoner's additions. Your choice sticks; nothing puts it back.

### It is set by a script, and that is not a preference

It should be one line in the fence — and ontoink even has the hook, with a comment promising
`lod: N`. The hook is dead: its JavaScript reads `data.lod_default`, nothing on the Python side
ever writes it, the fence reader accepts a fixed list of keys and silently ignores the rest, and
the plugin's only mkdocs options are `report`, `quality_gate` and `strict_quality`. Measured on
ontoink 0.7.9 and 0.7.7: `lod:`, `lod_default:`, `lod_level:`, `detail:` and `level:` all produce
a payload identical to passing nothing, and the toolbar still opens on L6.

So [docs/assets/pattern-lod.js](assets/pattern-lod.js) calls the exported API instead, on `load`
rather than `DOMContentLoaded` because ontoink mounts from the latter and the plugin injects its
own script last. Wiring `lod:` through properly is a change to ontoink, which is a separate
repository; when it lands, this script can go.

It is loaded from `mkdocs.yml`'s `extra_javascript`, and that is the second thing this took to
get right. The first version wrote `<script src="../assets/pattern-lod.js">` into each generated
page, which cannot resolve: `use_directory_urls` defaults to true, the page is served from
`patterns/<id>/index.html`, and that path means `patterns/assets/pattern-lod.js`. mkdocs rewrites
relative links written in Markdown and copies a `src` attribute out verbatim, so every graph
would have opened at L6 under a page saying it opened at L3, and the build would have been green.
`tools/check-pattern-pages.py` now resolves every script and every `.ttl` link the **built** pages
ask for against the files mkdocs wrote, and CI runs it after the build.

## Where the examples come from

An example is `patterns/<id>/example.ttl`, and most of them are derived rather than written:

```
python -I tools/make-pattern-examples.py        # write what is missing
python -I tools/check-pattern-examples.py       # the gate; CI runs this
```

The generator reads the pattern's **own** `rdfs:domain` and `rdfs:range` and instantiates them,
which is the point: a property cannot come out backwards if the direction is taken from the file
rather than from a person's memory of it. It follows `owl:imports` into the rest of the library,
because 22 patterns keep their properties somewhere else — `agentrole` declares three classes and
imports `objectrole`, which is where `isRoleOf` actually lives.

It will not invent an axiom. A pattern that says nothing about how its classes relate gets no
edges, and that is reported rather than papered over.

### The checker is the gate, and it has teeth

`tools/check-pattern-examples.py` runs in CI before the site is built. For every example it
checks that it parses; that every term it uses is one the pattern declares or imports; that every
`rdfs:domain` and `rdfs:range` holds, **including** the union, intersection and complement class
expressions that BFO uses for most of its relations; that `owl:FunctionalProperty`,
`owl:IrreflexiveProperty` and `owl:AsymmetricProperty` are respected; that only one direction of
an inverse pair is asserted, since the other is inferred; and that the graph is connected.

It has caught, in the generator alone: two files that did not parse, 119 examples full of
unconnected individuals, a string written where an `xsd:integer` was declared, and a self-loop on
an asymmetric property. The curation pass added two more: `aquaticresourceobservation` asserted
the plain string `"a value"` against two properties declared `xsd:int`, and `climaticzone` held
an untyped string against a declared `xsd:gYear`. It separates **a wrong example** from **a pattern too thin to have one**,
and only the first fails the build.

### Adding a curated example

A derived example is structurally right and says nothing about the domain — "an Agent", "a
Material Entity". A curated one uses a real case, and is much better:

1. Edit `patterns/<id>/example.ttl`.
2. **Delete the four comment lines that begin `# GENERATED by tools/make-pattern-examples.py`**
   and say instead, in two or three lines, what the example shows. That marker is the only thing
   telling the generator the file is still generic; without it your work survives, and
   `--force` is then needed to lose it.
3. Keep the shape the pattern dictates. Use concrete names and readable `rdfs:label`s.
4. Run `python -I tools/check-pattern-examples.py --only <id>` and keep it green.

**Every example in the library is curated now.** All 160 of them were rewritten with real domain content in 1.128.0 - a raised hand counting as a vote at a faculty board, the lattice parameter of nickel, the scope screening of a journal submission - replacing individuals named after their own class and labelled "an Attribute". Two patterns have no example and will not get one: `template-instance` declares no classes or properties, and every example of `spatial-graph-adapter-sga` is inconsistent because the pattern is.

## What the audit found, and what is still wrong

Measured over all the patterns in the library. It held 163 when this audit was taken and holds
162 now: `contentops` was removed in 1.128.0, having never been a pattern at all but the text of
the ODP wiki's index page with another pattern's ontology attached. The extra entries under
`patterns/` are `index.tsv` and `soundness.tsv`.

| Finding | At harvest | Now |
|---|---|---|
| Patterns declaring **no object property with both a domain and a range** | 63 | **39** |
| Patterns with no example at all | 163 | 1 |
| Patterns whose IRIs are a **local file path** rather than a real IRI | 3 | 3 |
| Patterns whose `.owl` and `.ttl` disagree | 68 | 68 |

A pattern in the first row is a list of classes with no edges between them: a term list, not a
pattern. Nothing can be instantiated as a shape and the graph draws a row of unconnected dots.
Twenty-four were made instantiable by restoring, from upstream, relations the harvest had
dropped: all fifteen NFDIcore patterns, six of the eight MWO ones, and three of PMDco's.

The cause is worth knowing, because it is not carelessness. The extractor keeps an axiom only
when **every** term in it is already in the pattern, so a domain or range naming a class just
outside the pattern's own term set is silently discarded. PMDco states a domain or range for 71
of its 90 property declarations and the harvested files had lost at least one on 56 of them.

> Both counts here are measured **on the pattern**, which is the only way they are comparable.
> An earlier version of the checker asked the question of the *example* instead - "does any
> individual stand alone?" - and a curator who connects everything with properties that have no
> declared domain or range makes a thin pattern look sound. It undercounted, and the figures in
> an earlier draft of this page came from it. The rule now is: the whole library compared against
> its own form at HEAD - 163 patterns when that was measured - 63 thin then, 39 thin now, 24 made
> connectable.

The last row is left alone deliberately. The Turtle is not a superset of the OWL: `bag`'s `.owl`
carries an `owl:cardinality` restriction its `.ttl` does not, and the two assert opposite
directions of the same inverse pair. Rewriting every `.owl` from its `.ttl` would delete those
axioms. `tools/sync-pattern-owl.py` therefore syncs only the patterns the current tree has
edited, and reports the rest.

It is 68 and not 70. Two more *appeared* to disagree and did not: `airline` and `airline-owl`
have no ontology IRI, so each of their terms is named after the file it was read from, and the
two files spell that path differently - `file:/C:/…/protege-plugin/../patterns/…` against
`file:///C:/…/patterns/…`. 32 triples each side, 17 differing on each, every one of them the same
triple twice. The check normalises `file:` IRIs before comparing, because the alternative is to
pick a spelling for somebody else's term IRIs - and these should not be local paths at all,
which is the third row of the table.

Fixed along the way, each found by inventorying the library rather than by reading a file:

- **`airline`, `airline-owl`, `roledependentnames`** asserted `dbpedia:Airline rdfs:subClassOf`
  six classes called `Error1` … `Error6`, in the namespace
  `http://org.semanticweb.owlapi/error#`. The OWL API substitutes those placeholders for
  something it cannot read and then serialises them as if they were real, which inflated
  `airline` to 8 classes where its own metadata says 2. Removed, with an editorial note on the
  affected class recording that axioms were lost at harvest and naming the source — because the
  honest record is "we do not know what was here", not a silent gap.
- **`transportpattern`** declared both `TransportEvent` and `transportEvent` and pointed
  `referenceFrame`'s domain *and* range at the lower-case one. The serialiser had added a bare
  declaration for the typo, which is exactly what hid it.
- **BFO's own definition of `preceded by`** reads "b preceded by c =Def b precedes c", which
  reverses the relation. It is wrong in BFO 2020 release-2024-01-29, in `bfo-core.owl`, in both
  profiles and in current master, and it contradicts BFO's own `owl:inverseOf` and its Common
  Logic axiom `tib-1`. Corrected here, with the correction recorded as an erratum in
  `tools/extract-bfo-patterns.py` so that re-running the extractor cannot silently revert it —
  and so that the day BFO fixes it upstream, the extractor says the erratum is no longer needed.
- **Metadata disagrees with content.** `metadata.json` attributes 8 patterns to MWO; only one is
  actually built mostly from MWO terms. The rest are mostly BFO or NFDIcore. The `source` field
  records where a pattern was harvested from, which is not the same as which ontology it models.
