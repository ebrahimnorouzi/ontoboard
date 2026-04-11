"""ODP Repository Export Service.

Compiles the current board state into a complete ODP repository structure
that can be git init + pushed to GitHub as a valid standard ODK project.

Generated structure:
  {board_id}-export/
  ├── src/
  │   ├── ontology/
  │   │   ├── {board_id}.owl          (Main ontology)
  │   │   ├── {board_id}-edit.owl     (Edit file copy)
  │   │   ├── {board_id}-odk.yaml     (ODK configuration)
  │   │   ├── catalog-v001.xml        (Import catalog)
  │   │   ├── Makefile                (ODK Makefile)
  │   │   └── imports/                (Mirrored imports)
  │   ├── patterns/                   (DOSDP patterns)
  │   └── sparql/                     (Validation queries)
  ├── docs/                           (Generated documentation)
  ├── README.md                       (Auto-generated)
  ├── .gitignore
  ├── Makefile                        (Top-level)
  └── LICENSE
"""

import io
import shutil
import textwrap
import zipfile
from datetime import datetime
from pathlib import Path

from rdflib import Graph, RDF, RDFS, OWL

from app.config import DATA_DIR
from app.services.ontology import load_graph, get_ontology_metadata, get_ontology_statistics


def export_odp_repo(board_dir: Path, board_id: str) -> Path:
    """Generate a complete ODP repository directory. Returns the export path."""
    export_dir = board_dir / f"{board_id}-export"
    if export_dir.exists():
        shutil.rmtree(export_dir)

    # Create directory structure
    (export_dir / "src" / "ontology" / "imports").mkdir(parents=True)
    (export_dir / "src" / "patterns").mkdir(parents=True)
    (export_dir / "src" / "sparql").mkdir(parents=True)
    (export_dir / "docs").mkdir(parents=True)

    # Load the ontology
    try:
        g = load_graph(board_dir)
        meta = get_ontology_metadata(g)
        stats = get_ontology_statistics(g)
    except FileNotFoundError:
        g = Graph()
        meta = {"ontology_iri": f"http://example.org/{board_id}", "version_iri": None,
                "imports": [], "prefixes": [], "languages": []}
        stats = {"classes": 0, "object_properties": 0, "individuals": 0, "total_triples": 0}

    ont_iri = meta.get("ontology_iri", f"http://example.org/{board_id}")
    version = "0.1.0"
    for s in g.subjects(RDF.type, OWL.Ontology):
        for o in g.objects(s, OWL.versionInfo):
            version = str(o)

    # 1. Copy ontology files
    for owl_file in (board_dir / "src" / "ontology").glob("*.owl"):
        shutil.copy2(str(owl_file), str(export_dir / "src" / "ontology" / owl_file.name))
    # Also serialize as Turtle
    g.serialize(str(export_dir / "src" / "ontology" / f"{board_id}.ttl"), format="turtle")

    # 2. Copy/generate ODK config
    odk_yaml = board_dir / "src" / "ontology" / f"{board_id}-odk.yaml"
    if odk_yaml.exists():
        shutil.copy2(str(odk_yaml), str(export_dir / "src" / "ontology" / f"{board_id}-odk.yaml"))
    else:
        _write_odk_yaml(export_dir / "src" / "ontology" / f"{board_id}-odk.yaml", board_id, ont_iri)

    # 3. Catalog
    catalog = board_dir / "src" / "ontology" / "catalog-v001.xml"
    if catalog.exists():
        shutil.copy2(str(catalog), str(export_dir / "src" / "ontology" / "catalog-v001.xml"))
    else:
        _write_catalog(export_dir / "src" / "ontology" / "catalog-v001.xml", board_id, ont_iri)

    # 4. Makefile
    makefile = board_dir / "src" / "ontology" / "Makefile"
    if makefile.exists():
        shutil.copy2(str(makefile), str(export_dir / "src" / "ontology" / "Makefile"))
    _write_top_makefile(export_dir / "Makefile", board_id)

    # 5. Copy patterns
    for pat in (board_dir / "src" / "ontology").glob("*.yaml"):
        shutil.copy2(str(pat), str(export_dir / "src" / "patterns" / pat.name))

    # 6. Copy SPARQL queries
    sparql_dir = board_dir / "src" / "sparql"
    if sparql_dir.exists():
        for rq in sparql_dir.glob("*.rq"):
            shutil.copy2(str(rq), str(export_dir / "src" / "sparql" / rq.name))

    # 7. Copy docs
    docs_dir = board_dir / "docs"
    if docs_dir.exists():
        for doc in docs_dir.rglob("*"):
            if doc.is_file():
                dest = export_dir / "docs" / doc.relative_to(docs_dir)
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(str(doc), str(dest))

    # 8. Copy imports
    imports_dir = board_dir / "src" / "ontology" / "imports"
    if imports_dir.exists():
        for imp in imports_dir.iterdir():
            if imp.is_file():
                shutil.copy2(str(imp), str(export_dir / "src" / "ontology" / "imports" / imp.name))

    # 9. Generate README
    _write_readme(export_dir / "README.md", board_id, ont_iri, version, meta, stats)

    # 10. Generate .gitignore
    _write_gitignore(export_dir / ".gitignore")

    # 11. Generate LICENSE (CC0)
    _write_license(export_dir / "LICENSE")

    return export_dir


