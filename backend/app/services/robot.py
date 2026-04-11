"""Centralised ROBOT Docker executor.

All interactions with the ROBOT tool (inside the odkfull container) go
through this module.  Other services call these helpers instead of
touching the Docker SDK directly.
"""

import asyncio
import logging
from dataclasses import dataclass, field
from pathlib import Path

import docker
from docker.errors import ImageNotFound, DockerException

from app.config import ODK_IMAGE

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


# ── Synchronous executor (run in thread pool) ─────────────────
def _run_robot_sync(
    board_dir: Path,
    command: str,
    working_dir: str = "/work",
    timeout: int = 300,
) -> RobotResult:
    """Run a ROBOT command inside the odkfull container.

    Args:
        board_dir: Host path to mount as /work.
        command:   Full command string (e.g. "robot report -i /work/ont.owl ...").
        working_dir: Working directory inside the container.
        timeout:   Container execution timeout in seconds.
    """
    try:
        client = docker.from_env()
        client.images.get(ODK_IMAGE)
    except ImageNotFound:
        logger.warning("ODK image '%s' not found locally", ODK_IMAGE)
        return RobotResult(exit_code=-1, stdout="", stderr=f"Image {ODK_IMAGE} not found")
    except (DockerException, Exception) as exc:
        logger.warning("Docker unavailable: %s", exc)
        return RobotResult(exit_code=-1, stdout="", stderr=str(exc))

    container = client.containers.run(
        image=ODK_IMAGE,
        command=command,
        volumes={str(board_dir): {"bind": "/work", "mode": "rw"}},
        working_dir=working_dir,
        detach=True,
        stdout=True,
        stderr=True,
    )

    try:
        result = container.wait(timeout=timeout)
        stdout = container.logs(stdout=True, stderr=False).decode("utf-8", errors="replace")
        stderr = container.logs(stdout=False, stderr=True).decode("utf-8", errors="replace")
        return RobotResult(
            exit_code=result.get("StatusCode", -1),
            stdout=stdout,
            stderr=stderr,
        )
    finally:
        try:
            container.remove(force=True)
        except Exception:
            pass


# ── Async wrappers ─────────────────────────────────────────────
async def run_robot(
    board_dir: Path,
    command: str,
    working_dir: str = "/work",
    timeout: int = 300,
) -> RobotResult:
    """Async wrapper around _run_robot_sync."""
    loop = asyncio.get_event_loop()
    return await loop.run_in_executor(
        None, _run_robot_sync, board_dir, command, working_dir, timeout,
    )


async def robot_convert(
    board_dir: Path,
    owl_file: Path,
    output_path: Path,
    output_format: str = "json",
) -> RobotResult:
    """Convert an OWL file to another format via ROBOT."""
    rel_in = owl_file.relative_to(board_dir)
    rel_out = output_path.relative_to(board_dir)
    return await run_robot(
        board_dir,
        f"robot convert -i /work/{rel_in} -o /work/{rel_out} --format {output_format}",
    )


async def robot_template(
    board_dir: Path,
    template_path: Path,
    output_path: Path,
) -> RobotResult:
    """Run ROBOT template to generate an OWL file from a CSV template."""
    rel_t = template_path.relative_to(board_dir)
    rel_o = output_path.relative_to(board_dir)
    return await run_robot(
        board_dir,
        f"robot template --template /work/{rel_t} -o /work/{rel_o}",
    )


async def robot_report(
    board_dir: Path,
    owl_file: Path,
) -> RobotResult:
    """Run ROBOT report on an OWL file, producing a TSV report."""
    rel_in = owl_file.relative_to(board_dir)
    return await run_robot(
        board_dir,
        f"robot report -i /work/{rel_in} --output /work/report.tsv --format tsv",
    )


async def robot_reason(
    board_dir: Path,
    owl_file: Path,
    output_path: Path,
    reasoner: str = "ELK",
) -> RobotResult:
    """Run a reasoner on an OWL file."""
    rel_in = owl_file.relative_to(board_dir)
    rel_out = output_path.relative_to(board_dir)
    return await run_robot(
        board_dir,
        f"robot reason -r {reasoner} -i /work/{rel_in} -o /work/{rel_out}",
    )


async def robot_diff(
    board_dir: Path,
    left: Path,
    right: Path,
    output: Path,
) -> RobotResult:
    """Diff two OWL files."""
    rel_l = left.relative_to(board_dir)
    rel_r = right.relative_to(board_dir)
    rel_o = output.relative_to(board_dir)
    return await run_robot(
        board_dir,
        f"robot diff --left /work/{rel_l} --right /work/{rel_r} --output /work/{rel_o}",
    )


async def robot_query(
    board_dir: Path,
    owl_file: Path,
    sparql_file: Path,
    output_path: Path,
) -> RobotResult:
    """Run a SPARQL query against an OWL file via ROBOT."""
    rel_in = owl_file.relative_to(board_dir)
    rel_q = sparql_file.relative_to(board_dir)
    rel_o = output_path.relative_to(board_dir)
    return await run_robot(
        board_dir,
        f"robot query -i /work/{rel_in} --query /work/{rel_q} /work/{rel_o}",
    )
