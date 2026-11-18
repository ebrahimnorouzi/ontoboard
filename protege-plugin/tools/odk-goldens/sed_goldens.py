"""GNU sed's substitution in place (`sed -i 's/RE/REPL/[g]' FILE`), as ODK's image runs it.

Projects' own rules run it on the files they make - nfdicore's import rules:
`sed -i "s/obofoundry/obolibrary/g" ...` and `sed -i "s/Declaration(AnnotationProperty(rdf:type))//g" ...`.

For each case of CASES this writes sed/<case>/:

    before.txt   the file sed is given (from CASES), byte for byte
    script.txt   the script, one line
    after.txt    the file as GNU sed -i left it, byte for byte
    sed.txt      what sed printed, and its exit status last

each run in the case's own folder in the image as `sed -i "$(cat script.txt)" file.txt`.

Used by generate.py (stage `sed`); see README.md.
"""
import os
import shutil

import odkimage

# (case, script, the file before)
CASES = [
    ("nfdicore-obolibrary", "s/obofoundry/obolibrary/g",
     b"Prefix(obo:=<http://purl.obofoundry.org/obo/>)\n"
     b"Ontology(<http://purl.obofoundry.org/obo/swo_import.owl>\n"
     b"AnnotationAssertion(rdfs:seeAlso obo:SWO_0000001 <http://obofoundry.org/ontology/swo> "
     b"<http://www.obofoundry.org/>)\n"
     b")\n"),
    ("nfdicore-declaration", "s/Declaration(AnnotationProperty(rdf:type))//g",
     b"Declaration(Class(obo:SWO_0000001))\n"
     b"Declaration(AnnotationProperty(rdf:type))\n"
     b"Declaration(AnnotationProperty(rdfs:label)) Declaration(AnnotationProperty(rdf:type))\n"),
    ("group", r"s/\(egg\)-\(boil\)/\2-\1/g",
     b"egg-boil and egg-boil\nno match here\n"),
    ("ampersand", "s/[0-9][0-9]*/<&>/g",
     b"EGGBOIL_0000001 has 7 parts, 12 steps\n"),
    ("anchors", r"s/^\(.*\)$/[\1]/",
     b"first\n\nthird\n"),
    ("bracket", "s/[^a-z ]/./g",
     b"Egg-Boil 2026!\n"),
    ("interval", r"s/o\{2\}/0/g",
     b"foo fooo fo\n"),
    ("delimiter", "s|a/b|c|g",
     b"a/b/a/b\n"),
    ("first-only", "s/a/A/",
     b"banana\nalpha\n"),
    ("crlf", "s/end$/END/",
     b"line one\r\nline two end\r\nlast end"),
    ("empty-match", "s/a*/x/g",
     b"baaac\n"),
    ("utf8", "s/./<&>/g",
     "œuf é\n".encode("utf-8")),
    ("literal-specials", "s/a+b?(c)|{d}/X/g",
     b"a+b?(c)|{d} and ab\n"),
    ("escaped-delimiter", r"s/a\/b/a-b/g",
     b"a/b and a/b\n"),
    ("star-first", "s/*x/Y/",
     b"a*x\n"),
]

DRIVER = r"""#!/bin/sh
# For every case under /work/in: GNU sed in place, in the case's folder.
for dir in /work/in/*/; do
  cd "$dir" || exit 1
  cp before.txt file.txt
  sed -i "$(cat script.txt)" file.txt > sed.txt 2>&1
  echo "exit $?" >> sed.txt
done
echo "ODK_VERSION=$ODK_VERSION" > /work/versions.txt
sed --version | head -1 >> /work/versions.txt
echo "LANG=$LANG" >> /work/versions.txt
"""


def read(path):
    with open(path, "rb") as handle:
        return handle.read()


def run(tool, image, resources, work):
    """Makes sed/<case>/{before.txt,script.txt,after.txt,sed.txt}; returns (said, versions)."""
    if os.path.isdir(work):
        shutil.rmtree(work)
    for case, script, before in CASES:
        here = os.path.join(work, "in", case)
        odkimage.write_bytes(os.path.join(here, "before.txt"), before)
        odkimage.write_text(os.path.join(here, "script.txt"), script + "\n")
    odkimage.write_text(os.path.join(work, "driver.sh"), DRIVER)
    odkimage.in_image(tool, image, ["sh", "/work/driver.sh"], mounts=[(work, "/work", "rw")])

    said = []
    root = os.path.join(resources, "sed")
    if os.path.isdir(root):
        shutil.rmtree(root)
    for case, script, before in CASES:
        here = os.path.join(work, "in", case)
        given = os.path.join(root, case)
        printed = read(os.path.join(here, "sed.txt")).replace(b"\r\n", b"\n")
        status = printed.decode("utf-8", "replace").strip().splitlines()[-1]
        odkimage.write_bytes(os.path.join(given, "before.txt"), before)
        odkimage.write_text(os.path.join(given, "script.txt"), script + "\n")
        odkimage.write_bytes(os.path.join(given, "after.txt"),
                             read(os.path.join(here, "file.txt")))
        odkimage.write_bytes(os.path.join(given, "sed.txt"), printed)
        said.append("%s: %s, %s" % (case, status, "changed"
                                    if read(os.path.join(here, "file.txt")) != before
                                    else "unchanged"))
    versions = read(os.path.join(work, "versions.txt")).decode("utf-8").splitlines()
    return said, versions
