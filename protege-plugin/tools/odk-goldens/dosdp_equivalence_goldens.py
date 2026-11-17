"""What ODK's own make does with a project that has DOSDP patterns: its test and its release.

The project is ODK's own test configuration with patterns (tests/test-patterns.yaml in ODK's
repository, corpus/configs/tests__test-patterns.yaml here: the project ptt, a default pipeline
and the manual and auto ones), seeded by ODK in the image - kept as equivalence/dosdp/project,
as the seed wrote it - with equivalence/dosdp/overlay laid over it: three labelled classes in
the edit file, two rows of the seed's example pattern, and a second pattern of OntoBoard's own
(locatedIn) with a row in the manual pipeline, which takes the logical axioms only.

In the ODK image, on a fresh copy for each target, in the container's own disk:

    equivalence/dosdp/<target>/transcript.txt   what `make <target> IMP=false MIR=false ...`
                                                printed
    equivalence/dosdp/<target>/files/<path>     every file the run made or changed

The imports are not built again (IMP=false MIR=false), so nothing is downloaded. OntoBoard's
own engine runs the same targets with dosdp-tools 0.19.3 (DosdpEquivalenceLiveTest). Left out
of the files: tmp/plugins, ODK's copies of ROBOT's plugin jars.

Used by generate.py (stage `dosdp`); see README.md.
"""
import os
import shutil

import odkimage
from equivalence_goldens import DATE, OBODATE, VERSION, LEFT_OUT, files_under, read

TARGETS = ["test", "prepare_release"]

DRIVER = r"""#!/bin/sh
# Seeds ODK's test project with patterns, then for each target: what make does, on a fresh copy.
rm -rf /work/out && mkdir -p /work/out
odk.py seed --skipgit -C /work/in/ptt.yaml -D /work/out/project > /work/out/seed.log 2>&1 \
  || { echo "seed failed" > /work/out/status.txt; exit 0; }
for target in __TARGETS__; do
  rm -rf /tmp/odk && mkdir -p /tmp/odk && cp -r /work/out/project /tmp/odk/ptt \
    && cp -r /work/in/overlay/. /tmp/odk/ptt/ || exit 1
  # Every file the same age, as for the dry runs: make decides by file times.
  find /tmp/odk -exec touch -d "__DATE__ 12:00:00" {} +
  cd /tmp/odk/ptt/src/ontology || exit 1
  make $target IMP=false MIR=false VERSION=__VERSION__ TODAY=__DATE__ OBODATE="__OBODATE__" \
      > "/work/out/$target.transcript.txt" 2>&1
  echo "$target $?" >> /work/out/status.txt
  mkdir -p "/work/out/$target" && cp -r /tmp/odk/ptt/. "/work/out/$target/"
done

make --version | head -1 > /work/out/versions.txt
echo "ODK_VERSION=$ODK_VERSION" >> /work/out/versions.txt
robot --version | head -1 >> /work/out/versions.txt
echo "DOSDP_VERSION=$DOSDP_VERSION" >> /work/out/versions.txt
pip show dosdp 2>/dev/null | sed -n 's/^Version: /dosdp /p' >> /work/out/versions.txt
"""


def run(tool, image, resources, work):
    """Makes equivalence/dosdp/{project,<target>}; returns (lines for the console, versions)."""
    if os.path.isdir(work):
        shutil.rmtree(work)
    odkimage.write_bytes(os.path.join(work, "in", "ptt.yaml"), read(os.path.join(
        resources, "corpus", "configs", "tests__test-patterns.yaml")))
    overlay = os.path.join(resources, "equivalence", "dosdp", "overlay")
    for relative, full in files_under(overlay).items():
        odkimage.write_bytes(os.path.join(work, "in", "overlay", relative.replace("/", os.sep)),
                             read(full))
    driver = (DRIVER.replace("__TARGETS__", " ".join(TARGETS)).replace("__VERSION__", VERSION)
              .replace("__DATE__", DATE).replace("__OBODATE__", OBODATE))
    odkimage.write_text(os.path.join(work, "driver.sh"), driver)
    odkimage.in_image(tool, image, ["sh", "/work/driver.sh"], mounts=[(work, "/work", "rw")])

    statuses = read(os.path.join(work, "out", "status.txt")).decode("utf-8").splitlines()
    if statuses == ["seed failed"]:
        raise SystemExit("ODK's seed of tests__test-patterns failed:\n" + read(os.path.join(
            work, "out", "seed.log")).decode("utf-8", "replace"))
    status = dict(line.split() for line in statuses)
    dosdp = os.path.join(resources, "equivalence", "dosdp")
    seeded = files_under(os.path.join(work, "out", "project"))
    project = os.path.join(dosdp, "project")
    if os.path.isdir(project):
        shutil.rmtree(project)
    for relative, full in seeded.items():
        odkimage.write_bytes(os.path.join(project, relative.replace("/", os.sep)), read(full))

    before = dict(seeded)
    for relative, full in files_under(overlay).items():
        before[relative] = full
    said = ["seed of tests__test-patterns: %d files" % len(seeded)]
    for target in TARGETS:
        golden = os.path.join(dosdp, target)
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
