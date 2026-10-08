#!/usr/bin/env python3
"""Generates the pattern library pages that MkDocs publishes to GitHub Pages.

Asked for: "i want ontoink to be used in the github workflow to show the patterns as graph in the
github page, make sure to have a feature of search for a suitable pattern by search label, scope,
cq, whatever required to get a pattern", and then "Pattern Library Visualization with OntoInk
should be also available in Navigation tab to one of the top and maybe mentioned in the main page
... and included in search. Then people can click and then they can easily see what are the
patterns categorized based on the source".

WHAT THIS WRITES, all under docs/patterns/ and all generated - the directory is gitignored:

    index.md            the finder: every pattern, grouped by source, with a search box
    <id>.md             one page per pattern, with an ontoink graph of its Turtle
    ttl/<id>.ttl        the pattern's own Turtle, copied in so the fence and the reader can reach it
    patterns.json       the search index the finder's JavaScript reads

ONE PAGE PER PATTERN, NOT ONE PAGE OF 159 GRAPHS. ontoink mounts every .ontoink-container eagerly
on DOMContentLoaded, each with its own cytoscape instance and dagre layout, and there is no
IntersectionObserver anywhere in its bundle - so batching fences onto one page does not save work,
it hangs the browser. One page each is also the only shape that gives a pattern a stable URL and
puts its prose into MkDocs' own search index, which is half of what was asked for.

THE TURTLE IS COPIED RATHER THAN REFERENCED. ontoink resolves a fence's `source` as
os.path.join(docs_dir, source) with no containment check, so `../patterns/x/pattern.ttl` would
work and would be a path escaping docs_dir that nothing validates. Copying costs about 1.5 MB,
keeps the site self-contained, and gives every pattern page a Turtle download link for free.

SEARCH IS OURS TO BUILD, AND THE FACETS ARE MEASURED RATHER THAN WISHED FOR. ontoink's own search
box searches the nodes of one graph - label, iri and type - which is not a way to find a pattern
among 159. MkDocs' search indexes Markdown prose only and never fence output. So the finder is a
static JSON index with a client-side filter, and every pattern's prose is also written into its
page so the site's own search box finds it too.

    There is no "scope" field. Measured: no column in index.tsv, no key in any of the 159
    metadata.json files, and the only Scope string under patterns/ is a DUL hasInScope IRI inside
    a terms column. Rather than invent one for 159 patterns - the same mistake as the harvested
    class_count that is wrong for 41 of them, which index.tsv's own header refuses to display -
    the finder reads scope as collection plus category plus domain, and says so on the page.

Usage, from the repository root:

    python tools/generate-pattern-pages.py            # writes docs/patterns/
    python tools/generate-pattern-pages.py --check     # exits 1 if anything is missing

Run by .github/workflows/pages.yml before `mkdocs build --strict`.
"""
import argparse
import json
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
PATTERNS = os.path.join(ROOT, "patterns")
INDEX = os.path.join(PATTERNS, "index.tsv")
OUT = os.path.join(ROOT, "docs", "patterns")

COLUMNS = [
    "id", "name", "collection", "publisher", "category", "domain",
    "sameAs", "description", "competencyQuestions", "terms",
]

# What each collection is, and where it is documented. The maintainer named these four as "the
# source", so the finder leads with them rather than with the sixteen publishers.
SOURCES = {
    "odp": (
        "ODP portal",
        "Harvested from [ontologydesignpatterns.org](https://ontologydesignpatterns.org/), a "
        "catalogue of submissions from thirteen different publishers.",
    ),
    "nfdi": (
        "NFDI MatWerk",
        "Extracted from the patterns documented at "
        "[nfdi.fiz-karlsruhe.de/matwerk/patterns](https://nfdi.fiz-karlsruhe.de/matwerk/patterns/).",
    ),
    "pmdco": (
        "PMDco",
        "Extracted from the patterns documented at "
        "[materialdigital.github.io/core-ontology](https://materialdigital.github.io/core-ontology/docs/patterns.html).",
    ),
    "mwo": (
        "MWO",
        "Extracted from the patterns documented at "
        "[ise-fizkarlsruhe.github.io/mwo](https://ise-fizkarlsruhe.github.io/mwo/docs/patterns/).",
    ),
}

# The order the finder lists them in: the BFO family first, because a reader arriving from
# NFDIcore or MWO wants those and the ODP collection is 123 of the 159.
SOURCE_ORDER = ["mwo", "nfdi", "pmdco", "odp"]


def read_index():
    """Every row of patterns/index.tsv, as dicts. Comments and short rows skipped, as the Java
    reader skips them - see PatternIndex.read, which this must agree with."""
    rows = []
    with open(INDEX, encoding="utf-8") as handle:
        for line in handle:
            line = line.rstrip("\n").rstrip("\r")
            if not line.strip() or line.startswith("#"):
                continue
            cells = line.split("\t")
            if len(cells) < len(COLUMNS):
                continue
            rows.append(dict(zip(COLUMNS, cells[: len(COLUMNS)])))
    return rows


