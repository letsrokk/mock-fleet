#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
matrix=${repo_root}/fleet-api/src/main/resources/wiremock-option-compatibility.json
mode=${1:-}

command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }
jq -e '
  .minimumSupportedVersion == .stableReleases[0].version and
  .maximumResearchedVersion == .stableReleases[-1].version and
  ([.options[].name] | length == (unique | length)) and
  ([.options[] | select(.unavailableReason == "SECRET_STORAGE_REQUIRED")] | length == 5) and
  ([.options[] | select(.unavailableReason == "INCONSISTENT_VALUE_HANDLING")] | length == 1)
' "${matrix}" >/dev/null

if [[ ${mode} == --metadata-only ]]; then
  echo "WireMock option matrix metadata is valid."
  exit 0
fi

command -v docker >/dev/null || { echo "docker is required for live matrix verification" >&2; exit 1; }

version_at_least() {
  [[ $(printf '%s\n%s\n' "$1" "$2" | sort -V | head -1) == "$2" ]]
}

version_at_most() {
  [[ $(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -1) == "$2" ]]
}

expected_for_version() {
  local version=$1
  while IFS='|' read -r name minimum maximum unsupported advertised_when_unsupported; do
    [[ ${unsupported} == true ]] && continue
    minimum=${minimum:-3.0.0}
    version_at_least "${version}" "${minimum}" || continue
    if [[ -n ${maximum} ]] && ! version_at_most "${version}" "${maximum}" \
        && [[ ${advertised_when_unsupported} != true ]]; then
      continue
    fi
    printf '%s\n' "${name}"
  done < <(jq -r '.options[] | [.name, (.minimumVersion // ""), (.maximumVersion // ""), (.unsupported // false), (.advertisedWhenUnsupported // false)] | join("|")' "${matrix}")
}

while IFS='|' read -r version image_tag help_unavailable; do
  if [[ -z ${image_tag} ]]; then
    echo "WireMock ${version}: no official container image was published; tagged source evidence only"
    continue
  fi
  image="wiremock/wiremock:${image_tag}"
  help=$(docker run --rm "${image}" --help 2>&1) || {
    if [[ ${help_unavailable} == true ]]; then
      echo "${image}: reviewed upstream help-rendering defect; tagged source evidence only"
      continue
    fi
    echo "${image}: failed to render --help" >&2
    echo "${help}" | tail -20 >&2
    exit 1
  }
  expected=$(expected_for_version "${version}" | sort -u)
  actual_raw=$(printf '%s\n' "${help}" | grep -Eo -- '--[a-z0-9][a-z0-9-]*' | sort -u)
  actual=""
  while IFS= read -r token; do
    if [[ ${token} == *- ]]; then
      matches=$(printf '%s\n' "${expected}" | awk -v prefix="${token}" 'index($0, prefix) == 1')
      if [[ $(printf '%s\n' "${matches}" | grep -c .) -eq 1 ]]; then
        token=${matches}
      fi
    fi
    actual+="${token}"$'\n'
  done <<<"${actual_raw}"
  actual=$(printf '%s' "${actual}" | sort -u)
  if ! diff_output=$(diff -u <(printf '%s\n' "${expected}") <(printf '%s\n' "${actual}")); then
    echo "${image}: option drift" >&2
    printf '%s\n' "${diff_output}" >&2
    exit 1
  fi
  echo "${image}: option names match"
done < <(jq -r '.stableReleases[] | [.version, (.imageTag // ""), (.helpUnavailable // false)] | join("|")' "${matrix}")

echo "WireMock option matrix matches every researched stable 3.x image."
