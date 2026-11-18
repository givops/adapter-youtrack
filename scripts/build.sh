#!/usr/bin/env bash
set -euo pipefail
repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
sdk_revision=$(cat "$repo_root/sdk-revision.txt")
sdk_checkout=${GIVOPS_SDK_SOURCE:-}
cache=${GIVOPS_MAVEN_REPOSITORY:-"$repo_root/.m2-local"}
[[ "$cache" = /* ]] || cache="$PWD/$cache"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mode=verify
if [[ $# -gt 0 ]]; then
  [[ $# == 1 && "$1" == --package-only ]] || { echo 'Usage: scripts/build.sh [--package-only]' >&2; exit 2; }
  mode=package
fi
if [[ -z "$sdk_checkout" ]]; then
  if [[ ! "$sdk_revision" =~ ^[0-9a-f]{40}$ ]]; then
    echo 'sdk-revision.txt must contain the published SDK commit; use GIVOPS_SDK_SOURCE for coordinated local development.' >&2
    exit 1
  fi
  sdk_checkout="$work/sdk"
  GIT_TERMINAL_PROMPT=0 git clone --quiet https://github.com/givops/adapter-sdk.git "$sdk_checkout"
  git -C "$sdk_checkout" checkout --quiet --detach "$sdk_revision"
  [[ $(git -C "$sdk_checkout" rev-parse HEAD) == "$sdk_revision" ]]
else
  # Copy reviewed overrides so Maven never changes another worktree's outputs.
  mkdir "$work/sdk"
  tar -C "$sdk_checkout" --exclude=.git --exclude=target --exclude=.m2 --exclude=.m2-local --exclude=.m2-build -cf - . | tar -C "$work/sdk" -xf -
  sdk_checkout="$work/sdk"
fi
# Both projects use public dependencies. No private host checkout is read.
if [[ "$mode" == package ]]; then
  # A release build packages previously reviewed source; all verification is local.
  mvn -B --no-transfer-progress -Dmaven.repo.local="$cache" -Dmaven.test.skip=true -f "$sdk_checkout/pom.xml" clean install
  mvn -B --no-transfer-progress -Dmaven.repo.local="$cache" -Dmaven.test.skip=true -f "$repo_root/pom.xml" clean package
else
  mvn -B --no-transfer-progress -Dmaven.repo.local="$cache" -f "$sdk_checkout/pom.xml" clean install
  mvn -B --no-transfer-progress -Dmaven.repo.local="$cache" -f "$repo_root/pom.xml" clean verify
fi
