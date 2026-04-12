"""ODK Help & Guidance Service — contextual tooltips and step introductions.

Provides user-friendly explanations for every ODK concept, field, and workflow step.
Users who don't know ODK can click "?" icons to understand what each option does.

Resources:
- ODK Documentation: https://ontology-development-kit.readthedocs.io/en/latest/
- ODK Paper: https://arxiv.org/abs/2207.02056
- ODK Commands: https://oboacademy.github.io/obook/reference/frequently-used-odk-commands/
- ODK Summit Talk: https://www.youtube.com/watch?v=HAprZl0pldk
- ODK Seed Tutorial: https://www.youtube.com/watch?v=cd7750JVDaw
"""

# ═══════════════════════════════════════════════════════════════
# Board Creation Help
# ═══════════════════════════════════════════════════════════════

BOARD_CREATION = {
    "title": "Create a New Ontology Board",
    "intro": (
        "OntoBoard uses the **Ontology Development Kit (ODK)** to manage your ontology project. "
        "ODK provides a standardized structure, automated quality checks, import management, "
        "and release workflows — all inside a Docker container.\n\n"
        "You can choose between two modes:\n"
        "- **ODK Board**: Full ODK project with Makefile, imports, CI/CD, and ROBOT integration\n"
        "- **Blank Board**: Minimal ontology file for quick experimentation"
    ),
    "fields": {
        "ont_id": {
            "label": "Ontology ID",
            "help": (
                "A short, lowercase identifier for your ontology (e.g., `nfdicore`, `mwo`, `pizza`). "
                "This determines file names, default term IDs, and repository structure. "
                "Use only lowercase letters, numbers, and hyphens."
            ),
            "example": "nfdicore",
        },
        "title": {
            "label": "Ontology Title",
            "help": (
                "A human-readable name for your ontology. Used in README, documentation, "
                "and metadata fields like `dc:title`."
            ),
            "example": "NFDI Core Ontology",
        },
        "mode": {
            "label": "Board Mode",
            "help": (
                "**ODK Board**: Creates a full ODK project structure with Makefile, import management, "
                "ROBOT quality checks, GitHub Actions CI, and release automation. "
                "Recommended for production ontologies.\n\n"
                "**Blank Board**: Creates just an OWL file. Good for quick prototyping or learning."
            ),
        },
    },
    "resources": [
        {"title": "ODK Documentation", "url": "https://ontology-development-kit.readthedocs.io/en/latest/"},
        {"title": "ODK Seed Tutorial (30 min video)", "url": "https://www.youtube.com/watch?v=cd7750JVDaw"},
        {"title": "ODK Paper (arXiv)", "url": "https://arxiv.org/abs/2207.02056"},
    ],
}


# ═══════════════════════════════════════════════════════════════
# ODK YAML Configuration Help
# ═══════════════════════════════════════════════════════════════

