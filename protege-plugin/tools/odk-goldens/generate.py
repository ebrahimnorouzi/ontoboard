#!/usr/bin/env python3
"""Makes the goldens OntoBoard's ODK tests compare with - in the pinned ODK image.

Usage (from the repository root):

    python protege-plugin/tools/odk-goldens/generate.py seed
    python protege-plugin/tools/odk-goldens/generate.py corpus --odk-checkout <ODK git clone>
    python protege-plugin/tools/odk-goldens/generate.py lexer
    python protege-plugin/tools/odk-goldens/generate.py all --odk-checkout <ODK git clone>

Stages, each writing under protege-plugin/src/test/resources/odk/v1.6.1/:

    seed     for every configs/<name>.yaml: `odk.py seed --skipgit` -> seed/<name>/** (every file,
             byte for byte), and `odk.py export-project` -> model/<name>.yaml (the config with
             every default and derived field filled in)
    corpus   ODK's own sample configs (configs/, tests/, examples/ at the tag) -> corpus/configs/,
             each seeded the same way but kept as corpus/manifests/<name>.sha256 (paths and
             hashes, not the files) and corpus/model/<name>.yaml
    lexer    how Jinja2 3.1.6 cuts each bundled template -> lexer/*.json (lexer_fixture.py; needs
             that Jinja2 on the host)
    make     what GNU make does with each seeded project's Makefile (make_goldens.py): for every
             seed/<name> and every target OntoBoard may run without Docker, `make -n <target>` ->
             make-n/<name>-<target>.txt and the targets `make -n -d` considered ->
             closure/<name>-<target>.txt. Run `seed` first; this reads its output.

Nothing here downloads anything: `seed --skipgit` renders templates and stops, and `make -n` runs
no recipe. One container start does all the seeding, so a full run takes about a minute.
"""
import argparse
import datetime
import hashlib
import os
import shutil
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import equivalence_goldens  # noqa: E402
import imports_goldens  # noqa: E402
import lexer_fixture  # noqa: E402
import make_goldens  # noqa: E402
import migration_goldens  # noqa: E402
import odkimage  # noqa: E402
import update_goldens  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
PLUGIN = os.path.normpath(os.path.join(HERE, "..", ".."))
RES = os.path.join(PLUGIN, "src", "test", "resources", "odk", odkimage.PIN)
TEMPLATES = os.path.join(PLUGIN, "src", "main", "odk", odkimage.PIN, "templates")
WORK = os.path.join(PLUGIN, "target", "odk-goldens-work")

DRIVER = """#!/bin/sh
# Seeds every config under /work/configs and dumps its filled-in project, in one container.
cd /work || exit 1
rm -rf out logs
mkdir -p out/seed out/model logs
for cfg in configs/*.yaml; do
  name=$(basename "$cfg" .yaml)
  if odk.py seed --skipgit -C "$cfg" -D "out/seed/$name" > "logs/$name.seed.log" 2>&1; then s=ok; else s=failed; fi
  if odk.py export-project -C "$cfg" -o "out/model/$name.yaml" > "logs/$name.model.log" 2>&1; then m=ok; else m=failed; fi
  echo "$name $s $m" >> out/status.txt
done
echo "ODK_VERSION=$ODK_VERSION" > out/versions.txt
robot --version | head -1 >> out/versions.txt
python3 -c "import jinja2, yaml; print('Jinja2 ' + jinja2.__version__ + ', PyYAML ' + yaml.__version__)" >> out/versions.txt
"""


def read(path):
    with open(path, "rb") as handle:
        return handle.read()


def tree(root):
    """{relative path with /: bytes} for every file under root."""
    found = {}
    for directory, _, files in os.walk(root):
        for name in files:
            path = os.path.join(directory, name)
            found[os.path.relpath(path, root).replace(os.sep, "/")] = read(path)
    return found


