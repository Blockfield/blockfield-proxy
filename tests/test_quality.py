"""Public quality commands must run the JVM checks they advertise."""

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import quality

HQ_MOD = quality
HQ_ROOT = quality.ROOT  # noqa: E402


def shared_tool_cache():
    """The fixture reuses the parent checkout's warm tool cache (the core reads
    BF_TOOL_CACHE), so it never downloads into its own empty tree."""
    return patch.dict(
        os.environ, {"BF_TOOL_CACHE": str(HQ_ROOT / ".cache" / "quality")}
    )


def dead_proxy_env():
    """Env for the hermetic hook test: the shared cache serves every pinned
    tool, and any download attempt fails fast instead of stalling on egress."""
    offline = dict(os.environ)
    for var in (
        "HTTP_PROXY",
        "HTTPS_PROXY",
        "ALL_PROXY",
        "http_proxy",
        "https_proxy",
        "all_proxy",
        "NO_PROXY",
        "no_proxy",
    ):
        offline.pop(var, None)
    offline.update(
        {
            "BF_TOOL_CACHE": str(HQ_ROOT / ".cache" / "quality"),
            "HTTP_PROXY": "http://127.0.0.1:9",
            "HTTPS_PROXY": "http://127.0.0.1:9",
            "ALL_PROXY": "http://127.0.0.1:9",
            "http_proxy": "http://127.0.0.1:9",
            "https_proxy": "http://127.0.0.1:9",
            "all_proxy": "http://127.0.0.1:9",
        }
    )
    return offline


