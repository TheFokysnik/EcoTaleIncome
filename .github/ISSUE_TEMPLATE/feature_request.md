---
name: Feature request
about: Suggest a new feature or improvement for EcoTaleIncome
title: ''
labels: enhancement
assignees: TheFokysnik

---

name: 💡 Feature Request
description: Suggest a new feature or improvement for EcoTaleIncome
title: "[FEATURE] "
labels: ["enhancement"]
assignees: []
body:
  - type: markdown
    attributes:
      value: |
        ✨ Thank you for your idea!
        Please describe your request in detail and consider its impact on the server economy.

  - type: textarea
    id: feature_description
    attributes:
      label: Feature Description
      description: What would you like to add or change?
      placeholder: |
        Add income multipliers for high-risk regions...
    validations:
      required: true

  - type: textarea
    id: problem
    attributes:
      label: Problem Statement
      placeholder: |
        Currently, income does not depend on risk level...
    validations:
      required: true

  - type: textarea
    id: solution
    attributes:
      label: Proposed Solution
      placeholder: |
        Introduce regional income modifiers based on danger level...
    validations:
      required: true

  - type: textarea
    id: alternatives
    attributes:
      label: Alternative Solutions
      placeholder: |
        This could also be handled via permissions...
    validations:
      required: false

  - type: dropdown
    id: impact
    attributes:
      label: Impact on Economy
      options:
        - Low (QoL / cosmetic)
        - Medium
        - High (affects balance)
    validations:
      required: true

  - type: dropdown
    id: complexity
    attributes:
      label: Estimated Complexity
      options:
        - Low
        - Medium
        - High
        - Not sure
    validations:
      required: true

  - type: checkboxes
    id: confirmation
    attributes:
      label: Confirmation
      options:
        - label: I understand that this feature may be rejected
          required: true
        - label: I understand that implementation will take time
          required: true
