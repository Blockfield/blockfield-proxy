"""docker() keeps its CalledProcessError contract, including on timeouts."""

import importlib.util
import subprocess
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location(
    "deploy_webhooks", ROOT / "scripts" / "deploy-webhooks.py"
)
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)


class DockerFailureTest(unittest.TestCase):
    def test_timeout_surfaces_as_called_process_error(self):
        timeout = subprocess.TimeoutExpired(cmd=["docker", "ps"], timeout=300)
        with patch.object(deploy.subprocess, "run", side_effect=timeout):
            with self.assertRaises(subprocess.CalledProcessError):
                deploy.docker("ps")

    def test_unreadable_swarm_state_stays_unknown(self):
        # monitor_window() must never read a docker failure as a completed update.
        with patch.object(
            deploy,
            "docker",
            side_effect=subprocess.CalledProcessError(124, ["docker"]),
        ):
            self.assertEqual(deploy.monitor_window("blockfield-proxy"), 600)

    def test_swarm_update_reports_failures_as_called_process_error(self):
        with patch.object(
            deploy,
            "docker",
            side_effect=subprocess.CalledProcessError(1, ["docker"]),
        ):
            with self.assertRaises(subprocess.CalledProcessError):
                deploy.swarm_update("blockfield-proxy")


if __name__ == "__main__":
    unittest.main()
