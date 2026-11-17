#!/usr/bin/env bash
# Concern: checks annotations and comment budgets | Non-concern: application tests | IO: (repository) -> verdict
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
for tool in annotated-tree comment-crusher; do
  if ! command -v "$tool" >/dev/null; then
    echo "Missing $tool; see docs/quality.md for installation." >&2
    exit 1
  fi
done
annotated-tree --strict-check . --hidden --include-tests --max-length 160 --ignore platform_android/gradlew --ignore platform_android/gradlew.bat
comment-crusher --warnings-as-errors .
