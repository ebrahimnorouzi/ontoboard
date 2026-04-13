"""
Worker service — polls Redis job queue and executes long-running ROBOT tasks.

ROBOT is expected to be on PATH (installed in the Docker image).

Supported job types:
  - reason:       Run a reasoner on the ontology
  - publish:      Run the ODK publish pipeline
  - build_docs:   Generate documentation
  - robot_report: Run ROBOT report
"""

import json
import logging
import os
import subprocess
import time
import traceback

import redis

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(name)s] %(message)s")
logger = logging.getLogger("worker")

REDIS_URL = os.getenv("REDIS_URL", "redis://localhost:6379/0")
DATA_DIR = os.getenv("DATA_DIR", "/app/data")

JOB_QUEUE_KEY = "ontoboard:jobs"
JOB_PREFIX = "ontoboard:job:"
JOB_TTL = 3600 * 24
JOB_PROGRESS_CHANNEL = "ontoboard:progress:"


def update_job(r, job_id, **updates):
    key = f"{JOB_PREFIX}{job_id}"
    job = json.loads(r.get(key) or "{}")
    job.update(updates)
    r.set(key, json.dumps(job), ex=JOB_TTL)
    r.publish(f"{JOB_PROGRESS_CHANNEL}{job_id}", json.dumps(updates))


def run_command(board_dir, command, working_dir=None):
    """Run a command as a local subprocess. Returns (exit_code, stdout, stderr)."""
    cwd = working_dir or os.path.join(board_dir, "src", "ontology")
    if not os.path.isdir(cwd):
        cwd = board_dir

    try:
        result = subprocess.run(
            command, shell=True, cwd=cwd,
            capture_output=True, text=True, timeout=600,
        )
        return result.returncode, result.stdout, result.stderr
    except subprocess.TimeoutExpired:
        return -1, "", "Command timed out after 600 seconds"
    except Exception as exc:
        return -1, "", str(exc)


def handle_reason(r, job_id, board_id, params):
    """Run a reasoner on the ontology."""
    board_dir = os.path.join(DATA_DIR, board_id)
    reasoner = params.get("reasoner", "ELK")
    owl_file = _find_owl(board_dir)
    if not owl_file:
        return {"error": "No OWL file found"}

    update_job(r, job_id, status="running", progress=20)

    rel_owl = os.path.relpath(owl_file, board_dir).replace("\\", "/")
    code, stdout, stderr = run_command(
        board_dir,
        f"robot reason -r {reasoner} -i {rel_owl} -o src/ontology/tmp_inferred.owl",
    )

    update_job(r, job_id, progress=80)
    return {"exit_code": code, "stdout": stdout[-500:], "stderr": stderr[-500:], "reasoner": reasoner}


def handle_publish(r, job_id, board_id, params):
    """Run ODK publish pipeline."""
    board_dir = os.path.join(DATA_DIR, board_id)
    steps = params.get("steps", ["test", "prepare_release", "publish"])
    results = []

    for i, step in enumerate(steps):
        progress = 20 + (i / len(steps)) * 70
        update_job(r, job_id, status="running", progress=progress)
        code, stdout, stderr = run_command(board_dir, f"make {step}")
        results.append({"step": step, "exit_code": code, "stdout": stdout[-200:], "stderr": stderr[-200:]})
        if code != 0:
            return {"results": results, "failed_at": step}

    return {"results": results}


def handle_build_docs(r, job_id, board_id, params):
    """Run make docs."""
    board_dir = os.path.join(DATA_DIR, board_id)
    update_job(r, job_id, status="running", progress=30)
    code, stdout, stderr = run_command(board_dir, "make docs")
    return {"exit_code": code, "stdout": stdout[-500:], "stderr": stderr[-500:]}


def handle_robot_report(r, job_id, board_id, params):
    """Run ROBOT report."""
    board_dir = os.path.join(DATA_DIR, board_id)
    owl_file = _find_owl(board_dir)
    if not owl_file:
        return {"error": "No OWL file found"}

    update_job(r, job_id, status="running", progress=30)
    rel_owl = os.path.relpath(owl_file, board_dir).replace("\\", "/")
    code, stdout, stderr = run_command(
        board_dir,
        f"robot report -i {rel_owl} --output report.tsv --format tsv",
    )
    return {"exit_code": code, "stdout": stdout[-500:], "stderr": stderr[-500:]}


JOB_HANDLERS = {
    "reason": handle_reason,
    "publish": handle_publish,
    "build_docs": handle_build_docs,
    "robot_report": handle_robot_report,
}


def _find_owl(board_dir):
    ont_dir = os.path.join(board_dir, "src", "ontology")
    if os.path.isdir(ont_dir):
        # Prefer -edit.owl
        for f in os.listdir(ont_dir):
            if f.endswith("-edit.owl"):
                return os.path.join(ont_dir, f)
        for f in os.listdir(ont_dir):
            if f.endswith(".owl"):
                return os.path.join(ont_dir, f)
    return None


def main():
    r = redis.from_url(REDIS_URL)
    logger.info("Worker started. Listening on queue '%s'...", JOB_QUEUE_KEY)

    while True:
        try:
            item = r.brpop(JOB_QUEUE_KEY, timeout=5)
            if item is None:
                continue

            _, payload = item
            job = json.loads(payload)
            job_id = job["id"]
            job_type = job["type"]
            board_id = job["board_id"]
            params = job.get("params", {})

            logger.info("Processing job %s: type=%s board=%s", job_id, job_type, board_id)
            update_job(r, job_id, status="running", progress=10)

            handler = JOB_HANDLERS.get(job_type)
            if handler:
                result = handler(r, job_id, board_id, params)
                update_job(r, job_id, status="completed", progress=100, result=result)
                logger.info("Job %s completed", job_id)
            else:
                update_job(r, job_id, status="failed", result={"error": f"Unknown job type: {job_type}"})
                logger.warning("Unknown job type: %s", job_type)

        except Exception as exc:
            logger.error("Worker error: %s\n%s", exc, traceback.format_exc())
            time.sleep(1)


if __name__ == "__main__":
    main()
