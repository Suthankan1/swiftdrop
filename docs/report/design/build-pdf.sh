#!/usr/bin/env bash
# Builds the design documentation PDF from design.html with headless Chrome.
# Output goes to build/reports/design/ (git-ignored); docs/submission/build.sh copies it into docs/submission/.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${HERE}/../../.." && pwd)"
OUT_DIR="${ROOT}/build/reports/design"
OUT="${OUT_DIR}/SwiftDrop-Design-Documentation.pdf"
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
if [ ! -x "${CHROME}" ]; then
  for candidate in google-chrome chromium chromium-browser; do
    if command -v "${candidate}" >/dev/null 2>&1; then CHROME="$(command -v "${candidate}")"; break; fi
  done
fi
[ -x "${CHROME}" ] || { echo "Chrome or Chromium is required (set CHROME=/path/to/chrome)." >&2; exit 1; }
mkdir -p "${OUT_DIR}"
"${CHROME}" --headless=new --disable-gpu --allow-file-access-from-files --no-pdf-header-footer \
  --print-to-pdf="${OUT}" "file://${HERE}/design.html" >/dev/null 2>&1
echo "Wrote ${OUT}"
