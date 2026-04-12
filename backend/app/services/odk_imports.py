"""ODK Import Workflow Service — guided ontology import pipeline.

Implements the 6-step ODK import workflow:
1. Declare import in odk.yaml (import_group.products)
2. Run `make update_repo` → check generated Makefile
3. Add terms to {import}_terms.txt
4. Run `make refresh-imports` → copy import URI to edit.owl + catalog
5. Add import schema to {ont}.Makefile
6. Configure import settings (module_type) → make update_repo → make clean → make

Each step modifies the appropriate files and optionally triggers Docker commands.
"""

import re
import textwrap
import xml.etree.ElementTree as ET
from pathlib import Path

import yaml

from app.config import DATA_DIR


# ═══════════════════════════════════════════════════════════════
# Step 1: Declare import in odk.yaml
# ═══════════════════════════════════════════════════════════════

def declare_import(board_dir: Path, ont_id: str, import_config: dict) -> dict:
    """Add an import declaration to the odk.yaml import_group.products.

    import_config example:
    {
        "id": "ro",
        "mirror_from": "http://purl.obolibrary.org/obo/ro.owl",  # optional for OBO
        "module_type": "slme",     # mirror | custom | slme
        "module_type_slme": "BOT", # TOP | BOT | STAR | SUBSET (if slme)
        "slme_individuals": "exclude",  # optional
        "use_base": false          # optional
    }
    """
    yaml_path = board_dir / "src" / "ontology" / f"{ont_id}-odk.yaml"
    if not yaml_path.exists():
        return {"success": False, "error": "odk.yaml not found"}

    config = yaml.safe_load(yaml_path.read_text()) or {}

    # Ensure import_group.products exists
    if "import_group" not in config:
        config["import_group"] = {}
    if "products" not in config["import_group"]:
        config["import_group"]["products"] = []

    # Check if import already declared
    existing_ids = [p.get("id") for p in config["import_group"]["products"]]
    if import_config.get("id") in existing_ids:
        return {"success": False, "error": f"Import '{import_config['id']}' already declared"}

    # Build product entry
    product = {"id": import_config["id"]}
    if import_config.get("mirror_from"):
        product["mirror_from"] = import_config["mirror_from"]
    if import_config.get("module_type"):
        product["module_type"] = import_config["module_type"]
    if import_config.get("module_type_slme"):
        product["module_type_slme"] = import_config["module_type_slme"]
    if import_config.get("slme_individuals"):
        product["slme_individuals"] = import_config["slme_individuals"]
    if import_config.get("use_base"):
        product["use_base"] = True

    config["import_group"]["products"].append(product)

    # Write back
    yaml_path.write_text(yaml.dump(config, default_flow_style=False, sort_keys=False))

    return {
        "success": True,
        "import_id": import_config["id"],
        "yaml_snippet": yaml.dump({"products": [product]}, default_flow_style=False),
        "next_step": "Run `sh run.sh make update_repo` to generate Makefile entries",
    }


# ═══════════════════════════════════════════════════════════════
# Step 2: Check Makefile (read-only)
# ═══════════════════════════════════════════════════════════════

def check_makefile(board_dir: Path, ont_id: str) -> dict:
    """Read the generated Makefile to verify import targets were added."""
    makefile = board_dir / "src" / "ontology" / "Makefile"
    custom_makefile = board_dir / "src" / "ontology" / f"{ont_id}.Makefile"

    result = {
        "makefile_exists": makefile.exists(),
        "custom_makefile_exists": custom_makefile.exists(),
        "makefile_content": makefile.read_text() if makefile.exists() else "",
        "custom_makefile_content": custom_makefile.read_text() if custom_makefile.exists() else "",
    }

    # Extract import-related targets
    if makefile.exists():
        content = makefile.read_text()
        import_targets = [line for line in content.split("\n")
                          if "import" in line.lower() or "mirror" in line.lower()]
        result["import_targets"] = import_targets

    return result


# ═══════════════════════════════════════════════════════════════
# Step 3: Add terms to {import}_terms.txt
# ═══════════════════════════════════════════════════════════════

def add_import_terms(board_dir: Path, import_id: str, term_iris: list[str]) -> dict:
    """Add term IRIs to the import's terms file."""
    terms_file = board_dir / "src" / "ontology" / "imports" / f"{import_id}_terms.txt"
    terms_file.parent.mkdir(parents=True, exist_ok=True)

    # Read existing terms
    existing = set()
    if terms_file.exists():
        existing = set(line.strip() for line in terms_file.read_text().split("\n") if line.strip())

    # Add new terms
    new_terms = [t for t in term_iris if t not in existing]
    all_terms = sorted(existing | set(term_iris))

    terms_file.write_text("\n".join(all_terms) + "\n")

    return {
        "success": True,
        "file": str(terms_file.relative_to(board_dir)),
        "total_terms": len(all_terms),
        "new_terms_added": len(new_terms),
        "next_step": "Run `sh run.sh make refresh-imports` to download the import module",
    }


def get_import_terms(board_dir: Path, import_id: str) -> list[str]:
    """Read the current terms for an import."""
    terms_file = board_dir / "src" / "ontology" / "imports" / f"{import_id}_terms.txt"
    if not terms_file.exists():
        return []
    return [line.strip() for line in terms_file.read_text().split("\n") if line.strip()]


# ═══════════════════════════════════════════════════════════════
# Step 4: Copy import URI to edit.owl + catalog
# ═══════════════════════════════════════════════════════════════

