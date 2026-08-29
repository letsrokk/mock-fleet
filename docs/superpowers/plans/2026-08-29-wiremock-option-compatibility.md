# WireMock 3.x Option Compatibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the static WireMock option catalog with a researched 3.x compatibility matrix shared by Fleet API, the dashboard, and MCP, and preserve the successful MCP/Admin API live-test behavior as regression coverage.

**Architecture:** Fleet API loads and validates one checked-in matrix, resolves it against the globally pinned WireMock image, and publishes the resolved catalog. Dashboard and MCP consume that response; MCP's separate Admin API capability registry remains responsible for tool discovery and runtime gates. Compatibility warnings are advisory, while direct credential-bearing options remain unavailable and fail closed.

**Tech Stack:** Java 21, Quarkus, Jackson, JUnit 5, TypeScript/React/Vitest, MCP Java SDK, Bash, Docker, Helm, Kubernetes/Minikube.

**Spec:** `docs/superpowers/specs/2026-08-29-wiremock-option-compatibility-design.md`

## Global Constraints

- Support begins at WireMock `3.0.0`; the initial researched maximum is `3.13.2`.
- Allow exact future 3.x pins, mark their compatibility `unknown`, and continue rejecting unknown option names.
- Compatibility warnings never block persistence or mock startup.
- `--ca-keystore-password`, `--keystore-password`, `--key-manager-password`, `--truststore-password`, and `--admin-api-basic-auth` are visible but unavailable with reason `SECRET_STORAGE_REQUIRED`.
- Do not expose prohibited values through ConfigMaps, API/MCP output, logs, errors, or pod arguments.
- Keep `--proxy-via` available under the existing security policy.
- Fleet API is the only option-matrix authority; MCP must not duplicate it.
- Use WireMock triple-brace filename templates, such as `{{{method}}}-{{{url}}}.json`.

---

### Task 1: Build the upstream compatibility evidence

**Files:**
- Create: `fleet-api/src/main/resources/wiremock-option-compatibility.json`
- Create: `fleet-api/tests/verify-wiremock-option-matrix.sh`
- Create: `docs/wiremock-option-compatibility.md`
- Modify: `.github/workflows/build.yml`

**Interfaces:**
- Produces: a JSON matrix with `minimumSupportedVersion`, `maximumResearchedVersion`, and `options`.
- Produces: option variants with inclusive `minimumVersion`, nullable inclusive `maximumVersion`, `kind`, `values`, `minimum`, `maximum`, and optional `knownIssue`.
- Produces: reproducible evidence for every stable WireMock 3.x release.

- [x] **Step 1: Inventory stable releases and tagged option definitions**

  Enumerate stable tags from `3.0.0` through `3.13.2`. For each tag, capture `CommandLineOptions` parser declarations, standalone documentation, release notes, and container `--help`. Ignore beta and release-candidate tags.

- [x] **Step 2: Write the matrix schema and initial catalog**

  Store each option once with its presentation metadata and security availability. Store argument or support changes as ordered version variants rather than copying a full catalog per release. Encode the five unavailable direct-credential options and the four known 3.13.2 absences from the spec.

- [x] **Step 3: Add the verification script**

  Make the script enumerate the matrix's release list, run each official image's `--help`, and compare discovered option names with the expected set for that version. For safe options, verify parser arity with a bounded startup smoke test. Record declared exceptions for options that require external files, extensions, credentials, or intentionally reproduce a known upstream defect.

- [x] **Step 4: Prove the live findings in fixtures**

  Assert that `--timeout 10000` is not considered a valid 3.13.2 invocation, the four absent options fail parser discovery, and `--trust-all-proxy-targets` is classified `known_broken` for 3.13.2. Assert the documentation template is exactly `{{{method}}}-{{{url}}}.json`.

- [x] **Step 5: Add CI drift detection**

  Run the matrix verifier in the Fleet API job. Cache pulled WireMock layers where the CI platform supports it, print a version/option diff on failure, and require a reviewed matrix change when the supported bounds move.

- [x] **Step 6: Document the update procedure**

  Document how to advance the researched maximum: inspect every intervening stable tag, update variants and known issues, run the verifier and component suites, then update the support bounds and release notes together.

- [x] **Step 7: Verify Task 1**

  Run `fleet-api/tests/verify-wiremock-option-matrix.sh`. Expect every stable release through 3.13.2 to match or have an explicit reviewed exception.

