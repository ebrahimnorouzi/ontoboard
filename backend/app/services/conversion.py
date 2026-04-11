"""Format conversion service — parse any RDF format, convert between formats.

Supports: OWL/XML, Turtle, N-Triples, JSON-LD, OBO (via ROBOT), RDF/XML.
Uses rdflib for direct parsing; falls back to ROBOT for OBO format.
"""

import logging
from pathlib import Path

from rdflib import Graph

logger = logging.getLogger("ontoboard.conversion")

# Format detection by extension and content sniffing
_EXT_FORMAT = {
    ".owl": "xml",
    ".rdf": "xml",
    ".xml": "xml",
    ".ttl": "turtle",
    ".nt": "nt",
    ".nq": "nquads",
    ".jsonld": "json-ld",
    ".json": "json-ld",
    ".obo": "obo",  # Needs ROBOT
}


def detect_format(filename: str, content: str = "") -> str:
    """Detect RDF format from filename extension or content sniffing."""
    ext = Path(filename).suffix.lower()
    fmt = _EXT_FORMAT.get(ext)
    if fmt:
        return fmt

    # Content sniffing
    stripped = content.strip()
    if stripped.startswith("<?xml") or stripped.startswith("<rdf:RDF") or stripped.startswith("<owl:"):
        return "xml"
    if stripped.startswith("{"):
        return "json-ld"
    if stripped.startswith("@prefix") or stripped.startswith("@base"):
        return "turtle"
    if "format-version:" in stripped[:200]:
        return "obo"

    return "xml"  # Default


def parse_any_format(content: str, filename: str = "input.owl") -> Graph:
    """Parse RDF content in any supported format into an rdflib Graph.

    For OBO format, returns empty graph (needs ROBOT Docker conversion).
    """
    fmt = detect_format(filename, content)

    if fmt == "obo":
        logger.warning("OBO format detected — direct parsing not supported, use ROBOT convert")
        # Create minimal graph with a note
        g = Graph()
        return g

    g = Graph()
    try:
        g.parse(data=content, format=fmt)
    except Exception as exc:
        logger.error("Failed to parse %s as %s: %s", filename, fmt, exc)
        # Try alternate formats
        for alt_fmt in ("xml", "turtle", "json-ld", "nt"):
            if alt_fmt != fmt:
                try:
                    g = Graph()
                    g.parse(data=content, format=alt_fmt)
                    logger.info("Successfully parsed as %s (fallback)", alt_fmt)
                    return g
                except Exception:
                    continue
        raise ValueError(f"Cannot parse {filename}: {exc}")

    return g


def convert_graph(g: Graph, output_format: str) -> str:
    """Serialize an rdflib Graph to the specified format."""
    format_map = {
        "owl": "xml",
        "xml": "xml",
        "ttl": "turtle",
        "turtle": "turtle",
        "nt": "nt",
        "ntriples": "nt",
        "jsonld": "json-ld",
        "json-ld": "json-ld",
        "json": "json-ld",
    }
    rdflib_fmt = format_map.get(output_format.lower(), "xml")
    return g.serialize(format=rdflib_fmt)


def save_as_format(board_dir: Path, g: Graph, board_id: str, output_format: str = "xml") -> Path:
    """Save graph to the board's ontology directory in the specified format."""
    ext_map = {"xml": "owl", "turtle": "ttl", "nt": "nt", "json-ld": "jsonld"}
    rdflib_fmt = {"owl": "xml", "ttl": "turtle", "nt": "nt", "jsonld": "json-ld"}.get(output_format, output_format)
    ext = ext_map.get(rdflib_fmt, output_format)

    out_path = board_dir / "src" / "ontology" / f"{board_id}.{ext}"
    out_path.parent.mkdir(parents=True, exist_ok=True)
    g.serialize(str(out_path), format=rdflib_fmt)
    return out_path


def graph_preserves_axioms(g_original: Graph, g_converted: Graph) -> dict:
    """Verify that conversion preserved all logical axioms (triples)."""
    orig_triples = set((str(s), str(p), str(o)) for s, p, o in g_original)
    conv_triples = set((str(s), str(p), str(o)) for s, p, o in g_converted)

    missing = orig_triples - conv_triples
    added = conv_triples - orig_triples

    return {
        "preserved": len(missing) == 0,
        "original_count": len(orig_triples),
        "converted_count": len(conv_triples),
        "missing_count": len(missing),
        "added_count": len(added),
    }
