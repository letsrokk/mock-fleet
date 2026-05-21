#!/usr/bin/env bash
set -euo pipefail

WIREMOCK_HOST=${WIREMOCK_HOST:-wiremock.mock-fleet.localhost}
WIREMOCK_SCHEME=${WIREMOCK_SCHEME:-http}

usage() {
    cat <<EOF
Usage: $(basename "$0") [--host <host>] [--scheme <http|https>]

Fetch all local WireMock mappings.

Options:
  --host <host>       WireMock mock hostname. Defaults to ${WIREMOCK_HOST}.
  --scheme <value>    WireMock endpoint scheme. Defaults to ${WIREMOCK_SCHEME}.
  --help              Show this help.
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --host)
            if [[ $# -lt 2 ]]; then
                echo "Missing value for --host." >&2
                usage >&2
                exit 1
            fi
            WIREMOCK_HOST="$2"
            shift 2
            ;;
        --scheme)
            if [[ $# -lt 2 ]]; then
                echo "Missing value for --scheme." >&2
                usage >&2
                exit 1
            fi
            WIREMOCK_SCHEME="$2"
            shift 2
            ;;
        --help)
            usage
            exit 0
            ;;
        *)
            echo "Unknown option: $1" >&2
            usage >&2
            exit 1
            ;;
    esac
done

if [[ "${WIREMOCK_SCHEME}" != "http" && "${WIREMOCK_SCHEME}" != "https" ]]; then
    echo "Invalid scheme: ${WIREMOCK_SCHEME}. Expected http or https." >&2
    exit 1
fi

WIREMOCK_ADMIN_URL="${WIREMOCK_SCHEME}://${WIREMOCK_HOST}/__admin/mappings"

curl --fail-with-body --show-error --silent \
    --request GET "${WIREMOCK_ADMIN_URL}" \
    --header "Host: ${WIREMOCK_HOST}"

echo
