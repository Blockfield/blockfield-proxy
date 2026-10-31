"""Dokploy digest pin: the deploy sets dockerImage to the exact digest, no :latest, no registry credentials."""

import importlib.util
import json
import os
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location(
    "deploy_webhooks", ROOT / "scripts" / "deploy-webhooks.py"
)
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)

DIGEST = "a" * 64
PREVIOUS = "b" * 64
APP_ID = "3ssgIwfLJCYJ62BIsFvkZ"
IMAGE = "ghcr.io/blockfield/blockfield-proxy"


class FakeDokploy:
    """Minimal Dokploy API: records saveDockerProvider/redeploy calls."""

    def __init__(self, status=200):
        self.calls = []
        self.status = status
        state = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                length = int(self.headers.get("Content-Length", 0))
                body = json.loads(self.rfile.read(length) or b"{}")
                state.calls.append(
                    {
                        "path": self.path,
                        "key": self.headers.get("x-api-key"),
                        "body": body,
                    }
                )
                payload = b"{}"
                self.send_response(state.status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever)
        self.thread.start()

    @property
    def url(self):
        return f"http://127.0.0.1:{self.server.server_port}"

    def stop(self):
        self.server.shutdown()
        self.thread.join()
        self.server.server_close()


class PinTest(unittest.TestCase):
    def setUp(self):
        self.api = FakeDokploy()
        self.addCleanup(self.api.stop)
        self.env = patch.dict(
            os.environ,
            {"DOKPLOY_API_URL": self.api.url, "DOKPLOY_API_TOKEN": "test-token"},
        )
        self.env.start()
        self.addCleanup(self.env.stop)

    def test_pin_sets_digest_image_without_registry_credentials(self):
        deploy.pin("blockfield-proxy", f"sha256:{DIGEST}")
        (call,) = self.api.calls
        self.assertEqual(call["path"], "/api/application.saveDockerProvider")
        self.assertEqual(call["key"], "test-token")
        self.assertEqual(
            call["body"],
            {
                "applicationId": APP_ID,
                "dockerImage": f"{IMAGE}@sha256:{DIGEST}",
                "username": None,
                "password": None,
                "registryUrl": None,
            },
        )

    def test_pin_rejects_a_movable_tag_or_an_unknown_service(self):
        with self.assertRaises(ValueError):
            deploy.pin("blockfield-proxy", "latest")
        with self.assertRaises(ValueError):
            deploy.pin("blockfield-proxy", "sha256:" + "z" * 64)
        with self.assertRaises(ValueError):
            deploy.pin("blockfield-server", f"sha256:{DIGEST}")
        self.assertEqual(self.api.calls, [])

    def test_pin_fails_closed_without_api_credentials(self):
        with patch.dict(os.environ, {"DOKPLOY_API_TOKEN": ""}):
            with self.assertRaisesRegex(SystemExit, "DOKPLOY_API_TOKEN"):
                deploy.pin("blockfield-proxy", f"sha256:{DIGEST}")
        with patch.dict(os.environ, {"DOKPLOY_API_URL": ""}):
            with self.assertRaisesRegex(SystemExit, "DOKPLOY_API_URL"):
                deploy.pin("blockfield-proxy", f"sha256:{DIGEST}")
        self.assertEqual(self.api.calls, [])

    def test_redeploy_triggers_the_pinned_app(self):
        deploy.redeploy("blockfield-proxy")
        (call,) = self.api.calls
        self.assertEqual(call["path"], "/api/application.redeploy")
        self.assertEqual(call["body"], {"applicationId": APP_ID})

    def test_deploy_pins_redeploys_and_verifies_the_digest(self):
        with (
            patch.object(deploy, "runs", side_effect=[False, True, True]) as runs,
            patch.object(
                deploy,
                "dokploy_log",
                return_value=("p", "✅ pulled\nDigest: sha256:" + DIGEST),
            ),
            patch.object(deploy, "swarm_update", return_value="completed"),
        ):
            deploy.deploy("blockfield-proxy", f"sha256:{DIGEST}")
            self.assertTrue(runs.called)
        paths = [call["path"] for call in self.api.calls]
        self.assertEqual(
            paths,
            ["/api/application.saveDockerProvider", "/api/application.redeploy"],
        )
        self.assertEqual(
            self.api.calls[0]["body"]["dockerImage"], f"{IMAGE}@sha256:{DIGEST}"
        )

    def test_failed_deploy_leaves_the_new_pin_for_rollback_to_replace(self):
        # The workflow wrapper rolls back by deploying the previous digest,
        # which re-pins it: the failed deploy must not restore :latest itself.
        with (
            patch.object(deploy, "runs", side_effect=[False, True]),
            patch.object(
                deploy,
                "dokploy_log",
                return_value=("p", "✅ pulled\nDigest: sha256:" + DIGEST),
            ),
            patch.object(deploy, "swarm_update", return_value="paused"),
        ):
            with self.assertRaises(SystemExit):
                deploy.deploy("blockfield-proxy", f"sha256:{DIGEST}")
        with (
            patch.object(deploy, "runs", return_value=True),
            patch.object(deploy, "pin") as pin,
            patch.object(deploy, "redeploy"),
        ):
            deploy.deploy("blockfield-proxy", f"sha256:{PREVIOUS}")
            pin.assert_called_once_with("blockfield-proxy", f"sha256:{PREVIOUS}")


if __name__ == "__main__":
    unittest.main()
