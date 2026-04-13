"""
Worker service — polls Redis job queue and executes long-running tasks
inside the odkfull Docker container.

Supported job types:
  - reason:       Run a reasoner on the ontology
  - publish:      Run the ODK publish pipeline
  - build_docs:   Generate documentation
  - build_kg:     Build a knowledge graph from CSV
  - robot_report: Run ROBOT report
"""

import json
import logging
import os
import time
import traceback

import docker
import redis

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(name)s] %(message)s")
logger = logging.getLogger("worker")

REDIS_URL = os.getenv("REDIS_URL", "redis://localhost:6379/0")
DATA_DIR = os.getenv("DATA_DIR", "/app/data")
ODK_IMAGE = os.getenv("ODK_IMAGE", "obolibrary/odkfull:latest")
DOCKER_HOST = os.getenv("DOCKER_HOST", "unix:///var/run/docker.sock")

JOB_QUEUE_KEY = "ontoboard:jobs"
JOB_PREFIX = "ontoboard:job:"
JOB_PROGRESS_CHANNEL = "ontoboard:progress:"
JOB_TTL = 3600


def get_redis():
    return redis.from_url(REDIS_URL, decode_responses=True)


def update_job(r, job_id, **updates):
    """Update job status and publish progress."""
    key = f"{JOB_PREFIX}{job_id}"
    data = r.get(key)
    if not data:
        return
    job = json.loads(data)
    job.update(updates)
    r.set(key, json.dumps(job), ex=JOB_TTL)
    r.publish(f"{JOB_PROGRESS_CHANNEL}{job_id}", json.dumps(updates))


def run_docker_command(board_dir, command, working_dir="/work"):
    """Run a command in the odkfull container. Returns (exit_code, stdout, stderr).

    Auto-pulls the ODK image if it is not found locally.
    """
    try:
        client = docker.from_env()
        try:
            client.images.get(ODK_IMAGE)
        except Exception:
            print(f"[worker] ODK image '{ODK_IMAGE}' not found — pulling...")
            try:
                client.images.pull(ODK_IMAGE)
                print(f"[worker] Successfully pulled '{ODK_IMAGE}'")
            except Exception as pull_exc:
                return -1, "", f"Docker/ODK image not found and pull failed: {pull_exc}"
    except Exception as exc:
        return -1, "", f"Docker unavailable: {exc}"

    # Resolve to absolute path — Docker requires absolute paths for bind mounts
    abs_dir = os.path.abspath(board_dir)
    # Windows Docker mount path: C:\... → /c/...
    mount_path = abs_dir.replace("\\", "/")
    if len(mount_path) >= 2 and mount_path[1] == ":":
        mount_path = "/" + mount_path[0].lower() + mount_path[2:]

    container = client.containers.run(
        image=ODK_IMAGE,
        command=command,
        volumes={mount_path: {"bind": "/work", "mode": "rw"}},
        working_dir=working_dir,
        detach=True,
        stdout=True,
        stderr=True,
    )

    try:
        result = container.wait(timeout=600)
        stdout = container.logs(stdout=True, stderr=False).decode("utf-8", errors="replace")
        stderr = container.logs(stdout=False, stderr=True).decode("utf-8", errors="replace")
        return result.get("StatusCode", -1), stdout, stderr
    finally:
        try:
            container.remove(force=True)
        except Exception:
            pass


def handle_reason(r, job_id, board_id, params):
    """Run a reasoner on the ontology."""
    board_dir = os.path.join(DATA_DIR, board_id)
    reasoner = params.get("reasoner", "ELK")
    owl_file = _find_owl(board_dir)
    if not owl_file:
        return {"error": "No OWL file found"}

    update_job(r, job_id, status="running", progress=20)

    rel_owl = os.path.relpath(owl_file, board_dir)
    code, stdout, stderr = run_docker_command(
        board_dir,
        f"robot reason -r {reasoner} -i /work/{rel_owl} -o /work/src/ontology/tmp_inferred.owl",
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
        code, stdout, stderr = run_docker_command(
            board_dir, f"make {step}", working_dir="/work/src/ontology",
        )
        results.append({"step": step, "exit_code": code, "stdout": stdout[-200:], "stderr": stderr[-200:]})
        if code != 0:
            return {"results": results, "failed_at": step}

    return {"results": results}


def handle_build_docs(r, job_id, board_id, params):
    """Run make docs."""
    board_dir = os.path.join(DATA_DIR, board_id)
    update_job(r, job_id, status="running", progress=30)
    code, stdout, stderr = run_docker_command(
        board_dir, "make docs", working_dir="/work/src/ontology",
    )
    return {"exit_code": code, "stdout": stdout[-500:], "stderr": stderr[-500:]}


def handle_robot_report(r, job_id, board_id, params):
    """Run ROBOT report."""
    board_dir = os.path.join(DATA_DIR, board_id)
    owl_file = _find_owl(board_dir)
    if not owl_file:
        return {"error": "No OWL file found"}

    update_job(r, job_id, status="running", progress=30)
    rel_owl = os.path.relpath(owl_file, board_dir)
    code, stdout, stderr = run_docker_command(
        board_dir,
        f"robot report -i /work/{rel_owl} --output /work/report.tsv --format tsv",
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
        for f in os.listdir(ont_dir):
            if f.endswith(".owl"):
                return os.path.join(ont_dir, f)
    return None


def main():
    logger.info("Worker starting. Redis: %s, Data: %s", REDIS_URL, DATA_DIR)

    r = get_redis()
    r.ping()
    logger.info("Connected to Redis")

    while True:
        # Block-pop from the job queue (timeout 5s)
        item = r.blpop(JOB_QUEUE_KEY, timeout=5)
        if item is None:
            continue

        _, raw = item
        try:
            job_msg = json.loads(raw)
        except json.JSONDecodeError:
            logger.warning("Invalid job message: %s", raw)
            continue

        job_id = job_msg["job_id"]
        board_id = job_msg["board_id"]
        job_type = job_msg["job_type"]
        params = job_msg.get("params", {})

        logger.info("Processing job %s: %s for board %s", job_id, job_type, board_id)
        update_job(r, job_id, status="running", progress=10)

        handler = JOB_HANDLERS.get(job_type)
        if not handler:
            update_job(r, job_id, status="failed", error=f"Unknown job type: {job_type}", progress=100)
            continue

        try:
            t0 = time.time()
            result = handler(r, job_id, board_id, params)
            duration = time.time() - t0
            result["duration_seconds"] = round(duration, 2)

            if result.get("error"):
                update_job(r, job_id, status="failed", error=result["error"], result=result, progress=100)
            else:
                update_job(r, job_id, status="completed", result=result, progress=100)

            logger.info("Job %s completed in %.1fs", job_id, duration)

        except Exception as exc:
            logger.error("Job %s failed: %s", job_id, traceback.format_exc())
            update_job(r, job_id, status="failed", error=str(exc), progress=100)


if __name__ == "__main__":
    main()
