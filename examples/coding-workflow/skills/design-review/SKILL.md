# Design Review Skill

Use this skill after a design is drafted and before code changes begin.

## Goal

Find blocking problems in the design while the work is still cheap to change.

## Review criteria

1. The design must address the issue's actual requirement.
2. The design must avoid unrelated refactors.
3. The design must identify the right validation path.
4. The design must keep credentials, repository selection, and PR creation in trusted workflow code.
5. The design must not require capabilities Helm does not expose to the agent.

## Output

Return:

```text
## Decision
APPROVED or CHANGES_REQUIRED

## Findings
High-signal findings only, each with a concrete failure scenario.

## Required changes
Specific design changes required before implementation.
```
