# Code Review Skill

Use this skill after implementation and verification.

## Goal

Review the diff for correctness, safety, durability, and scope control.

## Review criteria

Report only blocking issues:

1. The implementation does not satisfy the issue.
2. The implementation introduces a likely bug.
3. The implementation mishandles errors, credentials, authorization, or persistence.
4. The implementation changes unrelated behavior.
5. The validation result does not prove the intended behavior.

Do not report style preferences, naming preferences, or speculative improvements.

## Output

If there are no blockers, return:

```text
APPROVED
```

If there are blockers, return:

```text
CHANGES_REQUIRED

## Findings
Each finding must include the risk, the failure scenario, and the smallest required fix.
```
