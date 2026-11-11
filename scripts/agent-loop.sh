#!/usr/bin/env bash
# shellcheck disable=SC2016  # GraphQL variables ($a, $p ...) are meant literally inside single quotes.
# Agent loop for Copilot pull requests. Design: docs/design-docs/agent-loop.md.
#   scripts/agent-loop.sh assign <issue>       assign an agent-ready issue to Copilot
#   scripts/agent-loop.sh reconcile <pr>       take the next step for a Copilot pull request
#   scripts/agent-loop.sh reconcile-branch <b> same, finding the pull request by its head branch
# Every call looks at the current state and takes at most one step, so running it twice is safe.
set -euo pipefail

REPO="${GITHUB_REPOSITORY:?set GITHUB_REPOSITORY to owner/name}"
OWNER="${REPO%/*}"
NAME="${REPO#*/}"
MAX_FIX_ROUNDS="${MAX_FIX_ROUNDS:-2}"
HUMAN_REVIEWER="${HUMAN_REVIEWER:?set HUMAN_REVIEWER to a GitHub login}"
AGENT_MODEL="${AGENT_MODEL:-}"
REREVIEW_EFFORT="${REREVIEW_EFFORT:-LITE}"
REVIEWER="copilot-pull-request-reviewer[bot]"
AGENT_WORKFLOW="dynamic/copilot-swe-agent/copilot"
LOOP_WORKFLOW=".github/workflows/agent-loop.yml"
READY_LABEL="agent-ready"
HANDOFF_LABEL="ready-for-human"
FIX_MARKER="agent-loop:fix"

log() { echo "agent-loop: $*" >&2; }  # stderr, so it never mixes into a captured result

# gh with retries for answers that mean "try again later" (rate limit, 5xx, GraphQL SERVICE_UNAVAILABLE).
gh_retry() {
  local attempt rc out err delay=5
  err=$(mktemp)
  for attempt in 1 2 3 4; do
    rc=0
    out=$(gh "$@" 2>"$err") || rc=$?
    if [ "$rc" -eq 0 ] && ! grep -q 'SERVICE_UNAVAILABLE' <<<"$out"; then
      rm -f "$err"; printf '%s\n' "$out"; return 0
    fi
    if [ "$attempt" -lt 4 ] && grep -qiE 'SERVICE_UNAVAILABLE|HTTP 5[0-9][0-9]|HTTP 429|timed out|connection reset' <<<"$out $(cat "$err")"; then
      log "GitHub API answered 'try again later' (attempt $attempt); retrying in ${delay}s"
      sleep "$delay"; delay=$((delay * 2)); continue
    fi
    cat "$err" >&2; rm -f "$err"; printf '%s\n' "$out"; return "$rc"
  done
}

assign() {
  local issue="$1" data issue_id bot_id
  data=$(gh_retry api "repos/$REPO/issues/$issue")
  if ! jq -e --arg l "$READY_LABEL" '.labels | map(.name) | index($l)' <<<"$data" >/dev/null; then
    log "issue #$issue has no $READY_LABEL label; it waits for a person"
    return
  fi
  if jq -e '.assignees | map(.login) | index("Copilot")' <<<"$data" >/dev/null; then
    log "issue #$issue is already assigned to Copilot"
    return
  fi
  issue_id=$(jq -r .node_id <<<"$data")
  bot_id=$(gh_retry api graphql -f query='query($o:String!,$n:String!){repository(owner:$o,name:$n){suggestedActors(capabilities:[CAN_BE_ASSIGNED],first:50){nodes{login ... on Bot{id}}}}}' \
    -f o="$OWNER" -f n="$NAME" --jq '.data.repository.suggestedActors.nodes[] | select(.login=="copilot-swe-agent") | .id')
  [ -n "$bot_id" ] || { echo "::error::Copilot cloud agent cannot be assigned in $REPO. FIX: enable it under Settings > Copilot > Cloud."; exit 1; }
  if [ -n "$AGENT_MODEL" ]; then
    gh_retry api graphql -H 'GraphQL-Features: issues_copilot_assignment_api_support,coding_agent_model_selection' \
      -f query='mutation($a:ID!,$b:ID!,$m:String!){addAssigneesToAssignable(input:{assignableId:$a,assigneeIds:[$b],agentAssignment:{model:$m}}){clientMutationId}}' \
      -f a="$issue_id" -f b="$bot_id" -f m="$AGENT_MODEL" >/dev/null
  else
    gh_retry api graphql -H 'GraphQL-Features: issues_copilot_assignment_api_support' \
      -f query='mutation($a:ID!,$b:ID!){addAssigneesToAssignable(input:{assignableId:$a,assigneeIds:[$b]}){clientMutationId}}' \
      -f a="$issue_id" -f b="$bot_id" >/dev/null
  fi
  log "issue #$issue assigned to Copilot, model ${AGENT_MODEL:-auto}"
}

