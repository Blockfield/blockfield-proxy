"""Public quality commands must run the JVM checks they advertise."""

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import quality  # noqa: E402


class JavaGatesTest(unittest.TestCase):
    def test_explicit_test_and_lint_commands_use_the_native_gradle_wrapper(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            wrapper = root / "plugin with spaces" / "gradlew"
            wrapper.parent.mkdir()
            wrapper.touch()
            files = [str(wrapper.relative_to(root))]
            with (
                patch.object(quality, "ROOT", root),
                patch.object(quality, "sources", return_value=files),
                patch.object(quality, "run") as run,
            ):
                for command, task in (
                    (quality.tests, "test"),
                    (lambda: quality.lint(files), "lintJava"),
                ):
                    with self.subTest(task=task):
                        run.reset_mock()
                        command()
                        run.assert_any_call(
                            quality.gradle_command(wrapper),
                            task,
                            "--no-daemon",
                            "--max-workers=2",
                            "-Dorg.gradle.jvmargs=-Xmx2G",
                            cwd=wrapper.parent,
                        )

    def test_windows_uses_the_batch_wrapper(self):
        self.assertEqual(
            quality.gradle_command(Path("plugin/gradlew"), windows=True),
            Path("plugin/gradlew.bat"),
        )


class PreCommitHook(unittest.TestCase):
    def test_hook_formats_staged_files_without_gradle(self):
        hook = quality.ROOT / ".githooks" / "pre-commit"
        self.assertTrue(hook.is_file())
        self.assertTrue(hook.stat().st_mode & 0o111)
        text = hook.read_text(encoding="utf-8")
        self.assertTrue(text.startswith("#!/bin/sh"))
        self.assertIn("just format-staged", text)
        commands = [
            line for line in text.splitlines() if line and not line.startswith("#")
        ]
        self.assertFalse(any("gradle" in line.lower() for line in commands))

    def test_wire_hooks_leaves_explicit_choice_alone(self):
        with tempfile.TemporaryDirectory(prefix="bf hooks ") as tmp:
            root = Path(tmp)
            subprocess.run(["git", "init", "--quiet", tmp], check=True)
            (root / ".githooks").mkdir()
            subprocess.run(
                ["git", "config", "core.hooksPath", ".custom"],
                cwd=root,
                check=True,
            )
            self.assertFalse(quality.wire_hooks(root))

    def test_commit_formats_staged_files(self):
        # Fail-before: without the hook + format-staged, the raw bytes commit.
        with tempfile.TemporaryDirectory(prefix="bf precommit ") as tmp:
            root = Path(tmp)
            for name in (
                ".gitignore",
                "justfile",
                "scripts/quality.py",
                "scripts/toolchain.py",
            ):
                src = quality.ROOT / name
                dst = root / name
                dst.parent.mkdir(parents=True, exist_ok=True)
                dst.write_bytes(src.read_bytes())
            (root / ".githooks").mkdir()
            (root / ".githooks" / "pre-commit").write_bytes(
                (quality.ROOT / ".githooks" / "pre-commit").read_bytes()
            )
            (root / ".githooks" / "pre-commit").chmod(0o755)

            def git(*args):
                return subprocess.run(
                    ["git", *args],
                    cwd=root,
                    check=True,
                    capture_output=True,
                    text=True,
                    timeout=120,
                ).stdout

            git("init", "--quiet")
            git("config", "user.email", "hook-test@example.com")
            git("config", "user.name", "hook-test")
            git("config", "core.hooksPath", str(root / ".githooks"))
            git("add", ".")
            git("commit", "--quiet", "-m", "fixture")
            (root / "probe.py").write_text("x=1\n", encoding="utf-8")
            (root / "probe.sh").write_text(
                "if [ 1 = 1 ] ; then echo hi ; fi\n", encoding="utf-8"
            )
            git("add", "probe.py", "probe.sh")
            git("commit", "--quiet", "-m", "probe")
            formatted_py = git("show", "HEAD:probe.py")
            formatted_sh = git("show", "HEAD:probe.sh")
            self.assertNotEqual(formatted_py, "x=1\n")
            self.assertNotEqual(formatted_sh, "if [ 1 = 1 ] ; then echo hi ; fi\n")
            self.assertEqual(
                subprocess.run(
                    ["just", "format-check"],
                    cwd=root,
                    capture_output=True,
                    text=True,
                    timeout=300,
                ).returncode,
                0,
            )


if __name__ == "__main__":
    unittest.main()
