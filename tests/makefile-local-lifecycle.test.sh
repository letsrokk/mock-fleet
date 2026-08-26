#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

run_make() {
    make --no-print-directory -n -C "${REPO_ROOT}" "$@"
}

assert_equal() {
    local expected="$1"
    local actual="$2"
    local description="$3"

    if [[ "${actual}" != "${expected}" ]]; then
        printf 'FAIL: %s\nExpected: %s\nActual:   %s\n' "${description}" "${expected}" "${actual}" >&2
        exit 1
    fi
}

assert_fails_with() {
    local expected_message="$1"
    shift

    local output
    if output=$(run_make "$@" 2>&1); then
        printf 'FAIL: make unexpectedly accepted: %s\n' "$*" >&2
        exit 1
    fi

    if [[ "${output}" != *"${expected_message}"* ]]; then
        printf 'FAIL: expected error containing %q, got:\n%s\n' "${expected_message}" "${output}" >&2
        exit 1
    fi

    if [[ "${output}" == *'bin/local/deploy.sh'* || "${output}" == *'bin/local/destroy.sh'* ]]; then
        printf 'FAIL: invalid input emitted a lifecycle command:\n%s\n' "${output}" >&2
        exit 1
    fi
}

assert_not_contains() {
    local unexpected="$1"
    local actual="$2"
    local description="$3"

    if [[ "${actual}" == *"${unexpected}"* ]]; then
        printf 'FAIL: %s\nUnexpected: %s\nActual:     %s\n' "${description}" "${unexpected}" "${actual}" >&2
        exit 1
    fi
}

assert_equal \
    "bin/local/deploy.sh --namespace 'mock-fleet'" \
    "$(run_make local-deploy)" \
    'default local deployment'

assert_equal \
    "bin/local/deploy.sh --namespace 'test-fleet' --logs --remote-dev api --port-forward" \
    "$(run_make local-deploy NAMESPACE=test-fleet LOGS=true DEV=true PORT_FORWARD=true)" \
    'API development deployment with all optional flags'

assert_equal \
    "bin/local/deploy.sh --namespace 'mock-fleet'" \
    "$(run_make local-deploy ROUTING=HOST)" \
    'Makefile does not expose the unsupported HOST routing override'

assert_not_contains \
    'ROUTING=' \
    "$(run_make help)" \
    'Makefile help does not advertise routing overrides'

assert_equal \
    "bin/local/deploy.sh --namespace 'mock-fleet' --remote-dev api" \
    "$(run_make local-deploy DEV=api)" \
    'explicit API development deployment'

assert_equal \
    "bin/local/deploy.sh --namespace 'mock-fleet' --remote-dev proxy" \
    "$(run_make local-deploy DEV=proxy)" \
    'proxy development deployment'

assert_equal \
    "bin/local/destroy.sh --namespace 'test-fleet' --release 'test-release' --delete-namespace" \
    "$(run_make local-destroy NAMESPACE=test-fleet RELEASE=test-release DELETE_NAMESPACE=true)" \
    'local destruction with namespace deletion'

assert_equal \
    "bin/local/destroy.sh --namespace 'mock-fleet' --release 'test;printf injected'" \
    "$(run_make local-destroy 'RELEASE=test;printf injected')" \
    'shell-safe release argument'

assert_equal \
    "bin/local/destroy.sh --namespace 'mock-fleet' --release 'test'\"'\"'release'" \
    "$(run_make local-destroy "RELEASE=test'release")" \
    'shell-safe release argument containing an apostrophe'

assert_fails_with 'LOGS must be true or false' local-deploy LOGS=yes
assert_fails_with 'PORT_FORWARD must be true or false' local-deploy PORT_FORWARD=yes
assert_fails_with 'DEV must be false, true, api, or proxy' local-deploy DEV=dashboard
assert_fails_with 'DEV must be false, true, api, or proxy' local-deploy 'DEV=api proxy'
assert_fails_with 'DELETE_NAMESPACE must be true or false' local-destroy DELETE_NAMESPACE=yes

echo 'Makefile local lifecycle tests passed.'