comment() { gh_retry api "repos/$REPO/issues/$1/comments" -f body="$2" >/dev/null; }

handoff() {
  local pr="$1" reason="$2" draft="$3"
  [ "$draft" = true ] && gh_retry pr ready "$pr" --repo "$REPO" >/dev/null
  gh_retry api "repos/$REPO/issues/$pr/labels" -f "labels[]=$HANDOFF_LABEL" >/dev/null
  comment "$pr" "@$HUMAN_REVIEWER Ready for a human. $reason

Check that the diff matches the acceptance criteria of the linked issue, then merge or ask for changes."
  log "PR #$pr handed to $HUMAN_REVIEWER: $reason"
}

# Starts a fix round. The marker comment is written first so the round is counted even if starting fails.
# The session is started through the agent tasks API, which lets us choose the model; a plain @copilot
# comment always runs with the default model. If the API refuses, the @copilot comment is the fallback.
start_fix() {
  local pr="$1" marker="$2" round="$3" what="$4" prompt="$5" head base
  head=$(jq -r .head.ref <<<"$info")
  base=$(jq -r .base.ref <<<"$info")
  comment "$pr" "Agent loop: fix round $round of $MAX_FIX_ROUNDS, $what.

<!-- $marker -->"
  local args=(api "agents/repos/$REPO/tasks" -f prompt="$prompt" -f head_ref="$head" -f base_ref="$base")
  [ -n "$AGENT_MODEL" ] && args+=(-f model="$AGENT_MODEL")
  if gh_retry "${args[@]}" >/dev/null; then
    log "PR #$pr: started fix round $round ($what) with model ${AGENT_MODEL:-auto}"
  else
    comment "$pr" "@copilot $prompt"
    log "PR #$pr: agent tasks API refused; asked @copilot for fix round $round ($what)"
  fi
}

wait_for_review() {
  local pr="$1" before="$2" now
  for _ in $(seq 1 60); do
    now=$(gh_retry api --paginate "repos/$REPO/pulls/$pr/reviews" | jq -s --arg r "$REVIEWER" '[.[][] | select(.user.login==$r and .state!="PENDING")] | length')
    if [ "$now" -gt "$before" ]; then log "PR #$pr: Copilot review $now arrived"; return 0; fi
    sleep 10
  done
  log "PR #$pr: no Copilot review after 10 minutes; a later event or a manual run moves it forward"
  return 1
}

# CI result of a commit: "pending", "success" or "failure:<workflow names>". Only pull_request runs count;
# Copilot's own runs and this loop are not CI.
ci_state() {
  gh_retry api "repos/$REPO/actions/runs?head_sha=$1&event=pull_request&per_page=50" | jq -r --arg loop "$LOOP_WORKFLOW" '
    [.workflow_runs[] | select((.path | startswith("dynamic/") | not) and .path != $loop)]
    | group_by(.path) | map(max_by(.created_at)) as $runs
    | if ($runs | length) == 0 or any($runs[]; .status != "completed") then "pending"
      else ([$runs[] | select(.conclusion != "success" and .conclusion != "skipped") | .name]
            | if length == 0 then "success" else "failure:" + join(", ") end)
      end'
}

