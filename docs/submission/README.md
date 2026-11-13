# SwiftDrop — Final Submission (15 November 2026)

Group **Minveli_2.0**, Enterprise Application Development. This folder holds the documents for the final submission. The code is the rest of this repository.

| Document | What it contains |
|---|---|
| [SwiftDrop-Final-Report.pdf](SwiftDrop-Final-Report.pdf) ([Word](SwiftDrop-Final-Report.docx)) | The final report: requirement coverage, final design, implementation, testing and evaluation, authenticated walkthrough evidence where verified, demonstration plan, conclusion and individual contributions |
| [SwiftDrop-Design-Documentation.pdf](SwiftDrop-Design-Documentation.pdf) | All design diagrams with explanations: use cases, architecture, activity and sequence flows, state machine, data models, integration and security design, and the reasons for each choice |
| [SwiftDrop-Test-Report.pdf](SwiftDrop-Test-Report.pdf) | Release CI results and locally verified frontend coverage |
| [SwiftDrop-Contribution-Sheet.pdf](SwiftDrop-Contribution-Sheet.pdf) ([Markdown](CONTRIBUTION_SHEET.md)) | Each member's role, main contributions, repository evidence and a signature table |
| [Setup instructions](../../README.md#local-development-and-running) | How to run the whole system with one command |

The 1 November milestone report (use case diagrams, architecture, flowcharts and justifications, with the initial code base and logins) was submitted separately; its content is carried forward and updated in the design documentation above.

## Rebuilding the documents

The PDFs and the Word file are generated from the sources in [`source/`](source/), [`VERIFICATION.md`](VERIFICATION.md), [`CONTRIBUTION_SHEET.md`](CONTRIBUTION_SHEET.md) and [`../report/design/design.html`](../report/design/design.html):

```bash
docs/submission/build.sh
```

It needs pandoc 3 and Google Chrome (or Chromium). The verification report identifies the release commit and the scope of the evidence.
