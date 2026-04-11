"""GitHub integration service — create issues from tasks."""

import logging
import urllib.request
import urllib.error
import json

logger = logging.getLogger("ontoboard.github")


def create_github_issue(
    repo_owner: str,
    repo_name: str,
    token: str,
    title: str,
    body: str,
    labels: list[str] | None = None,
) -> dict:
    """Create a GitHub issue via the REST API.

    Returns {"url": "...", "number": N} on success, or {"error": "..."} on failure.
    Uses urllib to avoid requiring httpx in production deps.
    """
    url = f"https://api.github.com/repos/{repo_owner}/{repo_name}/issues"
    payload = {
        "title": title,
        "body": body,
    }
    if labels:
        payload["labels"] = labels

    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "Content-Type": "application/json",
            "X-GitHub-Api-Version": "2022-11-28",
        },
        method="POST",
    )

    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            result = json.loads(resp.read())
            return {
                "url": result.get("html_url", ""),
                "number": result.get("number", 0),
            }
    except urllib.error.HTTPError as exc:
        body_text = exc.read().decode("utf-8", errors="replace")
        logger.warning("GitHub API error %d: %s", exc.code, body_text[:200])
        return {"error": f"GitHub API error {exc.code}: {body_text[:200]}"}
    except Exception as exc:
        logger.warning("GitHub request failed: %s", exc)
        return {"error": str(exc)}
