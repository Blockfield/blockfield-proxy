"""The proxy runs the tested immutable Raknetify build.

The Dockerfile ADD names a fixed fork release and file and carries its sha256,
so a moved tag or a replaced asset fails the image build instead of silently
changing the login transport. Change TESTED only after a stand run of RakNet
(both ports) and TCP logins, together with the client pack pin of the same
release (blockfield-client mods/raknetify.pw.toml).
"""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).parents[1]
DOCKERFILE = ROOT / "distribution" / "Dockerfile.proxy"
RAKNET_ONLY = ROOT / "proxy/plugin/src/main/java/io/blockfield/proxy/RaknetOnly.java"
TESTED = (
    "0.1.0+alpha.5.1-bf3",
    "d8a609bf562d20fbd98d50e9b4c32ffce7b8b51d413b6dc9418af2fd8d07273e",
)
PIN = re.compile(
    r"ADD --checksum=sha256:(?P<sha>[0-9a-f]{64}) \\\n\s+"
    r"https://github\.com/Blockfield/raknetify/releases/download/(?P<tag>bf\d+)/"
    r"(?P<file>raknetify-velocity-(?P<version>\S+)-all\.jar) /plugins/(?P<dest>\S+)\n"
)


class RaknetifyPinTest(unittest.TestCase):
    def test_proxy_pins_the_tested_raknetify_build(self):
        text = DOCKERFILE.read_text(encoding="utf-8")
        self.assertEqual(
            len(re.findall(r"https://\S*raknetify\S*", text)),
            1,
            "exactly one Raknetify download",
        )
        pin = PIN.search(text)
        self.assertIsNotNone(pin, "Raknetify ADD must carry --checksum=sha256")
        self.assertEqual((pin["version"], pin["sha"]), TESTED)
        self.assertTrue(pin["version"].endswith("-" + pin["tag"]), "tag matches jar")
        self.assertEqual(pin["dest"], pin["file"])
        self.assertIn(
            "Raknetify Velocity " + TESTED[0], RAKNET_ONLY.read_text(encoding="utf-8")
        )


if __name__ == "__main__":
    unittest.main()
