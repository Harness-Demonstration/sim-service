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
REVIEWER="copilot-pull-request-reviewer[bot]"
READY_LABEL="agent-ready"
HANDOFF_LABEL="ready-for-human"
FIX_MARKER="agent-loop:fix"

log() { echo "agent-loop: $*"; }

assign() {
  local issue="$1" data issue_id bot_id
  data=$(gh api "repos/$REPO/issues/$issue")
  if ! jq -e --arg l "$READY_LABEL" '.labels | map(.name) | index($l)' <<<"$data" >/dev/null; then
    log "issue #$issue has no $READY_LABEL label; it waits for a person"
    return
  fi
  if jq -e '.assignees | map(.login) | index("Copilot")' <<<"$data" >/dev/null; then
    log "issue #$issue is already assigned to Copilot"
    return
  fi
  issue_id=$(jq -r .node_id <<<"$data")
  bot_id=$(gh api graphql -f query='query($o:String!,$n:String!){repository(owner:$o,name:$n){suggestedActors(capabilities:[CAN_BE_ASSIGNED],first:50){nodes{login ... on Bot{id}}}}}' \
    -f o="$OWNER" -f n="$NAME" --jq '.data.repository.suggestedActors.nodes[] | select(.login=="copilot-swe-agent") | .id')
  [ -n "$bot_id" ] || { echo "::error::Copilot cloud agent cannot be assigned in $REPO. FIX: enable it under Settings > Copilot > Cloud."; exit 1; }
  if [ -n "$AGENT_MODEL" ]; then
    gh api graphql -H 'GraphQL-Features: issues_copilot_assignment_api_support,coding_agent_model_selection' \
      -f query='mutation($a:ID!,$b:ID!,$m:String!){addAssigneesToAssignable(input:{assignableId:$a,assigneeIds:[$b],agentAssignment:{model:$m}}){clientMutationId}}' \
      -f a="$issue_id" -f b="$bot_id" -f m="$AGENT_MODEL" >/dev/null
  else
    gh api graphql -H 'GraphQL-Features: issues_copilot_assignment_api_support' \
      -f query='mutation($a:ID!,$b:ID!){addAssigneesToAssignable(input:{assignableId:$a,assigneeIds:[$b]}){clientMutationId}}' \
      -f a="$issue_id" -f b="$bot_id" >/dev/null
  fi
  log "issue #$issue assigned to Copilot, model ${AGENT_MODEL:-auto}"
}

comment() { gh api "repos/$REPO/issues/$1/comments" -f body="$2" >/dev/null; }

handoff() {
  local pr="$1" reason="$2" draft="$3"
  [ "$draft" = true ] && gh pr ready "$pr" --repo "$REPO"
  gh api "repos/$REPO/issues/$pr/labels" -f "labels[]=$HANDOFF_LABEL" >/dev/null
  comment "$pr" "@$HUMAN_REVIEWER Ready for a human. $reason

Check that the diff matches the acceptance criteria of the linked issue, approve the CI run if it waits, then merge or ask for changes."
  log "PR #$pr handed to $HUMAN_REVIEWER: $reason"
}

reconcile() {
  local pr="$1" info timeline started finished reviews rounds fixes last_at last_id comments draft
  info=$(gh api "repos/$REPO/pulls/$pr")
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

  timeline=$(gh api --paginate "repos/$REPO/issues/$pr/timeline" | jq -rs '[.[][] | select(.event=="copilot_work_started" or .event=="copilot_work_finished")] | .[] | [.event, .created_at] | @tsv')
  started=$(awk '$1=="copilot_work_started"{t=$2} END{print t}' <<<"$timeline")
  finished=$(awk '$1=="copilot_work_finished"{t=$2} END{print t}' <<<"$timeline")
  if [ -z "$finished" ] || [[ "$started" > "$finished" ]]; then
    log "PR #$pr: Copilot is still working"; return 3
  fi

  reviews=$(gh api --paginate "repos/$REPO/pulls/$pr/reviews" | jq -s --arg r "$REVIEWER" '[.[][] | select(.user.login==$r and .state!="PENDING")] | sort_by(.submitted_at)')
  rounds=$(jq length <<<"$reviews")
  fixes=$(gh api --paginate "repos/$REPO/issues/$pr/comments" | jq -s --arg m "$FIX_MARKER" '[.[][] | select(.body | contains($m))] | length')
  last_at=$(jq -r '.[-1].submitted_at // ""' <<<"$reviews")

  if [ -z "$last_at" ] || [[ "$last_at" < "$finished" ]]; then
    if [ "$rounds" -gt "$MAX_FIX_ROUNDS" ]; then
      handoff "$pr" "The review limit ($rounds reviews) is reached." "$draft"; return
    fi
    gh api graphql -f query='mutation($p:ID!){requestCopilotCodeReview(input:{pullRequestId:$p}){success failureReason}}' \
      -f p="$(jq -r .node_id <<<"$info")" --jq '.data.requestCopilotCodeReview | "success=\(.success) reason=\(.failureReason)"'
    log "PR #$pr: requested Copilot review $((rounds + 1))"; return
  fi

  last_id=$(jq -r '.[-1].id' <<<"$reviews")
  comments=$(gh api "repos/$REPO/pulls/$pr/reviews/$last_id/comments" --jq length)
  if [ "$comments" -eq 0 ]; then
    handoff "$pr" "Copilot review $rounds has no comments." "$draft"; return
  fi
  if [ "$fixes" -ge "$MAX_FIX_ROUNDS" ]; then
    handoff "$pr" "Copilot review $rounds still has $comments comments after $fixes fix rounds." "$draft"; return
  fi
  if gh api --paginate "repos/$REPO/issues/$pr/comments" | jq -se --arg m "$FIX_MARKER:$last_id" '[.[][] | select(.body | contains($m))] | length > 0' >/dev/null; then
    log "PR #$pr: fix already requested for review $last_id"; return
  fi
  comment "$pr" "@copilot Please address the $comments comments in the latest Copilot review (fix round $((fixes + 1)) of $MAX_FIX_ROUNDS). Fix each one, or reply to the comment and explain why the code should stay as it is. Keep every acceptance criterion of the linked issue and follow \`.github/copilot-instructions.md\`.

<!-- $FIX_MARKER:$last_id -->"
  log "PR #$pr: asked Copilot to fix $comments comments from review $last_id"
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
    pr=$(gh pr list --repo "$REPO" --head "$2" --state open --json number --jq '.[0].number // empty')
    if [ -n "$pr" ]; then reconcile_with_retry "$pr"; else log "no open pull request for branch $2"; fi ;;
  *) echo "usage: $0 assign <issue> | reconcile <pr> | reconcile-branch <branch>"; exit 2 ;;
esac
