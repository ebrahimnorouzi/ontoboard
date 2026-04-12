"""ODK Setup Service — runs the full ODK seed workflow for new boards.

Flow:
1. Create board directory
2. Run `odk seed` inside odkfull container (generates full ODK structure)
3. Parse the generated odk.yaml for user editing
4. Connect the board to the *-edit.owl file
5. Initialize git repo

If odkfull is not available, falls back to creating a minimal scaffold
that mimics the ODK structure.
"""

import json
import logging
import textwrap
from pathlib import Path

import docker
from docker.errors import ImageNotFound, DockerException

from app.config import DATA_DIR, ODK_IMAGE

logger = logging.getLogger("ontoboard.odk_setup")

DEFAULT_ODK_YAML = """\
id: {ont_id}
title: "{title}"
github_org: ""
repo: {ont_id}
uribase: http://example.org/{ont_id}
release_artefacts:
  - base
  - full
primary_release: full
export_formats:
  - owl
  - ttl
import_group:
  annotation_properties:
    - rdfs:label
    - IAO:0000115
    - skos:definition
  products: []
documentation:
  documentation_system: mkdocs
robot_java_args: "-Xmx8G"
robot_report:
  use_labels: TRUE
  fail_on: ERROR
  report_on:
    - edit
"""


def run_odk_seed(board_dir: Path, ont_id: str, title: str = "",
                  versioning_strategy: str = "date") -> dict:
    """Run ODK seed to create a full ODK project structure.

    Returns: {"success": bool, "files": list[str], "yaml_path": str, "edit_owl": str}
    """
    title = title or ont_id
    board_dir.mkdir(parents=True, exist_ok=True)

    try:
        client = docker.from_env()
        client.images.get(ODK_IMAGE)

        # Run ODK seed
        logger.info("Running ODK seed for '%s'...", ont_id)
        container = client.containers.run(
            image=ODK_IMAGE,
            command=f"seed -n {ont_id} -t {ont_id} -d '{title}' -u http://example.org/{ont_id}",
            volumes={str(board_dir): {"bind": "/work", "mode": "rw"}},
            working_dir="/work",
            detach=True,
            stdout=True,
            stderr=True,
        )
        result = container.wait(timeout=300)
        stdout = container.logs(stdout=True, stderr=False).decode("utf-8", errors="replace")
        stderr = container.logs(stdout=False, stderr=True).decode("utf-8", errors="replace")
        container.remove(force=True)

        exit_code = result.get("StatusCode", -1)
        if exit_code == 0:
            logger.info("ODK seed completed for '%s'", ont_id)
        else:
            logger.warning("ODK seed failed (exit %d): %s", exit_code, stderr[:500])
            # Fall back to manual scaffold
            _create_manual_scaffold(board_dir, ont_id, title, versioning_strategy)

    except (ImageNotFound, DockerException, Exception) as exc:
        logger.warning("ODK unavailable (%s), creating manual scaffold", exc)
        _create_manual_scaffold(board_dir, ont_id, title, versioning_strategy)

    # Collect results
    files = []
    for f in board_dir.rglob("*"):
        if f.is_file() and ".git" not in str(f):
            files.append(str(f.relative_to(board_dir)))

    # Find the edit OWL and YAML config
    edit_owl = _find_file(board_dir, f"{ont_id}-edit.owl") or _find_file(board_dir, f"{ont_id}.owl") or ""
    yaml_path = _find_file(board_dir, f"{ont_id}-odk.yaml") or ""

    return {
        "success": True,
        "files": sorted(files),
        "edit_owl": edit_owl,
        "yaml_path": yaml_path,
        "ont_id": ont_id,
    }


def get_odk_yaml(board_dir: Path, ont_id: str) -> str:
    """Read the ODK YAML config file."""
    yaml_path = board_dir / "src" / "ontology" / f"{ont_id}-odk.yaml"
    if yaml_path.exists():
        return yaml_path.read_text()
    return ""


def save_odk_yaml(board_dir: Path, ont_id: str, content: str) -> bool:
    """Save edited ODK YAML config."""
    yaml_path = board_dir / "src" / "ontology" / f"{ont_id}-odk.yaml"
    yaml_path.parent.mkdir(parents=True, exist_ok=True)
    yaml_path.write_text(content)
    return True


