import json
import os
import urllib.request

for service, variable in [('blockfield-web', 'WEB_WEBHOOK'), ('blockfield-server', 'SERVER_WEBHOOK')]:
    url = os.environ[variable]
    if not url:
        raise SystemExit(f'Missing deployment webhook for {service}')
    body = {'repository': {'repo_name': f'ghcr.io/netherg-io/{service}'}, 'push_data': {'tag': 'latest', 'pusher': 'GitHub Actions'}}
    request = urllib.request.Request(url, data=json.dumps(body).encode(), headers={'Content-Type': 'application/json', 'User-Agent': 'Go-http-client/1.1'})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            if response.status != 200:
                raise SystemExit(f'{service} deployment rejected: HTTP {response.status}')
    except Exception as error:
        raise SystemExit(f'{service} webhook failed ({type(error).__name__}); inspect Dokploy deployments') from None
    print(f'{service}: deployment accepted')
