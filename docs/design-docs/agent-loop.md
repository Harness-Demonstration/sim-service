# Agent loop

How work moves from an issue to a merged pull request with as little human time as possible.
Implemented by `.github/workflows/agent-loop.yml` and `scripts/agent-loop.sh`.

## Flow
1. **Triage.** A person opens an issue with one of two templates. *Agent task* adds the `agent-ready` label: a well-scoped change with plain acceptance criteria. *Human task* adds `needs-human`: production-critical, security-sensitive or still unclear work. Adding `agent-ready` later has the same effect.
2. **Assign.** An `agent-ready` issue is assigned to the Copilot cloud agent with the model in `AGENT_MODEL`.
3. **Write.** Copilot opens a pull request and works until it requests a review.
4. **CI.** When Copilot has finished, the loop waits for the pull request's CI (up to 15 minutes). If CI fails, it starts a fix round for the failing workflows instead of a review: reviewing code that does not build wastes a review.
5. **Review.** With CI green and no review covering Copilot's latest work, the loop requests a Copilot code review and waits in the same run (up to 10 minutes) until it arrives. It does not rely on the run GitHub starts when the review finishes: that run is started by the reviewer bot and waits for approval. The first review uses the repository default (Balanced); later reviews use `REREVIEW_EFFORT` (Lite), because they only check the fixes.
6. **Fix.** If the review has comments, the loop starts a fix round. It writes a marker comment, then starts a Copilot session on the pull request branch through the agent tasks API with the model in `AGENT_MODEL` and the review comments in the prompt. A plain `@copilot` comment always runs with the default model, so it is only the fallback when the API refuses. Then step 4 runs again.
7. **Hand over.** The loop marks the pull request ready, adds `ready-for-human` and mentions `HUMAN_REVIEWER` when a review has no comments and CI is green, or when `MAX_FIX_ROUNDS` fix rounds (CI and review fixes together) are used up. `MAX_FIX_ROUNDS` comes from the repository variable `AGENT_MAX_FIX_ROUNDS`, default 2.

The human then checks the diff against the acceptance criteria and merges, or removes `ready-for-human` and asks `@copilot` for changes, which restarts the loop at step 4. The loop never merges.

## Rules
- Each run looks at the pull request's current state and takes at most one step, so duplicate events are harmless.
- GitHub API answers that mean "try again later" (HTTP 429, 5xx, GraphQL `SERVICE_UNAVAILABLE`) are retried up to four times with a growing delay.
- The loop stops for good once `ready-for-human` is on the pull request. Remove the label and run the workflow by hand to restart it.
- The workflow only uses triggers that run its definition from the default branch, never `pull_request_review` or `pull_request`, and never checks out pull request code. A pull request cannot change what runs with `AGENT_LOOP_TOKEN`.
- `AGENT_LOOP_TOKEN` is stored only in the `agent-loop` environment, whose deployment branch rule allows `main` alone. Because of that lock, "Require approval for workflow runs" for Copilot can be off.
- `AGENT_LOOP_TOKEN` is a fine-grained token of the human reviewer. Copilot only acts on `@copilot` comments from people with write access, and reviews requested with it count against that person's Copilot plan.

## Sources
- OpenAI, "Harness engineering": agents review their own changes and "iterate in a loop until all agent reviewers are satisfied"; humans are involved when judgment is required.
- GitHub Docs: assigning issues to Copilot through the API (user token, `agentAssignment.model`), `requestCopilotCodeReview`, and the advice that production-critical, security-sensitive and ambiguous tasks are better done by developers.
- The limit of two fix rounds is our own choice; no source names a number.
