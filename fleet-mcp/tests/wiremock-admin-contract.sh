#!/usr/bin/env bash
set -Eeuo pipefail

active_container=""
active_target=""
active_network=""
active_volume=""
base_url=""

cleanup() {
    if [[ -n "${active_container}" ]]; then
        docker rm --force "${active_container}" >/dev/null 2>&1 || true
    fi
    if [[ -n "${active_target}" ]]; then
        docker rm --force "${active_target}" >/dev/null 2>&1 || true
    fi
    if [[ -n "${active_network}" ]]; then
        docker network rm "${active_network}" >/dev/null 2>&1 || true
    fi
    if [[ -n "${active_volume}" ]]; then
        docker volume rm --force "${active_volume}" >/dev/null 2>&1 || true
    fi
}

report_error() {
    local status="$?"
    echo "WireMock Admin contract failed at line ${BASH_LINENO[0]} (exit ${status})" >&2
    exit "${status}"
}

trap cleanup EXIT
trap report_error ERR

require_command() {
    if ! command -v "$1" >/dev/null 2>&1; then
        echo "$1 is required for WireMock Admin contract tests" >&2
        exit 1
    fi
}

wait_for_wiremock() {
    local base_url="$1"
    local attempt
    for attempt in {1..60}; do
        if curl --fail --silent --output /dev/null "${base_url}/__admin/mappings"; then
            return
        fi
        sleep 1
    done
    echo "WireMock did not become ready at ${base_url}" >&2
    exit 1
}

start_wiremock() {
    local image="$1"
    docker run --detach --rm --name "${active_container}" --publish 127.0.0.1::8080 \
        --network "${active_network}" --volume "${active_volume}:/home/wiremock" "${image}" >/dev/null
    local port
    port="$(docker port "${active_container}" 8080/tcp | sed 's/.*://')"
    base_url="http://127.0.0.1:${port}"
    wait_for_wiremock "${base_url}"
}

start_recording_target() {
    local image="$1"
    docker run --detach --rm --name "${active_target}" --publish 127.0.0.1::8080 \
        --network "${active_network}" "${image}" >/dev/null
    local port
    port="$(docker port "${active_target}" 8080/tcp | sed 's/.*://')"
    local target_base_url="http://127.0.0.1:${port}"
    wait_for_wiremock "${target_base_url}"
    curl --fail --silent --request POST --header 'Content-Type: application/json' \
        --data-binary '{"request":{"method":"GET","urlPath":"/record-me"},"response":{"status":200,"body":"recorded-body"}}' \
        "${target_base_url}/__admin/mappings" >/dev/null
}

restart_wiremock() {
    local image="$1"
    docker rm --force "${active_container}" >/dev/null
    start_wiremock "${image}"
}

