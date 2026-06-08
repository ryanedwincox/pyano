#!/usr/bin/env bash
# Run the full quality gate: lint, type-check, tests.
# Usage: ./run-tests.sh   (run from platform_linux/, ideally inside the venv)
set -euo pipefail

cd "$(dirname "$0")"

echo "== ruff =="
ruff check .

echo "== mypy =="
mypy pyano.py

echo "== pytest =="
pytest -q

echo "All checks passed."