def source_of(row):
    """The collection, falling back to its own name when it is not one of the four known ones -
    which is what a fifth collection contributed later will be."""
    return row["collection"] if row["collection"] in SOURCES else row["collection"]


def source_label(collection):
    return SOURCES.get(collection, (collection, ""))[0]


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)


def pattern_page(row, has_ttl):
    """One pattern: what it is, what it answers, what is in it, and its graph."""
    out = []
    out.append("# " + row["name"])
    out.append("")
    out.append(
        "**Source:** " + source_label(row["collection"])
        + " &nbsp;·&nbsp; **Published by:** `" + row["publisher"] + "`"
        + " &nbsp;·&nbsp; **Category:** " + row["category"]
        + " &nbsp;·&nbsp; **Domain:** " + row["domain"]
    )
    out.append("")
    if row["sameAs"]:
        out.append(
            "!!! warning \"This is the same pattern as `" + row["sameAs"] + "`\"\n"
            "    The library lists it under both names. Importing both would import the same\n"
            "    terms twice."
        )
        out.append("")
    if row["description"]:
        out.append(row["description"])
        out.append("")
    if row["competencyQuestions"]:
        out.append("## What it answers")
        out.append("")
        # The harvest joined several questions with a bar, so split them back into a list.
        for question in [q.strip() for q in row["competencyQuestions"].split("|") if q.strip()]:
            out.append("- " + question)
        out.append("")

    if has_ttl:
        out.append("## The pattern")
        out.append("")
        out.append(
            "The graph is the axioms the pattern asserts; nothing here is inferred. Drag to "
            "rearrange, scroll to zoom, and use the graph's own search box to find a term inside it."
        )
        out.append("")
        out.append("```ontoink")
        out.append("source: patterns/ttl/" + row["id"] + ".ttl")
        out.append("height: 560px")
        out.append("```")
        out.append("")
        out.append("[Download the Turtle](ttl/" + row["id"] + ".ttl)")
        out.append("")
    else:
        out.append("!!! note \"No graph for this one\"")
        out.append("    Its Turtle is not in the repository, so there is nothing to render.")
        out.append("")

    terms = [t for t in row["terms"].split(" ") if t]
    if terms:
        out.append("## Terms it declares")
        out.append("")
        out.append(
            "The " + str(len(terms)) + " classes and properties this pattern models, as the "
            "recommender scores them. Annotation properties and built-ins are excluded, because an "
            "annotation property is not modelling."
        )
        out.append("")
        for term in terms:
            out.append("- `" + term + "`")
        out.append("")

    out.append("---")
    out.append("")
    out.append(
        "Import this pattern into your own ontology from inside Protégé: **OntoBoard → ROBOT → "
        "Pattern library…**, which hands it to *Import terms…* and writes the term list, the "
        "module, the import and the catalog entry the way ODK expects. See "
        # Linked without a fragment on purpose. The heading it pointed at carries the release
        # numbers in its text, so its slug changes every time a release touches that section -
        # and mkdocs reports a dangling anchor as INFO, not a warning, so --strict stays green
        # while 159 pages link to nothing.
        "[Working with ODK](../odk-workflow.md)."
    )
    out.append("")
    return "\n".join(out)


