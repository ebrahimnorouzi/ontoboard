"""ID range service — Protege/ODK-style ID range allocation per user.

Supports two storage formats:
1. OWL Functional Syntax (*-idranges.owl) — standard ODK format
2. XML (idranges.xml) — OntoBoard's simple format (legacy)

The OWL format is preferred and used when importing from ODK repositories.
"""

import re
import xml.etree.ElementTree as ET
from pathlib import Path

DEFAULT_BLOCK_SIZE = 10000


# ═══════════════════════════════════════════════════════════════
# Format Detection & Loading
# ═══════════════════════════════════════════════════════════════

def _find_idranges_file(board_dir: Path) -> tuple[Path | None, str]:
    """Find the ID ranges file and detect its format.

    Returns (path, format) where format is "owl" or "xml".
    """
    ont_dir = board_dir / "src" / "ontology"
    if not ont_dir.exists():
        return None, ""

    # Prefer OWL format (*-idranges.owl)
    for f in ont_dir.glob("*-idranges.owl"):
        return f, "owl"

    # Fallback to XML
    xml_path = ont_dir / "idranges.xml"
    if xml_path.exists():
        return xml_path, "xml"

    return None, ""


# ═══════════════════════════════════════════════════════════════
# OWL Functional Syntax Parser
# ═══════════════════════════════════════════════════════════════

def _parse_owl_idranges(path: Path) -> dict:
    """Parse an ODK-style *-idranges.owl file (OWL Functional Syntax).

    Extracts:
    - idsfor: ontology ID
    - idprefix: IRI prefix for new entities
    - iddigits: number of digits for IDs
    - ranges: list of {user, lower, upper}
    """
    content = path.read_text(encoding="utf-8", errors="replace")

    # Extract ontology-level annotations
    idsfor = ""
    idprefix = ""
    iddigits = 7

    m = re.search(r'idsfor:\s*"([^"]*)"', content)
    if m:
        idsfor = m.group(1)
    m = re.search(r'idprefix:\s*"([^"]*)"', content)
    if m:
        idprefix = m.group(1)
    m = re.search(r'iddigits:\s*(\d+)', content)
    if m:
        iddigits = int(m.group(1))

    # Extract ranges: Datatype blocks with allocatedto and integer bounds
    ranges = []
    # Pattern: Datatype: idrange:N ... allocatedto: "username" ... >= lower , <= upper
    pattern = re.compile(
        r'Datatype:\s*idrange:(\d+)\s*'
        r'Annotations:\s*allocatedto:\s*"([^"]*)"\s*'
        r'EquivalentTo:\s*xsd:integer\[>=\s*(\d+)\s*,\s*<=\s*(\d+)\s*\]',
        re.DOTALL
    )
    for m in pattern.finditer(content):
        range_id, user, lower, upper = m.groups()
        ranges.append({
            "user": user,
            "prefix": idprefix,
            "lower": lower.zfill(iddigits),
            "upper": upper.zfill(iddigits),
            "current": lower.zfill(iddigits),
            "range_id": int(range_id),
        })

    return {
        "idsfor": idsfor,
        "idprefix": idprefix,
        "iddigits": iddigits,
        "ranges": ranges,
        "ontology_iri": _extract_ontology_iri(content),
    }


def _extract_ontology_iri(content: str) -> str:
    m = re.search(r'Ontology:\s*<([^>]*)>', content)
    return m.group(1) if m else ""