FIXTURE_SCRIPTS = (
    ".gitignore",
    "justfile",
    "scripts/quality.py",
    "scripts/toolchain.py",
    "scripts/toolchain_local.py",
    "scripts/tools.lock.json",
    "scripts/terminal.py",
    "scripts/dev_cache.py",
)


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
                # The fixture root has no workflows; parity has its own tests.
                patch.object(quality, "cache_key_parity"),
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
        mode = subprocess.run(
            ["git", "ls-files", "-s", ".githooks/pre-commit"],
            cwd=hook.parent.parent,
            check=True,
            capture_output=True,
            text=True,
        ).stdout
        self.assertTrue(mode.startswith("100755"), mode)
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
        with (
            shared_tool_cache(),
            tempfile.TemporaryDirectory(prefix="bf precommit ") as tmp,
        ):
            root = Path(tmp)
            for name in FIXTURE_SCRIPTS:
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

    def test_wire_hooks_reports_already_wired_as_success(self):
        with tempfile.TemporaryDirectory(prefix="bf hooks ") as tmp:
            root = Path(tmp)
            subprocess.run(["git", "init", "--quiet", tmp], check=True)
            (root / ".githooks").mkdir()
            subprocess.run(
                ["git", "config", "core.hooksPath", ".githooks"],
                cwd=root,
                check=True,
            )
            self.assertTrue(HQ_MOD.wire_hooks(root))

    def test_wire_hooks_leaves_outer_repo_alone_on_invalid_gitdir(self):
        with tempfile.TemporaryDirectory(prefix="bf hooks ") as tmp:
            outer = Path(tmp)
            subprocess.run(["git", "init", "--quiet", tmp], check=True)
            subprocess.run(
                ["git", "config", "core.hooksPath", "/outer/hooks"],
                cwd=outer,
                check=True,
            )
            sub = outer / "sub"
            sub.mkdir()
            (sub / ".git").mkdir()  # invalid git dir: git would resolve upward
            (sub / ".githooks").mkdir()
            self.assertFalse(HQ_MOD.wire_hooks(sub))
            probe = subprocess.run(
                ["git", "config", "--local", "--get", "core.hooksPath"],
                cwd=outer,
                check=True,
                capture_output=True,
                text=True,
            )
            self.assertEqual(probe.stdout.strip(), "/outer/hooks")

    def test_wire_hooks_keeps_global_hooks_path(self):
        with tempfile.TemporaryDirectory(prefix="bf hooks ") as tmp:
            root = Path(tmp) / "repo"
            subprocess.run(["git", "init", "--quiet", str(root)], check=True)
            (root / ".githooks").mkdir()
            gitconfig = Path(tmp) / "gitconfig"
            gitconfig.write_text("[core]\n\thooksPath = /global/hooks\n")
            with patch.dict(os.environ, {"GIT_CONFIG_GLOBAL": str(gitconfig)}):
                self.assertFalse(quality.wire_hooks(root))
            local = subprocess.run(
                ["git", "config", "--local", "--get", "core.hooksPath"],
                cwd=root,
                capture_output=True,
                text=True,
            )
            self.assertEqual(local.stdout, "")

    def test_wire_hooks_keeps_active_hooks(self):
        with tempfile.TemporaryDirectory(prefix="bf hooks ") as tmp:
            root = Path(tmp)
            subprocess.run(["git", "init", "--quiet", tmp], check=True)
            (root / ".githooks").mkdir()
            (root / ".git" / "hooks" / "pre-commit").write_text("#!/bin/sh\n")
            self.assertFalse(quality.wire_hooks(root))
            local = subprocess.run(
                ["git", "config", "--local", "--get", "core.hooksPath"],
                cwd=root,
                capture_output=True,
                text=True,
            )
            self.assertEqual(local.stdout, "")

    def test_commit_formats_staged_files_without_network(self):
        # The hook fixture must not download tools: CI runners hit transient
        # egress stalls and the commit fails with the hook stderr swallowed.
        offline = dead_proxy_env()
        with tempfile.TemporaryDirectory(prefix="bf precommit ") as tmp:
            root = Path(tmp)
            for name in FIXTURE_SCRIPTS:
                src = HQ_ROOT / name
                dst = root / name
                dst.parent.mkdir(parents=True, exist_ok=True)
                dst.write_bytes(src.read_bytes())
            (root / ".githooks").mkdir()
            (root / ".githooks" / "pre-commit").write_bytes(
                (HQ_ROOT / ".githooks" / "pre-commit").read_bytes()
            )
            (root / ".githooks" / "pre-commit").chmod(0o755)

            def git(*args):
                try:
                    return subprocess.run(
                        ["git", *args],
                        cwd=root,
                        check=True,
                        capture_output=True,
                        text=True,
                        timeout=120,
                        env=offline,
                    ).stdout
                except subprocess.CalledProcessError as error:
                    raise AssertionError(
                        f"git {' '.join(args)} failed:\n"
                        f"STDOUT:\n{error.stdout}\nSTDERR:\n{error.stderr}"
                    ) from error

            git("init", "--quiet")
            git("config", "user.email", "hook-test@example.com")
            git("config", "user.name", "hook-test")
            git("config", "core.hooksPath", str(root / ".githooks"))
            git("add", ".")
            git("commit", "--quiet", "-m", "fixture")
            (root / "probe.py").write_text("x=1\n", encoding="utf-8")
            git("add", "probe.py")
            git("commit", "--quiet", "-m", "probe")
            self.assertNotEqual(git("show", "HEAD:probe.py"), "x=1\n")

    def test_commit_leaves_partially_staged_files_alone(self):
        # Fail-before: the old hook formatted the worktree copy and staged it
        # whole, committing hunks the user never staged.
        # Same shared tool cache as above: no downloads into the fixture tree.
        with (
            shared_tool_cache(),
            tempfile.TemporaryDirectory(prefix="bf precommit ") as tmp,
        ):
            root = Path(tmp)
            for name in FIXTURE_SCRIPTS:
                src = HQ_ROOT / name
                dst = root / name
                dst.parent.mkdir(parents=True, exist_ok=True)
                dst.write_bytes(src.read_bytes())
            (root / ".githooks").mkdir()
            (root / ".githooks" / "pre-commit").write_bytes(
                (HQ_ROOT / ".githooks" / "pre-commit").read_bytes()
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
            git("add", "probe.py")
            (root / "probe.py").write_text(
                "x=1\n# scratch, never staged\n", encoding="utf-8"
            )
            git("commit", "--quiet", "-m", "probe")
            self.assertEqual(git("show", "HEAD:probe.py"), "x=1\n")
            self.assertEqual(
                (root / "probe.py").read_text(encoding="utf-8"),
                "x=1\n# scratch, never staged\n",
            )


class WireHooksStdout(unittest.TestCase):
    def test_wiring_notice_stays_off_stdout(self):
        # CI captures `quality.py run ... ` stdout into $GITHUB_OUTPUT; a notice there broke it.
        import contextlib
        import io

        with tempfile.TemporaryDirectory(prefix="bf hooks ") as tmp:
            subprocess.run(["git", "init", "--quiet", tmp], check=True)
            (Path(tmp) / ".githooks").mkdir()
            out = io.StringIO()
            with (
                contextlib.redirect_stdout(out),
                contextlib.redirect_stderr(io.StringIO()),
            ):
                self.assertTrue(quality.wire_hooks(Path(tmp)))
        self.assertEqual(out.getvalue(), "")


if __name__ == "__main__":
    unittest.main()


class WireHooksStrict(unittest.TestCase):
    def test_setup_fails_when_the_hooks_path_cannot_be_written(self):
        # pre-push runs the secret scan: `just setup` must not pass without it.
        import contextlib
        import io

        for module in (quality,):
            with tempfile.TemporaryDirectory(prefix="bf hooks ") as tmp:
                root = Path(tmp)
                subprocess.run(["git", "init", "--quiet", tmp], check=True)
                (root / ".githooks").mkdir()
                # Left behind by an interrupted git: config writes fail.
                (root / ".git" / "config.lock").write_text("")
                with contextlib.redirect_stderr(io.StringIO()):
                    self.assertFalse(module.wire_hooks(root))
                    with self.assertRaises(SystemExit):
                        module.wire_hooks(root, strict=True)

    def test_non_utf8_hooks_path_only_warns(self):
        import contextlib
        import io

        for module in (quality,):
            with tempfile.TemporaryDirectory(prefix="bf hooks ") as tmp:
                root = Path(tmp)
                subprocess.run(["git", "init", "--quiet", tmp], check=True)
                (root / ".githooks").mkdir()
                with (root / ".git" / "config").open("ab") as config:
                    config.write(b"[core]\n\thooksPath = /hooks\xff\n")
                with contextlib.redirect_stderr(io.StringIO()):
                    self.assertFalse(module.wire_hooks(root))

    def test_setup_fails_when_git_config_cannot_be_read(self):
        import contextlib
        import io

        for module in (quality,):
            with tempfile.TemporaryDirectory(prefix="bf hooks ") as tmp:
                root = Path(tmp)
                subprocess.run(["git", "init", "--quiet", tmp], check=True)
                (root / ".githooks").mkdir()
                # An unreadable hooks dir: wiring state unknown, so setup must stop.
                with (
                    patch.object(Path, "iterdir", side_effect=PermissionError),
                    contextlib.redirect_stderr(io.StringIO()),
                ):
                    self.assertFalse(module.wire_hooks(root))
                    with self.assertRaises(SystemExit):
                        module.wire_hooks(root, strict=True)


class CacheKeyParityTest(unittest.TestCase):
    def test_repo_workflows_agree(self):
        quality.cache_key_parity()

    def test_drifted_key_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            workflows = root / ".github" / "workflows"
            workflows.mkdir(parents=True)
            source = quality.ROOT / ".github" / "workflows"
            (workflows / "build.yml").write_text((source / "build.yml").read_text())
            warm = (source / "warm-caches.yml").read_text()
            self.assertIn("key: quality-tools-v1", warm)
            (workflows / "warm-caches.yml").write_text(
                warm.replace("key: quality-tools-v1", "key: quality-tools-v2", 1)
            )
            with patch.object(quality, "ROOT", root):
                with self.assertRaisesRegex(SystemExit, "quality-tools"):
                    quality.cache_key_parity()
