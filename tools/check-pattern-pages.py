"""Checks the BUILT pattern pages, not the sources that were meant to produce them.

WHY THIS EXISTS. Everything a pattern page promises is invisible when it breaks. The level of
detail is set by a script; if that script 404s, the graphs open at level 6 and the page still
says, in prose, that they open at 3. A download link that points at a file mkdocs did not copy
is a link that looks right until somebody clicks it. ontoink's fence renderer catches every
exception and bakes a <div class="ov-error"> into the page, so a pattern that fails to render
leaves a green --strict build with a red traceback box in it.

All three are failures of the output, so they are checked in the output:

    python -I tools/check-pattern-pages.py              # after mkdocs build
    python -I tools/check-pattern-pages.py --site site  # the default

THE ONE THAT CAUGHT SOMETHING. docs/assets/pattern-lod.js was first emitted as
`<script src="../assets/pattern-lod.js">` into each generated page. use_directory_urls defaults
to true, so a pattern page is served from patterns/<id>/index.html and that path resolves to
patterns/assets/pattern-lod.js - which does not exist, on all 163 pages. mkdocs rewrites
relative links written in Markdown; a src attribute in raw HTML it copies out verbatim. The fix
was to list the file in mkdocs.yml's extra_javascript and let mkdocs compute the prefix, and
this check is here so that the next way of getting it wrong fails the build instead of shipping.

WHAT IT DOES NOT CHECK. Whether setLodLevel actually took - that needs a browser. It checks that
the script the page asks for is a file that exists, which is the part that was wrong.
"""
import argparse
import io
import os
import posixpath
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

LOD_SCRIPT = "assets/pattern-lod.js"

SCRIPT_SRC = re.compile(r"<script[^>]+src=[\"']([^\"']+)[\"']", re.I)
HREF = re.compile(r"href=[\"']([^\"']+[.]ttl)[\"']", re.I)
GRAPH = re.compile(r"data-ontoink-graph|class=[\"'][^\"']*ontoink-container", re.I)
FENCE_ERROR = re.compile(r"<div class=[\"']ov-error", re.I)


def pages_under(site):
    """Every built page below site/patterns, as (url path, file path)."""
    found = []
    base = os.path.join(site, "patterns")
    for folder, _subfolders, files in os.walk(base):
        for name in files:
            if not name.endswith(".html"):
                continue
            path = os.path.join(folder, name)
            url = os.path.relpath(path, site).replace(os.sep, "/")
            found.append((url, path))
    return sorted(found)


def resolve(url_of_page, reference):
    """Where a browser would look for `reference`, as a site-relative path.

    None for anything that does not resolve inside the site: an absolute URL, a protocol-relative
    one, or a path that climbs out of the site root.
    """
    if re.match(r"^[a-z][a-z0-9+.-]*:", reference, re.I) or reference.startswith("//"):
        return None
    if reference.startswith("#"):
        return None
    reference = reference.split("#", 1)[0].split("?", 1)[0]
    if not reference:
        return None
    if reference.startswith("/"):
        # Root-relative, which under a project site is prefixed with the repository name.
        return reference.lstrip("/")
    here = posixpath.dirname(url_of_page)
    joined = posixpath.normpath(posixpath.join(here, reference))
    if joined.startswith(".."):
        return None
    return joined


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--site", default=os.path.join(ROOT, "site"),
                        help="the built site directory (default: site)")
    args = parser.parse_args()

    site = args.site if os.path.isabs(args.site) else os.path.join(ROOT, args.site)
    if not os.path.isdir(os.path.join(site, "patterns")):
        print("no built pages at " + os.path.join(site, "patterns")
              + " - run mkdocs build first", file=sys.stderr)
        return 2

    pages = pages_under(site)
    problems = []
    with_graph = 0
    examples = 0
    downloads = 0

    for url, path in pages:
        html = io.open(path, encoding="utf-8", errors="replace").read()
        graphs = len(GRAPH.findall(html))
        is_pattern = graphs > 0
        if is_pattern:
            with_graph += 1

        # 1. The level-of-detail script has to be a file that exists.
        wanted = None
        for src in SCRIPT_SRC.findall(html):
            where = resolve(url, src)
            if where and where.endswith(LOD_SCRIPT):
                wanted = where
                break
        if wanted is None:
            problems.append((url, "asks for no " + LOD_SCRIPT
                             + " - the graphs would open at level 6"))
        elif not os.path.isfile(os.path.join(site, wanted.replace("/", os.sep))):
            problems.append((url, "points at " + wanted + ", which mkdocs did not write"))

        # 2. Every .ttl a page offers has to be downloadable.
        for href in HREF.findall(html):
            where = resolve(url, href)
            if where is None:
                continue
            downloads += 1
            if not os.path.isfile(os.path.join(site, where.replace("/", os.sep))):
                problems.append((url, "offers " + href + ", which is not in the site"))
            elif where.endswith("-example.ttl"):
                examples += 1

        # 3. No fence rendered an error into the page.
        if FENCE_ERROR.search(html):
            problems.append((url, "has an ontoink render error baked into it"))

    print("pages under patterns/:     %d" % len(pages))
    print("of those, with a graph:    %d" % with_graph)
    print("downloadable .ttl links:   %d  (%d of them examples)" % (downloads, examples))
    print("problems:                  %d" % len(problems))
    if problems:
        print()
        print("These built pages do not deliver what they say:", file=sys.stderr)
        for url, why in problems[:40]:
            print("    %-46s %s" % (url, why), file=sys.stderr)
        if len(problems) > 40:
            print("    ... and %d more" % (len(problems) - 40), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
