"""Repo-only tool layer next to the vendored core.

scripts/toolchain.py + scripts/tools.lock.json are byte-identical copies of
the workspace core; the core imports this module for repo-specific pieces.
CI installs Temurin 21 via setup-java, so java_home() resolves the ambient
JDK instead of downloading one. The Debian/workspace helpers the core also
imports are workspace-only and raise here.
"""

import os
import shutil
from pathlib import Path

# Workspace-only names the core imports alongside java_home; this repo has
# no managed JDK, boat CLI or Debian helpers, so they stay unreachable stubs.
JAVA_VERSION = None
JAVA_ASSETS = {}


def check_java(*args, **kwargs):
    raise RuntimeError("check_java is workspace-only; use the ambient JDK")


def deb_payload(*args, **kwargs):
    raise RuntimeError("deb_payload is workspace-only")


def deb_libraries(*args, **kwargs):
    raise RuntimeError("deb_libraries is workspace-only")


def local_tool(name):
    raise RuntimeError(f"Unknown tool {name!r}: not pinned in scripts/tools.lock.json")


def java_home():
    """Ambient JDK (setup-java Temurin 21 on CI, host JDK locally)."""
    home = os.environ.get("JAVA_HOME", "")
    if home and (Path(home) / "bin").is_dir():
        return Path(home)
    java = shutil.which("java")
    if java is not None:
        return Path(java).resolve().parents[1]
    raise RuntimeError(
        "Missing native java; install the repository's documented SDK prerequisites"
    )