### Task 2: Load and resolve the matrix in Fleet API

**Files:**
- Create: `fleet-api/src/main/java/com/github/letsrokk/WireMockVersion.java`
- Create: `fleet-api/src/main/java/com/github/letsrokk/WireMockOptionMatrix.java`
- Modify: `fleet-api/src/main/java/com/github/letsrokk/WireMockOptionCatalog.java`
- Test: `fleet-api/src/test/java/com/github/letsrokk/WireMockOptionMatrixTest.java`

**Interfaces:**
- Produces: `WireMockVersion.parseImage(String)` for exact 3.x tags with optional Docker revision suffixes.
- Produces: `WireMockOptionMatrix.resolve(WireMockVersion)` returning version metadata and all resolved option definitions.
- Produces compatibility values `supported`, `unsupported`, `known_broken`, and `unknown`.

- [x] **Step 1: Write failing version-parser tests**

  Cover `wiremock/wiremock:3.0.0`, `wiremock/wiremock:3.13.2-2`, and a future `3.14.0` tag. Reject mutable tags, missing tags, 2.x, 4.x, prereleases, and malformed numeric components.

- [x] **Step 2: Implement exact image parsing**

  Parse the WireMock semantic version separately from the Docker image revision. Keep the accepted syntax aligned with the chart's pinned-image validation.

- [x] **Step 3: Write failing matrix-integrity tests**

  Reject duplicate option names, unknown kinds, invalid bounds, empty version ranges, overlapping variants, missing control metadata, unavailable entries without a reason, and a matrix whose declared bounds do not cover its release inventory.

- [x] **Step 4: Implement immutable matrix loading**

  Load and validate the JSON resource once during API startup. Fail startup for a malformed application-owned matrix, because that is a Mock Fleet packaging defect rather than a user configuration error.

- [x] **Step 5: Write failing resolution tests**

  Cover the minimum, both sides of every option change point, 3.13.2, and 3.14.0. At a researched version, select the exact variant. When the option is absent, return its nearest known control shape with `unsupported`. Above 3.13.2, return the latest shape with `unknown`.

- [x] **Step 6: Implement catalog resolution**

  Resolve every entry in stable group/name order. Keep security availability independent of compatibility so secret entries remain disabled even when their version range otherwise matches.

- [x] **Step 7: Verify Task 2**

  Run `cd fleet-api && ./mvnw -B -Dtest=WireMockOptionMatrixTest test`. Expect all parser, integrity, and boundary cases to pass.

### Task 3: Apply matrix-aware validation without compatibility blocking

**Files:**
- Modify: `fleet-api/src/main/java/com/github/letsrokk/WireMockOptionCatalog.java`
- Modify: `fleet-api/src/main/java/com/github/letsrokk/WireMockOptions.java`
- Modify: `fleet-api/src/main/java/com/github/letsrokk/WireMockConfigService.java`
- Test: `fleet-api/src/test/java/com/github/letsrokk/WireMockOptionValidationTest.java`
- Test: `fleet-api/src/test/java/com/github/letsrokk/WireMockOptionsTest.java`

**Interfaces:**
- Consumes: the resolved matrix and pinned `WireMockVersion` from Task 2.
- Produces: normalized argument lists for any catalogued, available option regardless of compatibility status.
- Preserves: `INVALID_OPTIONS` for unknown, malformed, duplicate, or unavailable options.

- [x] **Step 1: Add failing advisory-compatibility tests**

  Prove that an option classified `unsupported`, `known_broken`, or `unknown` still normalizes, persists, and reaches generated pod arguments. Prove Fleet does not pre-emptively block restart/start for these statuses.

- [x] **Step 2: Add failing shape-resolution tests**

  Prove supported versions use their exact variant shape. For an unsupported version, use the nearest known variant shape; for a future version, use the latest researched shape. Cover flags, input values, numbers, selects, inline `--name=value`, and split arguments.

- [x] **Step 3: Add failing secret and unknown-name tests**

  Cover all five unavailable options in inline and split forms. Assert error details include only the option name. Preserve legacy redaction tests and prove no persistence or pod creation occurs. Continue rejecting names absent from the matrix on researched and future pins.

