"""Check the actual packaged backup policy without executing application code."""

# Smart Island (2026)
# © Animesh Gupta — github.com/agupta07505
# Licensed under the GNU GPL v3 License

from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main"
ANDROID = "{http://schemas.android.com/apk/res/android}"


class BackupRulesTest(unittest.TestCase):
    def setUp(self):
        self.application = ET.parse(MAIN / "AndroidManifest.xml").getroot().find("application")

    def policy(self, attribute):
        reference = self.application.get(ANDROID + attribute, "")
        self.assertRegex(reference, r"^@xml/[a-z_]+$")
        return ET.parse(MAIN / "res/xml" / (reference.removeprefix("@xml/") + ".xml")).getroot()

    def assert_sensitive_data_excluded(self, policy):
        excluded = {(item.get("domain"), item.get("path")) for item in policy.findall("exclude")}
        # Whole domains cover the history DB's journal/WAL and location caches too.
        self.assertIn(("database", "."), excluded)
        self.assertIn(("sharedpref", "."), excluded)
        self.assertIn(("file", "datastore/"), excluded)
        recorder = (MAIN / "java/com/agupta07505/smartisland/util/AppLogRecorder.kt").read_text()
        log_name = re.search(r'LOG_FILE_NAME\s*=\s*"([^"\n]+)"', recorder)
        self.assertIsNotNone(log_name)
        self.assertIn(("file", log_name.group(1)), excluded)
        self.assertFalse(policy.findall("include"), "Additional include paths need a separate privacy review")

    def test_legacy_backup_excludes_history_settings_location_and_logs(self):
        policy = self.policy("fullBackupContent")
        self.assertEqual("full-backup-content", policy.tag)
        self.assert_sensitive_data_excluded(policy)

    def test_android12_cloud_backup_excludes_sensitive_data(self):
        rules = self.policy("dataExtractionRules")
        self.assertEqual("data-extraction-rules", rules.tag)
        policy = rules.find("cloud-backup")
        self.assertIsNotNone(policy)
        self.assert_sensitive_data_excluded(policy)

    def test_android12_device_transfer_excludes_sensitive_data(self):
        policy = self.policy("dataExtractionRules").find("device-transfer")
        self.assertIsNotNone(policy)
        self.assert_sensitive_data_excluded(policy)


if __name__ == "__main__":
    unittest.main()
