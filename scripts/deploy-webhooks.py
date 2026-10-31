"""Deploy an exact release by pinning the Dokploy app to its digest. Actions hold a Dokploy API token, never registry credentials.

The Dokploy app is configured with a digest-pinned image ghcr.io/blockfield/<app>@sha256:<digest> (Docker provider:
only dockerImage; authentication uses the centrally configured GHCR registry, so no per-app registry credentials).
deploy() pins the validated digest via the Dokploy API, redeploys and returns only when the Dokploy log shows the
pull of that digest, every running task of the swarm service runs it healthy and swarm has closed the update's
monitor window. Rollback re-pins the previous digest (see docs in README).

    deploy-webhooks.py digest <app> <vX.Y.Z>   registry digest of a release image
    deploy-webhooks.py current <app>           digest of the running image (the exact rollback target)
    deploy-webhooks.py deploy <app> <digest>   <app>: server or proxy
    deploy-webhooks.py serves <app> <digest>   exit 0 when every running task was started from this digest
"""

import contextlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

try:
    import fcntl
except ImportError:
    fcntl = None

REGISTRY = "ghcr.io/blockfield/"
# Dokploy app name (= swarm service and log directory).
APPS = {
    "blockfield-server": "blockfield-server-lfcxy0",
    "blockfield-proxy": "blockfield-proxy-niocmj",
}
# Dokploy application IDs for the digest pin (stable; see the app URLs in Dokploy).
APPLICATION_IDS = {
    "blockfield-proxy": "3ssgIwfLJCYJ62BIsFvkZ",
}
TAG = re.compile(r"v[0-9]+\.[0-9]+\.[0-9]+")
DIGEST = re.compile(r"sha256:[0-9a-f]{64}")
LOGS = Path("/etc/dokploy/logs")
# One pin and rollout at a time on the deploy host, whichever repository runs it.
LOCK = "/tmp/blockfield-deploy.lock"
# Held for a whole server or client deploy (validation to rollback), so the pair is never checked by one job and
# switched by another. Deploy server holds it from its workflow with flock(1).
PAIR_LOCK = "/tmp/blockfield-pair.lock"
# Webhook to healthy task on the new digest: pull, stop-first stop (grace 120 s), then the game server's health check
# (start period 600 s, 6 retries x 30 s). Measured on prod 2026-09-27: about 1.5 min.
STARTUP = 1200
# Healthy to swarm's "completed": the service's UpdateConfig.Monitor (server 120 s, proxy 60 s)
# plus this margin; 600 s when the monitor cannot be read.
MARGIN = 300
POLL = 5


def docker(*args):
    try:
        return subprocess.run(
            ["docker", *args], text=True, capture_output=True, check=True, timeout=300
        ).stdout.strip()
    except subprocess.CalledProcessError as error:
        # The registry's answer (denied, not found) is the useful part of the failure.
        print(
            f"docker {' '.join(args[:4])}: {error.stderr.strip()[-400:]}",
            file=sys.stderr,
            flush=True,
        )
        raise
    except subprocess.TimeoutExpired as error:
        # TimeoutExpired is not a CalledProcessError: translate it so every caller
        # keeps the documented docker() failure contract.
        print(
            f"docker {' '.join(args[:4])}: timed out after {error.timeout} s",
            file=sys.stderr,
            flush=True,
        )
        raise subprocess.CalledProcessError(
            124, error.cmd, error.stdout, error.stderr
        ) from error


def registry_digest(ref):
    return json.loads(
        docker("buildx", "imagetools", "inspect", ref, "--format", "{{json .Manifest}}")
    )["digest"]


def release_digest(service, tag):
    if service not in APPS or not TAG.fullmatch(tag):
        raise ValueError("Expected a known app and a release tag")
    return registry_digest(f"{REGISTRY}{service}:{tag}")


