"""Tag-push jobs must survive a skipped bot-gate.

GitHub applies a skip to the whole downstream dependency chain from the
point of the skip onwards (docs: jobs.<job_id>.needs), unless a downstream
job's own `if` uses `always()`. v2.63.0 shipped no Release because `build`
gained `needs: [bot-gate, ...]` (bot-gate skips on every push) while
`release` kept a bare `if: github.ref_type == 'tag'`.
"""

import re
import unittest
from pathlib import Path

WORKFLOW = Path(__file__).resolve().parents[1] / ".github" / "workflows" / "build.yml"


def parse_jobs(text):
    """Job name -> {"needs": [...], "if": "..."} (stdlib only, no PyYAML)."""
    jobs, name, key = {}, None, None
    for line in text.splitlines():
        header = re.match(r"^  (\S+):\s*$", line)
        if header:
            name, key = header.group(1), None
            jobs[name] = {"needs": [], "if": ""}
            continue
        if name is None:
            continue
        field = re.match(r"^    (needs|if):\s*(.*)$", line)
        if field:
            key = field.group(1)
            value = field.group(2).strip()
            if key == "needs":
                value = value.strip("[]")
                jobs[name]["needs"] = [
                    part.strip().strip("'\"")
                    for part in value.split(",")
                    if part.strip()
                ]
            else:
                jobs[name]["if"] = value
        elif key == "if" and re.match(r"^      \S", line):
            jobs[name]["if"] += " " + line.strip()
    return jobs


def transitive_needs(jobs, name, seen=None):
    seen = seen or set()
    for need in jobs[name]["needs"]:
        if need not in seen and need in jobs:
            seen.add(need)
            transitive_needs(jobs, need, seen)
    return seen


class ReleaseGateTest(unittest.TestCase):
    def test_release_runs_on_tags_when_build_succeeds(self):
        jobs = parse_jobs(WORKFLOW.read_text(encoding="utf-8"))
        condition = jobs["release"]["if"]
        self.assertIn("always()", condition)
        self.assertIn("needs.build.result == 'success'", condition)
        self.assertIn("github.ref_type == 'tag'", condition)

    def test_every_job_with_needs_survives_a_skipped_transitive_need(self):
        jobs = parse_jobs(WORKFLOW.read_text(encoding="utf-8"))
        for name, job in jobs.items():
            if not job["needs"]:
                continue
            with self.subTest(job=name):
                if "always()" in job["if"]:
                    continue
                skippable = sorted(
                    upstream
                    for upstream in transitive_needs(jobs, name)
                    if jobs[upstream]["if"]
                )
                self.assertEqual(skippable, [])


if __name__ == "__main__":
    unittest.main()