- [x] **Step 4: Replace hardcoded validation with resolved metadata**

  Route baseline, user, and effective option normalization through the same pinned-version resolver. Remove the static definition list and keep tokenization/redaction behavior in the catalog facade where existing callers rely on it.

- [x] **Step 5: Fix the identified option shapes**

  Encode `--timeout` according to its actual version variants. Do not represent the 3.13.2 behavior as a numeric input. Preserve the four absent options and `--trust-all-proxy-targets` in the catalog with advisory compatibility metadata.

- [x] **Step 6: Verify Task 3**

  Run `cd fleet-api && ./mvnw -B -Dtest=WireMockOptionValidationTest,WireMockOptionsTest test`. Expect advisory compatibility to pass through and secret/unknown inputs to fail closed.

### Task 4: Publish the version-aware API contract

**Files:**
- Modify: `fleet-api/src/main/java/com/github/letsrokk/WireMockConfigService.java`
- Modify: `fleet-api/src/main/resources/META-INF/openapi.yaml`
- Test: `fleet-api/src/test/java/com/github/letsrokk/WireMockConfigResourceTest.java`

**Interfaces:**
- Produces: `wireMock` metadata containing `configuredImage`, `version`, `minimumSupportedVersion`, `maximumResearchedVersion`, and `rangeStatus`.
- Extends option definitions with `available`, `unavailableReason`, `compatibility`, `compatibilityMessage`, and `versionRanges`.
- Keeps existing option fields and mutation request shapes backward compatible.

- [x] **Step 1: Add failing API serialization tests**

  Assert the config response includes the exact pinned version metadata and every matrix option. Cover an in-range pin and a future 3.x pin. Assert secret entries are present but unavailable.

- [x] **Step 2: Add failing OpenAPI contract assertions**

  Require the new version object, compatibility enums, nullable reason/message fields, and inclusive range objects. Keep all existing required option properties.

- [x] **Step 3: Extend `ConfigView` and option DTOs**

  Add the fields without renaming or removing existing JSON properties. Return deterministic ordering so dashboard and MCP snapshots remain stable.

- [x] **Step 4: Update the checked-in OpenAPI document**

  Document that compatibility is advisory and `available=false` is enforced. Include examples for supported, unsupported, known-broken, unknown, and secret-unavailable entries.

- [x] **Step 5: Verify Task 4**

  Run `cd fleet-api && ./mvnw -B -Dtest=WireMockConfigResourceTest test`. Then run the full Fleet API package build and expect the OpenAPI consistency checks to pass.

### Task 5: Render compatibility and security state in the dashboard

**Files:**
- Modify: `fleet-dash/src/configOptions.ts`
- Modify: `fleet-dash/src/App.tsx`
- Modify: `fleet-dash/src/configOptions.test.ts`
- Modify: dashboard component tests colocated with `App.tsx`

**Interfaces:**
- Consumes: the additive Fleet API fields from Task 4.
- Produces: warning state for `unsupported`, `known_broken`, and `unknown`; disabled state for `available=false`.

- [x] **Step 1: Add failing type and helper tests**

  Extend `OptionDefinition` and config response types. Add helpers that distinguish advisory warnings from hard unavailability and return the API-provided explanation without recreating version logic in TypeScript.

- [x] **Step 2: Add failing rendering tests**

  Assert every option appears. Assert `(!)` plus accessible text and tooltip for unsupported, known-broken, and unknown options. Assert warned controls remain editable. Assert secret controls are disabled and explain that Secret-backed storage is required.

- [x] **Step 3: Render pinned-version context**

  Show the resolved WireMock version and researched range near the option catalog. For a future 3.x pin, show one catalog-level warning in addition to per-option unknown indicators.

- [x] **Step 4: Preserve configured warned values**

  Ensure `draftFromConfig` and `optionsFromDraft` round-trip warned options instead of moving them to an untyped or dropped state. Keep unavailable legacy values redacted and non-submittable.

- [x] **Step 5: Correct filename-template examples**

  Replace double-brace/static-prefix examples with `{{{method}}}-{{{url}}}.json` and add a regression assertion for the literal value.

- [x] **Step 6: Verify Task 5**

  Run `cd fleet-dash && npm test && npm run build`. Expect all accessibility, round-trip, and production-build checks to pass.

### Task 6: Extend the MCP option contract and version gates

