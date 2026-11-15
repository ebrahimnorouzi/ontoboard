#!/usr/bin/env python3
"""Dumps how Jinja2 itself cuts ODK's templates, as the fixture OntoBoard's lexer is held to.

For every *.jinja2 among the bundled templates: Jinja2's own token stream, reduced to what a
renderer needs - the text chunks (with every `-` marker, comment and raw block already applied,
line endings normalised and the last newline dropped, as Jinja2 does), and between them each
`{{ ... }}` and `{% ... %}` with its source text and line number.

Needs Jinja2 3.1.6 on the host - ODK v1.6.1's own pin (constraints.txt) - and refuses any other
version: the point is to record what ODK's renderer does, not what a newer Jinja2 would.

Usage: python lexer_fixture.py <templates directory> <output directory>
Writes <output>/<template path with / as __>.json: a JSON list of [kind, text, line] with kind
"data", "variable" or "block".
"""
import json
import os
import sys

WANTED = "3.1.6"


def reduced(source, name):
    import jinja2
    env = jinja2.Environment()
    out = []
    inside = None  # (kind, line, parts) while between a begin and an end token
    for line, kind, value in env.lex(source, name):
        if kind in ("variable_begin", "block_begin"):
            inside = (kind[:-len("_begin")], line, [])
        elif kind in ("variable_end", "block_end"):
            out.append([inside[0], "".join(inside[2]).strip(), inside[1]])
            inside = None
        elif inside is not None:
            inside[2].append(value)
        elif kind == "data":
            if out and out[-1][0] == "data":
                out[-1][1] += value  # two chunks with only a comment or a raw marker between them
            else:
                out.append(["data", value, line])
        # comment_begin, comment, comment_end, raw_begin, raw_end: nothing is rendered for them
    return [entry for entry in out if not (entry[0] == "data" and entry[1] == "")]


def main(templates, output):
    import jinja2
    if jinja2.__version__ != WANTED:
        sys.exit("Jinja2 %s is installed; ODK v1.6.1 renders with %s (pip install jinja2==%s)."
                 % (jinja2.__version__, WANTED, WANTED))
    os.makedirs(output, exist_ok=True)
    written = 0
    for root, _, files in os.walk(templates):
        for name in sorted(files):
            if not name.endswith(".jinja2"):
                continue
            path = os.path.join(root, name)
            relative = os.path.relpath(path, templates).replace(os.sep, "/")
            # As odk.py reads a template: text mode, UTF-8 (the image runs under C.UTF-8).
            with open(path, encoding="utf-8") as handle:
                source = handle.read()
            target = os.path.join(output, relative.replace("/", "__") + ".json")
            with open(target, "wb") as handle:
                handle.write(json.dumps(reduced(source, relative), ensure_ascii=False,
                                        indent=0).encode("utf-8"))
                handle.write(b"\n")
            written += 1
    print("%d templates lexed by Jinja2 %s into %s" % (written, jinja2.__version__, output))
    return written


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