_FILE_DESCRIPTIONS = {
    "-edit.owl": "The editing ontology file — OntoBoard modifies this",
    "-odk.yaml": "Main ODK configuration — imports, releases, settings",
    ".Makefile": "Custom Makefile — add your own targets here (not overwritten)",
    "Makefile": "Auto-generated Makefile — DO NOT edit (use .Makefile instead)",
    "-idranges.owl": "ID range allocation per user",
    "profile.txt": "ROBOT report quality profile (checks & severity)",
    "catalog-v001.xml": "Import catalog — maps URIs to local files",
    "run.sh": "Docker wrapper script — runs ODK commands",
    "check_labels.rq": "SPARQL check: entities without rdfs:label",
    "qc.yml": "GitHub Actions CI workflow",
    "README.md": "Project documentation",
    ".gitignore": "Git ignore rules",
}


def list_board_files(board_dir: Path) -> list[dict]:
    """List all files in the board directory with metadata and descriptions."""
    files = []
    for f in board_dir.rglob("*"):
        if f.is_file() and ".git" not in str(f):
            rel = str(f.relative_to(board_dir)).replace("\\", "/")
            # Find matching description
            desc = ""
            for key, d in _FILE_DESCRIPTIONS.items():
                if rel.endswith(key) or f.name == key:
                    desc = d
                    break
            files.append({
                "path": rel,
                "name": f.name,
                "size": f.stat().st_size,
                "editable": f.suffix in (".owl", ".yaml", ".yml", ".xml", ".txt", ".md",
                                          ".rq", ".sparql", ".tsv", ".csv", ".sh", ".Makefile"),
                "description": desc,
            })
    return sorted(files, key=lambda x: x["path"])


def read_board_file(board_dir: Path, file_path: str) -> str | None:
    """Read a file from the board directory (with path traversal protection)."""
    target = (board_dir / file_path).resolve()
    try:
        target.relative_to(board_dir.resolve())
    except ValueError:
        return None
    if target.exists() and target.is_file():
        return target.read_text(errors="replace")
    return None


def write_board_file(board_dir: Path, file_path: str, content: str) -> bool:
    """Write a file to the board directory (with path traversal protection)."""
    target = (board_dir / file_path).resolve()
    try:
        target.relative_to(board_dir.resolve())
    except ValueError:
        return False
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content)
    return True


# ── Manual scaffold (when Docker unavailable) ─────────────────

