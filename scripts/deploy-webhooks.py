"""Deploy an exact release through the app's Dokploy webhook. Actions hold only webhook URLs, never a Dokploy API key.

The Dokploy webhook redeploys only the image configured in the app and answers before pulling. So every app is
configured once with its channel tag ghcr.io/blockfield/<app>:prod. deploy() points the channel at the validated
digest, calls the webhook and returns only when the Dokploy log shows the pull of that digest and every running task
of the swarm service runs it healthy.

    deploy-webhooks.py digest <app> <vX.Y.Z>   registry digest of a release image
    deploy-webhooks.py current <app>           digest of the running image (the exact rollback target)
    deploy-webhooks.py deploy <app> <digest>   <app>: server, distribution or proxy
"""
import contextlib
import fcntl
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

REGISTRY = 'ghcr.io/blockfield/'
# Dokploy app name (= swarm service and log directory) and the repository secret holding its webhook URL.
APPS = {
    'blockfield-server': ('blockfield-server-lfcxy0', 'DOKPLOY_SERVER_WEBHOOK'),
    'blockfield-distribution': ('blockfield-web-pojcp4', 'DOKPLOY_DISTRIBUTION_WEBHOOK'),
    'blockfield-proxy': ('blockfield-proxy-niocmj', 'DOKPLOY_PROXY_WEBHOOK'),
}
TAG = re.compile(r'v[0-9]+\.[0-9]+\.[0-9]+')
DIGEST = re.compile(r'sha256:[0-9a-f]{64}')
LOGS = Path('/etc/dokploy/logs')
# One channel move and rollout at a time on the deploy host, whichever repository runs it.
LOCK = '/tmp/blockfield-deploy.lock'
# Held for a whole server or client deploy (validation to rollback), so the pair is never checked by one job and
# switched by another. Deploy server holds it from its workflow with flock(1).
PAIR_LOCK = '/tmp/blockfield-pair.lock'
DEADLINE = 900  # the game server's health check allows a 600 s start
POLL = 5


def docker(*args):
    return subprocess.run(['docker', *args], text=True, capture_output=True, check=True, timeout=300).stdout.strip()


def registry_digest(ref):
    return json.loads(docker('buildx', 'imagetools', 'inspect', ref, '--format', '{{json .Manifest}}'))['digest']


def release_digest(service, tag):
    if service not in APPS or not TAG.fullmatch(tag):
        raise ValueError('Expected a known app and a release tag')
    return registry_digest(f'{REGISTRY}{service}:{tag}')


def running(service):
    """(digests of this app's repository, health) for every running task of the swarm service."""
    ids = docker('ps', '--no-trunc', '--filter', 'label=com.docker.swarm.service.name=' + APPS[service][0],
                 '--format', '{{.ID}}').split()
    result = []
    for cid in ids:
        container = json.loads(docker('inspect', cid))[0]
        digests = json.loads(docker('image', 'inspect', container['Image']))[0].get('RepoDigests') or []
        own = {d.split('@', 1)[1] for d in digests if d.startswith(f'{REGISTRY}{service}@')}
        result.append((own, (container['State'].get('Health') or {}).get('Status', 'none')))
    return result


def runs(service, digest):
    tasks = running(service)
    return bool(tasks) and all(digest in own and health in ('healthy', 'none') for own, health in tasks)


def current(service):
    tasks = running(service)
    if len(tasks) != 1 or not tasks[0][0]:
        raise ValueError(f'{service}: the running image is not one {REGISTRY}{service} digest; no automatic rollback')
    # One image can carry several repo digests (pulled under other manifests); roll back to one the registry still has.
    for digest in sorted(tasks[0][0]):
        try:
            if registry_digest(f'{REGISTRY}{service}@{digest}') == digest:
                return digest
        except subprocess.CalledProcessError:
            continue
    raise ValueError(f'{service}: the running image is gone from the registry; no automatic rollback')


