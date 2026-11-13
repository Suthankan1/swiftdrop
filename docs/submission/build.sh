#!/usr/bin/env bash
# Builds the submission documents in docs/submission/ from their Markdown/HTML sources.
# Needs pandoc (3.x) and Google Chrome or Chromium. Run from anywhere:  docs/submission/build.sh
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${HERE}/../.." && pwd)"
SRC="${HERE}/source"
PANDOC="${PANDOC:-$(command -v pandoc || echo /opt/anaconda3/bin/pandoc)}"
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
if [ ! -x "${CHROME}" ]; then
  for candidate in google-chrome chromium chromium-browser; do
    if command -v "${candidate}" >/dev/null 2>&1; then CHROME="$(command -v "${candidate}")"; break; fi
  done
fi
[ -x "${PANDOC}" ] || { echo "pandoc is required (set PANDOC=/path/to/pandoc)." >&2; exit 1; }
[ -x "${CHROME}" ] || { echo "Chrome or Chromium is required (set CHROME=/path/to/chrome)." >&2; exit 1; }
TMP="$(mktemp -d)"; trap 'rm -rf "${TMP}"' EXIT

print_pdf() { # html pdf
  "${CHROME}" --headless=new --disable-gpu --allow-file-access-from-files --no-pdf-header-footer \
    --print-to-pdf="$2" "file://$1" >/dev/null 2>&1
  echo "Wrote $2"
}

# Final report: PDF (via HTML) and Word.
(cd "${SRC}" && "${PANDOC}" final-report.md -s --lua-filter=indexes.lua --number-sections --embed-resources \
  --css report.css --resource-path=. -o "${TMP}/final-report.html")
print_pdf "${TMP}/final-report.html" "${HERE}/SwiftDrop-Final-Report.pdf"
(cd "${SRC}" && "${PANDOC}" final-report.md --lua-filter=indexes.lua --number-sections --resource-path=. \
  -o "${HERE}/SwiftDrop-Final-Report.docx")
echo "Wrote ${HERE}/SwiftDrop-Final-Report.docx"

# Short documents.
compact() { # markdown-file pdf-file title
  "${PANDOC}" "$1" -s --embed-resources --css "${SRC}/report.css" --metadata title="$3" \
    --variable pagetitle="$3" --resource-path="$(dirname "$1")" -o "${TMP}/compact.html"
  sed -i.bak 's/<body>/<body class="compact">/' "${TMP}/compact.html"
  # The Markdown already opens with its own heading; drop the duplicate pandoc title block.
  python3 - "${TMP}/compact.html" <<'PY'
import re, sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
s = re.sub(r'<header id="title-block-header">.*?</header>', '', s, count=1, flags=re.S)
open(p, "w", encoding="utf-8").write(s)
PY
  print_pdf "${TMP}/compact.html" "$2"
}
compact "${HERE}/VERIFICATION.md" "${HERE}/SwiftDrop-Test-Report.pdf" "SwiftDrop Test Report"
compact "${HERE}/CONTRIBUTION_SHEET.md" "${HERE}/SwiftDrop-Contribution-Sheet.pdf" "SwiftDrop Contribution Sheet"

# Design documentation (diagrams, architecture, flows and justifications).
"${ROOT}/docs/report/design/build-pdf.sh" >/dev/null
cp "${ROOT}/build/reports/design/SwiftDrop-Design-Documentation.pdf" "${HERE}/SwiftDrop-Design-Documentation.pdf"
echo "Wrote ${HERE}/SwiftDrop-Design-Documentation.pdf"
