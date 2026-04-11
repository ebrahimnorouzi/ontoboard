"""Import management service — add/remove owl:imports, catalog.xml, Makefile."""

import re
import xml.etree.ElementTree as ET
from pathlib import Path

from rdflib import Graph, URIRef, Literal, RDF, RDFS, OWL

from app.services.ontology import load_graph


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


def remove_import(board_dir: Path, iri: str) -> bool:
    """Remove an owl:imports from the ontology."""
    g = load_graph(board_dir)
    ont_node = _get_ontology_node(g)
    if not ont_node:
        return False

    g.remove((ont_node, OWL.imports, URIRef(iri)))
    _save(g, board_dir)

    # Remove from catalog
    _remove_catalog_entry(board_dir, iri)
    return True


def _get_ontology_node(g: Graph):
    for s in g.subjects(RDF.type, OWL.Ontology):
        return s
    return None


def _add_catalog_entry(board_dir: Path, iri: str, prefix: str):
    catalog = board_dir / "src" / "ontology" / "catalog-v001.xml"
    if not catalog.exists():
        return
    try:
        tree = ET.parse(str(catalog))
        root = tree.getroot()
        ns = "urn:oasis:names:tc:entity:xmlns:xml:catalog"
        uri_elem = ET.SubElement(root, f"{{{ns}}}uri" if ns in root.tag else "uri")
        uri_elem.set("id", prefix or _guess_prefix(iri))
        uri_elem.set("name", iri)
        uri_elem.set("uri", f"imports/{_local(iri)}")
        tree.write(str(catalog), xml_declaration=True, encoding="UTF-8")
    except Exception:
        pass


def _remove_catalog_entry(board_dir: Path, iri: str):
    catalog = board_dir / "src" / "ontology" / "catalog-v001.xml"
    if not catalog.exists():
        return
    try:
        tree = ET.parse(str(catalog))
        root = tree.getroot()
        for child in list(root):
            if child.get("name") == iri:
                root.remove(child)
        tree.write(str(catalog), xml_declaration=True, encoding="UTF-8")
    except Exception:
        pass


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


def _guess_prefix(iri: str) -> str:
    local = _local(iri)
    return local.replace(".owl", "").replace(".obo", "").lower()


def _local(iri: str) -> str:
    return iri.rsplit("/", 1)[-1]


def _save(g: Graph, board_dir: Path):
    owl_files = list((board_dir / "src" / "ontology").glob("*.owl"))
    if owl_files:
        g.serialize(str(owl_files[0]), format="xml")
