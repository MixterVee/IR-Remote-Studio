"""Run IR protocol and Tk UI tests in isolated Python processes.

On Windows, destroyed Tk variables can have __del__ hooks invoked by
the BLE session's event-loop thread during a later unittest, stalling
the mocked BLE test for 25 seconds. Run the mocked session tests before
creating any Tk interpreters, and put UI tests in a separate process.

All test modules still run; no test is skipped or silently retried.
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TEST_DIR = ROOT / "tests"
SESSION_MODULE = "test_sofabaton_u1_session.py"


def main() -> int:
    # Pure mocked-Bluetooth tests run while no Tk widgets have ever existed.
    groups = [
        [sys.executable, "-m", "unittest", "discover",
         "-s", str(TEST_DIR), "-p", SESSION_MODULE, "-v"],
    ]
    # The remaining protocol and GUI tests run as separate Python processes,
    # so the session loop cannot be starved by Tcl variable finalizers.
    all_test_files = sorted(TEST_DIR.glob("test_*.py"))
    remaining = [f for f in all_test_files if f.name != SESSION_MODULE]
    if not (TEST_DIR / SESSION_MODULE).is_file() or not remaining:
        raise RuntimeError("Required test modules are missing.")
    for path in remaining:
        groups.append([
            sys.executable, "-m", "unittest", "discover",
            "-s", str(TEST_DIR), "-p", path.name, "-v",
        ])
    for index, cmd in enumerate(groups, 1):
        print(f"\n=== Test group {index}/{len(groups)}: {cmd[-3]} ===",
              flush=True)
        finished = subprocess.run(cmd, cwd=ROOT, check=False)
        if finished.returncode != 0:
            print(f"ERROR: {cmd[-3]} failed ({finished.returncode}).",
                  flush=True)
            return finished.returncode
    print(f"All {len(groups)} test groups passed.", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
