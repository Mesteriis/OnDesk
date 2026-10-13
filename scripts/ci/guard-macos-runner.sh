#!/bin/bash
# Install a copy outside the runner application/workspace and point the runner's
# ACTIONS_RUNNER_HOOK_JOB_STARTED at that copy. Fail before any workflow step.
set -euo pipefail
reject() { echo 'This Mac runner accepts only trusted OnDesk main/release jobs.' >&2; exit 1; }
[ "${GITHUB_REPOSITORY:-}" = Mesteriis/OnDesk ] || reject
case "${GITHUB_EVENT_NAME:-}" in push|workflow_dispatch) ;; *) reject ;; esac
case "${GITHUB_REF:-}" in
    refs/heads/main) ;;
    refs/tags/v*) [[ "$GITHUB_REF" =~ ^refs/tags/v[0-9]+\.[0-9]+\.[0-9]+$ ]] || reject ;;
    *) reject ;;
esac
case "${GITHUB_WORKFLOW_REF:-}" in
    "Mesteriis/OnDesk/.github/workflows/checks.yml@refs/heads/main") ;;
    "Mesteriis/OnDesk/.github/workflows/release.yml@$GITHUB_REF") ;;
    "Mesteriis/OnDesk/.github/workflows/android-instrumentation.yml@refs/heads/main") ;;
    *) reject ;;
esac
echo 'Trusted OnDesk workflow accepted.'
