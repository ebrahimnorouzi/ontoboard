"""What ODK's own make does for a project's imports - and for the full test and the full
release, which build the imports first.

The project is seed/localimports with equivalence/loim/overlay laid over it: an edit file
that uses a term of each of its three imports, and two term lists. The ontologies it mirrors
are the small ones in equivalence/loim/upstream. They are given to make as
OBOBASE=file:///work/in/upstream, so that nothing is fetched from the network: ODK's rule for
an OBO ontology is `curl -L $(OBOBASE)/<id>.owl ...`, and curl reads a file: address as it
reads any other. (configs/oboimports has a fourth product whose address only the network
answers; ODK's own refresh stops there without it, so this configuration is its own.)

In the ODK image, on a fresh copy for each target, in the container's own disk:

    equivalence/loim/<target>/transcript.txt   what `make <target> ...` printed
    equivalence/loim/<target>/files/<path>     every file the run made or changed

OntoBoard's in-process engine runs the same recipes itself, with its own stand-in for curl. Its
files are held against these: text files by their rows, ontologies by their axioms and
annotations.

Left out of the files: tmp/plugins, ODK's copies of ROBOT's plugin jars.

Used by generate.py (stage `imports`); see README.md.
"""
import os
import shutil

import odkimage
from equivalence_goldens import DATE, OBODATE, VERSION, LEFT_OUT, files_under, read

UPSTREAM = "file:///work/in/upstream"

# The target, and what else is given on make's command line for it.
TARGETS = [
    ("refresh-imports", ""),
    ("test", ""),
    ("prepare_release", ""),
]

DRIVER = r"""#!/bin/sh
# For the project under /work/in/project: what make does for each target, on a fresh copy.
rm -rf /work/out && mkdir -p /work/out
fresh() {
  rm -rf /tmp/odk && mkdir -p /tmp/odk && cp -r /work/in/project /tmp/odk/loim || exit 1
  # Every file the same age, as for the dry runs: make decides by file times.
  find /tmp/odk -exec touch -d "__DATE__ 12:00:00" {} +
  cd /tmp/odk/loim/src/ontology || exit 1
}
while read -r target extra; do
  fresh
  make $target $extra OBOBASE=__UPSTREAM__ VERSION=__VERSION__ TODAY=__DATE__ \
      OBODATE="__OBODATE__" > "/work/out/$target.transcript.txt" 2>&1
  echo "$target $?" >> /work/out/status.txt
  mkdir -p "/work/out/$target" && cp -r /tmp/odk/loim/. "/work/out/$target/"
done < /work/targets.txt

make --version | head -1 > /work/out/versions.txt
echo "ODK_VERSION=$ODK_VERSION" >> /work/out/versions.txt
robot --version | head -1 >> /work/out/versions.txt
curl --version | head -1 >> /work/out/versions.txt
"""


def compose(resources, target):
    """The project: the localimports seed with the overlay's files over it."""
    if os.path.isdir(target):
        shutil.rmtree(target)
    shutil.copytree(os.path.join(resources, "seed", "localimports"), target)
    overlay = os.path.join(resources, "equivalence", "loim", "overlay")
    for relative, full in files_under(overlay).items():
        destination = os.path.join(target, relative.replace("/", os.sep))
        os.makedirs(os.path.dirname(destination), exist_ok=True)
        shutil.copyfile(full, destination)


def run(tool, image, resources, work):
    """Makes equivalence/loim/<target>/; returns (lines for the console, versions)."""
    if os.path.isdir(work):
        shutil.rmtree(work)
    project = os.path.join(work, "in", "project")
    compose(resources, project)
    shutil.copytree(os.path.join(resources, "equivalence", "loim", "upstream"),
                    os.path.join(work, "in", "upstream"))
    driver = (DRIVER.replace("__VERSION__", VERSION).replace("__DATE__", DATE)
              .replace("__OBODATE__", OBODATE).replace("__UPSTREAM__", UPSTREAM))
    odkimage.write_text(os.path.join(work, "driver.sh"), driver)
    odkimage.write_text(os.path.join(work, "targets.txt"),
                        "".join("%s %s\n" % (name, extra) for name, extra in TARGETS))
    odkimage.in_image(tool, image, ["sh", "/work/driver.sh"], mounts=[(work, "/work", "rw")])

    said = []
    status = dict(line.split() for line in
                  read(os.path.join(work, "out", "status.txt")).decode("utf-8").splitlines())
    before = files_under(project)
    loim = os.path.join(resources, "equivalence", "loim")
    for target, _extra in TARGETS:
        golden = os.path.join(loim, target)
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
