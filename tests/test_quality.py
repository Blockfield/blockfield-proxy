"""Public quality commands must run the JVM checks they advertise."""

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


if __name__ == "__main__":
    unittest.main()
