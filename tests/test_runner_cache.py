"""Self-hosted runner must reuse a warm tool cache: zero downloads (server#351).

The toolchain core is shared with the server (its warm-cache behavior is
tested there); this pins the proxy side of the fix: the blockfield-deploy
job must not round-trip through the cache service and must wire the
persistent runner directory instead.
"""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

WORKFLOWS = (".github/workflows/deploy.yml",)
PERSISTENT_CACHE_STEP = "BF_TOOL_CACHE=$HOME/.cache/blockfield-tools"


def job_blocks(text):
    """Split a workflow into {job: lines}; only blocks under `jobs:` count."""
    blocks, current, in_jobs = {}, None, False
    for line in text.splitlines():
        if re.match(r"^\S", line):
            in_jobs = line.strip() == "jobs:"
            current = None
            continue
        if not in_jobs:
            continue
        match = re.match(r"^  ([A-Za-z0-9_.-]+):\s*(#.*)?$", line)
        if match:
            current = []
            blocks[match.group(1)] = current
        elif current is not None:
            current.append(line)
    return blocks


class RunnerWorkflows(unittest.TestCase):
    def test_self_hosted_jobs_use_the_persistent_cache(self):
        for relative in WORKFLOWS:
            text = (ROOT / relative).read_text(encoding="utf-8")
            found = False
            for job, lines in job_blocks(text).items():
                block = "\n".join(lines)
                if "runs-on: blockfield-deploy" not in block:
                    continue
                found = True
                with self.subTest(workflow=relative, job=job):
                    self.assertNotIn("actions/cache", block)
                    self.assertIn(PERSISTENT_CACHE_STEP, block)
            self.assertTrue(found, f"{relative}: no blockfield-deploy job")


if __name__ == "__main__":
    unittest.main()
