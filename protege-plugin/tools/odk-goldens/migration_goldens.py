"""What ODK's own update does to a 1.135.x project once OntoBoard has converted it.

*Update project files...* carries a project of OntoBoard's older layout over in two steps:
OntoBoard's own conversion (`OdkMigration.convert`: the configuration, the test queries as ODK
checks, the imported modules declared), and then ODK's update. This records the second step as
ODK itself makes it, on the state the first one leaves.

The converted project is written by the Java side, which is the only place the conversion
exists:

    cd protege-plugin
    mvn -o -Dtest=MigrationConvertedWriter -Dontoboard.writeConverted=<new folder> test

Then, in the ODK image, on a copy in the container's own disk, `odk.py update` is run in
src/ontology, and then once more:

    migration/expected/converted.sha256      every file of the converted project with its
                                             SHA-256, so that a test that converts the fixture
                                             again can tell it has the same one
    migration/expected/transcript.txt        what the update printed
    migration/expected/after/files/<path>    every file the update made or changed
    migration/expected/after/gone.txt        every file it removed
    migration/expected/second-run.txt        "identical", or the files a second update changed

Nothing is downloaded: an update writes files from ODK's templates and rewrites the edit
file's import declarations.

Used by generate.py (stage `migration --converted <folder>`); see README.md.
"""
import hashlib
import os
import shutil

import equivalence_goldens
import odkimage
import update_goldens

NAME = "eggboil"


def read(path):
    with open(path, "rb") as handle:
        return handle.read()


def run(tool, image, converted, expected, work):
    """Makes migration/expected/...; returns (lines for the console, versions)."""
    if not os.path.isfile(os.path.join(converted, "src", "ontology", NAME + "-odk.yaml")):
        raise SystemExit("no converted project in %s (src/ontology/%s-odk.yaml is not there)"
                         % (converted, NAME))
    if os.path.isdir(work):
        shutil.rmtree(work)
    shutil.copytree(converted, os.path.join(work, "in", NAME))
    odkimage.write_text(os.path.join(work, "driver.sh"), update_goldens.DRIVER)
    odkimage.in_image(tool, image, ["sh", "/work/driver.sh"], mounts=[(work, "/work", "rw")])

    status = {}
    for line in read(os.path.join(work, "out", "status.txt")).decode("utf-8").splitlines():
        name, which, code = line.split()
        status[(name, which)] = code
    for stale in ("after", "converted.sha256", "transcript.txt", "second-run.txt"):
        path = os.path.join(expected, stale)
        if os.path.isdir(path):
            shutil.rmtree(path)
        elif os.path.isfile(path):
            os.remove(path)
    before = equivalence_goldens.files_under(os.path.join(work, "in", NAME))
    first = equivalence_goldens.files_under(os.path.join(work, "out", NAME, "first"))
    second = equivalence_goldens.files_under(os.path.join(work, "out", NAME, "second"))

    odkimage.write_text(os.path.join(expected, "converted.sha256"), "".join(
        "%s  %s\n" % (hashlib.sha256(read(before[p])).hexdigest(), p) for p in sorted(before)))
    odkimage.write_bytes(os.path.join(expected, "transcript.txt"),
                         read(os.path.join(work, "out", NAME + ".transcript.txt"))
                         .replace(b"\r\n", b"\n"))
    kept = 0
    for relative in sorted(first):
        data = read(first[relative])
        if relative in before and read(before[relative]) == data:
            continue
        odkimage.write_bytes(os.path.join(expected, "after", "files",
                                          relative.replace("/", os.sep)), data)
        kept += 1
    gone = sorted(p for p in before if p not in first)
    odkimage.write_text(os.path.join(expected, "after", "gone.txt"),
                        "".join(p + "\n" for p in gone))
    differs = sorted(p for p in set(first) | set(second)
                     if p not in first or p not in second or read(first[p]) != read(second[p]))
    odkimage.write_text(os.path.join(expected, "second-run.txt"),
                        "identical\n" if not differs else "".join(p + "\n" for p in differs))
    said = ["%s: update exit %s, %d files made or changed, %d removed; second update exit %s, %s"
            % (NAME, status[(NAME, "first")], kept, len(gone), status[(NAME, "second")],
               "changes nothing" if not differs else "changes " + ", ".join(differs))]
    versions = read(os.path.join(work, "out", "versions.txt")).decode("utf-8").splitlines()
    return said, versions
