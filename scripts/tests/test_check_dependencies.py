# Smart Island (2026)
# Copyright Animesh Gupta — github.com/agupta07505
# Licensed under the GNU GPL v3 License

import importlib.util
from pathlib import Path
import unittest


SPEC = importlib.util.spec_from_file_location("check_dependencies", Path(__file__).resolve().parents[1] / "check_dependencies.py")
checker = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(checker)
DEPENDENCY = {"package": {"name": "example:library", "ecosystem": "Maven"}, "version": "1.0"}


class DependencyCheckTest(unittest.TestCase):
    def test_follows_advisory_pagination(self):
        calls = []

        def query(queries):
            calls.append(queries)
            if len(calls) == 1:
                return [{"next_page_token": "page2"}]
            return [{"vulns": [{"id": "GHSA-example"}]}]

        findings = checker.scan([DEPENDENCY], query=query)
        self.assertEqual("page2", calls[1][0]["page_token"])
        self.assertEqual([{**DEPENDENCY, "vulnerability": "GHSA-example"}], findings)

    def test_rejects_partial_or_empty_inventory(self):
        with self.assertRaises(ValueError):
            checker.scan([], query=lambda _: [])
        with self.assertRaises(ValueError):
            checker.scan([DEPENDENCY], query=lambda _: [])

    def test_network_failure_is_not_a_clean_scan(self):
        def failed_query(_):
            raise OSError("connection failed")

        with self.assertRaises(OSError):
            checker.scan([DEPENDENCY], query=failed_query)


if __name__ == "__main__":
    unittest.main()
