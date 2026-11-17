#!/usr/bin/env bash
# Portable entry point when `make` is unavailable (e.g. MinGit on Windows).
# Equivalent to `make aar` followed by `make check`.
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
bash "$DIR/build.sh" && bash "$DIR/check.sh"