def _generate_owl_idranges(board_id: str, idprefix: str, iddigits: int,
                            ranges: list[dict], ontology_iri: str = "") -> str:
    """Generate OWL Functional Syntax content for ID ranges."""
    if not ontology_iri:
        ontology_iri = f"http://example.org/{board_id}/{board_id}-idranges.owl"

    lines = [
        "## ID Ranges File",
        "Prefix: rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>",
        "Prefix: idsfor: <http://purl.obolibrary.org/obo/IAO_0000598>",
        "Prefix: dce: <http://purl.org/dc/elements/1.1/>",
        "Prefix: xsd: <http://www.w3.org/2001/XMLSchema#>",
        "Prefix: allocatedto: <http://purl.obolibrary.org/obo/IAO_0000597>",
        "Prefix: xml: <http://www.w3.org/XML/1998/namespace>",
        "Prefix: idprefix: <http://purl.obolibrary.org/obo/IAO_0000599>",
        "Prefix: iddigits: <http://purl.obolibrary.org/obo/IAO_0000596>",
        "Prefix: rdfs: <http://www.w3.org/2000/01/rdf-schema#>",
        f"Prefix: idrange: <{idprefix.rsplit('/', 1)[0]}/idrange/>",
        "Prefix: owl: <http://www.w3.org/2002/07/owl#>",
        "",
        f"Ontology: <{ontology_iri}>",
        "",
        "",
        "Annotations: ",
        f'    idsfor: "{board_id.upper()}",',
        f'    idprefix: "{idprefix}",',
        f"    iddigits: {iddigits}",
        "",
        "AnnotationProperty: idprefix:",
        "",
        "    ",
        "AnnotationProperty: iddigits:",
        "",
        "    ",
        "AnnotationProperty: idsfor:",
        "",
        "    ",
        "AnnotationProperty: allocatedto:",
        "",
    ]

    for r in ranges:
        rid = r.get("range_id", ranges.index(r) + 1)
        lower = int(r["lower"])
        upper = int(r["upper"])
        lines.extend([
            f"Datatype: idrange:{rid}",
            "",
            "    Annotations: ",
            f'        allocatedto: "{r["user"]}"',
            "    ",
            "    EquivalentTo: ",
            f"        xsd:integer[>= {lower} , <= {upper}]",
            "",
            "    ",
        ])

    lines.extend([
        "Datatype: xsd:integer",
        "Datatype: rdf:PlainLiteral",
        "",
    ])

    return "\n".join(lines)


# ═══════════════════════════════════════════════════════════════
# Public API
# ═══════════════════════════════════════════════════════════════

def parse_idranges(board_dir: Path) -> list[dict]:
    """Read ID ranges from whatever format exists."""
    path, fmt = _find_idranges_file(board_dir)
    if not path:
        return []

    if fmt == "owl":
        data = _parse_owl_idranges(path)
        return data["ranges"]

    # XML format
    tree = ET.parse(str(path))
    ranges = []
    for elem in tree.findall("range"):
        ranges.append({
            "user": elem.get("user", ""),
            "prefix": elem.get("prefix", ""),
            "lower": elem.get("lower", ""),
            "upper": elem.get("upper", ""),
            "current": elem.get("current", ""),
        })
    return ranges


def generate_idranges_xml(board_dir: Path, board_id: str) -> Path:
    """Generate initial ID ranges file (OWL format for ODK compatibility)."""
    ont_dir = board_dir / "src" / "ontology"
    ont_dir.mkdir(parents=True, exist_ok=True)

    # Check if OWL format already exists
    for f in ont_dir.glob("*-idranges.owl"):
        return f  # Already exists

    idprefix = f"http://example.org/{board_id}/{board_id.upper()}_"
    content = _generate_owl_idranges(
        board_id, idprefix, 7,
        [{"user": "default", "lower": "0000001", "upper": str(DEFAULT_BLOCK_SIZE).zfill(7),
          "current": "0000001", "range_id": 1}],
    )
    path = ont_dir / f"{board_id}-idranges.owl"
    path.write_text(content, encoding="utf-8")
    return path


def allocate_range(board_dir: Path, username: str, prefix: str = "") -> dict:
    """Allocate a new ID range block for a user."""
    path, fmt = _find_idranges_file(board_dir)

    if not path:
        generate_idranges_xml(board_dir, board_dir.name)
        path, fmt = _find_idranges_file(board_dir)

    if fmt == "owl":
        return _allocate_owl_range(path, username)

    # XML fallback
    return _allocate_xml_range(path, board_dir, username, prefix)


def _allocate_owl_range(path: Path, username: str) -> dict:
    """Allocate a range in OWL format."""
    data = _parse_owl_idranges(path)
    ranges = data["ranges"]

    # Find max upper and next range_id
    max_upper = 0
    max_rid = 0
    for r in ranges:
        max_upper = max(max_upper, int(r["upper"]))
        max_rid = max(max_rid, r.get("range_id", 0))

    lower = max_upper + 1
    upper = lower + DEFAULT_BLOCK_SIZE - 1
    new_range = {
        "user": username,
        "prefix": data["idprefix"],
        "lower": str(lower).zfill(data["iddigits"]),
        "upper": str(upper).zfill(data["iddigits"]),
        "current": str(lower).zfill(data["iddigits"]),
        "range_id": max_rid + 1,
    }
    ranges.append(new_range)

    # Rewrite file
    content = _generate_owl_idranges(
        data["idsfor"] or path.stem.replace("-idranges", ""),
        data["idprefix"],
        data["iddigits"],
        ranges,
        data["ontology_iri"],
    )
    path.write_text(content, encoding="utf-8")
    return new_range