def running(service):
    """(digests of this app's repository, health) for every running task of the swarm service."""
    ids = docker(
        "ps",
        "--no-trunc",
        "--filter",
        "label=com.docker.swarm.service.name=" + APPS[service],
        "--format",
        "{{.ID}}",
    ).split()
    result = []
    for cid in ids:
        container = json.loads(docker("inspect", cid))[0]
        digests = (
            json.loads(docker("image", "inspect", container["Image"]))[0].get(
                "RepoDigests"
            )
            or []
        )
        own = {
            d.split("@", 1)[1] for d in digests if d.startswith(f"{REGISTRY}{service}@")
        }
        result.append(
            (own, (container["State"].get("Health") or {}).get("Status", "none"))
        )
    return result


def runs(service, digest):
    tasks = running(service)
    return bool(tasks) and all(
        digest in own and health in ("healthy", "none") for own, health in tasks
    )


def swarm_update(service):
    """completed/updating/paused/rollback_* of the last service update; '' when it has none.

    Raises CalledProcessError when the inspect itself fails: the state is unknown, which
    deploy() must never read as a completed update.
    """
    return docker(
        "service",
        "inspect",
        APPS[service],
        "--format",
        "{{if .UpdateStatus}}{{.UpdateStatus.State}}{{end}}",
    )


def monitor_window(service):
    try:
        # json: a plain template prints the Duration as "1m0s"
        nanoseconds = docker(
            "service",
            "inspect",
            APPS[service],
            "--format",
            "{{with .Spec.UpdateConfig}}{{json .Monitor}}{{end}}",
        )
        return int(nanoseconds) // 10**9 or 600
    except (subprocess.CalledProcessError, ValueError):
        return 600


def serves(service, digest):
    """Every running task was started from digest, healthy or not: which release the app is actually on."""
    tasks = running(service)
    return bool(tasks) and all(digest in own for own, _ in tasks)


def current(service):
    tasks = running(service)
    if len(tasks) != 1 or not tasks[0][0]:
        raise ValueError(
            f"{service}: the running image is not one {REGISTRY}{service} digest; no automatic rollback"
        )
    # One image can carry several repo digests (pulled under other manifests); roll back to one the registry still has.
    for digest in sorted(tasks[0][0]):
        try:
            if registry_digest(f"{REGISTRY}{service}@{digest}") == digest:
                return digest
        except subprocess.CalledProcessError:
            continue
    raise ValueError(
        f"{service}: the running image is gone from the registry; no automatic rollback"
    )


