"""ODK configuration service — edit-file.yaml, custom Makefile targets, workflows."""

import subprocess
from pathlib import Path

import yaml


def get_config(board_dir: Path) -> dict | None:
    """Read edit-file.yaml if it exists."""
    config_path = board_dir / "src" / "ontology" / "edit-file.yaml"
    if config_path.exists():
        return yaml.safe_load(config_path.read_text()) or {}
    return None


def save_config(board_dir: Path, config: dict) -> bool:
    """Write edit-file.yaml."""
    config_path = board_dir / "src" / "ontology" / "edit-file.yaml"
    config_path.parent.mkdir(parents=True, exist_ok=True)
    config_path.write_text(yaml.dump(config, default_flow_style=False, sort_keys=False))
    return True


def list_makefile_targets(board_dir: Path) -> list[dict]:
    """Parse Makefile for available targets."""
    makefile = board_dir / "src" / "ontology" / "Makefile"
    if not makefile.exists():
        return []
    targets = []
    for line in makefile.read_text().split("\n"):
        if ":" in line and not line.startswith("\t") and not line.startswith("#"):
            target = line.split(":")[0].strip()
            if target and not target.startswith(".") and "=" not in target:
                targets.append({"name": target, "is_custom": False})
    return targets


def generate_changelog(board_dir: Path) -> str:
    """Generate changelog from git log."""
    try:
        from dulwich.repo import Repo
        repo = Repo(str(board_dir))
        entries = []
        walker = repo.get_walker(max_entries=50)
        for entry in walker:
            commit = entry.commit
            msg = commit.message.decode("utf-8", errors="replace").strip()
            time_str = str(commit.author_time)
            entries.append(f"- {msg}")
        return "# Changelog\n\n" + "\n".join(entries) if entries else "# Changelog\n\nNo commits yet."
    except Exception:
        return "# Changelog\n\nCould not read git history."


def generate_ci_yaml(board_id: str) -> str:
    """Generate a GitHub Actions workflow YAML."""
    return f"""name: Ontology CI - {board_id}
on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  qc:
    runs-on: ubuntu-latest
    container: obolibrary/odkfull:latest
    steps:
      - uses: actions/checkout@v4
      - name: Run QC
        run: cd src/ontology && make check
      - name: Run Tests
        run: cd src/ontology && make test
"""
