#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd "${script_dir}/../.." && pwd)
expected_quarkus_version=3.33.3.1
expected_hazelcast_version=5.7.0
failed=0

fail() {
  printf '[dependency-contracts] ERROR: %s\n' "$*" >&2
  failed=1
}

xml_property_value() {
  local file=$1
  local property=$2

  awk -v property="${property}" '
    {
      opening_tag = "<" property ">"
      closing_tag = "</" property ">"
      start = index($0, opening_tag)
      if (start == 0) {
        next
      }
      value = substr($0, start + length(opening_tag))
      end = index(value, closing_tag)
      if (end == 0) {
        next
      }
      print substr(value, 1, end - 1)
      exit
    }
  ' "${file}"
}

assert_quarkus_platform_version() {
  local pom=$1
  local actual

  actual=$(xml_property_value "${pom}" quarkus.platform.version)
  if [[ "${actual}" != "${expected_quarkus_version}" ]]; then
    fail "${pom#"${repo_root}/"} quarkus.platform.version must be ${expected_quarkus_version}, found ${actual:-missing}"
  fi
}

hazelcast_version() {
  local pom=$1

  awk '
    /<dependency>/ {
      in_dependency = 1
      is_hazelcast_group = 0
      is_hazelcast_artifact = 0
      version = ""
    }
    in_dependency && /<groupId>com\.hazelcast<\/groupId>/ {
      is_hazelcast_group = 1
    }
    in_dependency && /<artifactId>hazelcast<\/artifactId>/ {
      is_hazelcast_artifact = 1
    }
    in_dependency && /<version>/ {
      value = $0
      sub(/^.*<version>/, "", value)
      sub(/<\/version>.*$/, "", value)
      version = value
    }
    /<\/dependency>/ {
      if (is_hazelcast_group && is_hazelcast_artifact) {
        print version
        exit
      }
      in_dependency = 0
      is_hazelcast_group = 0
      is_hazelcast_artifact = 0
      version = ""
    }
  ' "${pom}"
}

assert_hazelcast_version() {
  local actual

  actual=$(hazelcast_version "${repo_root}/fleet-api/pom.xml")
  if [[ "${actual}" != "${expected_hazelcast_version}" ]]; then
    fail "fleet-api/pom.xml Hazelcast must be ${expected_hazelcast_version}, found ${actual:-missing}"
  fi
}

version_at_least() {
  local actual=$1
  local minimum=$2
  local -a actual_parts minimum_parts
  local index actual_part minimum_part

  [[ "${actual}" =~ ^[0-9]+(\.[0-9]+)*$ ]] || return 1
  IFS=. read -r -a actual_parts <<<"${actual}"
  IFS=. read -r -a minimum_parts <<<"${minimum}"

  for index in 0 1 2 3; do
    actual_part=${actual_parts[index]:-0}
    minimum_part=${minimum_parts[index]:-0}
    if ((10#${actual_part} > 10#${minimum_part})); then
      return 0
    fi
    if ((10#${actual_part} < 10#${minimum_part})); then
      return 1
    fi
  done
}

assert_runtime_dependency_tree() {
  local module=$1
  local maven_wrapper=$2
  local dependency_tree line artifact version
  local found_rest=0
  local found_hazelcast=0

  if ! dependency_tree=$("${repo_root}/${maven_wrapper}" -B -f "${repo_root}/${module}/pom.xml" \
    dependency:tree -Dscope=runtime -Dincludes='io.quarkus:quarkus-rest*,com.hazelcast:hazelcast' 2>&1); then
    fail "${module} runtime dependency tree could not be resolved"
    return
  fi

  while IFS= read -r line; do
    if [[ "${line}" =~ io\.quarkus:(quarkus-rest[^:]*):[^:]+:([^:]+): ]]; then
      artifact=${BASH_REMATCH[1]}
      version=${BASH_REMATCH[2]}
      found_rest=1
      if ! version_at_least "${version}" "${expected_quarkus_version}"; then
        fail "${module} runtime dependency ${artifact}:${version} is older than ${expected_quarkus_version}"
      fi
    fi

    if [[ "${line}" =~ com\.hazelcast:hazelcast:[^:]+:([^:]+): ]]; then
      version=${BASH_REMATCH[1]}
      found_hazelcast=1
      if ! version_at_least "${version}" "${expected_hazelcast_version}"; then
        fail "${module} runtime dependency hazelcast:${version} is older than ${expected_hazelcast_version}"
      fi
    fi
  done <<<"${dependency_tree}"

  if ((found_rest == 0)); then
    fail "${module} runtime dependency tree does not contain a Quarkus REST artifact"
  fi
  if [[ "${module}" == fleet-api && ${found_hazelcast} -eq 0 ]]; then
    fail "fleet-api runtime dependency tree does not contain Hazelcast"
  fi
}

run_self_test() {
  local fixture="${script_dir}/fixtures/dependency-contracts/hazelcast-selector.xml"
  local actual

  actual=$(hazelcast_version "${fixture}")
  if [[ "${actual}" != "5.8.0" ]]; then
    printf '[dependency-contracts] ERROR: Hazelcast selector must return core hazelcast 5.8.0, found %s\n' \
      "${actual:-missing}" >&2
    return 1
  fi

  printf '[dependency-contracts] Hazelcast selector regression test passed.\n'
}

if [[ "${1:-}" == "--self-test" ]]; then
  run_self_test
  exit $?
fi

assert_quarkus_platform_version "${repo_root}/fleet-api/pom.xml"
assert_quarkus_platform_version "${repo_root}/fleet-proxy/pom.xml"
assert_quarkus_platform_version "${repo_root}/fleet-mcp/pom.xml"
assert_hazelcast_version

if ((failed != 0)); then
  exit 1
fi

assert_runtime_dependency_tree fleet-api fleet-api/mvnw
assert_runtime_dependency_tree fleet-proxy fleet-proxy/mvnw
assert_runtime_dependency_tree fleet-mcp fleet-api/mvnw

if ((failed != 0)); then
  exit 1
fi

printf '[dependency-contracts] Runtime dependency contracts passed.\n'
