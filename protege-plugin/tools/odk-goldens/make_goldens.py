"""What GNU make itself does with the Makefiles ODK seeds: its dry runs, and what it considers.

For each seeded project among the goldens (src/test/resources/odk/<pin>/seed/<name>) and each
target OntoBoard may run without Docker, in the ODK image:

    make-n/<name>-<label>.txt      `make -n <arguments>`: every command make would run, in order
    closure/<name>-<label>.txt     `make -n -d <arguments>`: every target and file make looked at
                                   to decide that - one name per line, sorted - which is the
                                   set a custom Makefile's rule can change the outcome through
    make-n/targets.txt             each label with the arguments it stands for

OntoBoard's in-process fallback runs ODK's recipes itself. The first file is what its list of
ROBOT commands is compared with; the second is what its "does the custom Makefile override
anything this target depends on?" is compared with. Both come from make, not from reading the
Makefile by eye.

Nothing is built and nothing is downloaded: `-n` runs no recipe (but sub-makes, which a dry run
has to follow). The projects are copied into the container's own disk first, so that a slow or
case-blind bind mount cannot change what make sees.

Used by generate.py (stage `make`); see README.md.
"""
import os
import re
import shutil

import odkimage

# Fixed, so that the files do not change with the day they were made on.
DATE = "2026-11-11"
OBODATE = "11:11:2026 12:00"
VERSION = "1.0.0"

# What is asked of make: a label for the file name, then make's own arguments. The targets of the
# fallback (design 6.6), and the parts of `test` that Build offers one by one.
#
# `test_fast` is there twice. Its recipe is `$(MAKE_FAST) test`, and a dry run follows a sub-make
# only where the recipe says $(MAKE) in so many words - so `make -n test_fast` prints that one
# line and stops. What test_fast then does is `test-fast`: test with the four switches off.
FAST = ["IMP=false", "PAT=false", "COMP=false", "MIR=false"]
TARGETS = [
    ("test_fast", ["test_fast"]),
    ("test-fast", ["test"] + FAST),
    ("test", ["test"]),
    ("reason_test", ["reason_test"]),
    ("sparql_test", ["sparql_test"]),
    ("robot_reports", ["robot_reports"]),
    ("validate_idranges", ["validate_idranges"]),
    ("prepare_release_fast", ["prepare_release_fast"]),
    ("prepare_release", ["prepare_release"]),
    ("refresh-imports", ["refresh-imports"]),
    ("all", ["all"]),
    ("clean", ["clean"]),
]

DRIVER = r"""#!/bin/sh
# Dry runs of every target, for every project under /work/in/projects.
rm -rf /tmp/odk && mkdir -p /tmp/odk && cp -r /work/in/projects/. /tmp/odk/ || exit 1
# Every file the same age. make remakes a target that exists only when a prerequisite is newer,
# and a copy gives each file the moment it was copied at: which of two seeded files is the
# newer would be the order the disk happened to list them in.
find /tmp/odk -exec touch -d "__DATE__ 12:00:00" {} +
rm -rf /work/out && mkdir -p /work/out/make-n /work/out/closure /work/out/err
make --version | head -1 > /work/out/versions.txt
echo "ODK_VERSION=$ODK_VERSION" >> /work/out/versions.txt
for dir in /tmp/odk/*/; do
  name=$(basename "$dir")
  cd "$dir/src/ontology" || exit 1
  while read -r label arguments; do
    # $arguments unquoted on purpose: it is make's argument list.
    make -n $arguments VERSION=__VERSION__ TODAY=__DATE__ OBODATE="__OBODATE__" \
        > "/work/out/make-n/$name-$label.txt" 2> "/work/out/err/$name-$label.n.txt"
    echo "$name $label n $?" >> /work/out/status.txt
    make -n -d $arguments VERSION=__VERSION__ TODAY=__DATE__ OBODATE="__OBODATE__" 2>&1 \
        | grep -E "Considering target file|Pruning file" \
        > "/work/out/closure/$name-$label.raw.txt"
    echo "$name $label d $?" >> /work/out/status.txt
  done < /work/targets.txt
done
"""

CONSIDERED = re.compile(r"(?:Considering target file|Pruning file) '(.*)'\.\s*$")


def read(path):
    with open(path, "rb") as handle:
        return handle.read()


def run(tool, image, resources, work):
    """Makes make-n/ and closure/ under `resources`; returns (files written, lines for provenance)."""
    seeds = os.path.join(resources, "seed")
    names = sorted(n for n in os.listdir(seeds) if os.path.isdir(os.path.join(seeds, n)))
    if os.path.isdir(work):
        shutil.rmtree(work)
    for name in names:
        shutil.copytree(os.path.join(seeds, name), os.path.join(work, "in", "projects", name))
    driver = (DRIVER.replace("__VERSION__", VERSION).replace("__DATE__", DATE)
              .replace("__OBODATE__", OBODATE))
    odkimage.write_text(os.path.join(work, "driver.sh"), driver)
    # One line per label: the label, a blank, make's arguments.
    table = "".join("%s %s\n" % (label, " ".join(arguments)) for label, arguments in TARGETS)
    odkimage.write_text(os.path.join(work, "targets.txt"), table)
    odkimage.in_image(tool, image, ["sh", "/work/driver.sh"], mounts=[(work, "/work", "rw")])

    for folder in ("make-n", "closure"):
        target = os.path.join(resources, folder)
        if os.path.isdir(target):
            shutil.rmtree(target)
    written = 0
    failed = []
    for line in read(os.path.join(work, "out", "status.txt")).decode("utf-8").splitlines():
        name, target, kind, status = line.split()
        if kind == "n":
            text = read(os.path.join(work, "out", "make-n", "%s-%s.txt" % (name, target)))
            if status != "0":
                error = read(os.path.join(work, "out", "err", "%s-%s.n.txt" % (name, target)))
                failed.append("%s %s: make -n exited %s: %s" % (
                    name, target, status,
                    error.decode("utf-8", "replace").strip().splitlines()[-1:] or "(no message)"))
                text = b"!make -n failed with exit status " + status.encode("ascii") + b"\n" + error
            odkimage.write_bytes(os.path.join(resources, "make-n", "%s-%s.txt" % (name, target)),
                                 text.replace(b"\r\n", b"\n"))
            written += 1
        else:
            raw = read(os.path.join(work, "out", "closure", "%s-%s.raw.txt" % (name, target)))
            considered = set()
            for entry in raw.decode("utf-8", "replace").splitlines():
                match = CONSIDERED.search(entry)
                if match:
                    considered.add(match.group(1))
            odkimage.write_text(os.path.join(resources, "closure", "%s-%s.txt" % (name, target)),
                                "".join(item + "\n" for item in sorted(considered)))
            written += 1
    # What each label asked of make, beside the files: a test reads the variables from here.
    odkimage.write_text(os.path.join(resources, "make-n", "targets.txt"), table)
    versions = read(os.path.join(work, "out", "versions.txt")).decode("utf-8").splitlines()
    return names, written, failed, versions