def _create_manual_scaffold(board_dir: Path, ont_id: str, title: str,
                            versioning_strategy: str = "date"):
    """Create a full ODK-compatible workspace structure without Docker.

    Exact ODK workspace layout:
    ├── docs/                           # MkDocs documentation site
    ├── src/
    │   ├── metadata/                   # OBO Foundry submission metadata
    │   ├── scripts/
    │   │   ├── run-command.sh          # Script for running commands
    │   │   └── update_repo.sh          # Update repository from YAML
    │   ├── sparql/                     # SPARQL quality control queries
    │   │   └── check_labels.rq         # Example: check all entities have labels
    │   ├── patterns/                   # DOSDP patterns
    │   └── ontology/
    │       ├── {ont_id}-edit.owl       # ← THE EDITING FILE (board connects here)
    │       ├── {ont_id}.owl            # Release ontology
    │       ├── {ont_id}-odk.yaml       # Main ODK configuration
    │       ├── {ont_id}.Makefile        # Custom Makefile (user edits)
    │       ├── {ont_id}-idranges.owl    # ID ranges per user
    │       ├── Makefile                # Auto-generated (DO NOT EDIT manually)
    │       ├── catalog-v001.xml        # Import resolution catalog
    │       ├── profile.txt             # ROBOT report quality profile
    │       ├── run.sh                  # Docker wrapper script
    │       └── imports/               # Mirrored import modules
    ├── .github/
    │   └── workflows/
    │       └── qc.yml                  # GitHub Actions CI
    ├── .gitignore
    └── README.md
    """
    # ── Directory structure ────────────────────────────────────
    ont_dir = board_dir / "src" / "ontology"
    for d in [
        ont_dir, ont_dir / "imports",
        board_dir / "src" / "metadata",
        board_dir / "src" / "scripts",
        board_dir / "src" / "sparql",
        board_dir / "src" / "patterns",
        board_dir / "docs",
        board_dir / ".github" / "workflows",
    ]:
        d.mkdir(parents=True, exist_ok=True)

    # ── {ont_id}-edit.owl — THE EDITING FILE ───────────────────
    owl = textwrap.dedent(f"""\
        <?xml version="1.0"?>
        <rdf:RDF xmlns="http://example.org/{ont_id}#"
             xml:base="http://example.org/{ont_id}"
             xmlns:owl="http://www.w3.org/2002/07/owl#"
             xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
             xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
             xmlns:xsd="http://www.w3.org/2001/XMLSchema#"
             xmlns:dc="http://purl.org/dc/elements/1.1/"
             xmlns:dcterms="http://purl.org/dc/terms/"
             xmlns:skos="http://www.w3.org/2004/02/skos/core#">
            <owl:Ontology rdf:about="http://example.org/{ont_id}">
                <rdfs:label xml:lang="en">{title}</rdfs:label>
                <owl:versionInfo>0.1.0</owl:versionInfo>
                <dc:description xml:lang="en">{title}</dc:description>
            </owl:Ontology>
        </rdf:RDF>
    """)
    (ont_dir / f"{ont_id}-edit.owl").write_text(owl)
    (ont_dir / f"{ont_id}.owl").write_text(owl)

    # ── {ont_id}-odk.yaml — MAIN CONFIG ────────────────────────
    yaml_content = DEFAULT_ODK_YAML.format(ont_id=ont_id, title=title)
    (ont_dir / f"{ont_id}-odk.yaml").write_text(yaml_content)

    # ── Makefile (auto-generated, do not edit) ─────────────────
    makefile = textwrap.dedent(f"""\
        # WARNING: This Makefile is auto-generated by ODK.
        # Do NOT edit this file manually. Use {ont_id}.Makefile for custom targets.
        # To regenerate: sh run.sh make update_repo

        ONT_ID := {ont_id}
        ONT := $(ONT_ID)

        -include $(ONT).Makefile

        .PHONY: all test clean prepare_release publish docs reason update_repo refresh-imports

        all:
        \t@echo "Build: $(ONT)"

        test:
        \t@echo "Running tests on $(ONT)..."

        reason:
        \trobot reason -r ELK -i $(ONT)-edit.owl -o $(ONT).owl || true

        clean:
        \t@rm -f tmp_* report.tsv *.bak

        prepare_release: test
        \t@echo "Preparing release for $(ONT)..."

        publish: prepare_release
        \t@echo "Publishing $(ONT)..."

        docs:
        \t@echo "Generating docs for $(ONT)..."

        update_repo:
        \t@echo "Updating repository from $(ONT)-odk.yaml..."

        refresh-imports:
        \t@echo "Refreshing imports..."
    """)
    (ont_dir / "Makefile").write_text(makefile)

    # ── {ont_id}.Makefile — CUSTOM Makefile (user edits this) ──
    custom_makefile = textwrap.dedent(f"""\
        ## Custom Makefile for {ont_id}
        ## Add your custom targets, import overrides, and release steps here.
        ## This file is NOT overwritten by `make update_repo`.

        # Example: custom import configuration
        # {ont_id}_IMPORT_MODULES := ro bfo iao

        # Example: custom release step
        # custom_release:
        # \t@echo "Running custom release step..."
    """)
    (ont_dir / f"{ont_id}.Makefile").write_text(custom_makefile)

    # ── {ont_id}-idranges.owl — ID RANGES ──────────────────────
    idranges = textwrap.dedent(f"""\
        Ontology: <http://example.org/{ont_id}/{ont_id}-idranges>

        # ID Range allocation for {ont_id}
        # Format: [Owner] [Lower] [Upper]
        # default 0000001 0009999
    """)
    (ont_dir / f"{ont_id}-idranges.owl").write_text(idranges)

    # ── profile.txt — ROBOT report quality profile ─────────────
    profile = textwrap.dedent("""\
        # ROBOT Report Profile
        # Customize which checks are run and their severity levels.
        # See: http://robot.obolibrary.org/report

        ERROR   duplicate_label
        ERROR   missing_definition
        WARN    missing_label
        WARN    duplicate_definition
        INFO    missing_synonyms
    """)
    (ont_dir / "profile.txt").write_text(profile)

    # ── catalog-v001.xml ───────────────────────────────────────
    catalog = textwrap.dedent(f"""\
        <?xml version="1.0" encoding="UTF-8" standalone="no"?>
        <catalog prefer="public" xmlns="urn:oasis:names:tc:entity:xmlns:xml:catalog">
            <uri id="User Coverage" name="http://example.org/{ont_id}.owl" uri="{ont_id}.owl"/>
        </catalog>
    """)
    (ont_dir / "catalog-v001.xml").write_text(catalog)

    # ── run.sh — Docker wrapper ────────────────────────────────
    run_sh = textwrap.dedent("""\
        #!/bin/sh
        # Run ODK commands inside the odkfull Docker container.
        # Usage: sh run.sh make all
        #        sh run.sh make update_repo
        #        sh run.sh make refresh-imports
        docker run --rm -v "$(pwd)":/work -w /work obolibrary/odkfull "$@"
    """)
    (ont_dir / "run.sh").write_text(run_sh)

    # ── src/scripts/ ───────────────────────────────────────────
    (board_dir / "src" / "scripts" / "run-command.sh").write_text(
        "#!/bin/sh\n# Run a custom command inside ODK\nsh run.sh \"$@\"\n"
    )
    (board_dir / "src" / "scripts" / "update_repo.sh").write_text(
        f"#!/bin/sh\n# Update the repository from {ont_id}-odk.yaml\ncd src/ontology && sh run.sh make update_repo\n"
    )

    # ── src/sparql/ — example SPARQL check ─────────────────────
    (board_dir / "src" / "sparql" / "check_labels.rq").write_text(textwrap.dedent("""\
        # Check: all named classes should have rdfs:label
        PREFIX owl: <http://www.w3.org/2002/07/owl#>
        PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

        SELECT ?entity WHERE {
          ?entity a owl:Class .
          FILTER NOT EXISTS { ?entity rdfs:label ?label }
          FILTER (isIRI(?entity))
        }
    """))

    # ── .github/workflows/qc.yml ───────────────────────────────
    (board_dir / ".github" / "workflows" / "qc.yml").write_text(textwrap.dedent(f"""\
        name: Ontology QC - {ont_id}
        on:
          push:
            branches: [main]
          pull_request:
            branches: [main]

        jobs:
          qc:
            runs-on: ubuntu-latest
            container: obolibrary/odkfull:latest
            steps:
              - uses: actions/checkout@v4
              - name: Run QC
                run: cd src/ontology && make test
              - name: Validate ID ranges
                run: cd src/ontology && robot report -i {ont_id}-idranges.owl || true
    """))

    # ── .gitignore ─────────────────────────────────────────────
    (board_dir / ".gitignore").write_text(textwrap.dedent("""\
        # Build artifacts
        tmp_*
        *.tmp
        report.tsv
        *.bak

        # OS
        .DS_Store
        Thumbs.db

        # IDE
        .idea/
        .vscode/
        *.swp
    """))

    # ── release.sh — versioned release script ──────────────────
    _write_release_sh(ont_dir, ont_id, versioning_strategy)

    # ── version-strategy.txt — remember user choice ───────────
    (board_dir / "version-strategy.txt").write_text(versioning_strategy + "\n")

    # ── README.md ──────────────────────────────────────────────
    (board_dir / "README.md").write_text(textwrap.dedent(f"""\
        # {title}

        Ontology managed with the [Ontology Development Kit (ODK)](https://github.com/INCATools/ontology-development-kit) and [OntoBoard](https://github.com/ISE-FIZKarlsruhe/ontoboard).

        ## Quick Start

        ```bash
        cd src/ontology
        sh run.sh make all          # Build the ontology
        sh run.sh make test         # Run quality checks
        sh run.sh make reason       # Run ELK reasoner
        sh run.sh make update_repo  # Regenerate from ODK config
        ```

        ## Directory Structure

        ```
        src/ontology/       Ontology files, Makefile, imports
        src/sparql/          SPARQL validation queries
        src/patterns/        DOSDP design patterns
        src/metadata/        OBO Foundry metadata
        src/scripts/         Utility scripts
        docs/                Generated documentation
        ```
    """))


