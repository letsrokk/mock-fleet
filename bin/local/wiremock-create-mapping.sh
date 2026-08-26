#!/usr/bin/env bash
set -euo pipefail

WIREMOCK_HOST=${WIREMOCK_HOST:-mock-fleet.minikube.localhost}
WIREMOCK_SCHEME=${WIREMOCK_SCHEME:-https}
PROXY_BASE_URL=${PROXY_BASE_URL:-https://wiremock.org}

usage() {
    cat <<EOF
Usage: $(basename "$0") [--host <host>] [--proxy-url <url>] [--scheme <http|https>]

Create a local WireMock catch-all proxy mapping.

Options:
  --host <host>       WireMock mock hostname. Defaults to ${WIREMOCK_HOST}.
  --proxy-url <url>   Upstream proxy base URL. Defaults to ${PROXY_BASE_URL}.
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
        --proxy-url)
            if [[ $# -lt 2 ]]; then
                echo "Missing value for --proxy-url." >&2
                usage >&2
                exit 1
            fi
            PROXY_BASE_URL="$2"
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

WIREMOCK_ADMIN_URL="${WIREMOCK_SCHEME}://${WIREMOCK_HOST}/wiremock/__admin/mappings"

echo "Creating WireMock proxy mapping at ${WIREMOCK_ADMIN_URL} -> ${PROXY_BASE_URL}"

curl --fail-with-body --show-error --silent \
    --request POST "${WIREMOCK_ADMIN_URL}" \
    --header "Host: ${WIREMOCK_HOST}" \
    --header "Content-Type: application/json" \
    --data @- <<JSON
{
  "name": "proxy-all",
  "priority": 10,
  "request": {
    "method": "ANY",
    "urlPattern": ".*"
  },
  "response": {
    "proxyBaseUrl": "${PROXY_BASE_URL}"
  }
}
JSON

echo
