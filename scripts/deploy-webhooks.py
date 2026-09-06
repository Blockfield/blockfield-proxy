import json
import os
import socket
import struct
import urllib.request


def varint(value):
    out = b''
    while True:
        byte = value & 0x7F
        value >>= 7
        out += struct.pack('B', byte | (0x80 if value else 0))
        if not value:
            return out


def read_varint(stream):
    result = shift = 0
    while True:
        byte = stream.recv(1)[0]
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result
        shift += 7


def players_online(host, port=25565, timeout=10):
    """Server List Ping: сколько игроков сейчас на сервере. None — сервер не ответил."""
    try:
        with socket.create_connection((host, port), timeout) as stream:
            payload = varint(0) + varint(765) + varint(len(host)) + host.encode() + struct.pack('>H', port) + varint(1)
            stream.sendall(varint(len(payload)) + payload)
            stream.sendall(varint(1) + varint(0))
            read_varint(stream), read_varint(stream)
            length = read_varint(stream)
            data = b''
            while len(data) < length:
                chunk = stream.recv(length - len(data))
                if not chunk:
                    break
                data += chunk
            return json.loads(data.decode('utf-8'))['players']['online']
    except Exception:
        return None


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

# Выкат сервера — это его рестарт: идущий матч он оборвал бы. Образ уже в GHCR,
# так что пропуск здесь ничего не теряет — деплой доедет со следующим выпуском или руками.
online = players_online(os.environ.get('SERVER_HOST', 'minecraft.play.nether.pp.ua'))
if online:
    print(f'blockfield-server: на сервере {online} игрок(ов) — деплой пропущен, образ ждёт в GHCR')
else:
    if online is None:
        print('blockfield-server: сервер не ответил на пинг, считаем что игроков нет')
    deploy('blockfield-server', os.environ['SERVER_WEBHOOK'])
