"""What ODK's own make does with rules of a project's own: nfdicore's shape of them.

The project is seed/oboimports with equivalence/own/overlay laid over it: the project's own
makefile with three rules as nfdicore writes them (a module cut by MIREOT between ODK's seed's
updates and nfdicore's own, then sed over it; a module cut by BOT; the base built the project's
way), small mirrors of its four imports - so that nothing is downloaded: the targets refresh
from the mirrors as they are (MIR=false) or do not touch the imports - term lists, and
nfdicore's two updates in src/sparql.

In the ODK image, on a fresh copy for each target, in the container's own disk:

    equivalence/own/<target>/transcript.txt   what `make <target> ...` printed
    equivalence/own/<target>/files/<path>     every file the run made or changed

OntoBoard's own engine runs the same targets itself; OwnRulesEquivalenceTest holds its runs
against these. Left out of the files: tmp/plugins, ODK's copies of ROBOT's plugin jars.

Used by generate.py (stage `own`); see README.md.
"""
import os
import shutil

import odkimage
from equivalence_goldens import DATE, OBODATE, VERSION, LEFT_OUT, files_under, read

TARGETS = ["test_fast", "no-mirror-refresh-imports", "prepare_release_fast"]

DRIVER = r"""#!/bin/sh
# For the project under /work/in/project: what make does for each target, on a fresh copy.
rm -rf /work/out && mkdir -p /work/out
for target in __TARGETS__; do
  rm -rf /tmp/odk && mkdir -p /tmp/odk && cp -r /work/in/project /tmp/odk/obim || exit 1
  # Every file the same age, as for the dry runs: make decides by file times.
  find /tmp/odk -exec touch -d "__DATE__ 12:00:00" {} +
  cd /tmp/odk/obim/src/ontology || exit 1
  make $target VERSION=__VERSION__ TODAY=__DATE__ OBODATE="__OBODATE__" \
      > "/work/out/$target.transcript.txt" 2>&1
  echo "$target $?" >> /work/out/status.txt
  mkdir -p "/work/out/$target" && cp -r /tmp/odk/obim/. "/work/out/$target/"
done

make --version | head -1 > /work/out/versions.txt
echo "ODK_VERSION=$ODK_VERSION" >> /work/out/versions.txt
robot --version | head -1 >> /work/out/versions.txt
sed --version | head -1 >> /work/out/versions.txt
"""


def compose(resources, target):
    """The project: the oboimports seed with the overlay's files over it."""
    if os.path.isdir(target):
        shutil.rmtree(target)
    shutil.copytree(os.path.join(resources, "seed", "oboimports"), target)
    overlay = os.path.join(resources, "equivalence", "own", "overlay")
    for relative, full in files_under(overlay).items():
        destination = os.path.join(target, relative.replace("/", os.sep))
        os.makedirs(os.path.dirname(destination), exist_ok=True)
        shutil.copyfile(full, destination)


def run(tool, image, resources, work):
    """Makes equivalence/own/<target>/; returns (lines for the console, versions)."""
    if os.path.isdir(work):
        shutil.rmtree(work)
    project = os.path.join(work, "in", "project")
    compose(resources, project)
    driver = (DRIVER.replace("__TARGETS__", " ".join(TARGETS)).replace("__VERSION__", VERSION)
              .replace("__DATE__", DATE).replace("__OBODATE__", OBODATE))
    odkimage.write_text(os.path.join(work, "driver.sh"), driver)
    odkimage.in_image(tool, image, ["sh", "/work/driver.sh"], mounts=[(work, "/work", "rw")])

    said = []
    status = dict(line.split() for line in
                  read(os.path.join(work, "out", "status.txt")).decode("utf-8").splitlines())
    before = files_under(project)
    own = os.path.join(resources, "equivalence", "own")
    for target in TARGETS:
        golden = os.path.join(own, target)
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
    versions = read(os.path.join(work, "out", "versions.txt")).decode("utf-8").splitlines()
    return said, versions