YAML_CONFIG = {
    "title": "ODK Configuration (odk.yaml)",
    "intro": (
        "The `<ontology>-odk.yaml` file is the **main configuration** for your ODK project. "
        "It controls imports, release artifacts, quality checks, and more. "
        "When you change this file and run `make update_repo`, ODK regenerates the Makefile, "
        "GitHub Actions workflows, and other auto-generated files.\n\n"
        "**Important**: The Makefile is auto-generated from this YAML. "
        "Do NOT edit the Makefile directly — use the `.Makefile` for custom targets."
    ),
    "fields": {
        "id": {
            "label": "Ontology ID",
            "help": "The lowercase identifier. Determines file naming (e.g., `nfdicore.owl`, `nfdicore-edit.owl`).",
        },
        "title": {
            "label": "Title",
            "help": "Human-readable title used in documentation and README.",
        },
        "github_org": {
            "label": "GitHub Organization",
            "help": "The GitHub organization or user that owns the repository (e.g., `ISE-FIZKarlsruhe`).",
        },
        "repo": {
            "label": "Repository Name",
            "help": "The GitHub repository name (e.g., `nfdicore`).",
        },
        "uribase": {
            "label": "URI Base",
            "help": (
                "The base URI for your ontology's IRI. For OBO ontologies this is typically "
                "`http://purl.obolibrary.org/obo`. For custom ontologies, use your own domain."
            ),
            "example": "https://nfdi.fiz-karlsruhe.de/ontology",
        },
        "release_artefacts": {
            "label": "Release Artifacts",
            "help": (
                "Which versions of the ontology to generate during release:\n"
                "- `base`: The base ontology without imports\n"
                "- `full`: The full ontology with all imports merged\n"
                "- `simple`: A simplified version with reasoning pre-applied"
            ),
        },
        "primary_release": {
            "label": "Primary Release",
            "help": "Which artifact is the main release file (the one most users download). Usually `full` or `base`.",
        },
        "export_formats": {
            "label": "Export Formats",
            "help": "File formats to generate during release: `owl` (OWL/XML), `ttl` (Turtle), `obo` (OBO format), `json` (JSON-LD).",
        },
        "import_group": {
            "label": "Import Group",
            "help": (
                "Defines which external ontologies to import and how. Each import is listed under `products` "
                "with an `id` and configuration options."
            ),
        },
        "robot_java_args": {
            "label": "ROBOT Java Arguments",
            "help": "JVM memory settings for ROBOT. Use `-Xmx8G` for large ontologies to avoid out-of-memory errors.",
            "example": "-Xmx8G",
        },
        "robot_report": {
            "label": "ROBOT Report Settings",
            "help": (
                "Controls the quality checking process:\n"
                "- `use_labels`: Show labels instead of IRIs in reports\n"
                "- `fail_on`: Which severity level causes the build to fail (`ERROR`, `WARN`, `INFO`)\n"
                "- `custom_profile`: Use `profile.txt` for custom check configuration\n"
                "- `report_on`: Which files to check (usually `edit`)"
            ),
        },
    },
    "resources": [
        {"title": "ODK Configuration Schema", "url": "https://incatools.github.io/ontology-development-kit/project-schema/"},
        {"title": "Frequently Used ODK Commands", "url": "https://oboacademy.github.io/obook/reference/frequently-used-odk-commands/"},
    ],
}


# ═══════════════════════════════════════════════════════════════
# Import Workflow Help (6 steps)
# ═══════════════════════════════════════════════════════════════

