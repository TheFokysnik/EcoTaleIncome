---
name: Bug report
about: Report a bug or unexpected behavior in EcoTaleIncome
title: ''
labels: bug
assignees: TheFokysnik

---

name: 🐞 Bug Report
description: Report a bug or unexpected behavior in EcoTaleIncome
title: "[BUG] "
labels: ["bug"]
assignees: []
body:
  - type: markdown
    attributes:
      value: |
        ⚠️ Before submitting a bug report, please make sure that:
        - You are using the latest version of EcoTaleIncome
        - The issue can be reproduced consistently
        - This is not a configuration-related problem

  - type: input
    id: version
    attributes:
      label: EcoTaleIncome Version
      description: Specify the plugin version
      placeholder: "e.g. 1.2.0"
    validations:
      required: true

  - type: input
    id: hytale_version
    attributes:
      label: Hytale Server Version
      placeholder: "e.g. 0.9.x"
    validations:
      required: true

  - type: textarea
    id: description
    attributes:
      label: Issue Description
      description: C
