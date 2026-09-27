import json
import os
import sys
import urllib.parse
import urllib.request

APPLICATIONS = {
    'blockfield-web': '8ov0bEGQkwpLNU2_-AoVT',
    'blockfield-server': 'O5lqpArRmom0IRB8Dzauf',
    'blockfield-proxy': '3ssgIwfLJCYJ62BIsFvkZ',
}


def api(method, payload, get=False):
    url = 'http://127.0.0.1:3000/api/' + method
    if get:
        url += '?' + urllib.parse.urlencode(payload)
    request = urllib.request.Request(url, data=None if get else json.dumps(payload).encode(),
        headers={'Content-Type': 'application/json', 'x-api-key': os.environ['DOKPLOY_API_KEY']})
    with urllib.request.urlopen(request, timeout=60) as response:
        data = response.read()
        return json.loads(data) if data else None


def deploy(service):
    application_id = APPLICATIONS[service]
    image = os.environ.get('DEPLOY_IMAGE', f'ghcr.io/blockfield/{service}:latest')
    if not any(image.startswith(f'ghcr.io/{owner}/{service}:') for owner in ('blockfield', 'netherg-io')):
        raise ValueError('Unexpected deployment image')
    try:
        current = api('application.one', {'applicationId': application_id}, get=True)
        api('application.saveDockerProvider', {'applicationId': application_id, 'dockerImage': image,
            **{k: current.get(k) for k in ('username', 'password', 'registryUrl')}})
        api('application.deploy', {'applicationId': application_id, 'title': f'GitHub Actions: {image}'})
    except Exception as error:
        raise SystemExit(f'{service} deployment failed ({type(error).__name__}); inspect Dokploy') from None
    print(f'{service}: deployment accepted')


if __name__ == '__main__':
    deploy('blockfield-' + sys.argv[1])