def _allocate_xml_range(path: Path, board_dir: Path, username: str, prefix: str) -> dict:
    """Allocate a range in XML format."""
    tree = ET.parse(str(path))
    root = tree.getroot()

    max_upper = 0
    for elem in root.findall("range"):
        try:
            max_upper = max(max_upper, int(elem.get("upper", "0")))
        except ValueError:
            pass

    lower = max_upper + 1
    upper = lower + DEFAULT_BLOCK_SIZE - 1

    if not prefix:
        ont = root.get("ontology", board_dir.name)
        prefix = f"http://example.org/{ont}#"

    ET.SubElement(root, "range", attrib={
        "user": username, "prefix": prefix,
        "lower": str(lower).zfill(7), "upper": str(upper).zfill(7),
        "current": str(lower).zfill(7),
    })
    tree.write(str(path), xml_declaration=True, encoding="UTF-8")

    return {"user": username, "prefix": prefix, "lower": str(lower).zfill(7),
            "upper": str(upper).zfill(7), "current": str(lower).zfill(7)}


def update_range(board_dir: Path, username: str, *, prefix: str | None = None,
                  lower: str | None = None, upper: str | None = None) -> dict | None:
    """Update an existing ID range for a user."""
    path, fmt = _find_idranges_file(board_dir)
    if not path:
        return None

    if fmt == "owl":
        data = _parse_owl_idranges(path)
        for r in data["ranges"]:
            if r["user"] == username:
                if prefix is not None:
                    r["prefix"] = prefix
                if lower is not None:
                    r["lower"] = lower
                if upper is not None:
                    r["upper"] = upper
                content = _generate_owl_idranges(
                    data["idsfor"] or path.stem.replace("-idranges", ""),
                    data["idprefix"], data["iddigits"],
                    data["ranges"], data["ontology_iri"],
                )
                path.write_text(content, encoding="utf-8")
                return r
        return None

    # XML
    tree = ET.parse(str(path))
    for elem in tree.findall(".//range"):
        if elem.get("user") == username:
            if prefix is not None:
                elem.set("prefix", prefix)
            if lower is not None:
                elem.set("lower", lower)
            if upper is not None:
                elem.set("upper", upper)
            tree.write(str(path), xml_declaration=True, encoding="UTF-8")
            return {
                "user": elem.get("user", ""), "prefix": elem.get("prefix", ""),
                "lower": elem.get("lower", ""), "upper": elem.get("upper", ""),
                "current": elem.get("current", ""),
            }
    return None


def delete_range(board_dir: Path, username: str) -> bool:
    """Delete an ID range for a user."""
    path, fmt = _find_idranges_file(board_dir)
    if not path:
        return False

    if fmt == "owl":
        data = _parse_owl_idranges(path)
        original_len = len(data["ranges"])
        data["ranges"] = [r for r in data["ranges"] if r["user"] != username]
        if len(data["ranges"]) == original_len:
            return False
        content = _generate_owl_idranges(
            data["idsfor"] or path.stem.replace("-idranges", ""),
            data["idprefix"], data["iddigits"],
            data["ranges"], data["ontology_iri"],
        )
        path.write_text(content, encoding="utf-8")
        return True

    # XML
    tree = ET.parse(str(path))
    root = tree.getroot()
    for elem in root.findall("range"):
        if elem.get("user") == username:
            root.remove(elem)
            tree.write(str(path), xml_declaration=True, encoding="UTF-8")
            return True
    return False


def reserve_next_id(board_dir: Path, username: str) -> str | None:
    """Reserve the next available ID in the user's range."""
    path, fmt = _find_idranges_file(board_dir)
    if not path:
        return None

    ranges = parse_idranges(board_dir)
    for r in ranges:
        if r["user"] == username:
            current = int(r.get("current", r["lower"]))
            upper = int(r["upper"])
            if current > upper:
                return None
            prefix = r.get("prefix", "")
            iri = f"{prefix}{str(current).zfill(7)}"
            # Update current (only for XML format — OWL format doesn't track current)
            if fmt == "xml":
                tree = ET.parse(str(path))
                for elem in tree.findall(".//range"):
                    if elem.get("user") == username:
                        elem.set("current", str(current + 1).zfill(7))
                        tree.write(str(path), xml_declaration=True, encoding="UTF-8")
            return iri
    return None
