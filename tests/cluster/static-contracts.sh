#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd "${script_dir}/../.." && pwd)

fail() {
  printf '[cluster-static] ERROR: %s\n' "$*" >&2
  exit 1
}

assert_contains() {
  local file=$1
  local text=$2
  grep -Fq -- "${text}" "${file}" || fail "${file#"${repo_root}/"} is missing: ${text}"
}

assert_absent() {
  local file=$1
  local text=$2
  if grep -Fq -- "${text}" "${file}"; then
    fail "${file#"${repo_root}/"} must not contain: ${text}"
  fi
}

workflow="${repo_root}/.github/workflows/cluster-e2e.yml"
readme="${repo_root}/README.md"
harness="${repo_root}/bin/cluster-e2e.sh"

assert_contains "${workflow}" 'runs-on: [self-hosted, linux, mock-fleet-e2e]'
assert_absent "${workflow}" 'runner_labels'
assert_contains "${readme}" 'adds a short per-execution suffix to each generated run-specific bucket name'
assert_absent "${readme}" 'pre-create an isolated SeaweedFS bucket'
assert_contains "${harness}" 'assert_mock_starting_contract'
assert_contains "${harness}" 'persistent-stub-deleted.json'
[[ -s "${repo_root}/tests/cluster/fixtures/persistent-stub-deleted.json" ]] \
  || fail 'The persistent-deletion fixture is missing.'

self_test_output=$("${harness}" --self-test)
grep -Fq 'MOCK_STARTING error contract passed.' <<<"${self_test_output}" \
  || fail 'Harness self-test did not exercise the cold-start error contract.'
grep -Fq 'Persistent restart state contract passed.' <<<"${self_test_output}" \
  || fail 'Harness self-test did not exercise persistent deletion state.'
grep -Fq 'Existing bucket refusal contract passed.' <<<"${self_test_output}" \
  || fail 'Harness self-test did not exercise accessible existing bucket refusal.'
grep -Fq 'Ambiguous bucket refusal contract passed.' <<<"${self_test_output}" \
  || fail 'Harness self-test did not exercise ambiguous bucket probe refusal.'
grep -Fq 'Ownership marker cleanup gate passed.' <<<"${self_test_output}" \
  || fail 'Harness self-test did not exercise exact ownership marker cleanup gating.'
grep -Fq 'Ownership-token bucket isolation contract passed.' <<<"${self_test_output}" \
  || fail 'Harness self-test did not isolate buckets for identical run IDs with different ownership tokens.'
grep -Fq 'Post-create empty-bucket gate passed.' <<<"${self_test_output}" \
  || fail 'Harness self-test did not require a newly created bucket to be empty.'
grep -Fq 'Run-ID-only marker refusal contract passed.' <<<"${self_test_output}" \
  || fail 'Harness self-test did not reject a marker containing only the run ID.'

dry_run_output=$(MOCK_FLEET_E2E_RUN_ID=static-contract "${harness}" --dry-run)
grep -Fq 'S3 bucket:    mock-fleet-e2e-data-static-contract-' <<<"${dry_run_output}" \
  || fail 'Dry-run bucket does not include the ownership-token suffix.'
if grep -Fq 'Ownership token:' <<<"${dry_run_output}"; then
  fail 'Dry-run exposed the full ownership token.'
fi
if grep -Eq '[0-9a-f]{32}' <<<"${dry_run_output}"; then
  fail 'Dry-run exposed a full ownership-token value.'
fi

printf '[cluster-static] Static cluster contracts passed.\n'