def seed_in_image(tool, image, configs):
    """Seeds {name: config bytes}; returns ({name: (seed ok, model ok)}, the work folder)."""
    if os.path.isdir(WORK):
        shutil.rmtree(WORK)
    for name, data in configs.items():
        odkimage.write_bytes(os.path.join(WORK, "configs", name + ".yaml"), data)
    odkimage.write_text(os.path.join(WORK, "driver.sh"), DRIVER)
    odkimage.in_image(tool, image, ["sh", "/work/driver.sh"], mounts=[(WORK, "/work", "rw")])
    status = {}
    for line in read(os.path.join(WORK, "out", "status.txt")).decode("utf-8").splitlines():
        name, seeded, modelled = line.split()
        status[name] = (seeded == "ok", modelled == "ok")
    return status


def log_tail(name, kind):
    text = read(os.path.join(WORK, "logs", "%s.%s.log" % (name, kind))).decode("utf-8", "replace")
    lines = [line for line in text.splitlines() if line.strip()]
    return lines[-1] if lines else "(no output)"


SEED_COMMAND = "odk.py seed --skipgit -C <config> -D <out>; odk.py export-project -C <config> -o <out>"


def provenance(tool, image, what, versions=None, command=SEED_COMMAND):
    about = odkimage.describe(tool, image)
    if versions is None:
        versions = read(os.path.join(WORK, "out", "versions.txt")).decode("utf-8").splitlines()
    path = os.path.join(RES, "PROVENANCE.txt")
    kept = []
    if os.path.isfile(path):
        marker = "## "
        sections = read(path).decode("utf-8").split("\n" + marker)
        kept = [marker + s for s in sections[1:] if not s.startswith(what + "\n")]
    text = "\n".join([
        "Goldens for OntoBoard's ODK tests, made by protege-plugin/tools/odk-goldens/generate.py",
        "in the ODK image. Regenerate, never edit; .gitattributes marks this folder -text.",
        ""] + [k.rstrip("\n") + "\n" for k in kept] + [
        "## " + what,
        "Image:        " + about["reference"],
        "Image id:     " + about["id"],
        "Repo digests: " + ", ".join(about["digests"]),
        "Built:        " + about["created"]] + versions + [
        "Made on:      " + datetime.date.today().isoformat(),
        "Command:      " + command,
        ""])
    odkimage.write_text(path, text)


def stage_seed(tool, image):
    configs = {}
    for name in sorted(os.listdir(os.path.join(RES, "configs"))):
        if name.endswith(".yaml"):
            configs[name[:-len(".yaml")]] = read(os.path.join(RES, "configs", name))
    status = seed_in_image(tool, image, configs)
    for name in configs:
        seeded, modelled = status[name]
        if not seeded:
            sys.exit("%s: the image refused to seed it: %s" % (name, log_tail(name, "seed")))
        if not modelled:
            sys.exit("%s: export-project failed: %s" % (name, log_tail(name, "model")))
        target = os.path.join(RES, "seed", name)
        if os.path.isdir(target):
            shutil.rmtree(target)
        files = tree(os.path.join(WORK, "out", "seed", name))
        for path, data in files.items():
            odkimage.write_bytes(os.path.join(target, *path.split("/")), data)
        odkimage.write_bytes(os.path.join(RES, "model", name + ".yaml"),
                             read(os.path.join(WORK, "out", "model", name + ".yaml")))
        print("seed   %-18s %3d files" % (name, len(files)))
    provenance(tool, image, "seed")


