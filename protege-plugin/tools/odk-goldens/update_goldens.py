"""What ODK's own update (`odk.py update`, which `sh run.sh update_repo` runs) does to a project.

Two projects, each the egg fixture (seed/eggboil with equivalence/egg/overlay over it) with
what update/<case>/ lays over that:

    base.txt          another case to start from, instead of the fixture
    changes/<path>    a file as it is before the update, in place of the fixture's
    append/<path>     text added to the end of the file the fixture has at that path
    deleted.txt       paths taken away before the update, one a line

`egg-unchanged` has none of these: it is the fixture, and shows what an update rewrites even
when nothing is to be done. `egg-changed` is a project somebody has worked in: an import
declared in the configuration, a README written by hand, ignore rules and catalog entries of
their own, an import nobody declared, files of ODK's edited by hand, two files deleted.
`egg-unloadable` is that project without the file the undeclared import is read from: ODK's
update then stops at its last step, the edit file's imports, with every other file written.

In the ODK image, on a copy in the container's own disk, `odk.py update` is run in
src/ontology, and then once more:

    update/<case>/before.sha256        every file before the update with its SHA-256, so that a
                                       test that puts the project together again can tell it
                                       has the same one
    update/<case>/transcript.txt       what the update printed
    update/<case>/after/files/<path>   every file the update made or changed
    update/<case>/after/gone.txt       every file it removed
    update/<case>/second-run.txt       "identical", or the files a second update changed

Nothing is downloaded: an update writes files from ODK's templates and rewrites the edit
file's import declarations.

Used by generate.py (stage `update`); see README.md.
"""
import hashlib
import os
import shutil

import equivalence_goldens
import odkimage

CASES = ["egg-unchanged", "egg-changed", "egg-unloadable"]

DRIVER = r"""#!/bin/sh
# For every project under /work/in: ODK's update, twice, on a copy in the container's disk.
rm -rf /work/out && mkdir -p /work/out
for dir in /work/in/*/; do
  name=$(basename "$dir")
  rm -rf /tmp/odk && mkdir -p /tmp/odk && cp -r "$dir" /tmp/odk/egg || exit 1
  cd /tmp/odk/egg/src/ontology || exit 1
  odk.py update > "/work/out/$name.transcript.txt" 2>&1
  echo "$name first $?" >> /work/out/status.txt
  mkdir -p "/work/out/$name/first" && cp -r /tmp/odk/egg/. "/work/out/$name/first/"
  odk.py update > "/work/out/$name.second.transcript.txt" 2>&1
  echo "$name second $?" >> /work/out/status.txt
  mkdir -p "/work/out/$name/second" && cp -r /tmp/odk/egg/. "/work/out/$name/second/"
done
echo "ODK_VERSION=$ODK_VERSION" > /work/out/versions.txt
robot --version | head -1 >> /work/out/versions.txt
python3 -c "import jinja2, yaml; print('Jinja2 ' + jinja2.__version__ + ', PyYAML ' + yaml.__version__)" >> /work/out/versions.txt
python3 -c "import sys; print('Python ' + sys.version.split()[0])" >> /work/out/versions.txt
"""


def read(path):
    with open(path, "rb") as handle:
        return handle.read()


def compose(resources, case, target):
    """The project before the update: the egg fixture with the case's changes."""
    case_dir = os.path.join(resources, "update", case)
    base = os.path.join(case_dir, "base.txt")
    if os.path.isfile(base):
        # A case that starts from another: that one's project, then this one's changes.
        compose(resources, read(base).decode("utf-8").strip(), target)
    else:
        equivalence_goldens.compose(resources, target)
    for relative, full in sorted(equivalence_goldens.files_under(
            os.path.join(case_dir, "changes")).items()):
        destination = os.path.join(target, relative.replace("/", os.sep))
        os.makedirs(os.path.dirname(destination), exist_ok=True)
        shutil.copyfile(full, destination)
    for relative, full in sorted(equivalence_goldens.files_under(
            os.path.join(case_dir, "append")).items()):
        destination = os.path.join(target, relative.replace("/", os.sep))
        odkimage.write_bytes(destination, read(destination) + read(full))
    deleted = os.path.join(case_dir, "deleted.txt")
    if os.path.isfile(deleted):
        for line in read(deleted).decode("utf-8").splitlines():
            if line.strip():
                os.remove(os.path.join(target, line.strip().replace("/", os.sep)))


def run(tool, image, resources, work):
    """Makes update/<case>/...; returns (lines for the console, versions)."""
    if os.path.isdir(work):
        shutil.rmtree(work)
    for case in CASES:
        compose(resources, case, os.path.join(work, "in", case))
    odkimage.write_text(os.path.join(work, "driver.sh"), DRIVER)
    odkimage.in_image(tool, image, ["sh", "/work/driver.sh"], mounts=[(work, "/work", "rw")])

    said = []
    status = {}
    for line in read(os.path.join(work, "out", "status.txt")).decode("utf-8").splitlines():
        name, which, code = line.split()
        status[(name, which)] = code
    for case in CASES:
        case_dir = os.path.join(resources, "update", case)
        for stale in ("after", "before.sha256", "transcript.txt", "second-run.txt"):
            path = os.path.join(case_dir, stale)
            if os.path.isdir(path):
                shutil.rmtree(path)
            elif os.path.isfile(path):
                os.remove(path)
        before = equivalence_goldens.files_under(os.path.join(work, "in", case))
        first = equivalence_goldens.files_under(os.path.join(work, "out", case, "first"))
        second = equivalence_goldens.files_under(os.path.join(work, "out", case, "second"))

        odkimage.write_text(os.path.join(case_dir, "before.sha256"), "".join(
            "%s  %s\n" % (hashlib.sha256(read(before[p])).hexdigest(), p)
            for p in sorted(before)))
        odkimage.write_bytes(os.path.join(case_dir, "transcript.txt"),
                             read(os.path.join(work, "out", case + ".transcript.txt"))
                             .replace(b"\r\n", b"\n"))
        kept = 0
        for relative in sorted(first):
            data = read(first[relative])
            if relative in before and read(before[relative]) == data:
                continue
            odkimage.write_bytes(os.path.join(case_dir, "after", "files",
                                              relative.replace("/", os.sep)), data)
            kept += 1
        gone = sorted(p for p in before if p not in first)
        odkimage.write_text(os.path.join(case_dir, "after", "gone.txt"),
                            "".join(p + "\n" for p in gone))
        differs = sorted(p for p in set(first) | set(second)
                         if p not in first or p not in second
                         or read(first[p]) != read(second[p]))
        odkimage.write_text(os.path.join(case_dir, "second-run.txt"),
                            "identical\n" if not differs else
                            "".join(p + "\n" for p in differs))
        said.append("%s: update exit %s, %d files made or changed, %d removed; second update "
                    "exit %s, %s" % (case, status[(case, "first")], kept, len(gone),
                                     status[(case, "second")],
                                     "changes nothing" if not differs
                                     else "changes " + ", ".join(differs)))
    versions = read(os.path.join(work, "out", "versions.txt")).decode("utf-8").splitlines()
    return said, versions
