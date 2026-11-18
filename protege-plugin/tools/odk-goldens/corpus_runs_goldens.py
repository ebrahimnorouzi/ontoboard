"""What ODK's own make does with ten of ODK's own sample configurations: the fast test and the
fast release.

ODK v1.6.1 keeps 79 sample configurations (corpus/configs/); OntoBoard's seed of each is ODK's,
file for file (SeedCorpusTest). For ten of different kinds - SAMPLES - this seeds each in the
image and runs, on a fresh copy of the seed for each target, every file of one age:

    make <target> VERSION=1.0.0 TODAY=2026-11-11 OBODATE="11:11:2026 12:00"

for test_fast and prepare_release_fast, which build nothing they would have to download
(MAKE_FAST: IMP=false PAT=false COMP=false MIR=false). It keeps

    equivalence/corpus/<sample>/<target>/transcript.txt   what make printed
    equivalence/corpus/<sample>/<target>/status.txt       make's exit status
    equivalence/corpus/<sample>/<target>/files/<path>     every file the run made or changed

and not the seed, which OntoBoard's own seeder writes the same. CorpusEquivalenceTest runs the
same targets by OntoBoard's engine and holds them against these. Left out of the files:
tmp/plugins, ODK's copies of ROBOT's plugin jars.

Used by generate.py (stage `corpus-runs`); see README.md.
"""
import os
import shutil

import odkimage
from equivalence_goldens import DATE, OBODATE, VERSION, LEFT_OUT, files_under, read

SAMPLES = ["tests__test-minimal", "tests__test-release", "tests__test-release-reason",
           "tests__test-release-materialise", "tests__test-robot-report",
           "tests__test-sparql-report", "tests__test-edit-obo", "tests__test-uribase-suffix",
           "tests__test-module-star", "examples__minimal"]

TARGETS = ["test_fast", "prepare_release_fast"]

DRIVER = r"""#!/bin/sh
# For each sample: ODK's seed of it, then each target on a fresh copy of the seed.
rm -rf /work/out && mkdir -p /work/out
for sample in __SAMPLES__; do
  rm -rf /tmp/seed && mkdir -p /tmp/seed
  if ! odk.py seed --skipgit -C "/work/in/$sample.yaml" -D /tmp/seed/project \
      > "/work/out/$sample.seed.log" 2>&1; then
    echo "$sample seed-failed" >> /work/out/status.txt
    continue
  fi
  mkdir -p "/work/out/$sample/seed" && cp -r /tmp/seed/project/. "/work/out/$sample/seed/"
  for target in __TARGETS__; do
    rm -rf /tmp/odk && mkdir -p /tmp/odk && cp -r /tmp/seed/project /tmp/odk/project || exit 1
    # Every file the same age, as for the dry runs: make decides by file times.
    find /tmp/odk -exec touch -d "__DATE__ 12:00:00" {} +
    cd /tmp/odk/project/src/ontology || exit 1
    make $target VERSION=__VERSION__ TODAY=__DATE__ OBODATE="__OBODATE__" \
        > "/work/out/$sample.$target.transcript.txt" 2>&1
    echo "$sample $target $?" >> /work/out/status.txt
    mkdir -p "/work/out/$sample/$target" && cp -r /tmp/odk/project/. "/work/out/$sample/$target/"
    cd /
  done
done

make --version | head -1 > /work/out/versions.txt
echo "ODK_VERSION=$ODK_VERSION" >> /work/out/versions.txt
robot --version | head -1 >> /work/out/versions.txt
"""


def run(tool, image, resources, work):
    """Makes equivalence/corpus/<sample>/<target>/; returns (lines for the console, versions)."""
    if os.path.isdir(work):
        shutil.rmtree(work)
    for sample in SAMPLES:
        odkimage.write_bytes(os.path.join(work, "in", sample + ".yaml"), read(os.path.join(
            resources, "corpus", "configs", sample + ".yaml")))
    driver = (DRIVER.replace("__SAMPLES__", " ".join(SAMPLES))
              .replace("__TARGETS__", " ".join(TARGETS)).replace("__VERSION__", VERSION)
              .replace("__DATE__", DATE).replace("__OBODATE__", OBODATE))
    odkimage.write_text(os.path.join(work, "driver.sh"), driver)
    odkimage.in_image(tool, image, ["sh", "/work/driver.sh"], mounts=[(work, "/work", "rw")])

    statuses = {}
    for line in read(os.path.join(work, "out", "status.txt")).decode("utf-8").splitlines():
        parts = line.split()
        if parts[1] == "seed-failed":
            raise SystemExit("ODK's seed of %s failed:\n%s" % (parts[0], read(os.path.join(
                work, "out", parts[0] + ".seed.log")).decode("utf-8", "replace")))
        statuses[(parts[0], parts[1])] = parts[2]

    corpus = os.path.join(resources, "equivalence", "corpus")
    if os.path.isdir(corpus):
        shutil.rmtree(corpus)
    said = []
    for sample in SAMPLES:
        before = files_under(os.path.join(work, "out", sample, "seed"))
        for target in TARGETS:
            golden = os.path.join(corpus, sample, target)
            transcript = read(os.path.join(work, "out", sample + "." + target + ".transcript.txt"))
            odkimage.write_bytes(os.path.join(golden, "transcript.txt"),
                                 transcript.replace(b"\r\n", b"\n"))
            status = statuses[(sample, target)]
            odkimage.write_text(os.path.join(golden, "status.txt"), status + "\n")
            after = files_under(os.path.join(work, "out", sample, target))
            kept = 0
            for relative in sorted(after):
                if relative.startswith(LEFT_OUT):
                    continue
                data = read(after[relative])
                if relative in before and read(before[relative]) == data:
                    continue
                odkimage.write_bytes(os.path.join(golden, "files",
                                                  relative.replace("/", os.sep)), data)
                kept += 1
            said.append("%s make %s: exit %s, %d files made or changed"
                        % (sample, target, status, kept))
    versions = read(os.path.join(work, "out", "versions.txt")).decode("utf-8").splitlines()
    return said, versions