IMPORT_WORKFLOW = {
    "title": "Ontology Import Workflow",
    "intro": (
        "Importing external ontologies allows you to reuse established terms and relationships "
        "from community ontologies like BFO, RO, IAO, PATO, etc. "
        "ODK manages imports through a structured 6-step process that ensures:\n\n"
        "- **Consistency**: Everyone uses the exact same version of dependencies\n"
        "- **Offline builds**: Imports are mirrored locally, so builds don't break if a server is down\n"
        "- **Selective import**: You only import the terms you actually need (not the entire ontology)\n\n"
        "The import workflow modifies several files in your project. Each step is explained below."
    ),
    "steps": {
        "1_declare": {
            "title": "Step 1: Declare the Import",
            "intro": (
                "Add the import to your `odk.yaml` configuration under `import_group.products`. "
                "For OBO-standard ontologies (BFO, RO, IAO, etc.), you only need the ontology ID. "
                "For external ontologies, you also need the download URL (`mirror_from`)."
            ),
            "fields": {
                "id": {
                    "label": "Import ID",
                    "help": (
                        "The short identifier for the ontology you want to import. "
                        "For OBO ontologies, this is the standard prefix (e.g., `ro`, `bfo`, `iao`, `pato`). "
                        "For non-OBO ontologies, choose a meaningful short name."
                    ),
                    "examples": ["ro", "bfo", "iao", "swo", "edam", "dcat", "skos"],
                },
                "mirror_from": {
                    "label": "Mirror From (URL)",
                    "help": (
                        "The URL to download the ontology from. **Required for non-OBO ontologies**. "
                        "For OBO ontologies, ODK knows where to find them automatically.\n\n"
                        "Examples:\n"
                        "- BFO: `http://purl.obolibrary.org/obo/bfo/2020/notime/bfo.owl`\n"
                        "- SWO: `https://raw.githubusercontent.com/allysonlister/swo/master/swo.owl`\n"
                        "- DCAT: `http://www.w3.org/ns/dcat3`"
                    ),
                },
                "module_type": {
                    "label": "Module Type",
                    "help": (
                        "How to extract the import module:\n\n"
                        "- **mirror**: Download the entire ontology as-is (simplest)\n"
                        "- **custom**: Extract only the terms listed in `_terms.txt` (most common)\n"
                        "- **slme**: Use ROBOT's SLME algorithm to extract a module "
                        "(automatically includes related axioms)\n\n"
                        "See: https://robot.obolibrary.org/extract"
                    ),
                },
                "module_type_slme": {
                    "label": "SLME Extraction Method",
                    "help": (
                        "When using `slme` module type, choose the extraction strategy:\n\n"
                        "- **BOT** (Bottom): Includes the term and everything below it in the hierarchy\n"
                        "- **TOP** (Top): Includes the term and everything above it\n"
                        "- **STAR**: Includes the term and its immediate neighbors\n"
                        "- **SUBSET**: Extracts based on subset annotations\n\n"
                        "BOT is the most common choice for importing specific classes."
                    ),
                },
                "slme_individuals": {
                    "label": "Include Individuals?",
                    "help": "Whether to include named individuals from the imported ontology. Usually set to `exclude` to keep imports small.",
                },
                "use_base": {
                    "label": "Use Base Ontology?",
                    "help": "If true, imports from the base version of the ontology (without its own imports merged). Useful when you want cleaner, smaller imports.",
                },
            },
            "after": "After declaring, run: `sh run.sh make update_repo`",
        },
        "2_check_makefile": {
            "title": "Step 2: Check the Generated Makefile",
            "intro": (
                "After running `make update_repo`, ODK regenerates the Makefile with new targets "
                "for your declared imports. Review the Makefile to verify the import targets "
                "were created correctly.\n\n"
                "**Note**: Do NOT edit the Makefile directly — it will be overwritten. "
                "Use `{ontology}.Makefile` for any customizations."
            ),
        },
        "3_add_terms": {
            "title": "Step 3: Add Terms to Import",
            "intro": (
                "Create or edit the `imports/{import}_terms.txt` file. List the full IRIs "
                "of every term (class, property) you want to import from the external ontology.\n\n"
                "Each line should contain one full IRI, e.g.:\n"
                "```\n"
                "http://purl.obolibrary.org/obo/RO_0000052\n"
                "http://purl.obolibrary.org/obo/RO_0000053\n"
                "http://purl.obolibrary.org/obo/BFO_0000050\n"
                "```\n\n"
                "**Tip**: You can find term IRIs by browsing the ontology on "
                "[OLS](https://www.ebi.ac.uk/ols/) or [OntoBee](http://www.ontobee.org/)."
            ),
            "after": "After adding terms, run: `sh run.sh make refresh-imports`",
        },
        "4_register": {
            "title": "Step 4: Register the Import",
            "intro": (
                "Copy the import URI into your editing ontology (`*-edit.owl`) and the "
                "catalog file (`catalog-v001.xml`). This tells your ontology to include "
                "the imported module, and tells tools to resolve the import from local files "
                "instead of fetching from the web.\n\n"
                "OntoBoard does this automatically when you click 'Register'."
            ),
            "after": "Run `sh run.sh make refresh-imports` to download the actual import module.",
        },
        "5_custom_makefile": {
            "title": "Step 5: Add Import Schema to Custom Makefile",
            "intro": (
                "Copy the generated import build target from the global Makefile to your "
                "custom `{ontology}.Makefile`. This ensures the import target persists even "
                "when `make update_repo` regenerates the global Makefile.\n\n"
                "OntoBoard adds the target automatically."
            ),
        },
        "6_configure": {
            "title": "Step 6: Configure Import Settings",
            "intro": (
                "Final step: update the `module_type` in `odk.yaml` if needed. For example, "
                "change from `slme` to `custom` after you've set up your terms file.\n\n"
                "Then run the full rebuild:\n"
                "```\n"
                "sh run.sh make update_repo\n"
                "sh run.sh make clean\n"
                "sh run.sh make\n"
                "```"
            ),
        },
    },
    "resources": [
        {"title": "ROBOT Extract Documentation", "url": "https://robot.obolibrary.org/extract"},
        {"title": "ODK Import Tutorial", "url": "https://ontology-development-kit.readthedocs.io/en/latest/"},
        {"title": "OBO Academy: Import Guide", "url": "https://oboacademy.github.io/obook/"},
    ],
}


