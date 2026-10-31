"""Defaults and overrides of the RakNet port flags rendered at container start.

proxy/raknet-ports.sh is sourced by start-proxy.sh (strict mode) to build the
JVM flags from BLOCKFIELD_RAKNET_GAME_PORT/_WORKSHOP_PORT. No prod, no
network: hermetic bash invocations with a wiped environment.
"""

import subprocess
import unittest
from pathlib import Path

SNIPPET = Path(__file__).parents[1] / "proxy" / "raknet-ports.sh"


def render(env, strict=False):
    """Source the snippet with exactly env and return $RAKNET_JVM_OPTS."""
    prefix = "set -u; " if strict else ""
    completed = subprocess.run(
        [
            "bash",
            "-c",
            prefix + '. "$1"; printf "%s" "$RAKNET_JVM_OPTS"',
            "_",
            str(SNIPPET),
        ],
        env=dict(env),
        capture_output=True,
        text=True,
        timeout=30,
        check=True,
    )
    return completed.stdout


class RaknetPortsTest(unittest.TestCase):
    def test_defaults_render_25566_and_25567(self):
        self.assertIn("-Draknetify.velocity.portOverride=25566", render({}))
        self.assertIn("-Draknetify.velocity.workshopPort=25567", render({}))

    def test_workshop_override_takes_effect(self):
        flags = render({"BLOCKFIELD_RAKNET_WORKSHOP_PORT": "25568"})
        self.assertIn("-Draknetify.velocity.workshopPort=25568", flags)
        self.assertIn("-Draknetify.velocity.portOverride=25566", flags)

    def test_game_override_takes_effect(self):
        flags = render({"BLOCKFIELD_RAKNET_GAME_PORT": "25570"})
        self.assertIn("-Draknetify.velocity.portOverride=25570", flags)
        self.assertIn("-Draknetify.velocity.workshopPort=25567", flags)

    def test_strict_mode_like_start_proxy(self):
        # start-proxy.sh runs under `set -u`: the snippet must not trip it.
        flags = render({}, strict=True)
        self.assertIn("-Draknetify.velocity.portOverride=25566", flags)

    def test_flags_are_exported(self):
        completed = subprocess.run(
            [
                "bash",
                "-c",
                '. "$1"; bash -c \'printf "%s" "$RAKNET_JVM_OPTS"\'',
                "_",
                str(SNIPPET),
            ],
            env={},
            capture_output=True,
            text=True,
            timeout=30,
            check=True,
        )
        self.assertIn("-Draknetify.velocity.workshopPort=25567", completed.stdout)


if __name__ == "__main__":
    unittest.main()
