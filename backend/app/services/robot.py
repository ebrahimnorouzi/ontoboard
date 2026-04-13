"""Centralised ROBOT executor.

Runs ROBOT commands as local subprocesses. ROBOT (robot.jar) is installed
in the backend Docker image — no Docker-in-Docker needed.

Falls back to checking if `robot` is on PATH for local development.
"""

import asyncio
import logging
import shutil
import subprocess
from dataclasses import dataclass, field
from pathlib import Path

logger = logging.getLogger("ontoboard.robot")


@dataclass
class RobotResult:
    """Result of a ROBOT command execution."""
    exit_code: int
    stdout: str
    stderr: str
    success: bool = field(init=False)

    def __post_init__(self):
        self.success = self.exit_code == 0


def _robot_available() -> bool:
    """Check if ROBOT is available on PATH."""
    return shutil.which("robot") is not None


def _run_robot_sync(
    board_dir: Path,
    command: str,
    working_dir: str = "",
    timeout: int = 300,
) -> RobotResult:
    """Run a ROBOT command as a local subprocess.

    Args:
        board_dir: Working directory for the command.
        command:   Full command string (e.g. "robot report -i ont.owl ...").
        working_dir: Subdirectory within board_dir to run in (optional).
        timeout:   Execution timeout in seconds.
    """
    if not _robot_available():
        return RobotResult(
            exit_code=-1, stdout="",
            stderr="ROBOT not installed. Install Java and ROBOT (https://robot.obolibrary.org).",
        )

    # Determine working directory
    cwd = board_dir.resolve()
    if working_dir:
        cwd = cwd / working_dir.lstrip("/")
    if not cwd.exists():
        cwd = board_dir.resolve()

    # Replace /work/ references with actual board_dir path
    # (for backward compatibility with existing command strings)
    actual_command = command.replace("/work/", str(board_dir.resolve()).replace("\\", "/") + "/")
    actual_command = actual_command.replace("/work", str(board_dir.resolve()).replace("\\", "/"))

    try:
        result = subprocess.run(
            actual_command,
            shell=True,
            capture_output=True,
            text=True,
            timeout=timeout,
            cwd=str(cwd),
        )
        return RobotResult(
            exit_code=result.returncode,
            stdout=result.stdout,
            stderr=result.stderr,
        )
    except subprocess.TimeoutExpired:
        return RobotResult(exit_code=-1, stdout="", stderr=f"Command timed out after {timeout}s")
    except Exception as exc:
        logger.warning("ROBOT command failed: %s", exc)
        return RobotResult(exit_code=-1, stdout="", stderr=str(exc))


# ── Async wrapper ─────────────────────────────────────────────
async def run_robot(
    board_dir: Path,
    command: str,
    working_dir: str = "",
    timeout: int = 300,
) -> RobotResult:
    """Async wrapper around _run_robot_sync."""
    loop = asyncio.get_event_loop()
    return await loop.run_in_executor(
        None, _run_robot_sync, board_dir, command, working_dir, timeout,
    )


def _posix_rel(child: Path, parent: Path) -> str:
    """Get relative path as a POSIX string (forward slashes)."""
    return str(child.relative_to(parent)).replace("\\", "/")


async def robot_convert(
    board_dir: Path,
    owl_file: Path,
    output_path: Path,
    output_format: str = "json",
) -> RobotResult:
    """Convert an OWL file to another format via ROBOT."""
    rel_in = _posix_rel(owl_file, board_dir)
    rel_out = _posix_rel(output_path, board_dir)
    return await run_robot(
        board_dir,
        f"robot convert -i {rel_in} -o {rel_out} --format {output_format}",
    )


async def robot_template(
    board_dir: Path,
    template_path: Path,
    output_path: Path,
) -> RobotResult:
    """Run ROBOT template to generate an OWL file from a CSV template."""
    rel_t = _posix_rel(template_path, board_dir)
    rel_o = _posix_rel(output_path, board_dir)
    return await run_robot(
        board_dir,
        f"robot template --template {rel_t} -o {rel_o}",
    )


async def robot_report(
    board_dir: Path,
    owl_file: Path,
) -> RobotResult:
    """Run ROBOT report on an OWL file, producing a TSV report."""
    rel_in = _posix_rel(owl_file, board_dir)
    return await run_robot(
        board_dir,
        f"robot report -i {rel_in} --output report.tsv --format tsv",
    )


async def robot_reason(
    board_dir: Path,
    owl_file: Path,
    output_path: Path,
    reasoner: str = "ELK",
) -> RobotResult:
    """Run a reasoner on an OWL file."""
    rel_in = _posix_rel(owl_file, board_dir)
    rel_out = _posix_rel(output_path, board_dir)
    return await run_robot(
        board_dir,
        f"robot reason -r {reasoner} -i {rel_in} -o {rel_out}",
    )


async def robot_diff(
    board_dir: Path,
    left: Path,
    right: Path,
    output: Path,
) -> RobotResult:
    """Diff two OWL files."""
    rel_l = _posix_rel(left, board_dir)
    rel_r = _posix_rel(right, board_dir)
    rel_o = _posix_rel(output, board_dir)
    return await run_robot(
        board_dir,
        f"robot diff --left {rel_l} --right {rel_r} --output {rel_o}",
    )


async def robot_query(
    board_dir: Path,
    owl_file: Path,
    sparql_file: Path,
    output_path: Path,
) -> RobotResult:
    """Run a SPARQL query against an OWL file via ROBOT."""
    rel_in = _posix_rel(owl_file, board_dir)
    rel_q = _posix_rel(sparql_file, board_dir)
    rel_o = _posix_rel(output_path, board_dir)
    return await run_robot(
        board_dir,
        f"robot query -i {rel_in} --query {rel_q} {rel_o}",
    )
