#!/usr/bin/env bash
set -euo pipefail
[[ $# == 0 ]] || { echo 'Usage: scripts/verify-local.sh' >&2; exit 2; }
verification_log=$(mktemp)
trap 'rm -f "$verification_log"' EXIT
run_verification() {
  repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
  "$repo_root/scripts/build.sh"
  git -C "$repo_root" diff --check
}
run_verification "$@" 2>&1 | tee "$verification_log"
if grep -Eq 'Tests (are )?skipped|Tests run:.*Skipped: [1-9][0-9]*|OK \(skipped=[1-9]' "$verification_log"; then
  echo 'Local verification requires every test to run; skipped tests were reported.' >&2
  exit 1
fi
