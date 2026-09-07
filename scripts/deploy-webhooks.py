import json
import os
import urllib.request


def deploy(service, url):
    body = {'repository': {'repo_name': f'ghcr.io/netherg-io/{service}'},
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


deploy('blockfield-web', os.environ['WEB_WEBHOOK'])

# Выкат сервера — это его рестарт. Если серверное дерево не изменилось (см. SERVER_CHANGED в
# release.yml), образ не пересобирался и трогать сервер незачем.
if os.environ.get('SERVER_CHANGED') == '1':
    deploy('blockfield-server', os.environ['SERVER_WEBHOOK'])
else:
    print('blockfield-server: содержимое не изменилось, деплой пропущен')