wait_for_ci() {
  local sha="$1" state
  for _ in $(seq 1 90); do
    state=$(ci_state "$sha")
    [ "$state" != pending ] && { echo "$state"; return; }
    sleep 10
  done
  echo pending
}

reconcile() {
  local pr="$1" branch agent_runs finished reviews rounds fixes last_at last_id comments draft result sha ci effort prompt
  info=$(gh_retry api "repos/$REPO/pulls/$pr")
  if [ "$(jq -r .user.login <<<"$info")" != Copilot ] || [ "$(jq -r .state <<<"$info")" != open ]; then
    log "PR #$pr is not an open Copilot pull request"; return
  fi
  if jq -e --arg l "$HANDOFF_LABEL" '.labels | map(.name) | index($l)' <<<"$info" >/dev/null; then
    log "PR #$pr is already with a human"; return
  fi
  if jq -e '.requested_reviewers | map(.login) | map(test("^[Cc]opilot")) | any' <<<"$info" >/dev/null; then
    log "PR #$pr: a Copilot review is pending"; return 3
  fi
  draft=$(jq -r .draft <<<"$info")
  sha=$(jq -r .head.sha <<<"$info")

  # Whether the coding agent is working comes from its own Actions runs on the pull request branch.
  # The timeline is not used: Copilot code review also writes copilot_work_started and never a matching
  # copilot_work_finished.
  branch=$(jq -r '.head.ref | @uri' <<<"$info")
  agent_runs=$(gh_retry api "repos/$REPO/actions/runs?branch=$branch&per_page=50" \
    | jq --arg p "$AGENT_WORKFLOW" '[.workflow_runs[] | select(.path==$p)]')
  if jq -e 'length == 0 or any(.status != "completed")' <<<"$agent_runs" >/dev/null; then
    log "PR #$pr: Copilot is still working"; return 3
  fi
  finished=$(jq -r 'map(.updated_at) | max' <<<"$agent_runs")
  fixes=$(gh_retry api --paginate "repos/$REPO/issues/$pr/comments" | jq -s --arg m "$FIX_MARKER" '[.[][] | select(.body | contains($m))] | length')

  # CI first: a review of code that does not build wastes a review.
  ci=$(wait_for_ci "$sha")
  case "$ci" in
    pending) log "PR #$pr: CI still running after 15 minutes; a later event or a manual run moves it forward"; return ;;
    failure:*)
      if [ "$fixes" -ge "$MAX_FIX_ROUNDS" ]; then
        handoff "$pr" "CI fails (${ci#failure:}) after $fixes fix rounds." "$draft"; return
      fi
      if gh_retry api --paginate "repos/$REPO/issues/$pr/comments" | jq -se --arg m "$FIX_MARKER:ci:$sha" '[.[][] | select(.body | contains($m))] | length > 0' >/dev/null; then
        log "PR #$pr: CI fix already requested for $sha"; return
      fi
      prompt="CI fails on pull request #$pr (head $sha) in: ${ci#failure:}. Open the failing jobs, find the cause, fix it and run ./mvnw -q verify and scripts/check-docs.sh before you push. Never weaken or delete a test to make the build green; follow .github/copilot-instructions.md."
      start_fix "$pr" "$FIX_MARKER:ci:$sha" "$((fixes + 1))" "CI fails in ${ci#failure:}" "$prompt"
      return ;;
  esac

  reviews=$(gh_retry api --paginate "repos/$REPO/pulls/$pr/reviews" | jq -s --arg r "$REVIEWER" '[.[][] | select(.user.login==$r and .state!="PENDING")] | sort_by(.submitted_at)')
  rounds=$(jq length <<<"$reviews")
  last_at=$(jq -r '.[-1].submitted_at // ""' <<<"$reviews")

  if [ -z "$last_at" ] || [[ "$last_at" < "$finished" ]]; then
    if [ "$rounds" -gt "$MAX_FIX_ROUNDS" ]; then
      handoff "$pr" "The review limit ($rounds reviews) is reached." "$draft"; return
    fi
    # The first review uses the repository default (Balanced); later reviews only check the fixes.
    effort=""
    [ "$rounds" -gt 0 ] && effort="$REREVIEW_EFFORT"
    if [ -n "$effort" ]; then
      result=$(gh_retry api graphql -f query='mutation($p:ID!,$e:CopilotCodeReviewEffort!){requestCopilotCodeReview(input:{pullRequestId:$p,reviewEffort:$e}){success failureReason}}' \
        -f p="$(jq -r .node_id <<<"$info")" -f e="$effort" --jq '.data.requestCopilotCodeReview | "\(.success) \(.failureReason)"') || true
    else
      result=$(gh_retry api graphql -f query='mutation($p:ID!){requestCopilotCodeReview(input:{pullRequestId:$p}){success failureReason}}' \
        -f p="$(jq -r .node_id <<<"$info")" --jq '.data.requestCopilotCodeReview | "\(.success) \(.failureReason)"') || true
    fi
    case "$result" in
      true*) log "PR #$pr: requested Copilot review $((rounds + 1)), effort ${effort:-default}" ;;
      *REQUEST_CONFLICT*) log "PR #$pr: a Copilot review is already requested by another run"; return ;;
      *) echo "::error::Copilot review request for PR #$pr failed: $result"; return 1 ;;
    esac
    # The run that GitHub starts when Copilot's review finishes waits for approval (it is started by the
    # reviewer bot), so this run waits for the review itself and then takes the next step.
    wait_for_review "$pr" "$rounds" && reconcile "$pr"
    return
  fi

  last_id=$(jq -r '.[-1].id' <<<"$reviews")
  comments=$(gh_retry api "repos/$REPO/pulls/$pr/reviews/$last_id/comments")
  if [ "$(jq length <<<"$comments")" -eq 0 ]; then
    handoff "$pr" "Copilot review $rounds has no comments and CI is green." "$draft"; return
  fi
  if [ "$fixes" -ge "$MAX_FIX_ROUNDS" ]; then
    handoff "$pr" "Copilot review $rounds still has $(jq length <<<"$comments") comments after $fixes fix rounds." "$draft"; return
  fi
  if gh_retry api --paginate "repos/$REPO/issues/$pr/comments" | jq -se --arg m "$FIX_MARKER:$last_id" '[.[][] | select(.body | contains($m))] | length > 0' >/dev/null; then
    log "PR #$pr: fix already requested for review $last_id"; return
  fi
  prompt="Address the comments of the latest Copilot code review on pull request #$pr. Fix each one, or reply to the comment and explain why the code should stay as it is. Keep every acceptance criterion of the linked issue and follow .github/copilot-instructions.md. The comments:
