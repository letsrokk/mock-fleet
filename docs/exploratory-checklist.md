# Contract-reset exploratory checklist

Use a disposable namespace and unique mock IDs. Record the build SHA, WireMock image, Minikube version, Kubernetes version, S3 CSI driver version, and SeaweedFS version with the result.

- [ ] The chart runs two ready API replicas, one MCP replica, and persistent S3 storage on SeaweedFS.
- [ ] A configuration saved through the API service is visible from each API pod's direct endpoint, including a newly restarted API replica.
- [ ] Invalid options return `ApiError` with `INVALID_OPTIONS`; `resourceVersion` and saved config remain unchanged.
- [ ] `GET /__fleet/api/config/options`, `list_option_definitions`, and the dashboard agree on `{wireMockVersion,catalogStatus,options}` for the pinned version. The dashboard displays one catalog-level warning only when `catalogStatus` is `newer_unresearched`.
- [ ] Options hidden for the pinned WireMock version are rejected through dashboard Advanced arguments, REST, and MCP without changing `resourceVersion` or saved configuration.
- [ ] Every name from MCP `tools/list` appears in the live coverage manifest. The 3.7.0 `get_body_file` and 3.13.0 `list_unmatched_stubs` boundaries agree between discovery and direct calls.
- [ ] A stale config write returns `CONFIG_CONFLICT` with exact `expectedVersion` and `currentVersion` details.
- [ ] First `start_mock` on a cold mock returns RUNNING or STARTING. When STARTING, a WireMock tool returns retryable `MOCK_STARTING`, sends no Admin traffic, and succeeds after polling.
- [ ] DELETE stops STARTING, RUNNING, and FAILED mocks. Repeated DELETE returns STOPPED.
- [ ] A terminal image-pull/container failure becomes FAILED promptly and includes pod/container diagnostics.
- [ ] `restartActive` returns STARTING for an active mock and never starts an already STOPPED or FAILED mock.
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