def stage_corpus(tool, image, checkout):
    if not checkout:
        sys.exit("The corpus needs --odk-checkout: a git clone of ODK holding tag " + odkimage.PIN)
    listed = subprocess.run(["git", "-C", checkout, "ls-tree", "-r", "--name-only", odkimage.PIN,
                             "configs/", "tests/", "examples/"],
                            check=True, capture_output=True).stdout.decode("utf-8").splitlines()
    configs = {}
    for path in listed:
        parts = path.split("/")
        if parts[0] in ("configs", "tests") and len(parts) == 2 and path.endswith(".yaml"):
            name = parts[0] + "__" + parts[1][:-len(".yaml")]
        elif parts[0] == "examples" and len(parts) == 3 and parts[2] == "project.yaml":
            name = "examples__" + parts[1]
        else:
            continue
        configs[name] = subprocess.run(["git", "-C", checkout, "show", "%s:%s" % (odkimage.PIN, path)],
                                       check=True, capture_output=True).stdout
    status = seed_in_image(tool, image, configs)
    for folder in ("configs", "manifests", "model"):
        target = os.path.join(RES, "corpus", folder)
        if os.path.isdir(target):
            shutil.rmtree(target)
    refused = 0
    for name in sorted(configs):
        seeded, modelled = status[name]
        odkimage.write_bytes(os.path.join(RES, "corpus", "configs", name + ".yaml"), configs[name])
        if seeded:
            files = tree(os.path.join(WORK, "out", "seed", name))
            manifest = "".join("%s  %s\n" % (hashlib.sha256(files[p]).hexdigest(), p) for p in sorted(files))
        else:
            refused += 1
            manifest = "!refused %s\n" % log_tail(name, "seed")
        odkimage.write_text(os.path.join(RES, "corpus", "manifests", name + ".sha256"), manifest)
        if modelled:
            odkimage.write_bytes(os.path.join(RES, "corpus", "model", name + ".yaml"),
                                 read(os.path.join(WORK, "out", "model", name + ".yaml")))
        else:
            odkimage.write_text(os.path.join(RES, "corpus", "model", name + ".yaml"),
                                "!refused %s\n" % log_tail(name, "model"))
    odkimage.write_text(os.path.join(RES, "corpus", "README.md"), "\n".join([
        "# ODK's own sample configurations, as a differential corpus",
        "",
        "`configs/` holds the %d configurations ODK %s keeps under `configs/`, `tests/` and"
        % (len(configs), odkimage.PIN),
        "`examples/` (BSD-3-Clause, Gene Ontology Consortium; see the plugin's NOTICE), unchanged.",
        "`manifests/<name>.sha256` lists every file `odk.py seed --skipgit` wrote for it in the ODK",
        "image, with its SHA-256; `model/<name>.yaml` is `odk.py export-project`'s dump. A file that",
        "starts `!refused` records that ODK itself refused the configuration, and why.",
        "",
        "They exercise what OntoBoard's own configurations never do: components, DOSDP patterns,",
        "SSSOM mappings, translations, subsets, custom artefacts, mirror types, plugins.",
        "Made by `protege-plugin/tools/odk-goldens/generate.py corpus`. Regenerate, never edit.",
        ""]))
    print("corpus %d configurations, %d refused by ODK itself" % (len(configs), refused))
    provenance(tool, image, "corpus")


def stage_lexer():
    target = os.path.join(RES, "lexer")
    if os.path.isdir(target):
        shutil.rmtree(target)
    lexer_fixture.main(TEMPLATES, target)


def stage_make(tool, image):
    names, written, failed, versions = make_goldens.run(
        tool, image, RES, os.path.join(PLUGIN, "target", "odk-goldens-make"))
    for line in failed:
        print("make   " + line)
    print("make   %d projects x %d targets: %d files; make itself refused %d dry runs"
          % (len(names), len(make_goldens.TARGETS), written, len(failed)))
    provenance(tool, image, "make", versions,
               "make -n <target> and make -n -d <target>, each with VERSION=%s TODAY=%s OBODATE=\"%s\", "
               "in /tmp/odk/<name>/src/ontology on a copy of seed/<name>"
               % (make_goldens.VERSION, make_goldens.DATE, make_goldens.OBODATE))


