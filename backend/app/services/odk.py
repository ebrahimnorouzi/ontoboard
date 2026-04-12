"""ODK / ROBOT service — high-level ontology operations.

Docker-level ROBOT execution is delegated to robot.py.
This module handles: OWL parsing, template generation, build streaming.
"""

import asyncio
import csv
import json
from pathlib import Path

import docker

from app.config import DATA_DIR, ODK_IMAGE
from app.schemas.board import CanvasGraph
from app.services.robot import robot_convert as _robot_convert
from app.services.robot import robot_template as _robot_template


def _docker_mount_path(host_path: Path) -> str:
    """Convert a host path to a Docker-compatible mount path.
    On Windows, converts C:\\Users\\... to /c/Users/... for Docker Desktop.
    """
    p = str(host_path).replace("\\", "/")
    if len(p) >= 2 and p[1] == ":":
        p = "/" + p[0].lower() + p[2:]
    return p


# ── OWL helpers ────────────────────────────────────────────────
def find_owl_file(board_dir: Path) -> Path | None:
    """Find the best OWL file to use for ROBOT commands.

    Prefers the -edit.owl file (working copy in ODK convention) over the
    main release file, which often contains owl:imports that reference
    the edit file and would cause ROBOT to fail if the import can't be
    resolved.
    """
    ont_dir = board_dir / "src" / "ontology"
    if not ont_dir.exists():
        return None
    board_id = board_dir.name
    # Prefer -edit.owl (ODK working copy)
    edit_owl = ont_dir / f"{board_id}-edit.owl"
    if edit_owl.exists():
        return edit_owl
    # Fallback: any -edit.owl
    edit_files = list(ont_dir.glob("*-edit.owl"))
    if edit_files:
        return edit_files[0]
    # Fallback: any .owl file
    owl_files = list(ont_dir.glob("*.owl"))
    return owl_files[0] if owl_files else None


async def robot_convert(board_dir: Path, owl_file: Path, json_ld_path: Path) -> None:
    """Convert OWL → JSON-LD via ROBOT (backward-compat wrapper)."""
    await _robot_convert(board_dir, owl_file, json_ld_path, output_format="json")


def parse_json_ld(json_ld_path: Path) -> CanvasGraph:
    data = json.loads(json_ld_path.read_text())
    classes, properties = [], []
    y_offset = 0

    nodes = data if isinstance(data, list) else data.get("@graph", [data])
    for i, node in enumerate(nodes):
        node_types = node.get("@type", [])
        if isinstance(node_types, str):
            node_types = [node_types]
        node_id = node.get("@id", f"node_{i}")

        if "owl:Class" in node_types or "http://www.w3.org/2002/07/owl#Class" in node_types:
            label = node.get("rdfs:label", node.get("label", node_id))
            if isinstance(label, dict):
                label = label.get("@value", node_id)
            classes.append({"id": node_id, "label": label, "x": 100, "y": 100 + y_offset, "iri": node_id})
            y_offset += 120

        if "owl:ObjectProperty" in node_types or "http://www.w3.org/2002/07/owl#ObjectProperty" in node_types:
            label = node.get("rdfs:label", node.get("label", node_id))
            if isinstance(label, dict):
                label = label.get("@value", node_id)
            domain = node.get("rdfs:domain", {})
            range_ = node.get("rdfs:range", {})
            properties.append({
                "id": node_id,
                "source": domain.get("@id", "") if isinstance(domain, dict) else domain,
                "target": range_.get("@id", "") if isinstance(range_, dict) else range_,
                "label": label,
                "iri": node_id,
            })

    return CanvasGraph(classes=classes, properties=properties)


