# Contract-reset exploratory checklist

Use a disposable namespace and unique mock IDs. Record the build SHA, WireMock image, Minikube version, Kubernetes version, S3 CSI driver version, and SeaweedFS version with the result.

- [ ] The chart runs two ready API replicas, one MCP replica, and persistent S3 storage on SeaweedFS.
- [ ] A configuration saved through the API service is visible from each API pod's direct endpoint, including a newly restarted API replica.
- [ ] Invalid options return `ApiError` with `INVALID_OPTIONS`; `resourceVersion` and saved config remain unchanged.
- [ ] `GET /__fleet/api/config` reports the expected `defaultVersion`, `catalogResourceVersion`, and both selectable and retained `{version,image,selectable}` entries. A mock without a pin inherits the default; an exact pin remains unchanged when the default advances.
- [ ] Start two mocks pinned to two different selectable versions. Each config row and active-mock row reports the expected desired `wireMockVersion` and `runtimeVersion`, and each Pod image matches its catalog entry.
- [ ] `GET /__fleet/api/config/options?version=<selected>`, `list_option_definitions(version=<selected>)`, and the dashboard agree on `{wireMockVersion,catalogStatus,options}` for each live version. An exact version above 3.13.2 returns `newer_unresearched`; the dashboard displays one catalog-level warning.
- [ ] A new retained-version selection returns `UNSUPPORTED_WIREMOCK_VERSION`, while the mock that already references that retained version can save it again. An absent or malformed per-mock version also leaves `resourceVersion` and saved configuration unchanged.
- [ ] Options hidden for the desired WireMock version are rejected through dashboard Advanced arguments, REST, and MCP with `UNSUPPORTED_WIREMOCK_OPTION`, exact `version` and `options` details, and no change to `resourceVersion` or saved configuration.
- [ ] Every name from MCP `tools/list` appears in the live coverage manifest. The 3.7.0 `get_body_file` and 3.13.0 `list_unmatched_stubs` boundaries agree between discovery and direct calls.
- [ ] A stale config write returns `CONFIG_CONFLICT` with exact `expectedVersion` and `currentVersion` details.
- [ ] First `start_mock` on a cold mock returns RUNNING or STARTING. When STARTING, a WireMock tool returns retryable `MOCK_STARTING`, sends no Admin traffic, and succeeds after polling.
- [ ] DELETE stops STARTING, RUNNING, and FAILED mocks. Repeated DELETE returns STOPPED.
- [ ] A terminal image-pull/container failure becomes FAILED promptly and includes pod/container diagnostics.
- [ ] `futureOnly` changes an active mock's desired version without replacing its Pod; `runtimeVersion` stays old. `restartActive` returns STARTING for an active mock, converges desired and runtime versions, and never starts an already STOPPED or FAILED mock.
- [ ] Admission accepts managed Pods whose image occurs under `selectable.*` or `retained.*` in the named catalog parameter. It denies an unlisted image and denies creation when the parameter ConfigMap is missing or unavailable.
- [ ] With `mockOps.enabled=true`, a controlled fake Registry V2 endpoint advertises at least two stable minor lines and an optional newer image revision. One Job advances the constrained default without a downgrade, keeps the configured number of minor lines selectable, and retains only exact versions referenced by baseline or user configuration.
- [ ] The fake registry exercises pagination and, when credentials are configured, Basic plus Bearer-challenge authentication. A malformed registry response, malformed configuration, or missing referenced catalog version fails the Job without changing catalog data or `resourceVersion`.
- [ ] Modify the catalog between Fleet Mock Ops's read and update. The stale update fails with a Kubernetes `resourceVersion` conflict, does not overwrite the concurrent change, and does not retry in the same Job.
- [ ] A Fleet Mock Ops reconciliation does not replace either live WireMock Pod. Existing mocks keep their runtime versions until an explicit lifecycle action applies a saved desired version.
- [ ] A persistent stub can be updated, the mock can be stopped and restarted, and the updated response still matches.
- [ ] Persistent mutation recovery markers never appear in lists, requests, snapshots, or analysis.
- [ ] UTF-8 and binary body files round-trip with correct encoding, data, and decoded `sizeBytes`.
- [ ] Recording start reports active status. Stop and snapshot return candidate IDs/count plus an explicit zero-match result.
- [ ] Matched counts, unmatched requests/stubs, and near misses distinguish the expected requests.
- [ ] Authorization and configured sensitive headers are `[REDACTED]` in traffic, journal, recorder, and analysis results.
- [ ] Private/loopback/metadata recorder and mapping targets return a structured SSRF-policy error.
- [ ] `send_request` rejects literal, encoded, and slash-normalized `/__admin` paths.
- [ ] Every error has `{error:{code,message,retryable,stateMayHaveChanged,details}}` and `isError: true`.
- [ ] Cleanup removes the namespace, namespaced test data, cluster-scoped PV, port-forward process, and temporary files. A second cleanup and a full rerun both succeed.

For a retained acceptance instance, run `bin/cluster-e2e.sh --retain` (or set `MOCK_FLEET_E2E_RETAIN=true`). After success, confirm the printed namespace contains saved configurations, active and stopped mocks, persistent and temporary stubs, retained body files, scenario state, journal traffic, and a persisted recording candidate. Delete the namespace, release, PV, and owned S3 bucket manually after inspection.

The automated opt-in suite covers these paths where the cluster can make them deterministic. Treat timing-sensitive FAILED/STARTING observations as polling assertions with bounded timeouts, not sleeps. Do not mark unavailable checks as passed; record the prerequisite or environment gap.