def register_import(board_dir: Path, ont_id: str, import_id: str, import_iri: str = "") -> dict:
    """Add the import to the edit OWL file and catalog-v001.xml.

    If import_iri is not provided, uses the OBO standard:
    http://purl.obolibrary.org/obo/{import_id}.owl
    """
    if not import_iri:
        import_iri = f"http://purl.obolibrary.org/obo/{import_id}.owl"

    import_owl_file = f"imports/{import_id}_import.owl"

    # ── Update edit OWL file ───────────────────────────────────
    edit_owl = board_dir / "src" / "ontology" / f"{ont_id}-edit.owl"
    if edit_owl.exists():
        content = edit_owl.read_text()
        # Add owl:imports if not already present
        import_line = f'<owl:imports rdf:resource="{import_iri}"/>'
        if import_iri not in content:
            # Insert before closing </owl:Ontology> or </rdf:RDF>
            if "</owl:Ontology>" in content:
                content = content.replace("</owl:Ontology>", f"    {import_line}\n        </owl:Ontology>")
            elif "</rdf:RDF>" in content:
                # Try to find ontology block
                content = content.replace("</rdf:RDF>", f"    {import_line}\n</rdf:RDF>")
            edit_owl.write_text(content)

    # ── Update catalog-v001.xml ────────────────────────────────
    catalog = board_dir / "src" / "ontology" / "catalog-v001.xml"
    if catalog.exists():
        try:
            tree = ET.parse(str(catalog))
            root = tree.getroot()
            ns = "urn:oasis:names:tc:entity:xmlns:xml:catalog"
            # Check if already registered
            existing = [c for c in root if c.get("name") == import_iri]
            if not existing:
                elem = ET.SubElement(root, "uri")
                elem.set("id", import_id)
                elem.set("name", import_iri)
                elem.set("uri", import_owl_file)
                tree.write(str(catalog), xml_declaration=True, encoding="UTF-8")
        except Exception:
            pass

    return {
        "success": True,
        "import_iri": import_iri,
        "import_file": import_owl_file,
        "edit_owl_updated": edit_owl.exists(),
        "catalog_updated": catalog.exists(),
        "next_step": "Run `sh run.sh make refresh-imports` then add import schema to custom Makefile",
    }


# ═══════════════════════════════════════════════════════════════
# Step 5: Add import schema to custom Makefile
# ═══════════════════════════════════════════════════════════════

def add_import_to_custom_makefile(board_dir: Path, ont_id: str, import_id: str) -> dict:
    """Add import target to the custom {ont_id}.Makefile."""
    custom_mf = board_dir / "src" / "ontology" / f"{ont_id}.Makefile"
    if not custom_mf.exists():
        custom_mf.write_text(f"## Custom Makefile for {ont_id}\n\n")

    content = custom_mf.read_text()

    # Add import module target if not present
    target = f"imports/{import_id}_import.owl"
    if target not in content:
        snippet = textwrap.dedent(f"""
        ## Import: {import_id}
        {target}: imports/{import_id}_terms.txt
        \t@if [ $(IMP) = true ]; then $(ROBOT) extract -i imports/{import_id}_import.owl -T $< -o $@; fi
        """)
        content += snippet
        custom_mf.write_text(content)

    return {
        "success": True,
        "custom_makefile": str(custom_mf.relative_to(board_dir)),
        "next_step": "Configure module_type in odk.yaml, then run update_repo + clean + make",
    }


# ═══════════════════════════════════════════════════════════════
# Step 6: Configure import settings
# ═══════════════════════════════════════════════════════════════

def configure_import(board_dir: Path, ont_id: str, import_id: str, module_type: str = "custom") -> dict:
    """Update the module_type for an import in odk.yaml."""
    yaml_path = board_dir / "src" / "ontology" / f"{ont_id}-odk.yaml"
    if not yaml_path.exists():
        return {"success": False, "error": "odk.yaml not found"}

    config = yaml.safe_load(yaml_path.read_text()) or {}
    products = config.get("import_group", {}).get("products", [])

    updated = False
    for p in products:
        if p.get("id") == import_id:
            p["module_type"] = module_type
            updated = True
            break

    if not updated:
        return {"success": False, "error": f"Import '{import_id}' not found in odk.yaml"}

    yaml_path.write_text(yaml.dump(config, default_flow_style=False, sort_keys=False))

    return {
        "success": True,
        "import_id": import_id,
        "module_type": module_type,
        "next_step": "Run: sh run.sh make update_repo && sh run.sh make clean && sh run.sh make",
    }


# ═══════════════════════════════════════════════════════════════
# List all declared imports
# ═══════════════════════════════════════════════════════════════

def list_declared_imports(board_dir: Path, ont_id: str) -> list[dict]:
    """List all imports declared in odk.yaml with their status."""
    yaml_path = board_dir / "src" / "ontology" / f"{ont_id}-odk.yaml"
    if not yaml_path.exists():
        return []

    config = yaml.safe_load(yaml_path.read_text()) or {}
    products = config.get("import_group", {}).get("products", [])

    imports = []
    for p in products:
        imp_id = p.get("id", "")
        terms_file = board_dir / "src" / "ontology" / "imports" / f"{imp_id}_terms.txt"
        import_owl = board_dir / "src" / "ontology" / "imports" / f"{imp_id}_import.owl"

        imports.append({
            "id": imp_id,
            "mirror_from": p.get("mirror_from", ""),
            "module_type": p.get("module_type", ""),
            "module_type_slme": p.get("module_type_slme", ""),
            "has_terms_file": terms_file.exists(),
            "term_count": len(get_import_terms(board_dir, imp_id)),
            "has_import_owl": import_owl.exists(),
        })

    return imports