def call(service, note):
    # Docker Hub payload: Dokploy refuses (301) when the image or tag differs from the app's channel,
    # so a secret pasted into the wrong repository cannot redeploy another app.
    body = {'repository': {'repo_name': REGISTRY + service}, 'push_data': {'tag': 'prod', 'pusher': note}}
    request = urllib.request.Request(os.environ[APPS[service][1]], data=json.dumps(body).encode(), method='POST',
                                     headers={'Content-Type': 'application/json', 'User-Agent': 'Go-http-client/1.1'})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            response.read()
    except urllib.error.HTTPError as error:
        try:
            message = json.loads(error.read()).get('message', '')
        except ValueError:
            message = ''
        # Never print the URL: its path is the secret.
        raise SystemExit(f'{service}: Dokploy webhook refused ({error.code}) {message}') from None
    except urllib.error.URLError as error:
        raise SystemExit(f'{service}: Dokploy webhook unreachable ({type(error.reason).__name__})') from None


def dokploy_log(service, since, seen):
    logs = [p for p in (LOGS / APPS[service][0]).glob('*.log') if p.stat().st_mtime >= since and p not in seen]
    newest = max(logs, key=lambda p: p.stat().st_mtime, default=None)
    return newest, newest.read_text(errors='replace') if newest else ''


def wait(condition, seconds, message):
    deadline = time.monotonic() + seconds
    while not condition():
        if time.monotonic() > deadline:
            raise SystemExit(message)
        time.sleep(POLL)


@contextlib.contextmanager
def pair_lock(seconds=1800):
    with open(PAIR_LOCK, 'a') as lock:
        deadline = time.monotonic() + seconds
        while True:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                if time.monotonic() > deadline:
                    raise SystemExit('Another server or client deploy holds the pair lock') from None
                print('Waiting for the pair lock', flush=True)
                time.sleep(POLL)
        yield


def deploy(service, digest):
    if service not in APPS or not DIGEST.fullmatch(digest):
        raise ValueError('Unexpected deployment target')
    image = REGISTRY + service
    with open(LOCK, 'a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        # The channel always names what should run, so a redeploy from the Dokploy UI repeats it.
        docker('buildx', 'imagetools', 'create', '--tag', f'{image}:prod', f'{image}@{digest}')
        wait(lambda: registry_digest(f'{image}:prod') == digest, 120, f'{service}: registry does not serve the new channel')
        if runs(service, digest):
            print(f'{service}: {digest} already runs, webhook not sent')
            return
        note = f"{os.environ.get('GITHUB_REPOSITORY', 'local')} run {os.environ.get('GITHUB_RUN_ID', '-')} {digest}"
        sent, resent, seen = time.time() - 1, False, set()
        call(service, note)
        deadline = time.monotonic() + DEADLINE
        while time.monotonic() < deadline:
            path, log = dokploy_log(service, sent, seen)
            # A pull that failed or returned another digest (registry lag) left the service untouched: safe to resend once.
            if '❌' in log or ('✅' in log and 'Digest: ' + digest not in log):
                if resent:
                    raise SystemExit(f'{service}: Dokploy deployment failed:\n' + '\n'.join(log.splitlines()[-8:]))
                print(f'{service}: Dokploy pull failed, resending the webhook once', flush=True)
                seen.add(path)
                resent = True
                call(service, note)
            elif log and runs(service, digest):
                print(f'{service}: runs {digest}')
                return
            time.sleep(POLL)
        raise SystemExit(f'{service}: {digest} is not running healthy after {DEADLINE} s; see the Dokploy deployment log')


if __name__ == '__main__':
    command, app, *rest = sys.argv[1:]
    service = 'blockfield-' + app
    if command == 'digest':
        print(release_digest(service, *rest))
    elif command == 'current':
        print(current(service))
    elif command == 'deploy':
        deploy(service, *rest)
    else:
        raise SystemExit(__doc__)
