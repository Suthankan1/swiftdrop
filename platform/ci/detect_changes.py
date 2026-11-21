#!/usr/bin/env python3
"""
SwiftDrop CI/CD Change Impact Detection Utility
Evaluates git diff in pull requests against the target base branch to determine
whether a given subsystem needs to execute expensive builds, tests, and security scans.
Always fails open (changed=true) for non-pull_request events (push, dispatch) or on error.
"""

import argparse
import fnmatch
import os
import subprocess
import sys
from typing import List


def match_path(file_path: str, patterns: List[str]) -> bool:
    """Matches a file path against wildcard patterns or directory prefixes."""
    norm_path = file_path.replace("\\", "/").strip()
    for pat in patterns:
        norm_pat = pat.replace("\\", "/").strip()
        if norm_pat.endswith("/**"):
            prefix = norm_pat[:-3]
            if norm_path == prefix or norm_path.startswith(prefix + "/"):
                return True
        elif fnmatch.fnmatch(norm_path, norm_pat):
            return True
    return False


def set_github_output(name: str, value: str) -> None:
    """Writes key=value pair to GITHUB_OUTPUT environment file if available."""
    github_output = os.environ.get("GITHUB_OUTPUT")
    if github_output:
        try:
            with open(github_output, "a", encoding="utf-8") as f:
                f.write(f"{name}={value}\n")
        except OSError as e:
            print(f"Warning: Could not write to GITHUB_OUTPUT: {e}", file=sys.stderr)
    print(f"[CHANGE-DETECTOR] Output set: {name}={value}")


def get_changed_files(base_ref: str) -> List[str]:
    """Fetches base_ref if needed and returns the list of files modified in HEAD vs base_ref."""
    ref_name = base_ref.removeprefix("origin/").removeprefix("refs/heads/")
    target_ref = f"origin/{ref_name}"

    # Try shallow fetch of target branch from origin
    try:
        subprocess.run(
            ["git", "fetch", "origin", f"{ref_name}:{target_ref}", "--depth=50"],
            capture_output=True,
            check=False,
            timeout=30,
        )
    except (subprocess.SubprocessError, OSError) as e:
        print(f"Warning: git fetch encountered an error: {e}", file=sys.stderr)

    # Run git diff against base reference
    try:
        proc = subprocess.run(
            ["git", "diff", "--name-only", f"{target_ref}...HEAD"],
            capture_output=True,
            text=True,
            check=False,
            timeout=20,
        )
        if proc.returncode != 0:
            # Fallback to direct two-dot diff
            proc = subprocess.run(
                ["git", "diff", "--name-only", target_ref, "HEAD"],
                capture_output=True,
                text=True,
                check=False,
                timeout=20,
            )
        if proc.returncode == 0:
            return [line.strip() for line in proc.stdout.splitlines() if line.strip()]
        else:
            print(f"Warning: git diff returned non-zero exit code {proc.returncode}: {proc.stderr}", file=sys.stderr)
            return []
    except (subprocess.SubprocessError, OSError) as e:
        print(f"Warning: git diff execution failed: {e}", file=sys.stderr)
        return []


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate file impact for selective CI")
    parser.add_argument("--event", default=os.environ.get("GITHUB_EVENT_NAME", "push"))
    parser.add_argument("--base-ref", default=os.environ.get("GITHUB_BASE_REF", "main"))
    parser.add_argument("--patterns", nargs="+", required=True, help="Glob patterns of files triggering this subsystem")
    parser.add_argument("--output-name", default="changed", help="GITHUB_OUTPUT variable name (default: changed)")
    args = parser.parse_args()

    # Invariant: Full verification must run on push to release branches or workflow_dispatch
    if args.event != "pull_request":
        print(f"[CHANGE-DETECTOR] Event '{args.event}' is not 'pull_request'. Running all checks unconditionally.")
        set_github_output(args.output_name, "true")
        return 0

    base_ref = args.base_ref or "main"
    print(f"[CHANGE-DETECTOR] Analyzing pull request changes against base reference '{base_ref}'...")
    changed_files = get_changed_files(base_ref)

    if not changed_files:
        print("[CHANGE-DETECTOR] Warning: Could not detect changed files or diff was empty. Failing open (changed=true).")
        set_github_output(args.output_name, "true")
        return 0

    print(f"[CHANGE-DETECTOR] Detected {len(changed_files)} changed file(s) in this pull request.")
    for f in changed_files[:15]:
        print(f"  - {f}")
    if len(changed_files) > 15:
        print(f"  ... and {len(changed_files) - 15} additional files")

    # Global files that mandate full re-evaluation across all subsystems
    global_triggers = [
        ".github/workflows/ci.yml",
        "platform/ci/detect_changes.py",
    ]
    all_patterns = list(args.patterns) + global_triggers

    matched_files = []
    for f in changed_files:
        if match_path(f, all_patterns):
            matched_files.append(f)

    if matched_files:
        print(f"[CHANGE-DETECTOR] Subsystem is CHANGED. Found {len(matched_files)} matching file(s):")
        for m in matched_files[:5]:
            print(f"  * {m}")
        set_github_output(args.output_name, "true")
    else:
        print(f"[CHANGE-DETECTOR] Subsystem is UNCHANGED. None of the {len(changed_files)} file(s) matched patterns:")
        for p in args.patterns:
            print(f"  pattern: {p}")
        set_github_output(args.output_name, "false")

    return 0


if __name__ == "__main__":
    sys.exit(main())