# ═══════════════════════════════════════════════════════════════
# ODK Workspace Files Help
# ═══════════════════════════════════════════════════════════════

WORKSPACE_FILES = {
    "title": "ODK Workspace Files",
    "intro": (
        "Your ODK project contains several important files. Understanding their roles "
        "helps you work effectively with the ontology development workflow."
    ),
    "files": {
        "{ont}-edit.owl": {
            "role": "The Editing Ontology",
            "description": (
                "This is the file you actually edit — both in OntoBoard's visual canvas and in Protégé. "
                "All your classes, properties, individuals, and axioms live here. "
                "The release ontology (`{ont}.owl`) is generated FROM this file during the build process."
            ),
            "editable": True,
            "warning": None,
        },
        "{ont}-odk.yaml": {
            "role": "Main Configuration",
            "description": (
                "The central configuration file for your ODK project. Controls:\n"
                "- Which ontologies to import\n"
                "- Release artifact formats\n"
                "- Quality check settings\n"
                "- Documentation generation\n\n"
                "After editing, run `make update_repo` to apply changes."
            ),
            "editable": True,
            "warning": None,
        },
        "Makefile": {
            "role": "Auto-generated Build File",
            "description": (
                "This Makefile is automatically generated by ODK from your `odk.yaml`. "
                "It contains all the build targets: `make all`, `make test`, `make reason`, etc.\n\n"
                "**DO NOT edit this file manually** — your changes will be overwritten. "
                "Use `{ont}.Makefile` for custom targets instead."
            ),
            "editable": False,
            "warning": "Auto-generated. DO NOT edit manually. Use {ont}.Makefile for customizations.",
        },
        "{ont}.Makefile": {
            "role": "Custom Build Targets",
            "description": (
                "Your custom Makefile for project-specific build targets, import overrides, "
                "and release steps. This file is NOT overwritten by `make update_repo`.\n\n"
                "Add custom targets, import extraction rules, and release steps here."
            ),
            "editable": True,
            "warning": None,
        },
        "{ont}-idranges.owl": {
            "role": "ID Range Allocation",
            "description": (
                "Determines the ID ranges assigned to each contributor. Prevents URI collisions "
                "when multiple people create new terms simultaneously.\n\n"
                "Each user gets a block of IDs (e.g., 0001-9999) and creates terms within their range."
            ),
            "editable": True,
            "warning": None,
        },
        "profile.txt": {
            "role": "Quality Control Profile",
            "description": (
                "Configures which ROBOT quality checks to run and their severity levels. "
                "Controls what makes the build fail (ERROR) vs warn (WARN) vs inform (INFO).\n\n"
                "Requires `custom_profile: TRUE` in `odk.yaml` → `robot_report`."
            ),
            "editable": True,
            "warning": None,
        },
        "catalog-v001.xml": {
            "role": "Import Resolution Catalog",
            "description": (
                "Maps ontology URIs to local file paths. When tools try to fetch an imported ontology "
                "from the web, this catalog redirects them to the local mirror in `imports/`.\n\n"
                "Ensures:\n"
                "- Everyone uses the same version of dependencies\n"
                "- Builds work offline\n"
                "- No breakage if an external server goes down"
            ),
            "editable": True,
            "warning": None,
        },
        "run.sh": {
            "role": "Docker Wrapper Script",
            "description": (
                "A convenience script that runs commands inside the ODK Docker container. "
                "Usage: `sh run.sh make all`, `sh run.sh make test`, etc.\n\n"
                "In OntoBoard, the ODK panel buttons execute these commands for you."
            ),
            "editable": True,
            "warning": None,
        },
    },
}


