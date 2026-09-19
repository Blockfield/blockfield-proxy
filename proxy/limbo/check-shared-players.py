#!/usr/bin/env python3
"""Two-client protocol check: python3 check-shared-players.py <limbo.jar> <port>.

Run against an isolated Limbo with velocity-modern=false, view-distance=2,
no resource pack, and the Blockfield lobby spawn. Uses only the standard library.
"""
import io
import json
import socket
import struct
import sys
import time
import uuid
import zipfile


def varint(value):
    result = bytearray()
    while value > 127:
        result.append((value & 127) | 128)
        value >>= 7
    return bytes(result + bytes([value]))


def read_varint(stream):
    value = 0
    for shift in range(0, 35, 7):
        raw = stream.read(1)
        if not raw:
            raise EOFError('Connection closed')
        value |= (raw[0] & 127) << shift
        if raw[0] < 128:
            return value
    raise ValueError('Invalid VarInt')


def string(value):
    raw = value.encode()
    return varint(len(raw)) + raw


class Client:
    def __init__(self, name, port, packets):
        self.packets = packets
        self.uuid = uuid.uuid3(uuid.NAMESPACE_DNS, name)
        self.socket = socket.create_connection(('127.0.0.1', port), timeout=20)
        self.stream = self.socket.makefile('rb')
        self.phase = 'login'
        self.seen = []
        self.send(0, varint(767) + string('localhost') + struct.pack('>HB', port, 2))
        self.send(0, string(name) + self.uuid.bytes)
        while self.phase != 'play':
            key, _ = self.receive()
            if key == 'game_profile':
                self.send_named('login_acknowledged')
                self.phase = 'configuration'
            elif key == 'finish_configuration':
                self.send_named('finish_configuration')
                self.phase = 'play'
        self.until(lambda key, data: key == 'player_position')

    def send(self, packet, data=b''):
        body = varint(packet) + data
        self.socket.sendall(varint(len(body)) + body)

    def send_named(self, key, data=b''):
        self.send(self.packets[self.phase]['serverbound']['minecraft:' + key]['protocol_id'], data)

    def receive(self):
        size = read_varint(self.stream)
        body = io.BytesIO(self.stream.read(size))
        packet = read_varint(body)
        key = next(key.removeprefix('minecraft:') for key, item in
                   self.packets[self.phase]['clientbound'].items() if item['protocol_id'] == packet)
        data = body.read()
        self.seen.append((key, data))
        if key == 'keep_alive':
            self.send_named('keep_alive', data)
        elif key == 'player_position':
            teleport = read_varint(io.BytesIO(data[33:]))
            self.send_named('accept_teleportation', varint(teleport))
        elif key == 'disconnect':
            raise AssertionError(('Disconnected', data))
        return key, data

    def until(self, predicate):
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            key, data = self.receive()
            if predicate(key, data):
                return data
        raise AssertionError('Expected packet did not arrive')

    def sees(self, other):
        def spawned(key, data):
            if key != 'add_entity':
                return False
            body = io.BytesIO(data)
            entity = read_varint(body)
            if body.read(16) == other.uuid.bytes:
                other.entity = entity
                return True
            return False
        if not any(spawned(key, data) for key, data in self.seen):
            self.until(spawned)
        spawn_index = next(i for i, (key, data) in enumerate(self.seen) if spawned(key, data))
        assert any(key == 'player_info_update' and other.uuid.bytes in data
                   for key, data in self.seen[:spawn_index]), 'Profile must precede spawn'
        for key, data in self.seen:
            if key == 'add_entity':
                body = io.BytesIO(data)
                read_varint(body)
                assert body.read(16) != self.uuid.bytes, 'Must not spawn self'

    def close(self):
        self.stream.close()
        self.socket.close()


def main():
    with zipfile.ZipFile(sys.argv[1]) as jar:
        packets = json.loads(jar.read('reports/packets.json'))
    port = int(sys.argv[2])
    first = Client('LobbyCheckA', port, packets)
    second = None
    try:
        second = Client('LobbyCheckB', port, packets)
        first.sees(second)
        second.sees(first)
        second.send_named('move_player_pos_rot', struct.pack('>dddff?', 60.5, 18, 52.5, 90, 15, True))
        data = first.until(lambda key, data: key == 'teleport_entity')
        body = io.BytesIO(data)
        assert read_varint(body) == second.entity
        assert struct.unpack('>ddd', body.read(24)) == (60.5, 18, 52.5)
        assert body.read(2) == bytes([64, 10])
        data = first.until(lambda key, data: key == 'rotate_head')
        body = io.BytesIO(data)
        assert read_varint(body) == second.entity and body.read(1) == bytes([64])
        second.close()
        first.until(lambda key, data: key == 'player_info_remove' and second.uuid.bytes in data)
        data = first.until(lambda key, data: key == 'remove_entities')
        body = io.BytesIO(data)
        assert read_varint(body) == 1 and read_varint(body) == second.entity
        first.seen.clear()
        second = Client('LobbyCheckB', port, packets)
        first.sees(second)
        second.sees(first)
        print('PASS: mutual visibility, profile ordering, no self-spawn, movement, head rotation, quit, reconnect')
    finally:
        first.close()
        if second:
            second.close()


if __name__ == '__main__':
    main()