run_contract() {
    local image="$1"
    local expected_version="$2"
    local unmatched_supported="$3"
    local body_file_read_supported="$4"
    local suffix="${expected_version//./-}"
    active_container="mock-fleet-mcp-contract-${suffix}-$$"
    active_target="${active_container}-target"
    active_network="${active_container}"
    active_volume="mock-fleet-mcp-contract-${suffix}-$$"

    docker network create "${active_network}" >/dev/null
    docker volume create "${active_volume}" >/dev/null
    start_wiremock "${image}"

    curl --fail --silent "${base_url}/__admin/mappings?limit=50&offset=0" | jq --exit-status '.mappings | type == "array"' >/dev/null
    curl --fail --silent "${base_url}/__admin/files" | jq --exit-status 'type == "array"' >/dev/null
    curl --fail --silent "${base_url}/__admin/scenarios" | jq --exit-status '.scenarios | type == "array"' >/dev/null
    curl --fail --silent "${base_url}/__admin/recordings/status" | jq --exit-status '.status == "NeverStarted"' >/dev/null

    if [[ "${expected_version}" == 3.0.* ]]; then
        local version_status
        version_status="$(curl --silent --output /dev/null --write-out '%{http_code}' "${base_url}/__admin/version")"
        [[ "${version_status}" == "404" ]]
    else
        curl --fail --silent "${base_url}/__admin/version" | jq --exit-status --arg version "${expected_version}" '.version == $version' >/dev/null
    fi

    local unmatched_status
    unmatched_status="$(curl --silent --output /dev/null --write-out '%{http_code}' "${base_url}/__admin/mappings/unmatched")"
    if [[ "${unmatched_supported}" == "true" ]]; then
        [[ "${unmatched_status}" == "200" ]]
    else
        [[ "${unmatched_status}" == "400" ]]
    fi

    curl --fail --silent --request PUT --header 'Content-Type: text/plain' \
        --data-binary 'contract-body' "${base_url}/__admin/files/contract/body.txt" >/dev/null
    local mapping
    mapping="$(curl --fail --silent --request POST --header 'Content-Type: application/json' \
        --data-binary '{"request":{"method":"GET","urlPath":"/contract"},"response":{"status":202,"bodyFileName":"contract/body.txt"},"persistent":false}' \
        "${base_url}/__admin/mappings")"
    jq --exit-status '.persistent == false and (.id | type == "string")' <<<"${mapping}" >/dev/null
    local mapping_id
    mapping_id="$(jq --raw-output '.id' <<<"${mapping}")"
    [[ "$(curl --fail --silent "${base_url}/contract")" == "contract-body" ]]

    local request_pattern='{"method":"GET","urlPath":"/contract"}'
    curl --fail --silent --request POST --header 'Content-Type: application/json' --data-binary "${request_pattern}" \
        "${base_url}/__admin/requests/find" | jq --exit-status '.requests | length == 1' >/dev/null
    curl --fail --silent --request POST --header 'Content-Type: application/json' --data-binary "${request_pattern}" \
        "${base_url}/__admin/requests/count" | jq --exit-status '.count == 1' >/dev/null

    start_recording_target "${image}"
    jq --null-input --compact-output --arg target "http://${active_target}:8080" \
        '{targetBaseUrl:$target,persist:false,outputFormat:"IDS"}' \
        | curl --fail --silent --request POST --header 'Content-Type: application/json' --data-binary @- \
            "${base_url}/__admin/recordings/start" >/dev/null
    [[ "$(curl --fail --silent "${base_url}/record-me")" == "recorded-body" ]]
    local recording_result
    recording_result="$(curl --fail --silent --request POST "${base_url}/__admin/recordings/stop")"
    local recorded_id
    recorded_id="$(jq --exit-status --raw-output '.ids[0]' <<<"${recording_result}")"
    curl --fail --silent "${base_url}/__admin/mappings/${recorded_id}" \
        | jq --exit-status '(.persistent // false) == false' >/dev/null
    docker rm --force "${active_target}" >/dev/null
    active_target=""
    [[ "$(curl --fail --silent "${base_url}/record-me")" == "recorded-body" ]]

    curl --fail --silent --request POST --header 'Content-Type: application/json' \
        --data-binary '{"persist":false,"outputFormat":"IDS"}' "${base_url}/__admin/recordings/snapshot" \
        | jq --exit-status '.ids | type == "array"' >/dev/null
    curl --fail --silent --request DELETE "${base_url}/__admin/requests" >/dev/null
    curl --fail --silent --request POST "${base_url}/__admin/scenarios/reset" >/dev/null

    local persistent_mapping
    persistent_mapping="$(jq --compact-output '.persistent = true' <<<"${mapping}")"
    curl --fail --silent --request PUT --header 'Content-Type: application/json' --data-binary "${persistent_mapping}" \
        "${base_url}/__admin/mappings/${mapping_id}" | jq --exit-status '.persistent == true' >/dev/null
    restart_wiremock "${image}"
    curl --fail --silent "${base_url}/__admin/mappings/${mapping_id}" | jq --exit-status '.persistent == true' >/dev/null
    [[ "$(curl --fail --silent "${base_url}/contract")" == "contract-body" ]]

    local stored_mapping
    stored_mapping="$(curl --fail --silent "${base_url}/__admin/mappings/${mapping_id}")"
    local updated_mapping
    updated_mapping="$(jq --compact-output '.response.status = 203' <<<"${stored_mapping}")"
    local recovery_marker
    recovery_marker="$(jq --null-input --compact-output --arg stub_id "${mapping_id}" \
        --argjson before "${stored_mapping}" --argjson after "${updated_mapping}" \
        '{persistent:true,request:{method:"OPTIONS",urlPath:("/__mock_fleet_mcp/recovery/" + $stub_id)},response:{status:503},metadata:{_mockFleetMcpRecovery:{operation:"update",stubId:$stub_id,before:$before,after:$after}}}' \
        | curl --fail --silent --request POST --header 'Content-Type: application/json' --data-binary @- \
            "${base_url}/__admin/mappings")"
    local recovery_marker_id
    recovery_marker_id="$(jq --exit-status --raw-output '.id' <<<"${recovery_marker}")"
    restart_wiremock "${image}"
    local recovered_after
    recovered_after="$(curl --fail --silent "${base_url}/__admin/mappings/${recovery_marker_id}" \
        | jq --exit-status --arg id "${mapping_id}" \
            '.metadata._mockFleetMcpRecovery | select(.operation == "update" and .stubId == $id and .before.id == $id) | .after')"
    curl --fail --silent --request DELETE "${base_url}/__admin/mappings/${mapping_id}" >/dev/null
    curl --fail --silent --request POST --header 'Content-Type: application/json' --data-binary "${recovered_after}" \
        "${base_url}/__admin/mappings" | jq --exit-status '.persistent == true and .response.status == 203' >/dev/null
    local verified_updated_mapping
    verified_updated_mapping="$(curl --fail --silent "${base_url}/__admin/mappings/${mapping_id}")"
    jq --exit-status --argjson expected "${recovered_after}" \
        '. == $expected and .persistent == true and .response.status == 203' \
        <<<"${verified_updated_mapping}" >/dev/null
    curl --fail --silent --request DELETE "${base_url}/__admin/mappings/${recovery_marker_id}" >/dev/null
    restart_wiremock "${image}"
    curl --fail --silent "${base_url}/__admin/mappings/${mapping_id}" \
        | jq --exit-status '.persistent == true and .response.status == 203' >/dev/null

    stored_mapping="$(curl --fail --silent "${base_url}/__admin/mappings/${mapping_id}")"
    local temporary_mapping
    temporary_mapping="$(jq --compact-output '.persistent = false' <<<"${stored_mapping}")"
    recovery_marker="$(jq --null-input --compact-output --arg stub_id "${mapping_id}" \
        --argjson before "${stored_mapping}" --argjson after "${temporary_mapping}" \
        '{persistent:true,request:{method:"OPTIONS",urlPath:("/__mock_fleet_mcp/recovery/" + $stub_id)},response:{status:503},metadata:{_mockFleetMcpRecovery:{operation:"unpersist",stubId:$stub_id,before:$before,after:$after}}}' \
        | curl --fail --silent --request POST --header 'Content-Type: application/json' --data-binary @- \
            "${base_url}/__admin/mappings")"
    recovery_marker_id="$(jq --exit-status --raw-output '.id' <<<"${recovery_marker}")"
    restart_wiremock "${image}"
    local recovered_temporary
    recovered_temporary="$(curl --fail --silent "${base_url}/__admin/mappings/${recovery_marker_id}" \
        | jq --exit-status --arg id "${mapping_id}" \
            '.metadata._mockFleetMcpRecovery | select(.operation == "unpersist" and .stubId == $id and .before.id == $id) | .after')"
    curl --fail --silent --request DELETE "${base_url}/__admin/mappings/${mapping_id}" >/dev/null
    curl --fail --silent --request POST --header 'Content-Type: application/json' --data-binary "${recovered_temporary}" \
        "${base_url}/__admin/mappings" | jq --exit-status '.persistent == false' >/dev/null
    curl --fail --silent --request DELETE "${base_url}/__admin/mappings/${recovery_marker_id}" >/dev/null
    restart_wiremock "${image}"
    [[ "$(curl --silent --output /dev/null --write-out '%{http_code}' "${base_url}/__admin/mappings/${mapping_id}")" == "404" ]]
    [[ "$(curl --silent --output /dev/null --write-out '%{http_code}' "${base_url}/__admin/mappings/${recovery_marker_id}")" == "404" ]]
    if [[ "${body_file_read_supported}" == "true" ]]; then
        [[ "$(curl --fail --silent "${base_url}/__admin/files/contract/body.txt")" == "contract-body" ]]
    fi

    cleanup
    active_container=""
    active_network=""
    active_volume=""
    echo "WireMock ${expected_version} Admin contract passed"
}

require_command curl
require_command docker
require_command jq

run_contract wiremock/wiremock:3.0.4-1 3.0.4 false false
run_contract wiremock/wiremock:3.13.2-2 3.13.2 true true