def generate_robot_template(graph: CanvasGraph, output_path: Path) -> None:
    with open(output_path, "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["ID", "Label", "Type"])
        writer.writerow(["ID", "A rdfs:label", ""])
        for cls in graph.classes:
            writer.writerow([cls["iri"], cls["label"], "owl:Class"])
        for prop in graph.properties:
            writer.writerow([prop["iri"], prop["label"], "owl:ObjectProperty"])


def generate_kg_template(mappings: list, csv_file: Path, template_path: Path) -> None:
    source_data = csv_file.read_text()
    sep = "\t" if "\t" in source_data.split("\n")[0] else ","
    reader = csv.DictReader(source_data.splitlines(), delimiter=sep)

    with open(template_path, "w", newline="") as f:
        writer = csv.writer(f)
        cols = ["ID"] + [m["column"] for m in mappings]
        writer.writerow(cols)
        template_row = ["ID"] + [f"A {m['class_iri']}" for m in mappings]
        writer.writerow(template_row)
        for i, row in enumerate(reader):
            data_row = [f"http://example.org/instance/{i}"]
            for m in mappings:
                data_row.append(row.get(m["column"], ""))
            writer.writerow(data_row)


async def robot_template(board_dir: Path, template: Path, output: Path) -> None:
    """Run ROBOT template (backward-compat wrapper)."""
    await _robot_template(board_dir, template, output)


async def stream_build(board_dir: Path, target: str):
    """Generator that yields SSE lines from an ODK build using sh run.sh make pattern."""
    loop = asyncio.get_event_loop()

    try:
        client = docker.from_env()
    except Exception as exc:
        yield f"data: [ERROR] Docker unavailable: {exc}\n\n"
        yield f"data: [EXIT 1]\n\n"
        return

    # Determine the correct working directory by finding the Makefile
    makefile_primary = board_dir / "src" / "ontology" / "Makefile"
    makefile_fallback = board_dir / "Makefile"

    if makefile_primary.exists():
        working_dir = "/work/src/ontology"
        makefile_path = makefile_primary
    elif makefile_fallback.exists():
        working_dir = "/work"
        makefile_path = makefile_fallback
    else:
        # Search recursively for a Makefile
        found = None
        for mf in board_dir.rglob("Makefile"):
            if ".git" not in str(mf):
                found = mf
                break
        if found:
            rel = found.parent.relative_to(board_dir)
            working_dir = f"/work/{str(rel).replace(chr(92), '/')}"
            makefile_path = found
        else:
            yield f"data: [ERROR] No Makefile found in {board_dir}. Run 'ODK Seed' first.\n\n"
            yield f"data: [EXIT 1]\n\n"
            return

    # Verify the target exists in the Makefile
    try:
        mk_content = makefile_path.read_text()
        if target + ":" not in mk_content and target != "all":
            targets = [line.split(":")[0] for line in mk_content.splitlines()
                       if ":" in line and not line.startswith("#")
                       and not line.startswith("\t") and not line.startswith(" ")]
            targets_str = ", ".join(targets)
            yield f"data: [WARN] Target '{target}' not found in Makefile. Available: {targets_str}\n\n"
    except Exception:
        pass

    mount_path = _docker_mount_path(board_dir)
    yield f"data: $ make {target}\n\n"
    yield f"data: [mount: {mount_path} -> /work, cwd: {working_dir}]\n\n"

    container = await loop.run_in_executor(
        None,
        lambda: client.containers.run(
            image=ODK_IMAGE,
            command=f"make {target}",
            volumes={mount_path: {"bind": "/work", "mode": "rw"}},
            working_dir=working_dir,
            remove=False,
            detach=True,
            stdout=True,
            stderr=True,
        ),
    )

    try:
        for chunk in container.logs(stream=True, follow=True):
            line = chunk.decode("utf-8", errors="replace")
            yield f"data: {line}\n\n"
        result = await loop.run_in_executor(None, container.wait)
        code = result.get("StatusCode", -1)
        yield f"data: [EXIT {code}]\n\n"
    finally:
        try:
            await loop.run_in_executor(None, container.remove)
        except Exception:
            pass
