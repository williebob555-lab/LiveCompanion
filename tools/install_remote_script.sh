#!/usr/bin/env bash
# Installs the LiveCompanion remote script into a Wine prefix's Live User Library.
# Usage: WINEPREFIX=~/.wine tools/install_remote_script.sh [windows-user-name]
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
src="$here/remote_script/LiveCompanion"
prefix="${WINEPREFIX:-$HOME/.wine}"
user="${1:-$USER}"
docs="$prefix/drive_c/users/$user/Documents"
[ -d "$docs" ] || docs="$(ls -d "$prefix"/drive_c/users/*/Documents 2>/dev/null | grep -v Public | head -n1)"
[ -n "$docs" ] && [ -d "$docs" ] || { echo "Documents folder not found under $prefix" >&2; exit 1; }
dst="$docs/Ableton/User Library/Remote Scripts/LiveCompanion"
rm -rf "$dst"
mkdir -p "$dst"
cp -r "$src/." "$dst/"
find "$dst" -name __pycache__ -type d -prune -exec rm -rf {} +
echo "Installed to $dst"