**Files:**
- Modify: `fleet-mcp/src/main/java/com/github/letsrokk/mcp/FleetMcpTools.java`
- Modify: `fleet-mcp/src/main/java/com/github/letsrokk/mcp/OutputSchemas.java`
- Modify: `fleet-mcp/src/main/java/com/github/letsrokk/mcp/ToolCapabilityRegistry.java` only if upstream evidence changes a boundary
- Test: `fleet-mcp/src/test/java/com/github/letsrokk/mcp/McpConfigContractTest.java`
- Test: `fleet-mcp/src/test/java/com/github/letsrokk/mcp/VersionFilteredDiscoveryTest.java`
- Test: `fleet-mcp/src/test/java/com/github/letsrokk/mcp/ToolCapabilityRegistryTest.java`

**Interfaces:**
- Consumes: Fleet API's `wireMock` and `options` objects verbatim.
- Produces: `list_option_definitions` structured output with the same metadata and ordering.
- Preserves: discovery and runtime gates for Admin API tools.

- [x] **Step 1: Add a failing MCP catalog contract test**

  Stub a Fleet API response containing all compatibility states and a secret-unavailable entry. Assert MCP returns the version metadata and option array unchanged, including nullable fields and version ranges.

- [x] **Step 2: Update the stable MCP output schema**

  Extend `ListOptionDefinitions` without defining a second matrix or reclassifying entries. Update the tool description from “supported options” to “known WireMock 3.x options with compatibility and availability metadata.”

- [x] **Step 3: Lock capability boundaries**

  Keep `get_body_file` hidden and rejected below 3.7.0 and available at 3.7.0+. Keep `list_unmatched_stubs` hidden and rejected below 3.13.0 and available at 3.13.0+. Assert other registered Admin tools retain the 3.0.0 baseline unless the upstream evidence says otherwise.

- [x] **Step 4: Cover configured/runtime disagreement**

  Preserve discovery filtering by configured version and runtime rejection by the version reported from the active mock. Assert the error identifies the tool and minimum version without exposing internal URLs or response bodies.

- [x] **Step 5: Verify Task 6**

  Run `cd fleet-mcp && ../fleet-api/mvnw -B -Dtest=McpConfigContractTest,VersionFilteredDiscoveryTest,ToolCapabilityRegistryTest test`. Expect catalog passthrough and both version gates to pass.

### Task 7: Turn Admin API behavior into a release-boundary contract suite

**Files:**
- Modify: `fleet-mcp/tests/wiremock-admin-contract.sh`
- Modify: `.github/workflows/build.yml`
- Modify: `docs/mcp-contract.md`

**Interfaces:**
- Exercises: the upstream endpoints used by every MCP Admin API tool family.
- Produces: explicit per-version expectations consumed by the MCP capability tests.

- [x] **Step 1: Convert the two-image script to a version table**

  Keep 3.0.x and 3.13.2 coverage and add release change points discovered during the matrix survey, including 3.7.0 and 3.13.0. Each row declares expected support for version reporting, body-file reads, and unmatched-stub queries.

- [x] **Step 2: Cover stub lifecycle and persistence**

  Create, read, update, list, and delete a temporary stub. Persist it, restart the container on the same volume, and verify it survives. Unpersist it, restart again, and verify it no longer survives. Verify referenced body files are not deleted with a stub.

- [x] **Step 3: Cover traffic and request-journal operations**

  Send matched and unmatched requests; find and count requests; list unmatched requests; calculate near misses; list unmatched stubs where supported; reset the journal and verify it is empty.

- [x] **Step 4: Cover recording and snapshot persistence**

  Record from an isolated target with `persist=false` and `outputFormat=IDS`, stop recording, inspect candidate IDs, persist one candidate, restart WireMock, and verify the mapping still serves traffic. Exercise request snapshots with the same temporary-candidate semantics.

- [x] **Step 5: Cover body files and scenarios**

  Put, list, read where supported, reference, and delete body files, including the referenced-file conflict. Create a stateful scenario, advance it through traffic, list state, reset scenarios, and verify the initial state returns.

- [x] **Step 6: Keep failures diagnostic and bounded**

  Report the image, phase, HTTP status, and sanitized response excerpt. Use bounded readiness polling and deterministic cleanup. Never print generated credentials, authorization headers, cookies, or credential-bearing option values.