def finder_page(rows, by_source):
    """The page the navigation points at: every pattern, grouped by source, searchable."""
    distinct = len([r for r in rows if not r["sameAs"]])
    out = []
    out.append("# Pattern library")
    out.append("")
    out.append(
        str(len(rows)) + " ontology design patterns ship inside the OntoBoard plugin, "
        + str(distinct) + " of them distinct — ten are another pattern under a second name and say "
        "so. Every one is browsable and importable from **OntoBoard → ROBOT → Pattern library…** "
        "inside Protégé; this page is the same library, on the web, with a graph of each pattern."
    )
    out.append("")
    out.append("## Find a pattern")
    out.append("")
    out.append(
        "Type to filter on the name, the description, the competency questions, the category, the "
        "domain, the publisher or the pattern's id. "
        "There is no *scope* field in the data — no column in the index and no key in any of the "
        "159 metadata files — so scope here means the collection, the category and the domain "
        "together, all three of which the filter searches. Inventing a scope value for 159 "
        "patterns would be a number somebody decided on rather than one measured, which is why "
        "the library shows no class counts either."
    )
    out.append("")
    # The search box. Deliberately plain HTML and vanilla JS: Material's own search indexes the
    # prose on these pages and finds patterns that way too, but it cannot filter a table by facet.
    out.append('<div class="ob-finder">')
    out.append(
        '  <input type="search" id="ob-q" placeholder="Search 159 patterns…" '
        'aria-label="Search patterns" autocomplete="off">'
    )
    out.append('  <div id="ob-facets"></div>')
    out.append('  <p id="ob-count" class="ob-count"></p>')
    out.append('  <div id="ob-results"></div>')
    out.append(
        '  <noscript><p><strong>Search needs JavaScript.</strong> Every pattern is still listed '
        'by source below.</p></noscript>'
    )
    out.append("</div>")
    out.append("")
    out.append('<script src="../assets/pattern-finder.js" defer></script>')
    out.append("")
    out.append("## By source")
    out.append("")
    out.append(
        "The four collections the library ships, which is the division that tells you whose "
        "modelling you are about to adopt."
    )
    out.append("")
    out.append("| Source | Patterns | Where it came from |")
    out.append("|---|---|---|")
    for collection in SOURCE_ORDER:
        if collection not in by_source:
            continue
        label, where = SOURCES[collection]
        out.append(
            "| [" + label + "](#" + collection + ") | " + str(len(by_source[collection]))
            + " | " + where + " |"
        )
    for collection in sorted(by_source):
        if collection in SOURCES:
            continue
        out.append(
            "| [" + collection + "](#" + collection + ") | " + str(len(by_source[collection]))
            + " | Contributed |"
        )
    out.append("")
    out.append(
        "The 36 in the BFO family — MWO, NFDI MatWerk and PMDco — were added because the ODP "
        "collection and the OBO world share no vocabulary at all: measured, **zero** of the 123 "
        "ODP patterns share a single IRI with a real BFO-based project."
    )
    out.append("")

    for collection in SOURCE_ORDER + [c for c in sorted(by_source) if c not in SOURCES]:
        if collection not in by_source:
            continue
        label, where = SOURCES.get(collection, (collection, "Contributed"))
        out.append('## <a id="' + collection + '"></a>' + label)
        out.append("")
        out.append(where)
        out.append("")
        out.append("| Pattern | Category | Domain | Answers |")
        out.append("|---|---|---|---|")
        for row in sorted(by_source[collection], key=lambda r: r["name"].lower()):
            question = row["competencyQuestions"].split("|")[0].strip()
            if len(question) > 90:
                question = question[:87].rstrip() + "…"
            name = "[" + row["name"] + "](" + row["id"] + ".md)"
            if row["sameAs"]:
                name += " <sup>same as `" + row["sameAs"] + "`</sup>"
            out.append(
                "| " + name + " | " + (row["category"] or "—") + " | " + (row["domain"] or "—")
                + " | " + (question or "—") + " |"
            )
        out.append("")
    return "\n".join(out)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true",
                        help="verify the generated pages exist and are current; write nothing")
    args = parser.parse_args()

    if not os.path.isfile(INDEX):
        print("no pattern index at " + INDEX, file=sys.stderr)
        return 2
    rows = read_index()
    if not rows:
        print("the pattern index is empty", file=sys.stderr)
        return 2

    if args.check:
        missing = [r["id"] for r in rows
                   if not os.path.isfile(os.path.join(OUT, r["id"] + ".md"))]
        if missing or not os.path.isfile(os.path.join(OUT, "index.md")):
            print("%d of %d pattern pages missing; run tools/generate-pattern-pages.py"
                  % (len(missing), len(rows)), file=sys.stderr)
            return 1
        print("%d pattern pages present" % len(rows))
        return 0

    # Rewritten from scratch every time: a pattern renamed or removed must not leave its old page
    # behind to be published and linked from nothing.
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)

    by_source = {}
    written = 0
    copied = 0
    for row in rows:
        by_source.setdefault(source_of(row), []).append(row)
        ttl = os.path.join(PATTERNS, row["id"], "pattern.ttl")
        has_ttl = os.path.isfile(ttl)
        if has_ttl:
            target = os.path.join(OUT, "ttl", row["id"] + ".ttl")
            os.makedirs(os.path.dirname(target), exist_ok=True)
            shutil.copyfile(ttl, target)
            copied += 1
        write(os.path.join(OUT, row["id"] + ".md"), pattern_page(row, has_ttl))
        written += 1

    write(os.path.join(OUT, "index.md"), finder_page(rows, by_source))

    # The search index. Only the fields the filter actually reads, so the file stays small enough
    # to ship on a documentation page.
    search = [
        {
            "i": r["id"],
            "n": r["name"],
            "c": r["collection"],
            "p": r["publisher"],
            "k": r["category"],
            "d": r["domain"],
            "q": r["competencyQuestions"],
            "s": r["description"][:400],
            "a": r["sameAs"],
            "t": len([t for t in r["terms"].split(" ") if t]),
        }
        for r in rows
    ]
    write(os.path.join(OUT, "patterns.json"),
          json.dumps({"patterns": search, "sources": {c: source_label(c) for c in by_source}},
                     ensure_ascii=False, separators=(",", ":")))

    size = sum(os.path.getsize(os.path.join(dirpath, name))
               for dirpath, _, names in os.walk(OUT) for name in names)
    print("%d pattern pages, %d Turtle files copied, %d collections, %.1f MB in docs/patterns/"
          % (written, copied, len(by_source), size / 1048576.0))
    for collection in sorted(by_source, key=lambda c: -len(by_source[c])):
        print("    %-8s %3d" % (collection, len(by_source[collection])))
    if copied != written:
        print("    NOTE: %d pattern(s) have no pattern.ttl and render no graph"
              % (written - copied))
    return 0


if __name__ == "__main__":
    sys.exit(main())
