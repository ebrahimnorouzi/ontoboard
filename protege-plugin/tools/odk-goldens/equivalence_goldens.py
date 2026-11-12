"""What ODK's own make produces for the targets OntoBoard runs without Docker.

The project is seed/eggboil with equivalence/egg/overlay laid over it: an edit file with six
classes, a defined class and something for the reasoner to find, and OntoBoard's label check,
which the configuration lists and ODK's seed does not write. It is the project New ODK project
makes, with a little work in it.

In the ODK image, on a fresh copy for each target, in the container's own disk:

    equivalence/egg/<target>/transcript.txt   what `make <target> ...` printed
    equivalence/egg/<target>/files/<path>     every file the run made or changed
    equivalence/egg/query/<name>              what the forms of `robot query` give that ODK's
                                              other targets use, on the same ontology

OntoBoard's in-process engine runs the same recipes itself. Its files are held against these:
text files by their rows, ontologies by their axioms and annotations.

Left out of the files: tmp/plugins, ODK's copies of ROBOT's plugin jars.

Used by generate.py (stage `equivalence`); see README.md.
"""
import os
import shutil

import odkimage

DATE = "2026-11-11"
OBODATE = "11:11:2026 12:00"
VERSION = "1.0.0"

TARGETS = ["test_fast", "prepare_release_fast"]

# Not goldens: the jars of ROBOT's plugins, which ODK copies beside the build.
LEFT_OUT = ("src/ontology/tmp/plugins/",)

DRIVER = r"""#!/bin/sh
# For the project under /work/in/project: what make does for each target, on a fresh copy.
rm -rf /work/out && mkdir -p /work/out/query
fresh() {
  rm -rf /tmp/odk && mkdir -p /tmp/odk && cp -r /work/in/project /tmp/odk/egg || exit 1
  # Every file the same age, as for the dry runs: make decides by file times.
  find /tmp/odk -exec touch -d "__DATE__ 12:00:00" {} +
  cd /tmp/odk/egg/src/ontology || exit 1
}
while read -r target; do
  fresh
  make $target VERSION=__VERSION__ TODAY=__DATE__ OBODATE="__OBODATE__" \
      > "/work/out/$target.transcript.txt" 2>&1
  echo "$target $?" >> /work/out/status.txt
  mkdir -p "/work/out/$target" && cp -r /tmp/odk/egg/. "/work/out/$target/"
done < /work/targets.txt

# The forms of `robot query` in ODK's Makefile, each on the ontology its recipe gives it.
fresh
make tmp/merged-eggboil-edit.ofn VERSION=__VERSION__ TODAY=__DATE__ OBODATE="__OBODATE__" \
    > /work/out/query.transcript.txt 2>&1
echo "merged $?" >> /work/out/status.txt
q() {
  name=$1
  shift
  robot --catalog catalog-v001.xml query "$@" >> /work/out/query.transcript.txt 2>&1
  echo "query-$name $?" >> /work/out/status.txt
}
q terms --input tmp/merged-eggboil-edit.ofn --format --csv \
    --query ../sparql/terms.sparql /work/out/query/terms.format--csv.txt
q simple-seed -f csv -i tmp/merged-eggboil-edit.ofn \
    --query ../sparql/simple-seed.sparql /work/out/query/simple-seed.csv.txt
q ontology-terms -f csv -i tmp/merged-eggboil-edit.ofn \
    --query ../sparql/eggboil_terms.sparql /work/out/query/eggboil_terms.csv.txt
q exports -f tsv --use-graphs true -i tmp/eggboil-preprocess.owl \
    -s ../sparql/basic-report.sparql /work/out/query/basic-report.tsv \
    -s ../sparql/edges.sparql /work/out/query/edges.tsv \
    -s ../sparql/synonyms.sparql /work/out/query/synonyms.tsv

make --version | head -1 > /work/out/versions.txt
echo "ODK_VERSION=$ODK_VERSION" >> /work/out/versions.txt
robot --version | head -1 >> /work/out/versions.txt
"""


def read(path):
    with open(path, "rb") as handle:
        return handle.read()


def files_under(root):
    found = {}
    for folder, _dirs, names in os.walk(root):
        for name in names:
            full = os.path.join(folder, name)
            found[os.path.relpath(full, root).replace("\\", "/")] = full
    return found


def compose(resources, target):
    """The project: the eggboil seed with the overlay's files over it."""
    if os.path.isdir(target):
        shutil.rmtree(target)
    shutil.copytree(os.path.join(resources, "seed", "eggboil"), target)
    overlay = os.path.join(resources, "equivalence", "egg", "overlay")
    for relative, full in files_under(overlay).items():
        destination = os.path.join(target, relative.replace("/", os.sep))
        os.makedirs(os.path.dirname(destination), exist_ok=True)
        shutil.copyfile(full, destination)


def run(tool, image, resources, work):
    """Makes equivalence/egg/<target>/ and query/; returns (lines for the console, versions)."""
    if os.path.isdir(work):
        shutil.rmtree(work)
    project = os.path.join(work, "in", "project")
    compose(resources, project)
    driver = (DRIVER.replace("__VERSION__", VERSION).replace("__DATE__", DATE)
              .replace("__OBODATE__", OBODATE))
    odkimage.write_text(os.path.join(work, "driver.sh"), driver)
    odkimage.write_text(os.path.join(work, "targets.txt"), "".join(t + "\n" for t in TARGETS))
    odkimage.in_image(tool, image, ["sh", "/work/driver.sh"], mounts=[(work, "/work", "rw")])

    said = []
    status = dict(line.split() for line in
                  read(os.path.join(work, "out", "status.txt")).decode("utf-8").splitlines())
    before = files_under(project)
    egg = os.path.join(resources, "equivalence", "egg")
    for target in TARGETS:
        golden = os.path.join(egg, target)
        if os.path.isdir(golden):
            shutil.rmtree(golden)
        transcript = read(os.path.join(work, "out", target + ".transcript.txt"))
        odkimage.write_bytes(os.path.join(golden, "transcript.txt"),
                             transcript.replace(b"\r\n", b"\n"))
        after = files_under(os.path.join(work, "out", target))
        kept = 0
        for relative in sorted(after):
            if relative.startswith(LEFT_OUT):
                continue
            data = read(after[relative])
            if relative in before and read(before[relative]) == data:
                continue
            odkimage.write_bytes(os.path.join(golden, "files", relative.replace("/", os.sep)),
                                 data)
            kept += 1
        said.append("make %s: exit %s, %d files made or changed" % (target, status[target], kept))
        if status[target] != "0":
            said.append("  !! make itself failed: " + transcript.decode("utf-8", "replace")
                        .strip().splitlines()[-1])
    queries = os.path.join(egg, "query")
    if os.path.isdir(queries):
        shutil.rmtree(queries)
    for relative, full in sorted(files_under(os.path.join(work, "out", "query")).items()):
        odkimage.write_bytes(os.path.join(queries, relative.replace("/", os.sep)), read(full))
    odkimage.write_bytes(os.path.join(queries, "transcript.txt"),
                         read(os.path.join(work, "out", "query.transcript.txt"))
                         .replace(b"\r\n", b"\n"))
    for name in sorted(status):
        if name.startswith("query-") or name == "merged":
            said.append("%s: exit %s" % (name, status[name]))
    versions = read(os.path.join(work, "out", "versions.txt")).decode("utf-8").splitlines()
    return said, versions
