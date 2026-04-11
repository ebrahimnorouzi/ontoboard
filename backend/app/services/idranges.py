"""ID range service — Protege-style ID range allocation per user."""

import xml.etree.ElementTree as ET
from pathlib import Path


DEFAULT_BLOCK_SIZE = 10000


def generate_idranges_xml(board_dir: Path, board_id: str) -> Path:
    """Generate initial idranges.xml for a board."""
    path = board_dir / "src" / "ontology" / "idranges.xml"
    root = ET.Element("idranges")
    root.set("ontology", board_id)
    ET.SubElement(root, "range", attrib={
        "user": "default",
        "prefix": f"http://example.org/{board_id}#",
        "lower": "0000001",
        "upper": str(DEFAULT_BLOCK_SIZE).zfill(7),
        "current": "0000001",
    })
    tree = ET.ElementTree(root)
    path.parent.mkdir(parents=True, exist_ok=True)
    tree.write(str(path), xml_declaration=True, encoding="UTF-8")
    return path


def parse_idranges(board_dir: Path) -> list[dict]:
    """Read idranges.xml and return all ranges."""
    path = board_dir / "src" / "ontology" / "idranges.xml"
    if not path.exists():
        return []
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


def allocate_range(board_dir: Path, username: str, prefix: str = "") -> dict:
    """Allocate a new ID range block for a user."""
    path = board_dir / "src" / "ontology" / "idranges.xml"
    if not path.exists():
        generate_idranges_xml(board_dir, board_dir.name)

    tree = ET.parse(str(path))
    root = tree.getroot()

    # Find max upper bound
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
        "user": username,
        "prefix": prefix,
        "lower": str(lower).zfill(7),
        "upper": str(upper).zfill(7),
        "current": str(lower).zfill(7),
    })
    tree.write(str(path), xml_declaration=True, encoding="UTF-8")

    return {"user": username, "prefix": prefix, "lower": str(lower).zfill(7),
            "upper": str(upper).zfill(7), "current": str(lower).zfill(7)}


def reserve_next_id(board_dir: Path, username: str) -> str | None:
    """Reserve the next available ID in the user's range."""
    path = board_dir / "src" / "ontology" / "idranges.xml"
    if not path.exists():
        return None

    tree = ET.parse(str(path))
    for elem in tree.findall(".//range"):
        if elem.get("user") == username:
            current = int(elem.get("current", "0"))
            upper = int(elem.get("upper", "0"))
            if current > upper:
                return None  # Range exhausted
            prefix = elem.get("prefix", "")
            iri = f"{prefix}{elem.get('user', 'ONTO')}_{str(current).zfill(7)}"
            elem.set("current", str(current + 1).zfill(7))
            tree.write(str(path), xml_declaration=True, encoding="UTF-8")
            return iri
    return None
