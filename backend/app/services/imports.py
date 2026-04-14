"""Import management service — add/remove owl:imports, catalog.xml, Makefile.

Extended with full OWL import resolution: resolve status, download remote
imports, and manage catalog-v001.xml mappings.
"""

import logging
import re
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path

import urllib.request
from rdflib import Graph, URIRef, Literal, RDF, RDFS, OWL

from app.services.ontology import load_graph

logger = logging.getLogger("ontoboard.imports")

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _get_ontology_node(g: Graph):
    for s in g.subjects(RDF.type, OWL.Ontology):
        return s
    return None


def _guess_prefix(iri: str) -> str:
    local = _local(iri)
    return local.replace(".owl", "").replace(".obo", "").lower()


def _local(iri: str) -> str:
    return iri.rsplit("/", 1)[-1]


def _save(g: Graph, board_dir: Path):
    owl_files = list((board_dir / "src" / "ontology").glob("*.owl"))
    if owl_files:
        g.serialize(str(owl_files[0]), format="xml")


def _count_entities(g: Graph) -> tuple[int, int]:
    """Return (classes_count, properties_count) for a graph."""
    classes = len(set(g.subjects(RDF.type, OWL.Class)))
    obj_props = len(set(g.subjects(RDF.type, OWL.ObjectProperty)))
    data_props = len(set(g.subjects(RDF.type, OWL.DatatypeProperty)))
    ann_props = len(set(g.subjects(RDF.type, OWL.AnnotationProperty)))
    return classes, obj_props + data_props + ann_props


def _parse_file_safe(path: Path) -> Graph | None:
    """Try to parse an RDF file, returning the graph or None."""
    g = Graph()
    for fmt in ("xml", "turtle", "n3", "nt", "json-ld"):
        try:
            g.parse(str(path), format=fmt)
            return g
        except Exception:
            continue
    return None


# ---------------------------------------------------------------------------
# Catalog helpers
# ---------------------------------------------------------------------------

_CATALOG_NS = "urn:oasis:names:tc:entity:xmlns:xml:catalog"


def _catalog_path(board_dir: Path) -> Path:
    return board_dir / "src" / "ontology" / "catalog-v001.xml"


def _read_catalog(board_dir: Path) -> dict[str, str]:
    """Return {iri: local_relative_path} from catalog-v001.xml."""
    cat = _catalog_path(board_dir)
    mapping: dict[str, str] = {}
    if not cat.exists():
        return mapping
    try:
        tree = ET.parse(str(cat))
        root = tree.getroot()
        for child in root:
            tag = child.tag
            # Strip namespace
            if "}" in tag:
                tag = tag.split("}", 1)[1]
            if tag == "uri":
                name = child.get("name", "")
                uri = child.get("uri", "")
                if name:
                    mapping[name] = uri
    except Exception:
        pass
    return mapping


def _write_catalog(board_dir: Path, mapping: dict[str, str]):
    """Write catalog-v001.xml from a {iri: local_path} mapping."""
    cat = _catalog_path(board_dir)
    cat.parent.mkdir(parents=True, exist_ok=True)

    root = ET.Element("catalog")
    root.set("xmlns", _CATALOG_NS)
    root.set("prefer", "public")

    for iri, local in sorted(mapping.items()):
        uri_elem = ET.SubElement(root, "uri")
        uri_elem.set("id", str(uuid.uuid4())[:8])
        uri_elem.set("name", iri)
        uri_elem.set("uri", local)

    tree = ET.ElementTree(root)
    ET.indent(tree, space="    ")
    tree.write(str(cat), xml_declaration=True, encoding="UTF-8")


# ---------------------------------------------------------------------------
# Core API: list / add / remove (original)
# ---------------------------------------------------------------------------

def list_imports(board_dir: Path) -> list[dict]:
    """List all owl:imports in the ontology."""
    g = load_graph(board_dir)
    imports = []
    for s in g.subjects(RDF.type, OWL.Ontology):
        for o in g.objects(s, OWL.imports):
            iri = str(o)
            mirrored = (board_dir / "imports" / _local(iri)).exists()
            imports.append({"iri": iri, "prefix": _guess_prefix(iri), "mirrored": mirrored})
    return imports


def add_import(board_dir: Path, iri: str, prefix: str = "") -> bool:
    """Add an owl:imports to the ontology and update catalog-v001.xml."""
    g = load_graph(board_dir)
    ont_node = _get_ontology_node(g)
    if not ont_node:
        return False

    import_uri = URIRef(iri)
    g.add((ont_node, OWL.imports, import_uri))
    _save(g, board_dir)

    # Update catalog
    _add_catalog_entry(board_dir, iri, prefix)

    # Update Makefile IMPORT_MODULES
    if prefix:
        _add_makefile_import(board_dir, prefix)

    return True