- [x] **Step 7: Verify Task 7**

  Run `fleet-mcp/tests/wiremock-admin-contract.sh`. Expect all declared capabilities and lifecycle assertions to pass for each version row.

### Task 8: Exercise every MCP tool family on Minikube

**Files:**
- Modify: `bin/cluster-e2e.sh`
- Modify: `docs/exploratory-checklist.md`
- Modify: `docs/mcp-contract.md`
- Modify: `README.md`

**Interfaces:**
- Exercises: the registered MCP surface through the deployed MCP endpoint, Fleet API, Fleet Proxy, WireMock pods, and persistent mapping/body-file storage.
- Produces: a repeatable automated run plus a final retained “actively used” local deployment.

- [x] **Step 1: Add a registered-tool coverage assertion**

  Compare MCP `tools/list` with the test manifest. Fail when a registered tool has no scenario, except a version-gated tool that has an explicit boundary test. This prevents newly added tools from silently escaping live coverage.

- [x] **Step 2: Cover Fleet configuration and lifecycle tools**

  Exercise `list_mocks`, `list_mock_configs`, `get_mock_config`, `list_option_definitions`, `update_mock_config`, `delete_mock_config`, `start_mock`, and `stop_mock`. Assert optimistic concurrency, apply modes, pinned-version metadata, all compatibility states, and hard-disabled secret entries.

- [x] **Step 3: Cover stub, traffic, journal, recorder, body-file, and scenario tools**

  Invoke every tool in those families through MCP rather than directly through WireMock. Verify structured schemas, sanitization, pagination, idempotence, destructive annotations, and expected side effects.

- [x] **Step 4: Add the full persistence cycle**

  Create a temporary stub and a recorded candidate, persist both, write referenced body content, restart the mock, and verify all persistent resources survive and serve traffic. Verify temporary stubs, journal entries, scenario state, and active recording state reset as documented.

- [x] **Step 5: Exercise capability boundaries**

  Deploy representative pinned versions at 3.0.x, 3.7.x, pre-3.13, and 3.13.2. Assert `tools/list` and direct calls agree for `get_body_file` and `list_unmatched_stubs`. For a synthetic future 3.x catalog test, assert known options report `unknown` while unknown names remain rejected.

- [x] **Step 6: Add the retained local acceptance profile**

  Provide a documented opt-in mode that skips cleanup after success. Seed multiple saved configurations, active and stopped mocks, temporary and persistent stubs, body files, scenario state, journal traffic, recorded mappings, and at least one warned option. Print the namespace, release, mock IDs, and inspection URLs without secrets.

- [x] **Step 7: Update operator and MCP documentation**

  Document advisory compatibility, future-version behavior, the secret-storage deferral, Admin API capability gates, recording/persistence semantics, and how to run or inspect the retained Minikube profile.

- [x] **Step 8: Run final verification**

  Run Fleet API and MCP Maven packages, dashboard tests/build, Helm lint/render tests, the Docker Admin API contract, and the Minikube end-to-end suite. Finish with the retained acceptance mode and verify its resources through both MCP and the dashboard.

### Task 9: Final review and release evidence

**Files:**
- Modify: release notes or upgrade documentation used by the repository
- Review: all files changed by Tasks 1-8

**Interfaces:**
- Produces: reviewable evidence that the matrix, consumers, MCP behavior, and retained deployment satisfy the design.

- [x] **Step 1: Audit requirement coverage**

  Map every design requirement and each original live finding to an automated assertion or documented manual acceptance check. Treat missing version, security, or persistence evidence as blocking.

- [x] **Step 2: Run the complete clean verification set**

  Re-run all component builds and tests from clean build outputs. Capture the tested WireMock images and Minikube/Kubernetes versions in the release evidence.

- [x] **Step 3: Review public compatibility**

  Confirm the REST and MCP changes are additive, old dashboard clients tolerate the new fields, existing saved configurations remain readable, and warned options are never silently removed.

- [x] **Step 4: Review secret non-disclosure**

  Search test output, ConfigMaps, API/MCP responses, pod specs, events, and logs for seeded secret markers. Confirm all five unavailable direct-credential options fail before persistence or pod creation.

- [x] **Step 5: Record the retained Minikube state**

  Report the surviving release and mock identifiers, counts of saved configurations, active mocks, persistent stubs, recordings, body files, and scenarios. Leave those resources running for user inspection.
