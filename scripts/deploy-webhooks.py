import json
import os
import sys
import urllib.request


def deploy(service, url):
    body = {'repository': {'repo_name': f'ghcr.io/blockfield/{service}'},
            'push_data': {'tag': 'latest', 'pusher': 'GitHub Actions'}}
    request = urllib.request.Request(url, data=json.dumps(body).encode(),
                                     headers={'Content-Type': 'application/json', 'User-Agent': 'Go-http-client/1.1'})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            if response.status != 200:
                raise SystemExit(f'{service} deployment rejected: HTTP {response.status}')
    except Exception as error:
        raise SystemExit(f'{service} webhook failed ({type(error).__name__}); inspect Dokploy deployments') from None
    print(f'{service}: deployment accepted')


if __name__ == '__main__':
    service = sys.argv[1]
    if service not in ('web', 'server', 'proxy'):
        raise SystemExit('Expected web, server or proxy')
    deploy(f'blockfield-{service}', os.environ[f'{service.upper()}_WEBHOOK'])
