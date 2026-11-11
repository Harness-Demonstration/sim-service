# Execution plans

When writing complex features or significant refactors, use an execution plan (ExecPlan). Small changes do not need one; the plan Copilot writes at the top of its pull request is enough.

This follows the format OpenAI describes in its cookbook article "Using PLANS.md for multi-hour problem solving". Active plans live in `docs/exec-plans/active/`, finished ones move to `docs/exec-plans/completed/`.

## Requirements

- Every ExecPlan is fully self-contained: someone who knows nothing about this repository can implement the feature end to end from the plan alone.
- Every ExecPlan is a living document. Update it as work proceeds.
- Every ExecPlan produces demonstrably working behaviour, not just code changes.
- Every term of art is defined in plain language, or not used.

## Skeleton

Use these headings in this order. Write in plain prose; only `Progress` uses a checklist. Keep `Progress`, `Surprises & Discoveries`, `Decision Log` and `Outcomes & Retrospective` up to date while working.

    # <Short, action-oriented description>

    ## Purpose / Big Picture
    ## Progress
    ## Surprises & Discoveries
    ## Decision Log
    ## Outcomes & Retrospective
    ## Context and Orientation
    ## Plan of Work
    ## Concrete Steps
    ## Validation and Acceptance
    ## Idempotence and Recovery
    ## Artifacts and Notes
    ## Interfaces and Dependencies
