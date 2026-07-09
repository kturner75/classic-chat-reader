# Product Tracking

This folder is the source of truth for product scope and planning.

## Files

- `current-features.md`: implemented capabilities verified against code.
- `backlog.md`: prioritized work queue (features, improvements, tech debt).
- `bl-023-qa-checklist.md`: mobile QA + desktop regression checklist for adaptive reader behavior.
- `bl-021-auth-architecture-adr.md`: auth and security decision record for user registration/account rollout.
- `landing-ranking.md`: deterministic ranking rules for personalized landing queues.
- `discover-affinity.md`: deterministic recommendation model for the `Discover` rail.
- `classroom-landing-usage.md`: setup and usage guide for classroom-aware landing mode (`BL-018.6`).
- `reading-buddy-mode.md`: design document for Reading Buddy Mode (canned personas, sparse commentary, memory, PR plan).

## Backlog Workflow

1. Capture
- Add an item to `backlog.md` with a unique ID (`BL-###`), problem statement, and acceptance criteria.

2. Triage
- Set `Type` (`Feature`, `Improvement`, `Tech Debt`).
- Set `Priority` (`P0`, `P1`, `P2`, `P3`).
- Set `Effort` (`S`, `M`, `L`, `XL`).
- Set `Status` (`Discovery`, `Proposed`, `Ready`, `In Progress`, `Blocked`, `Done`).

3. Refine
- Add dependencies, risks, and rollout notes for anything `P0` or `P1`.
- Ensure acceptance criteria are testable.

4. Execute
- Move item status to `In Progress`.
- Link branch/PR in the Notes field.

5. Close
- Move to `Done` with completion date and link to merged PR.

## Priority Definitions

- `P0`: reliability/security issues or blocked core user journey.
- `P1`: high-value, near-term work.
- `P2`: important, but not urgent.
- `P3`: exploratory or low-impact work.

## Intake Template

Use this template when adding new items:

```
| BL-XYZ | Feature|Improvement|Tech Debt | P0|P1|P2|P3 | S|M|L|XL | Proposed | Title |
Problem: <one sentence>
Acceptance Criteria:
- <observable behavior 1>
- <observable behavior 2>
Notes/Dependencies: <optional>
```

For larger initiatives, use this epic template:

```
### BL-XYZ - <Epic title>
- Type: Feature|Improvement|Tech Debt
- Priority: P0|P1|P2|P3
- Effort: XL
- Status: Discovery
- Problem: <one sentence>
- Scope Buckets:
- <workstream 1>
- <workstream 2>
- Discovery Questions:
- <question 1>
- <question 2>
- Exit Criteria for Discovery:
- <decision or artifact required before implementation>
```