def export_odp_repo_zip(board_dir: Path, board_id: str) -> bytes:
    """Generate ODP repo and return as ZIP bytes."""
    export_dir = export_odp_repo(board_dir, board_id)

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        for file in export_dir.rglob("*"):
            if file.is_file():
                arcname = str(file.relative_to(export_dir))
                zf.write(str(file), arcname)

    # Cleanup export dir
    shutil.rmtree(export_dir, ignore_errors=True)

    return buf.getvalue()


# ── Template generators ────────────────────────────────────────

def _write_odk_yaml(path: Path, board_id: str, ont_iri: str):
    path.write_text(textwrap.dedent(f"""\
        id: {board_id}
        title: {board_id}
        github_org: ""
        repo: {board_id}
        release_artefacts:
          - base: {board_id}
            formats: [owl, ttl]
        import_group:
          products: []
        robot_report:
          use_labels: true
          fail_on: ERROR
    """))


def _write_catalog(path: Path, board_id: str, ont_iri: str):
    path.write_text(textwrap.dedent(f"""\
        <?xml version="1.0" encoding="UTF-8" standalone="no"?>
        <catalog prefer="public" xmlns="urn:oasis:names:tc:entity:xmlns:xml:catalog">
            <uri id="User Coverage" name="{ont_iri}" uri="{board_id}.owl"/>
        </catalog>
    """))


def _write_top_makefile(path: Path, board_id: str):
    path.write_text(textwrap.dedent(f"""\
        # Top-level Makefile for {board_id} ODP Repository
        ONT := {board_id}

        .PHONY: all test release docs clean

        all:
        \tcd src/ontology && make all

        test:
        \tcd src/ontology && make test

        release:
        \tcd src/ontology && make release

        docs:
        \tcd src/ontology && make docs

        clean:
        \tcd src/ontology && make clean
    """))


def _write_readme(path: Path, board_id: str, ont_iri: str, version: str, meta: dict, stats: dict):
    now = datetime.now().strftime("%Y-%m-%d")
    path.write_text(textwrap.dedent(f"""\
        # {board_id}

        **Ontology IRI:** `{ont_iri}`
        **Version:** {version}

        ## Statistics

        | Metric | Count |
        |--------|-------|
        | Classes | {stats.get('classes', 0)} |
        | Object Properties | {stats.get('object_properties', 0)} |
        | Data Properties | {stats.get('data_properties', 0)} |
        | Individuals | {stats.get('individuals', 0)} |
        | Total Axioms | {stats.get('total_axioms', 0)} |
        | Total Triples | {stats.get('total_triples', 0)} |

        ## Directory Structure

        ```
        src/
          ontology/     Main ontology files, Makefile, catalog
          patterns/     DOSDP pattern templates
          sparql/       SPARQL validation queries
        docs/           Generated documentation
        ```

        ## Quick Start

        ```bash
        # Build the ontology
        cd src/ontology && make all

        # Run tests
        make test

        # Build release
        make release
        ```

        ## License

        [CC0 1.0 Universal](https://creativecommons.org/publicdomain/zero/1.0/)

        ---
        *Generated by OntoBoard on {now}*
    """))


def _write_gitignore(path: Path):
    path.write_text(textwrap.dedent("""\
        # Build artifacts
        *.tmp
        tmp_*
        report.tsv
        *.bak

        # OS
        .DS_Store
        Thumbs.db

        # IDEs
        .idea/
        .vscode/
        *.swp
    """))


def _write_license(path: Path):
    path.write_text(textwrap.dedent("""\
        CC0 1.0 Universal

        CREATIVE COMMONS CORPORATION IS NOT A LAW FIRM AND DOES NOT PROVIDE LEGAL SERVICES.

        The person who associated a work with this deed has dedicated the work to the
        public domain by waiving all of his or her rights to the work worldwide under
        copyright law, including all related and neighboring rights, to the extent
        allowed by law.

        https://creativecommons.org/publicdomain/zero/1.0/
    """))
