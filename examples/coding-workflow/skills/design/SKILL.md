# Design Skill

Use this skill before changing code.

## Goal

Turn the bound GitHub issue into a concrete implementation design that a coding agent can execute safely.

## Process

1. Restate the requested behavior in one paragraph.
2. Identify the smallest durable change that satisfies the issue.
3. List the files or subsystems likely to change.
4. Describe the user-visible behavior after the change.
5. Describe validation commands or tests that should prove the change works.
6. Call out blockers when the issue is ambiguous, unsafe, or not actionable.

## Output

Return a concise design with these headings:

```text
## Requirement
## Proposed change
## Files and boundaries
## Validation
## Risks
```
