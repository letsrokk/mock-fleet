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
assert_contains "${readme}" 'creates a generated, run-specific bucket'
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

printf '[cluster-static] Static cluster contracts passed.\n'
