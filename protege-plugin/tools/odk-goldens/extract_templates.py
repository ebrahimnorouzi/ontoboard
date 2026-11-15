#!/usr/bin/env python3
"""Copies ODK's template folder out of the pinned ODK image, byte for byte, into the plugin.

Usage (from the repository root):

    python protege-plugin/tools/odk-goldens/extract_templates.py [--odk-checkout <ODK git clone>]

Writes protege-plugin/src/main/odk/v1.6.1/:

    templates/**       /tools/templates of the image: ODK's template/ at the tag, plus
                       src/ontology/profile.txt, ROBOT's standard report profile, which the image
                       downloads when it is built
    templates.index    one line per file: <sha256>  <644|755>  <path>
    PROVENANCE.txt     which image this is, and when it was read
    LICENSE-ODK.txt    ODK's licence (BSD-3-Clause), with --odk-checkout

Why from the image and not from a git checkout: the image is what `odk.py seed` renders from, it
holds one file the repository does not, and a Windows checkout with core.autocrlf=true has CRLF in
every template. With --odk-checkout the files are compared with the tag's blobs anyway.
"""
import argparse
import datetime
import hashlib
import io
import os
import shutil
import subprocess
import sys
import tarfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import odkimage  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
TARGET = os.path.normpath(os.path.join(HERE, "..", "..", "src", "main", "odk", odkimage.PIN))


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--odk-checkout", help="a git clone of INCATools/ontology-development-kit "
                        "holding the tag, to compare the templates with and to take LICENSE from")
    options = parser.parse_args()

    tool = odkimage.runtime()
    image = odkimage.pinned_image(tool)
    _, tarball, _ = odkimage.in_image(tool, image, ["tar", "-C", "/tools/templates", "-cf", "-", "."])
    _, sums, _ = odkimage.in_image(tool, image, [
        "sh", "-c", "cd /tools/templates && find . -type f | LC_ALL=C sort | xargs sha256sum"])
    said = {}
    for line in sums.decode("utf-8").splitlines():
        digest, name = line.split(None, 1)
        said[name.strip().lstrip("*")[2:]] = digest

    templates = os.path.join(TARGET, "templates")
    if os.path.isdir(templates):
        shutil.rmtree(templates)
    index = []
    with tarfile.open(fileobj=io.BytesIO(tarball)) as archive:
        for member in archive.getmembers():
            if not member.isfile():
                continue
            path = member.name[2:] if member.name.startswith("./") else member.name
            data = archive.extractfile(member).read()
            digest = hashlib.sha256(data).hexdigest()
            if said.get(path) != digest:
                sys.exit("%s: the image says %s, the copy is %s" % (path, said.get(path), digest))
            mode = "755" if member.mode & 0o111 else "644"
            odkimage.write_bytes(os.path.join(templates, *path.split("/")), data)
            index.append((path, digest, mode))
    if sorted(said) != sorted(path for path, _, _ in index):
        sys.exit("The copy and the image disagree about which files there are.")
    index.sort()
    odkimage.write_text(os.path.join(TARGET, "templates.index"),
                        "".join("%s  %s  %s\n" % (digest, mode, path) for path, digest, mode in index))

    compared = "not compared with a checkout of the tag"
    if options.odk_checkout:
        compared = compare_with_tag(options.odk_checkout, index)
        licence = subprocess.run(["git", "-C", options.odk_checkout, "show", odkimage.PIN + ":LICENSE"],
                                 check=True, capture_output=True).stdout
        odkimage.write_bytes(os.path.join(TARGET, "LICENSE-ODK.txt"), licence)

    about = odkimage.describe(tool, image)
    _, env, _ = odkimage.in_image(tool, image, ["sh", "-c", "echo $ODK_VERSION; robot --version | head -1"])
    version, robot = env.decode("utf-8").splitlines()[:2]
    odkimage.write_text(os.path.join(TARGET, "PROVENANCE.txt"), "\n".join([
        "ODK's templates, as OntoBoard bundles them.",
        "",
        "Source: /tools/templates of the ODK image, copied by",
        "  protege-plugin/tools/odk-goldens/extract_templates.py",
        "",
        "Image:        " + about["reference"],
        "Image id:     " + about["id"],
        "Repo digests: " + ", ".join(about["digests"]),
        "Built:        " + about["created"],
        "ODK_VERSION:  " + version,
        "ROBOT:        " + robot,
        "ODK tag:      %s (commit %s) of https://github.com/INCATools/ontology-development-kit"
        % (odkimage.PIN, odkimage.ODK_TAG_COMMIT),
        "Read on:      " + datetime.date.today().isoformat(),
        "Files:        %d (%d templates, %d copied as they are)"
        % (len(index), sum(1 for p, _, _ in index if p.endswith(".jinja2")),
           sum(1 for p, _, _ in index if not p.endswith(".jinja2"))),
        "Against tag:  " + compared,
        "",
        "Licence: BSD-3-Clause, Copyright (c) 2015, Gene Ontology Consortium - LICENSE-ODK.txt.",
        "src/ontology/profile.txt is ROBOT %s's report_profile.txt (BSD-3-Clause, ontodev/robot)."
        % odkimage.ROBOT,
        "",
        "Do not edit these files and do not let git convert their line endings (.gitattributes",
        "marks them -text): OntoBoard renders ODK's seed from them, byte for byte.",
        ""]))
    print("%d files from %s into %s" % (len(index), image, TARGET))
    print(compared)


def compare_with_tag(checkout, index):
    """Every file of the tag's template/ is in the image with the same bytes; says what is not."""
    listed = subprocess.run(["git", "-C", checkout, "ls-tree", "-r", "--name-only", odkimage.PIN,
                             "template/"], check=True, capture_output=True).stdout.decode("utf-8")
    in_tag = {}
    for name in listed.splitlines():
        blob = subprocess.run(["git", "-C", checkout, "show", "%s:%s" % (odkimage.PIN, name)],
                              check=True, capture_output=True).stdout
        in_tag[name[len("template/"):]] = hashlib.sha256(blob).hexdigest()
    in_image = {path: digest for path, digest, _ in index}
    differing = sorted(p for p in in_tag if in_image.get(p) != in_tag[p])
    only_image = sorted(p for p in in_image if p not in in_tag)
    if differing:
        sys.exit("The image's templates differ from tag %s in: %s" % (odkimage.PIN, ", ".join(differing)))
    return ("identical to the tag's %d template files; only in the image: %s"
            % (len(in_tag), ", ".join(only_image) or "nothing"))


if __name__ == "__main__":
    main()
