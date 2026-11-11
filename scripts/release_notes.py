#!/usr/bin/env python3
"""Render release notes without interpreting changelog text as shell source."""

# Smart Island (2026)
# Copyright Animesh Gupta — github.com/agupta07505
# Licensed under the GNU GPL v3 License

import os
from pathlib import Path


def render_release_notes(version_tag: str, apk_name: str, repository: str, changelog: str) -> str:
    base = f"https://github.com/{repository}"
    return f"""> **Smart Island {version_tag}** brings a lightweight, privacy-conscious Dynamic Island experience to Android with glanceable notifications, calls, media controls, battery status, and quick app shortcuts.

{changelog}

## Download and install

Download the signed Android APK: **[{apk_name}]({base}/releases/download/{version_tag}/{apk_name})**

Smart Island supports **Android 8.0 and newer**.

> Android may ask you to allow installation from your browser or file manager. After installation, Smart Island guides you through the overlay and notification permissions it needs.

## Project links

[Full changelog]({base}/blob/main/CHANGELOG.md) · [Features and setup]({base}#readme) · [Privacy]({base}/blob/main/PRIVACY.md) · [Report a bug]({base}/issues/new?template=bug_report.md) · [Request a feature]({base}/issues/new?template=feature_request.md)

---
"""


def main() -> None:
    notes = render_release_notes(
        os.environ["VERSION_TAG"],
        os.environ["APK_NAME"],
        os.environ["GH_REPO"],
        os.environ["RELEASE_CHANGELOG"],
    )
    Path("release_notes.md").write_text(notes, encoding="utf-8")


if __name__ == "__main__":
    main()
