# Design documentation (PDF source)

`design.html` is the source of the design documentation PDF required by the assignment brief. It uses the
rendered diagrams in `docs/diagrams/rendered` (figures 01–20) plus two views drawn in the page itself
(Figure A, implemented containers; Figure B, AI assistant flow).

Build the PDF (needs Google Chrome or Chromium):

```bash
docs/report/design/build-pdf.sh
```

The output is written to `build/reports/design/SwiftDrop-Design-Documentation.pdf`. It is git-ignored: PDFs are
not committed (see `docs/diagrams/README.md`); attach the built file to the submission instead.
