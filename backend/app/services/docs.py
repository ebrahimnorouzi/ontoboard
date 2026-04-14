"""Documentation generation service — ODK mkdocs + ontology stats dashboard."""

import datetime
import json
import logging
from pathlib import Path

from app.services.robot import run_robot
from app.services.ontology import load_graph, get_ontology_metadata, get_ontology_statistics

logger = logging.getLogger("ontoboard.docs")


async def generate_docs(board_dir: Path):
    """Run `make docs` inside ODK container, yielding SSE events."""
    yield _sse("start", "Starting documentation generation...", 0)

    # Step 1: Generate ontology summary as a markdown file
    yield _sse("summary", "Generating ontology summary...", 10)
    try:
        summary = _generate_summary_md(board_dir)
        summary_path = board_dir / "docs" / "ontology-summary.md"
        summary_path.parent.mkdir(parents=True, exist_ok=True)
        summary_path.write_text(summary)
        yield _sse("summary_done", f"Summary written ({len(summary)} chars)", 25)
    except Exception as exc:
        yield _sse("summary_warn", f"Summary generation skipped: {exc}", 25)

    # Step 2: Try ODK make docs
    yield _sse("odk_docs", "Running ODK make docs...", 30)
    result = await run_robot(
        board_dir,
        "make docs",
        working_dir="/work/src/ontology",
        timeout=300,
    )

    if result.success:
        yield _sse("odk_done", "ODK documentation generated", 80)
        for line in result.stdout.strip().split("\n")[-5:]:
            if line.strip():
                yield _sse("log", line.strip(), 80)
    elif result.exit_code == -1:
        yield _sse("odk_skip", "ODK unavailable — using summary only", 80)
    else:
        yield _sse("odk_warn", f"make docs failed: {result.stderr[:200]}", 80)

    # Step 3: Try Widoco HTML documentation
    import shutil, os
    widoco_jar = Path("/usr/local/bin/widoco.jar")
    widoco_version = os.environ.get("WIDOCO_VERSION", "1.4.25")
    if widoco_jar.exists():
        yield _sse("widoco", f"Running Widoco v{widoco_version} documentation generator...", 85)
        owl_files = list((board_dir / "src" / "ontology").glob("*.owl"))
        if owl_files:
            # Prefer -edit.owl
            owl_file = owl_files[0]
            for f in owl_files:
                if "-edit.owl" in f.name:
                    owl_file = f
                    break
            import subprocess
            widoco_result = subprocess.run(
                ["java", "-jar", str(widoco_jar), "-ontFile", str(owl_file),
                 "-outFolder", str(board_dir / "docs" / "widoco"),
                 "-rewriteAll", "-crossRef", "-uniteSections", "-lang", "en",
                 "-getOntologyMetadata"],
                capture_output=True, text=True, timeout=300,
                cwd=str(board_dir),
            )
            if widoco_result.returncode == 0:
                yield _sse("widoco_done", f"Widoco v{widoco_version} HTML documentation generated in docs/widoco/", 95)
            else:
                yield _sse("widoco_warn", f"Widoco v{widoco_version} failed: {widoco_result.stderr[:200]}", 95)
    else:
        yield _sse("info", "Widoco not installed — Markdown summary generated instead. "
                    "Rebuild the backend image (./run.sh build) to install Widoco.", 95)

    # Step 4: Save build status
    _save_docs_status(board_dir)
    yield _sse("done", "Documentation generation complete", 100)


def get_docs_status(board_dir: Path) -> dict:
    """Check documentation build status."""
    docs_dir = board_dir / "docs"
    status_file = board_dir / ".docs_status.json"

    generated = docs_dir.exists() and any(docs_dir.iterdir()) if docs_dir.exists() else False
    page_count = sum(1 for _ in docs_dir.rglob("*.md")) if docs_dir.exists() else 0
    page_count += sum(1 for _ in docs_dir.rglob("*.html")) if docs_dir.exists() else 0

    last_built = None
    if status_file.exists():
        data = json.loads(status_file.read_text())
        last_built = data.get("last_built")

    return {
        "generated": generated,
        "last_built": last_built,
        "page_count": page_count,
        "output_dir": str(docs_dir),
        "index_url": f"/api/docs/{board_dir.name}/serve/ontology-summary.md" if generated else None,
    }


def get_docs_content(board_dir: Path, file_path: str) -> str | None:
    """Read a documentation file."""
    docs_dir = board_dir / "docs"
    target = docs_dir / file_path

    # Prevent path traversal
    try:
        target.resolve().relative_to(docs_dir.resolve())
    except ValueError:
        return None

    if target.exists() and target.is_file():
        return target.read_text(errors="replace")
    return None


def list_docs_files(board_dir: Path) -> list[str]:
    """List all documentation files."""
    docs_dir = board_dir / "docs"
    if not docs_dir.exists():
        return []
    return sorted(
        str(f.relative_to(docs_dir))
        for f in docs_dir.rglob("*")
        if f.is_file() and f.suffix in (".md", ".html", ".css", ".js", ".txt")
    )


def _generate_summary_md(board_dir: Path) -> str:
    """Generate a markdown summary of the ontology."""
    g = load_graph(board_dir)
    meta = get_ontology_metadata(g)
    stats = get_ontology_statistics(g)

    lines = [
        f"# {meta['ontology_iri'] or 'Ontology'} Documentation",
        "",
        "## Metadata",
        "",
        f"| Property | Value |",
        f"|----------|-------|",
        f"| **Ontology IRI** | `{meta['ontology_iri']}` |",
        f"| **Version IRI** | `{meta['version_iri'] or 'N/A'}` |",
        f"| **Languages** | {', '.join(meta['languages']) or 'N/A'} |",
    ]

    if meta["imports"]:
        lines.append(f"| **Imports** | {', '.join(f'`{i}`' for i in meta['imports'])} |")

    lines += [
        "",
        "## Statistics",
        "",
        f"| Metric | Count |",
        f"|--------|-------|",
        f"| Classes | **{stats['classes']}** |",
        f"| Object Properties | **{stats['object_properties']}** |",
        f"| Data Properties | **{stats['data_properties']}** |",
        f"| Annotation Properties | **{stats['annotation_properties']}** |",
        f"| Individuals | **{stats['individuals']}** |",
        f"| Total Axioms | **{stats['total_axioms']}** |",
        f"| Total Triples | **{stats['total_triples']}** |",
        "",
        "### Axiom Breakdown",
        "",
        f"- SubClassOf: {stats['subclass_axioms']}",
        f"- EquivalentClass: {stats['equivalent_axioms']}",
        f"- DisjointWith: {stats['disjoint_axioms']}",
        f"- Domain: {stats['domain_axioms']}",
        f"- Range: {stats['range_axioms']}",
        "",
        "## Prefixes",
        "",
        "| Prefix | Namespace |",
        "|--------|-----------|",
    ]

    for p in meta["prefixes"][:20]:
        lines.append(f"| `{p['prefix']}` | `{p['namespace']}` |")

    lines += [
        "",
        "---",
        f"*Generated by OntoBoard on {datetime.datetime.now().strftime('%Y-%m-%d %H:%M')}*",
    ]

    return "\n".join(lines)


def _save_docs_status(board_dir: Path):
    status_file = board_dir / ".docs_status.json"
    status_file.write_text(json.dumps({
        "last_built": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    }))


def _sse(step: str, message: str, progress: float) -> str:
    data = json.dumps({"step": step, "message": message, "progress": progress})
    return f"data: {data}\n\n"