def stage_equivalence(tool, image):
    said, versions = equivalence_goldens.run(
        tool, image, RES, os.path.join(PLUGIN, "target", "odk-goldens-equivalence"))
    for line in said:
        print("equiv  " + line)
    provenance(tool, image, "equivalence", versions,
               "make <target> VERSION=%s TODAY=%s OBODATE=\"%s\" for %s, each on a fresh copy of "
               "seed/eggboil with equivalence/egg/overlay over it, in /tmp/odk/egg/src/ontology; "
               "then the forms of robot query that ODK's other targets use"
               % (equivalence_goldens.VERSION, equivalence_goldens.DATE,
                  equivalence_goldens.OBODATE, ", ".join(equivalence_goldens.TARGETS)))


def stage_update(tool, image):
    said, versions = update_goldens.run(
        tool, image, RES, os.path.join(PLUGIN, "target", "odk-goldens-update"))
    for line in said:
        print("update " + line)
    provenance(tool, image, "update", versions,
               "odk.py update, twice, in /tmp/odk/egg/src/ontology, on a copy of seed/eggboil "
               "with equivalence/egg/overlay and then update/<case>/ laid over it, for "
               + ", ".join(update_goldens.CASES))


def stage_migration(tool, image, converted):
    if not converted:
        raise SystemExit("the migration stage needs --converted <folder>: the project "
                         "MigrationConvertedWriter wrote (see migration_goldens.py)")
    said, versions = migration_goldens.run(
        tool, image, os.path.abspath(converted),
        os.path.join(PLUGIN, "src", "test", "resources", "odk", "migration", "expected"),
        os.path.join(PLUGIN, "target", "odk-goldens-migration"))
    for line in said:
        print("migration " + line)
    provenance(tool, image, "migration", versions,
               "odk.py update, twice, in /tmp/odk/egg/src/ontology, on a copy of "
               "migration/eggboil-1.135.2 as OdkMigration.convert leaves it "
               "(MigrationConvertedWriter); recorded under migration/expected")


def stage_imports(tool, image):
    said, versions = imports_goldens.run(
        tool, image, RES, os.path.join(PLUGIN, "target", "odk-goldens-imports"))
    for line in said:
        print("import " + line)
    provenance(tool, image, "imports", versions,
               "make <target> OBOBASE=%s VERSION=%s TODAY=%s OBODATE=\"%s\" for %s, each on a "
               "fresh copy of seed/localimports with equivalence/loim/overlay over it, in "
               "/tmp/odk/loim/src/ontology, the ontologies to mirror being "
               "equivalence/loim/upstream"
               % (imports_goldens.UPSTREAM, imports_goldens.VERSION, imports_goldens.DATE,
                  imports_goldens.OBODATE,
                  ", ".join((name + " " + extra).strip()
                            for name, extra in imports_goldens.TARGETS)))


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("stage", choices=["seed", "corpus", "lexer", "make", "equivalence",
                                          "update", "imports", "migration", "all"])
    parser.add_argument("--converted", help="the 1.135.x project as OntoBoard's conversion "
                        "leaves it, written by MigrationConvertedWriter (for the migration "
                        "stage, which is not part of 'all')")
    parser.add_argument("--odk-checkout", help="a git clone of INCATools/ontology-development-kit "
                        "holding tag " + odkimage.PIN + " (for the corpus)")
    options = parser.parse_args()
    if options.stage in ("seed", "corpus", "make", "equivalence", "update", "imports",
                         "migration", "all"):
        tool = odkimage.runtime()
        image = odkimage.pinned_image(tool)
        if options.stage in ("seed", "all"):
            stage_seed(tool, image)
        if options.stage in ("corpus", "all"):
            stage_corpus(tool, image, options.odk_checkout)
        if options.stage in ("make", "all"):
            stage_make(tool, image)
        if options.stage in ("equivalence", "all"):
            stage_equivalence(tool, image)
        if options.stage in ("update", "all"):
            stage_update(tool, image)
        if options.stage in ("imports", "all"):
            stage_imports(tool, image)
        if options.stage == "migration":
            stage_migration(tool, image, options.converted)
    if options.stage in ("lexer", "all"):
        stage_lexer()


if __name__ == "__main__":
    main()