def dokploy_api(service, path, payload):
    base = os.environ.get("DOKPLOY_API_URL", "").rstrip("/")
    token = os.environ.get("DOKPLOY_API_TOKEN", "")
    if not base or not token:
        raise SystemExit(
            f"{service}: set DOKPLOY_API_URL (Dokploy dashboard base URL) and DOKPLOY_API_TOKEN "
            "(Dokploy profile settings -> API/CLI) repository secrets before deploying; "
            "see the deployment section in README.md"
        )
    request = urllib.request.Request(
        base + path,
        data=json.dumps(payload).encode(),
        method="POST",
        headers={"Content-Type": "application/json", "x-api-key": token},
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            response.read()
    except urllib.error.HTTPError as error:
        # Never print the token: it travels only in the request header.
        raise SystemExit(
            f"{service}: Dokploy API {path} refused ({error.code})"
        ) from None
    except urllib.error.URLError as error:
        raise SystemExit(
            f"{service}: Dokploy API unreachable ({type(error.reason).__name__})"
        ) from None


def pin(service, digest):
    """Point the Dokploy app at the exact digest (Docker provider: image only, no registry credentials in app fields)."""
    if service not in APPLICATION_IDS or not DIGEST.fullmatch(digest):
        raise ValueError("Unexpected deployment target")
    dokploy_api(
        service,
        "/api/application.saveDockerProvider",
        {
            "applicationId": APPLICATION_IDS[service],
            "dockerImage": f"{REGISTRY}{service}@{digest}",
            "username": None,
            "password": None,
            "registryUrl": None,
        },
    )


def redeploy(service):
    if service not in APPLICATION_IDS:
        raise ValueError("Unexpected deployment target")
    dokploy_api(
        service,
        "/api/application.redeploy",
        {"applicationId": APPLICATION_IDS[service]},
    )


def dokploy_log(service, since, seen):
    logs = [
        p
        for p in (LOGS / APPS[service]).glob("*.log")
        if p.stat().st_mtime >= since and p not in seen
    ]
    newest = max(logs, key=lambda p: p.stat().st_mtime, default=None)
    return newest, newest.read_text(errors="replace") if newest else ""


def wait(condition, seconds, message):
    deadline = time.monotonic() + seconds
    while not condition():
        if time.monotonic() > deadline:
            raise SystemExit(message)
        time.sleep(POLL)


@contextlib.contextmanager
def pair_lock(seconds=1800):
    if fcntl is None:
        raise RuntimeError("Production deployment locking requires a Linux runner")
    with open(PAIR_LOCK, "a") as lock:
        deadline = time.monotonic() + seconds
        while True:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                if time.monotonic() > deadline:
                    raise SystemExit(
                        "Another server or client deploy holds the pair lock"
                    ) from None
                print("Waiting for the pair lock", flush=True)
                time.sleep(POLL)
        yield


def deploy(service, digest):
    if service not in APPS or not DIGEST.fullmatch(digest):
        raise ValueError("Unexpected deployment target")
    if fcntl is None:
        raise RuntimeError("Production deployment locking requires a Linux runner")
    with open(LOCK, "a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        # The pin always names what should run, so a redeploy from the Dokploy UI repeats it.
        pin(service, digest)
        if runs(service, digest):
            print(f"{service}: {digest} already runs, redeploy not sent")
            return
        sent, resent, seen = time.time() - 1, False, set()
        redeploy(service)
        limit, monitoring = STARTUP, False
        deadline = time.monotonic() + limit
        while time.monotonic() < deadline:
            path, log = dokploy_log(service, sent, seen)
            # A pull that failed or returned another digest (registry lag) left the service untouched: safe to resend once.
            if "❌" in log or ("✅" in log and "Digest: " + digest not in log):
                if resent:
                    raise SystemExit(
                        f"{service}: Dokploy deployment failed:\n"
                        + "\n".join(log.splitlines()[-8:])
                    )
                print(
                    f"{service}: Dokploy pull failed, resending the redeploy once",
                    flush=True,
                )
                seen.add(path)
                resent = True
                redeploy(service)
            # The update state is only this update's once a task of the digest ran healthy; before that it can be
            # the previous update's paused or rollback.
            elif log and (monitoring or runs(service, digest)):
                # Swarm may still pause or roll back the update inside its monitor window.
                try:
                    state = swarm_update(service)
                except subprocess.CalledProcessError:
                    # The update state is unknown, never a completed update: keep waiting
                    # for a readable state within the deadline instead of reporting success.
                    print(
                        f"{service}: cannot read the swarm update state, retrying",
                        flush=True,
                    )
                    state = None
                if state is not None and (
                    state.startswith("rollback") or state == "paused"
                ):
                    raise SystemExit(
                        f"{service}: swarm update {state}; see docker service ps"
                    )
                if state is not None and state != "updating" and runs(service, digest):
                    print(f"{service}: runs {digest}")
                    return
                if not monitoring:
                    # A slow start does not eat into the monitor window: the limit restarts here.
                    monitoring, limit = True, monitor_window(service) + MARGIN
                    deadline = time.monotonic() + limit
                    print(
                        f"{service}: healthy on {digest}, waiting up to {limit} s for swarm to complete the update",
                        flush=True,
                    )
            time.sleep(POLL)
        if monitoring:
            raise SystemExit(
                f"{service}: swarm has not completed the update {limit} s after the task became healthy; "
                "see docker service inspect"
            )
        raise SystemExit(
            f"{service}: {digest} is not running healthy after {STARTUP} s; see the Dokploy deployment log"
        )


if __name__ == "__main__":
    command, app, *rest = sys.argv[1:]
    service = "blockfield-" + app
    if command == "digest":
        print(release_digest(service, *rest))
    elif command == "current":
        print(current(service))
    elif command == "deploy":
        deploy(service, *rest)
    elif command == "serves":
        sys.exit(0 if serves(service, *rest) else 1)
    else:
        raise SystemExit(__doc__)
