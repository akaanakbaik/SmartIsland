# Smart Island (2026)
# Copyright Animesh Gupta — github.com/agupta07505
# Licensed under the GNU GPL v3 License

import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "release_notes.py"
SPEC = importlib.util.spec_from_file_location("release_notes", SCRIPT)
release_notes = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release_notes)


class ReleaseNotesTest(unittest.TestCase):
    def test_preserves_untrusted_changelog_verbatim(self):
        # These strings are data only. No shell is invoked by these tests.
        titles = [
            "feat: literal $(touch sentinel)",
            "fix: literal `touch sentinel`",
            "EOF\n$(touch sentinel)\nEOF",
            'quotes: "double" and \'single\' and $GH_TOKEN',
            "Unicode: 日本語 · español 🏝️\nsecond line",
        ]
        for title in titles:
            with self.subTest(title=title):
                notes = release_notes.render_release_notes(
                    "v7.6.0", "SmartIsland-v7.6.0.apk", "owner/repo", title
                )
                self.assertIn("\n\n" + title + "\n\n## Download and install", notes)
                self.assertIn("https://github.com/owner/repo/releases/download/v7.6.0/SmartIsland-v7.6.0.apk", notes)

    def test_cli_writes_utf8_data_without_shell_or_side_effects(self):
        changelog = "EOF\n$(touch sentinel)\n`touch sentinel`\n日本語"
        with tempfile.TemporaryDirectory() as directory:
            env = dict(os.environ, VERSION_TAG="v7.6.0", APK_NAME="SmartIsland-v7.6.0.apk",
                       GH_REPO="owner/repo", RELEASE_CHANGELOG=changelog)
            subprocess.run([sys.executable, str(SCRIPT)], cwd=directory, env=env, check=True)
            output = Path(directory) / "release_notes.md"
            self.assertIn(changelog, output.read_text(encoding="utf-8"))
            self.assertEqual(["release_notes.md"], sorted(p.name for p in Path(directory).iterdir()))

    def test_missing_input_fails_without_creating_notes(self):
        with tempfile.TemporaryDirectory() as directory:
            env = {key: value for key, value in os.environ.items()
                   if key not in {"VERSION_TAG", "APK_NAME", "GH_REPO", "RELEASE_CHANGELOG"}}
            result = subprocess.run([sys.executable, str(SCRIPT)], cwd=directory, env=env,
                                    capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertFalse((Path(directory) / "release_notes.md").exists())


if __name__ == "__main__":
    unittest.main()
