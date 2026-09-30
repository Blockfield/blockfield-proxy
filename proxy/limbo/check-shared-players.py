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
            raise EOFError("Connection closed")
        value |= (raw[0] & 127) << shift
        if raw[0] < 128:
            return value
    raise ValueError("Invalid VarInt")


def string(value):
    raw = value.encode()
    return varint(len(raw)) + raw


class Client:
    def __init__(self, name, port, packets, player_type):
        self.packets = packets
        self.player_type = player_type
        self.uuid = uuid.uuid3(uuid.NAMESPACE_DNS, name)
        self.socket = socket.create_connection(("127.0.0.1", port), timeout=20)
        self.stream = self.socket.makefile("rb")
        self.phase = "login"
        self.seen = []
        self.send(0, varint(767) + string("localhost") + struct.pack(">HB", port, 2))
        self.send(0, string(name) + self.uuid.bytes)
        while self.phase != "play":
            key, _ = self.receive()
            if key == "game_profile":
                self.send_named("login_acknowledged")
                self.phase = "configuration"
            elif key == "finish_configuration":
                self.send_named("finish_configuration")
                self.phase = "play"
        position = self.until(lambda key, data: key == "player_position")
        assert position[32] == 0, "Initial spawn must use absolute coordinates"
        self.position = struct.unpack(">ddd", position[:24])

    def send(self, packet, data=b""):
        body = varint(packet) + data
        self.socket.sendall(varint(len(body)) + body)

    def send_named(self, key, data=b""):
        self.send(
            self.packets[self.phase]["serverbound"]["minecraft:" + key]["protocol_id"],
            data,
        )

    def receive(self):
        size = read_varint(self.stream)
        body = io.BytesIO(self.stream.read(size))
        packet = read_varint(body)
        key = next(
            key.removeprefix("minecraft:")
            for key, item in self.packets[self.phase]["clientbound"].items()
            if item["protocol_id"] == packet
        )
        data = body.read()
        self.seen.append((key, data))
        if key == "keep_alive":
            self.send_named("keep_alive", data)
        elif key == "player_position":
            teleport = read_varint(io.BytesIO(data[33:]))
            self.send_named("accept_teleportation", varint(teleport))
        elif key == "disconnect":
            raise AssertionError(("Disconnected", data))
        return key, data

    def until(self, predicate):
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            key, data = self.receive()
            if predicate(key, data):
                return data
        raise AssertionError("Expected packet did not arrive")

    def sees(self, other):
        def spawned(key, data):
            if key != "add_entity":
                return False
            body = io.BytesIO(data)
            entity = read_varint(body)
            if body.read(16) == other.uuid.bytes:
                other.entity = entity
                return True
            return False

        if not any(spawned(key, data) for key, data in self.seen):
            self.until(spawned)
        spawn_index = next(
            i for i, (key, data) in enumerate(self.seen) if spawned(key, data)
        )
        # A wrong type id spawns another entity (106 = tnt) and the client drops on the skin-layer metadata below.
        body = io.BytesIO(self.seen[spawn_index][1])
        read_varint(body)
        body.read(16)
        spawned_type = read_varint(body)
        assert spawned_type == self.player_type, (
            "Player spawned as entity type",
            spawned_type,
        )
        assert any(
            key == "player_info_update" and other.uuid.bytes in data
            for key, data in self.seen[:spawn_index]
        ), "Profile must precede spawn"
        # 1.21.1 client decodes only this shape from Limbo: index 17 (skin layers), serializer 0 (byte), value, 0xff.
        prefix = varint(other.entity)
        metadata = [
            data
            for key, data in self.seen[spawn_index:]
            if key == "set_entity_data" and data.startswith(prefix)
        ]
        if not metadata:
            metadata = [
                self.until(
                    lambda key, data: (
                        key == "set_entity_data" and data.startswith(prefix)
                    )
                )
            ]
        for data in metadata:
            rest = data[len(prefix) :]
            assert len(rest) == 4 and rest[:2] == bytes([17, 0]) and rest[3] == 0xFF, (
                "Undecodable player metadata",
                rest,
            )
        for key, data in self.seen:
            if key == "add_entity":
                body = io.BytesIO(data)
                read_varint(body)
                assert body.read(16) != self.uuid.bytes, "Must not spawn self"

    def close(self):
        self.stream.close()
        self.socket.close()


def main():
    with zipfile.ZipFile(sys.argv[1]) as jar:
        packets = json.loads(jar.read("reports/packets.json"))
        player_type = json.loads(jar.read("reports/registries.json"))[
            "minecraft:entity_type"
        ]["entries"]["minecraft:player"]["protocol_id"]
    port = int(sys.argv[2])
    first = Client("LobbyCheckA", port, packets, player_type)
    second = None
    try:
        second = Client("LobbyCheckB", port, packets, player_type)
        first.sees(second)
        second.sees(first)
        x, y, z = first.position
        target = (x + 1, y, z + 1)
        second.send_named(
            "move_player_pos_rot", struct.pack(">dddff?", *target, 90, 15, True)
        )
        data = first.until(lambda key, data: key == "teleport_entity")
        body = io.BytesIO(data)
        assert read_varint(body) == second.entity
        assert struct.unpack(">ddd", body.read(24)) == target
        assert body.read(2) == bytes([64, 10])
        data = first.until(lambda key, data: key == "rotate_head")
        body = io.BytesIO(data)
        assert read_varint(body) == second.entity and body.read(1) == bytes([64])
        second.close()
        first.until(
            lambda key, data: key == "player_info_remove" and second.uuid.bytes in data
        )
        data = first.until(lambda key, data: key == "remove_entities")
        body = io.BytesIO(data)
        assert read_varint(body) == 1 and read_varint(body) == second.entity
        first.seen.clear()
        second = Client("LobbyCheckB", port, packets, player_type)
        first.sees(second)
        second.sees(first)
        print(
            "PASS: player entity type, mutual visibility, profile ordering, no self-spawn, movement, head rotation, quit, reconnect"
        )
    finally:
        first.close()
        if second:
            second.close()


if __name__ == "__main__":
    main()