$(jq -r '.[] | "- \(.path):\(.line // .original_line // "?"): \(.body | .[0:1500])"' <<<"$comments")"
  start_fix "$pr" "$FIX_MARKER:$last_id" "$((fixes + 1))" "$(jq length <<<"$comments") review comments" "$prompt"
}

# A finished Copilot run can show up in the API a few seconds late, and no later event would
# wake the loop again. So "still working" and "review pending" (exit 3) are retried for a minute.
reconcile_with_retry() {
  local attempt rc
  for attempt in 1 2 3 4 5 6; do
    rc=0
    reconcile "$1" || rc=$?
    [ "$rc" -eq 3 ] || return "$rc"
    [ "$attempt" -lt 6 ] && sleep 10
  done
  log "PR #$1: still waiting; a later event moves it forward"
}

case "${1:-}" in
  assign) assign "$2" ;;
  reconcile) reconcile_with_retry "$2" ;;
  reconcile-branch)
    pr=$(gh_retry pr list --repo "$REPO" --head "$2" --state open --json number --jq '.[0].number // empty')
    if [ -n "$pr" ]; then reconcile_with_retry "$pr"; else log "no open pull request for branch $2"; fi ;;
  *) echo "usage: $0 assign <issue> | reconcile <pr> | reconcile-branch <branch>"; exit 2 ;;
esac
