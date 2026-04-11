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


# ── OWL helpers ────────────────────────────────────────────────
def find_owl_file(board_dir: Path) -> Path | None:
    ont_dir = board_dir / "src" / "ontology"
    if not ont_dir.exists():
        return None
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
    client = docker.from_env()

    # Use the ODK pattern: run make inside odkfull container at /work/src/ontology
    container = await loop.run_in_executor(
        None,
        lambda: client.containers.run(
            image=ODK_IMAGE,
            command=f"sh -c 'cd /work/src/ontology && make {target}'",
            volumes={str(board_dir): {"bind": "/work", "mode": "rw"}},
            working_dir="/work",
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