def _write_release_sh(ont_dir: Path, ont_id: str, strategy: str):
    """Generate release.sh based on versioning strategy (date or semantic)."""
    if strategy == "semantic":
        # NFDIcore-style semantic versioning release script
        release_sh = textwrap.dedent(f"""\
            #!/bin/sh
            # Release script for {ont_id} — semantic versioning (X.Y.Z)
            # Usage: sh release.sh <version> [prior_version]
            #   e.g. sh release.sh 1.0.0
            #   e.g. sh release.sh 1.1.0 1.0.0

            set -e

            VERSION=${{1:?"Usage: sh release.sh <version> [prior_version]"}}
            PRIOR_VERSION=${{2:-""}}

            ONTBASE="http://example.org/{ont_id}"

            echo "Releasing {ont_id} version $VERSION ..."

            # Annotate with version IRI
            ANNOTATE_ONTOLOGY_VERSION="annotate -V $ONTBASE/$VERSION/\\$@ --annotation owl:versionInfo $VERSION"

            # Clean and build
            sh run.sh make clean
            sh run.sh make VERSION=$VERSION ONTBASE=$ONTBASE \\
                ANNOTATE_ONTOLOGY_VERSION="$ANNOTATE_ONTOLOGY_VERSION" prepare_release

            # Update version.txt
            echo "$VERSION" > ../../version.txt

            # Create releases directory
            mkdir -p ../../releases

            echo "Release $VERSION complete."
            echo "Artifacts in releases/ directory."
        """)
    else:
        # ODK default: date-based versioning (YYYY-MM-DD)
        release_sh = textwrap.dedent(f"""\
            #!/bin/sh
            # Release script for {ont_id} — date-based versioning (YYYY-MM-DD)
            # Usage: sh release.sh [date]
            #   e.g. sh release.sh           # uses today's date
            #   e.g. sh release.sh 2024-03-15

            set -e

            VERSION=${{1:-$(date +%Y-%m-%d)}}

            ONTBASE="http://example.org/{ont_id}"

            echo "Releasing {ont_id} version $VERSION ..."

            # Annotate with version IRI (date-based)
            ANNOTATE_ONTOLOGY_VERSION="annotate -V $ONTBASE/releases/$VERSION/\\$@ --annotation owl:versionInfo $VERSION"

            # Clean and build
            sh run.sh make clean
            sh run.sh make VERSION=$VERSION ONTBASE=$ONTBASE \\
                ANNOTATE_ONTOLOGY_VERSION="$ANNOTATE_ONTOLOGY_VERSION" prepare_release

            # Update version.txt
            echo "$VERSION" > ../../version.txt

            # Create releases directory
            mkdir -p ../../releases

            echo "Release $VERSION complete."
            echo "Artifacts in releases/ directory."
        """)
    (ont_dir / "release.sh").write_text(release_sh)


def get_versioning_strategy(board_dir: Path) -> str:
    """Read the versioning strategy for a board (date or semantic)."""
    strategy_file = board_dir / "version-strategy.txt"
    if strategy_file.exists():
        return strategy_file.read_text().strip()
    return "date"


def set_versioning_strategy(board_dir: Path, strategy: str, ont_id: str) -> str:
    """Update versioning strategy and regenerate release.sh."""
    if strategy not in ("date", "semantic"):
        strategy = "date"
    (board_dir / "version-strategy.txt").write_text(strategy + "\n")
    ont_dir = board_dir / "src" / "ontology"
    _write_release_sh(ont_dir, ont_id, strategy)
    return strategy


def _find_file(board_dir: Path, filename: str) -> str:
    """Find a file recursively and return its relative path."""
    for f in board_dir.rglob(filename):
        return str(f.relative_to(board_dir)).replace("\\", "/")
    return ""
