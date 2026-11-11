#!/usr/bin/env python3
"""Query OSV for a resolved Maven inventory; fail on findings or incomplete queries."""

# Smart Island (2026)
# Copyright Animesh Gupta — github.com/agupta07505
# Licensed under the GNU GPL v3 License

import argparse
import json
from pathlib import Path
import sys
import urllib.request


def query_batch(queries):
    request = urllib.request.Request(
        "https://api.osv.dev/v1/querybatch",
        data=json.dumps({"queries": queries}).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        results = json.load(response)["results"]
    if len(results) != len(queries):
        raise ValueError("OSV returned an incomplete batch")
    return results


def scan(queries, query=query_batch):
    if not queries:
        raise ValueError("Dependency inventory is empty")
    findings = []
    for offset in range(0, len(queries), 100):
        pending = [(item, item) for item in queries[offset:offset + 100]]
        while pending:
            results = query([request for _, request in pending])
            if len(results) != len(pending):
                raise ValueError("OSV returned an incomplete batch")
            next_page = []
            for (original, _), result in zip(pending, results):
                for vulnerability in result.get("vulns", []):
                    findings.append({**original, "vulnerability": vulnerability["id"]})
                if result.get("next_page_token"):
                    next_page.append((original, {**original, "page_token": result["next_page_token"]}))
            pending = next_page
    return findings


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inventory", type=Path)
    parser.add_argument("--report", type=Path, default=Path("build/reports/audit/osv.json"))
    args = parser.parse_args()
    queries = json.loads(args.inventory.read_text(encoding="utf-8"))["queries"]
    findings = scan(queries)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps({"queried": len(queries), "findings": findings}, indent=2), encoding="utf-8")
    print(f"OSV queried {len(queries)} Maven coordinates; findings: {len(findings)}")
    for finding in findings:
        print(f"{finding['package']['name']}:{finding['version']} — {finding['vulnerability']}")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
