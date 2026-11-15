"""What the golden scripts share: finding the pinned ODK image and running things in it.

Standard library only, so it runs wherever Python and Docker (or Podman) do - the Windows
machine the goldens were first made on, and a Mac.
"""
import json
import os
import subprocess
import sys

PIN = "v1.6.1"
REPOSITORY = "obolibrary/odkfull"
ROBOT = "1.9.10"
ODK_TAG_COMMIT = "3e46f08e8a96de0fcc4fa8e7581d39e8f6b7563f"


def runtime():
    """docker, or podman when docker does not answer."""
    for candidate in ("docker", "podman"):
        try:
            subprocess.run([candidate, "info"], check=True, capture_output=True)
            return candidate
        except (OSError, subprocess.CalledProcessError):
            continue
    sys.exit("Neither docker nor podman answers. The goldens are made in the ODK image.")


def _env_of(tool, reference):
    try:
        out = subprocess.run([tool, "image", "inspect", "--format", "{{json .Config.Env}}", reference],
                             check=True, capture_output=True).stdout
    except subprocess.CalledProcessError:
        return None
    return json.loads(out.decode("utf-8"))


def pinned_image(tool):
    """The image that IS the pinned ODK, whatever it is tagged.

    obolibrary/odkfull:v1.6.1 when it is there; otherwise a local tag of the repository whose own
    ODK_VERSION says v1.6.1 - a machine that pulled `latest` while v1.6.1 was the latest has
    exactly the pinned image under another name.
    """
    wanted = "ODK_VERSION=" + PIN
    candidates = [REPOSITORY + ":" + PIN]
    listed = subprocess.run([tool, "image", "ls", REPOSITORY, "--format", "{{.Repository}}:{{.Tag}}"],
                            check=True, capture_output=True).stdout.decode("utf-8").split()
    candidates += [tag for tag in listed if tag not in candidates and not tag.endswith(":<none>")]
    for reference in candidates:
        env = _env_of(tool, reference)
        if env is not None and wanted in env:
            return reference
    sys.exit("No local %s image is ODK %s (looked at: %s). Pull %s:%s first."
             % (REPOSITORY, PIN, ", ".join(candidates), REPOSITORY, PIN))


def describe(tool, reference):
    """Image id, repo digests and creation time, for a PROVENANCE file."""
    out = subprocess.run([tool, "image", "inspect", "--format",
                          "{{.Id}}\n{{json .RepoDigests}}\n{{.Created}}", reference],
                         check=True, capture_output=True).stdout.decode("utf-8").splitlines()
    return {"reference": reference, "id": out[0], "digests": json.loads(out[1]), "created": out[2]}


def in_image(tool, reference, command, mounts=(), workdir=None, check=True):
    """Runs a command in the image; returns (exit code, stdout bytes, stderr bytes).

    `mounts` are (host directory, container path, "ro" or "rw") triples. Host paths are handed over
    with forward slashes, which Docker Desktop takes on Windows and which keep a backslash out of
    the colon-separated bind specification.
    """
    args = [tool, "run", "--rm"]
    for host, inside, mode in mounts:
        spec = os.path.abspath(host).replace("\\", "/") + ":" + inside + (":ro" if mode == "ro" else "")
        args += ["-v", spec]
    if workdir:
        args += ["-w", workdir]
    if os.name != "nt":
        # As run.sh does: the entrypoint then writes files as this user, not as root.
        args += ["-e", "ODK_USER_ID=%d" % os.getuid(), "-e", "ODK_GROUP_ID=%d" % os.getgid()]
    args += [reference] + list(command)
    done = subprocess.run(args, capture_output=True)
    if check and done.returncode != 0:
        sys.exit("In %s, %s failed (%d):\n%s" % (reference, " ".join(command), done.returncode,
                                                done.stderr.decode("utf-8", "replace")))
    return done.returncode, done.stdout, done.stderr


def write_bytes(path, data):
    directory = os.path.dirname(path)
    if directory:
        os.makedirs(directory, exist_ok=True)
    with open(path, "wb") as out:
        out.write(data)


def write_text(path, text):
    """UTF-8, LF, no byte-order mark - whatever the platform's own conventions are."""
    write_bytes(path, text.encode("utf-8"))