# ═══════════════════════════════════════════════════════════════
# ODK Commands Help
# ═══════════════════════════════════════════════════════════════

ODK_COMMANDS = {
    "title": "Frequently Used ODK Commands",
    "intro": (
        "These commands run inside the ODK Docker container. In OntoBoard, you can trigger them "
        "from the ODK panel buttons, or run them manually via the terminal."
    ),
    "commands": {
        "make all": {
            "description": "Build the ontology from the edit file. Runs reasoning and generates release artifacts.",
            "when": "After making changes to the edit file.",
        },
        "make test": {
            "description": "Run the quality control test suite (SPARQL checks, ROBOT report, reasoning validation).",
            "when": "Before committing changes. Also runs automatically in CI.",
        },
        "make reason": {
            "description": "Run the OWL reasoner (ELK or HermiT) to check consistency and compute inferences.",
            "when": "After adding axioms to verify logical consistency.",
        },
        "make update_repo": {
            "description": "Regenerate the Makefile, GitHub Actions, and other files from odk.yaml.",
            "when": "After editing odk.yaml (imports, release config, etc.).",
        },
        "make refresh-imports": {
            "description": "Download/update import modules from their sources.",
            "when": "After adding new imports or updating terms.txt files.",
        },
        "make clean": {
            "description": "Remove all generated temporary files and build artifacts.",
            "when": "Before a fresh rebuild, or to free disk space.",
        },
        "make prepare_release": {
            "description": "Run tests and prepare release artifacts without publishing.",
            "when": "Before making a release to verify everything is ready.",
        },
        "make publish": {
            "description": "Create the final release (version bump, generate all formats).",
            "when": "When ready to publish a new version.",
        },
        "make docs": {
            "description": "Generate the MkDocs documentation site.",
            "when": "When updating the project documentation.",
        },
    },
    "resources": [
        {"title": "Full ODK Command Reference", "url": "https://oboacademy.github.io/obook/reference/frequently-used-odk-commands/"},
        {"title": "ROBOT Documentation", "url": "https://robot.obolibrary.org/"},
    ],
}


# ═══════════════════════════════════════════════════════════════
# Public API
# ═══════════════════════════════════════════════════════════════

def get_help(topic: str) -> dict | None:
    """Get help content for a topic."""
    topics = {
        "board_creation": BOARD_CREATION,
        "yaml_config": YAML_CONFIG,
        "import_workflow": IMPORT_WORKFLOW,
        "workspace_files": WORKSPACE_FILES,
        "odk_commands": ODK_COMMANDS,
    }
    return topics.get(topic)


def get_field_help(topic: str, field: str) -> dict | None:
    """Get help for a specific field within a topic."""
    content = get_help(topic)
    if not content:
        return None
    fields = content.get("fields", {})
    return fields.get(field)


def get_all_topics() -> list[dict]:
    """List all available help topics."""
    return [
        {"id": "board_creation", "title": "Creating a New Board"},
        {"id": "yaml_config", "title": "ODK Configuration (YAML)"},
        {"id": "import_workflow", "title": "Ontology Import Workflow"},
        {"id": "workspace_files", "title": "Workspace File Guide"},
        {"id": "odk_commands", "title": "ODK Commands Reference"},
    ]
