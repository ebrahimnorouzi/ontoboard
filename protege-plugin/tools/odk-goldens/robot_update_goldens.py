"""ROBOT's SPARQL update (`robot query --update`), as ODK's image runs it.

Each case under robot-update/<case>/ holds what ROBOT is given, written by hand or copied:

    input.ofn       the ontology
    *.ru            the updates - ODK's seed's two and nfdicore's two are copied byte for byte
                    (see robot-update/README.md); the rest are the case's own
    terms.txt       the terms the chain's extract keeps

and this writes what ROBOT made of it, in the case's folder in the image:

    command.txt     ROBOT's line, as it was run there (from CASES below)
    robot.ofn       what ROBOT wrote, byte for byte
    robot.txt       what it printed, standard output and error, and its exit status last

Nothing is downloaded: the inputs import nothing.

Used by generate.py (stage `robot-update`); see README.md.
"""
import os
import shutil

import odkimage

# ROBOT's line for each case, after `robot`, run in the case's folder. Words without spaces:
# the driver splits the line as the shell does.
CASES = {
    "preprocess-module":
        "query --input input.ofn --update preprocess-module.ru --output robot.ofn",
    "postprocess-module":
        "query --input input.ofn --update postprocess-module.ru --output robot.ofn",
    "inject-subset-declaration":
        "query --input input.ofn --update inject-subset-declaration.ru --output robot.ofn",
    "inject-synonymtype-declaration":
        "query --input input.ofn --update inject-synonymtype-declaration.ru --output robot.ofn",
    "insert-data":
        "query --input input.ofn --update insert-data.ru --output robot.ofn",
    "delete-where":
        "query --input input.ofn --update delete-where.ru --output robot.ofn",
    # nfdicore's import rules, in short: an update, an extract, three updates in one query.
    "chain":
        "query --input input.ofn --update preprocess-module.ru "
        "extract --method BOT --term-file terms.txt --copy-ontology-annotations true "
        "--force true --individuals exclude "
        "query --update inject-subset-declaration.ru --update inject-synonymtype-declaration.ru "
        "--update postprocess-module.ru --output robot.ofn",
}

DRIVER = r"""#!/bin/sh
# For every case under /work/in: ROBOT's line, in the case's folder.
for dir in /work/in/*/; do
  cd "$dir" || exit 1
  robot $(cat command.txt) > robot.txt 2>&1
  echo "exit $?" >> robot.txt
done
echo "ODK_VERSION=$ODK_VERSION" > /work/versions.txt
robot --version | head -1 >> /work/versions.txt
java -XshowSettings:properties -version 2>&1 | grep -E "file.encoding =|java.version =" \
  | sed 's/^ *//' >> /work/versions.txt
"""


def read(path):
    with open(path, "rb") as handle:
        return handle.read()


def run(tool, image, resources, work):
    """Makes robot-update/<case>/{command.txt,robot.ofn,robot.txt}; returns (said, versions)."""
    if os.path.isdir(work):
        shutil.rmtree(work)
    root = os.path.join(resources, "robot-update")
    for case, line in CASES.items():
        given = os.path.join(root, case)
        here = os.path.join(work, "in", case)
        os.makedirs(here)
        for name in sorted(os.listdir(given)):
            if name not in ("command.txt", "robot.ofn", "robot.txt"):
                shutil.copyfile(os.path.join(given, name), os.path.join(here, name))
        odkimage.write_text(os.path.join(here, "command.txt"), line + "\n")
    odkimage.write_text(os.path.join(work, "driver.sh"), DRIVER)
    odkimage.in_image(tool, image, ["sh", "/work/driver.sh"], mounts=[(work, "/work", "rw")])

    said = []
    for case in CASES:
        here = os.path.join(work, "in", case)
        given = os.path.join(root, case)
        printed = read(os.path.join(here, "robot.txt")).replace(b"\r\n", b"\n")
        status = printed.decode("utf-8", "replace").strip().splitlines()[-1]
        if status != "exit 0" or not os.path.isfile(os.path.join(here, "robot.ofn")):
            raise SystemExit("robot failed for %s (%s):\n%s"
                             % (case, status, printed.decode("utf-8", "replace")))
        shutil.copyfile(os.path.join(here, "command.txt"), os.path.join(given, "command.txt"))
        shutil.copyfile(os.path.join(here, "robot.ofn"), os.path.join(given, "robot.ofn"))
        odkimage.write_bytes(os.path.join(given, "robot.txt"), printed)
        said.append("%s: %s, robot.ofn %d bytes" % (case, status,
                                                    len(read(os.path.join(here, "robot.ofn")))))
    versions = read(os.path.join(work, "versions.txt")).decode("utf-8").splitlines()
    return said, versions