def remove_import(board_dir: Path, iri: str, delete_local: bool = False) -> bool:
    """Remove an owl:imports from the ontology, optionally removing local file."""
    g = load_graph(board_dir)
    ont_node = _get_ontology_node(g)
    if not ont_node:
        return False

    g.remove((ont_node, OWL.imports, URIRef(iri)))
    _save(g, board_dir)

    # Remove from catalog
    catalog = _read_catalog(board_dir)
    local_rel = catalog.get(iri)
    _remove_catalog_entry(board_dir, iri)

    # Optionally remove local file
    if delete_local and local_rel:
        local_file = board_dir / "src" / "ontology" / local_rel
        if local_file.exists():
            local_file.unlink()

    return True


# ---------------------------------------------------------------------------
# Full import resolution
# ---------------------------------------------------------------------------

def resolve_imports(board_dir: Path) -> list[dict]:
    """Resolve all owl:imports and check their local/remote/missing status.

    Returns list of dicts with keys:
        iri, status ("local"|"remote"|"missing"), local_path, classes_count, properties_count
    """
    g = load_graph(board_dir)
    catalog = _read_catalog(board_dir)
    ont_dir = board_dir / "src" / "ontology"
    results: list[dict] = []

    for s in g.subjects(RDF.type, OWL.Ontology):
        for o in g.objects(s, OWL.imports):
            iri = str(o)
            local_rel = catalog.get(iri)
            local_path: str | None = None
            status = "missing"
            classes_count = 0
            properties_count = 0

            if local_rel:
                abs_path = ont_dir / local_rel
                if abs_path.exists():
                    status = "local"
                    local_path = local_rel
                    # Parse to get counts
                    parsed = _parse_file_safe(abs_path)
                    if parsed:
                        classes_count, properties_count = _count_entities(parsed)
                else:
                    # Catalog entry exists but file is missing
                    status = "missing"
                    local_path = local_rel
            else:
                # No catalog entry — check if it looks like a downloadable IRI
                if iri.startswith("http://") or iri.startswith("https://"):
                    status = "remote"
                else:
                    status = "missing"

            results.append({
                "iri": iri,
                "status": status,
                "local_path": local_path,
                "classes_count": classes_count,
                "properties_count": properties_count,
            })

    return results


def download_import(board_dir: Path, import_iri: str) -> dict:
    """Download an import ontology from its IRI and save locally.

    Steps:
        1. HTTP GET the IRI (with Accept header for RDF/XML)
        2. Save to src/ontology/imports/<filename>
        3. Update catalog-v001.xml
        4. Parse to count classes/properties

    Returns: {"success": bool, "local_path": str, "classes_count": int, "properties_count": int, "error": str|None}
    """
    imports_dir = board_dir / "src" / "ontology" / "imports"
    imports_dir.mkdir(parents=True, exist_ok=True)

    filename = _local(import_iri)
    # Ensure it has an extension
    if "." not in filename:
        filename += ".owl"
    local_file = imports_dir / filename
    local_rel = f"imports/{filename}"

    try:
        req = urllib.request.Request(
            import_iri,
            headers={"Accept": "application/rdf+xml, application/owl+xml, text/turtle, */*;q=0.5"},
        )
        with urllib.request.urlopen(req, timeout=60) as resp:
            local_file.write_bytes(resp.read())
        logger.info("Downloaded import %s -> %s", import_iri, local_file)
    except Exception as exc:
        logger.error("Failed to download %s: %s", import_iri, exc)
        return {
            "success": False,
            "local_path": local_rel,
            "classes_count": 0,
            "properties_count": 0,
            "error": str(exc),
        }

    # Update catalog
    update_catalog(board_dir, import_iri, local_rel)

    # Parse downloaded file for counts
    classes_count = 0
    properties_count = 0
    parsed = _parse_file_safe(local_file)
    if parsed:
        classes_count, properties_count = _count_entities(parsed)

    return {
        "success": True,
        "local_path": local_rel,
        "classes_count": classes_count,
        "properties_count": properties_count,
        "error": None,
    }


def update_catalog(board_dir: Path, iri: str, local_path: str):
    """Add or update a single IRI -> local_path mapping in catalog-v001.xml."""
    catalog = _read_catalog(board_dir)
    catalog[iri] = local_path
    _write_catalog(board_dir, catalog)


# ---------------------------------------------------------------------------
# Legacy catalog helpers (kept for backward compat)
# ---------------------------------------------------------------------------

def _add_catalog_entry(board_dir: Path, iri: str, prefix: str):
    update_catalog(board_dir, iri, f"imports/{_local(iri)}")


def _remove_catalog_entry(board_dir: Path, iri: str):
    catalog = _read_catalog(board_dir)
    catalog.pop(iri, None)
    _write_catalog(board_dir, catalog)


def _add_makefile_import(board_dir: Path, prefix: str):
    makefile = board_dir / "src" / "ontology" / "Makefile"
    if not makefile.exists():
        return
    content = makefile.read_text()
    # Look for IMPORT_MODULES line
    if "IMPORT_MODULES" in content:
        content = re.sub(
            r"(IMPORT_MODULES\s*:?=\s*)(.*)",
            lambda m: f"{m.group(1)}{m.group(2).strip()} {prefix}",
            content, count=1,
        )
    else:
        content += f"\nIMPORT_MODULES := {prefix}\n"
    makefile.write_text(content)
