# MCP contract and examples

Mock Fleet exposes stateful Streamable HTTP at `/__fleet/mcp`. Initialize a session, send `notifications/initialized`, then call tools with the returned `Mcp-Session-Id` and protocol version `2025-11-25`.

The server publishes 36 tools:

```text
list_mocks                 get_mock_config             list_option_definitions
update_mock_config         delete_mock_config          start_mock
stop_mock                  list_stubs                  list_unmatched_stubs
get_stub                   create_stub                 update_stub
delete_stub                persist_stub                unpersist_stub
send_request               find_requests               count_requests
list_unmatched_requests    get_near_misses             reset_request_journal
start_recording            get_recording_status        stop_recording
snapshot_requests          list_body_files             get_body_file
put_body_file              delete_body_file            list_scenarios
reset_scenarios             export_mock_configs         import_mock_configs
set_mock_pinned             patch_stub                  get_journal_summary
```

`get_recording_status` replaces `recording_status`; the old name is not registered. `start_mock` is explicit, and every WireMock Admin or traffic tool also performs the same start preflight. A RUNNING response continues. A STARTING response returns `isError: true` with `error.code=MOCK_STARTING`, `retryable=true`, and `details.retryAfterMs`; wait and call the tool again. Terminal startup errors keep their Fleet error code and diagnostics.

Every successful tool result has a tool-specific object in `structuredContent`. There is no common success envelope. Every failure has `isError: true` and this strict shape:

```json
{
  "error": {
    "code": "CONFIG_CONFLICT",
    "message": "WireMock config was modified by another writer.",
    "retryable": true,
    "stateMayHaveChanged": false,
    "details": {"expectedVersion": "42", "currentVersion": "43"}
  }
}
```

Each published `outputSchema` is `oneOf` its strict success object and this error object. Native WireMock inputs such as `mapping`, `requestPattern`, `recording`, and `snapshot` are JSON objects, not JSON strings and not `{ "map": ... }` wrappers. Enum-like tool arguments such as `applyMode` are strings in JSON-RPC and are parsed by the executor.

Every closed tool-input object rejects unknown properties. Native WireMock mapping and request-pattern objects stay open because WireMock owns those schemas. Missing arguments, wrong scalar or nested types, unknown closed-object properties, and Java binding failures all return the same structured `INVALID_ARGUMENT` envelope. Rejected argument values are never copied into an error.

## Collection pagination

`list_mocks`, `list_stubs`, `list_unmatched_stubs`, `find_requests`, `list_unmatched_requests`, `get_near_misses`, `list_body_files`, `list_scenarios`, and `get_journal_summary` accept `limit` and an optional opaque `cursor`. Do not decode or modify a cursor. A cursor is bound to its tool, mock, and canonical request pattern, so reuse with a different tool or filter returns `INVALID_ARGUMENT`.

Every collection returns `page` with the same metadata:

```json
{
  "limit": 50,
  "returned": 50,
  "hasMore": true,
  "nextCursor": "eyJ2ZXJzaW9uIjoxLC4uLn0"
}
```

`nextCursor` is a string only when `hasMore` is true and is otherwise null. Continue with that cursor until `hasMore` is false. WireMock collections do not provide snapshot isolation, so concurrent mutations between calls can cause duplicates or omissions. The MCP server streams large upstream collections, keeps only the requested page plus one item, and returns `RESULT_TOO_LARGE` if a configured byte or item scan budget is exhausted. `get_near_misses` returns `{mockId,nearMisses,page}`.

`list_mocks` reads one Fleet configuration snapshot and returns every configured inactive, configured active, and runtime-only mock, sorted by `mockId` before pagination. Each row is exactly `{mockId,lifecycle,wireMockVersion,runtimeVersion,hasSavedConfig}`. `wireMockVersion` is the desired version, `runtimeVersion` is null while no runtime is present, and `hasSavedConfig` reports membership in the snapshot's saved configuration IDs.

## Lifecycle

`start_mock` returns all lifecycle fields, including nullable fields:

```json
{
  "mockId": "orders",
  "status": "STARTING",
  "podName": "mock-fleet-orders-a1b2c",
  "message": null,
  "retryAfterMs": 1000
}
```

Poll `start_mock` after `retryAfterMs` until status is RUNNING. `stop_mock` is idempotent and waits for an existing pod to be removed before it returns all lifecycle fields with status exactly `STOPPED`, for example `{ "mockId": "orders", "status": "STOPPED", "podName": null, "message": null, "retryAfterMs": null }`. Already stopped or absent mocks return STOPPED directly. Pod deletion uses the longer `fleet.mcp.lifecycleTimeout` budget, which must exceed the Fleet API pod-creation timeout. If a mutating Fleet API request times out or loses its response, MCP reports `stateMayHaveChanged: true`; reconcile the lifecycle or config before retrying. The MCP wrapper rejects missing, malformed, or mismatched Fleet lifecycle responses as `INVALID_UPSTREAM_RESPONSE`.

## Configuration

`set_mock_pinned` takes `mockId` and a required boolean `pinned`, and returns `{mockId,pinned}`. Set `pinned` to `true` to keep the current running mock past its idle timeout, or `false` to resume cleanup using its existing last-access time. Pinning an inactive mock returns `MOCK_NOT_RUNNING` without starting it. Manual stop still works. Pin state is shared with other API members in Hazelcast; replacement pods and pods recovered after a complete loss of shared state start unpinned.

`export_mock_configs` exports saved overrides for all mocks, or one saved mock when `mockId` is supplied. It returns `{resourceVersion,mocks}`; save the `mocks` array directly as the same JSON file used by the Config tab. A requested mock without saved overrides returns `NOT_FOUND`. Export excludes inherited settings and unsaved edits, and retains existing sensitive-option redaction. Results are never silently truncated.

`import_mock_configs` accepts `resourceVersion` from the destination's current configuration and a `mocks` array. Matching IDs have their saved overrides replaced; missing IDs are created; other mocks are preserved. Optional `targetMockId` requires exactly one entry and applies it to that ID. Import validates the complete batch and writes once with optimistic concurrency. It affects future pods only, never restarts active mocks, and returns `{resourceVersion,importedMockIds}`. An empty array changes nothing. Existing option, resource, version, and password-option restrictions apply.

Example import arguments (replace `42` with the destination's current resource version):

```json
{
  "resourceVersion": "42",
  "mocks": [
    {"mockId": "orders", "version": null, "options": ["--verbose"], "resources": null}
  ]
}
```

The same array supports one mock or many. Each entry has `mockId`, nullable `version`, `options`, and nullable `resources`; non-null resources contain `requests` and `limits` maps. Null version/resources inherit destination defaults. The tool response wrapper is not part of the downloadable file.

`list_option_definitions` accepts an optional exact WireMock 3.x `version`. When omitted, it resolves the catalog default version. It forwards the Fleet API catalog unchanged as `{wireMockVersion,catalogStatus,options}`. The catalog contains only public options present in the selected version; hidden or sensitive options are rejected if submitted directly. `catalogStatus` is `supported` or `newer_unresearched`; it describes the catalog as a whole, not individual options.

The selected option version and the deployment version catalog are separate checks. `list_option_definitions` can inspect an exact future 3.x version and returns `newer_unresearched` above the researched maximum. `update_mock_config` can select only a catalog entry marked selectable, except that a mock can preserve its own existing retained baseline or user pin. A rejected pin returns the Fleet API's `UNSUPPORTED_WIREMOCK_VERSION`. Known options unavailable in the desired version return `UNSUPPORTED_WIREMOCK_OPTION` with exact `version` and `options` details. MCP preserves both errors in its standard error envelope without mutating configuration.

MCP always publishes the complete retained tool set. `get_body_file` requires WireMock 3.7.0, and `list_unmatched_stubs` requires WireMock 3.13.0. Direct calls gate these tools against the target mock's active runtime version, so desired/runtime drift fails with the tool name, required version, and actual runtime version. Legacy WireMock 3.0.x targets resolve their runtime through Fleet API after the Admin version endpoint is absent; an unknown runtime fails closed.

`update_mock_config` takes the complete mock-specific option override and an optional exact `wireMockVersion`; omit `wireMockVersion` to inherit the catalog default. Explicit `null` is not accepted because the published input schema requires a string when the field is present. Saved options replace inherited values by flag name; flags not already inherited are appended. For example, overriding `--max-request-journal-entries 20` with `--max-request-journal-entries 200` produces one effective flag with value 200. Journal entries retain request and response data in memory, so larger caps and large bodies increase heap usage. The Fleet API is authoritative for option tokenization and validation, including split, combined, quoted, and equals syntax; options hidden for the desired WireMock version, unknown options, duplicates, stray values, missing values, invalid numbers, and invalid select values fail without persisting a mutation.

```json
{
  "mockId": "orders",
  "resourceVersion": "42",
  "options": ["--verbose --filename-template '{{{method}}}-{{{url}}}.json'"],
  "resources": {"requests": {}, "limits": {}},
  "wireMockVersion": "3.12.1",
  "applyMode": "restartActive"
}
```

Omit `resources` to inherit baseline resources. Provide both `requests` and `limits`, including two empty maps, to replace inherited resources. `delete_mock_config` removes saved configuration overrides; stored stubs and body files survive and can reload when the same mock ID starts again. Update success is `{resourceVersion,mock,apply}`; delete success is `{resourceVersion,mockId,deleted,apply}`. `resourceVersion` is a string when the ConfigMap has one and can be null when the Fleet API legitimately has no version. Each `apply` is `{mockId,mode,lifecycle}`. `futureOnly` saves the desired configuration without replacing an active pod. `restartActive` replaces only a STARTING or RUNNING pod and is asynchronous, so `lifecycle` can be STARTING; it does not start a STOPPED or FAILED mock. Configuration rows use `lifecycle`, not `active`, expose baseline, user, and effective version fields, desired `wireMockVersion`, and nullable active `runtimeVersion`, and use null `user.resources` for inherited resources.

## Stub output control

`get_stub`, `list_stubs`, and `list_unmatched_stubs` omit inline response bodies by default. They retain request matchers and response metadata, including `bodyFileName`, and add `bodyBytes`. `create_stub`, `update_stub`, `persist_stub`, `unpersist_stub`, and `patch_stub` return a summary in `stub`: `{id,name,persistent,bodyBytes}`. An unnamed stub has `name: null`.

All eight tools accept optional `includeBody: true` to return the original full mapping. Full results do not add `bodyBytes`. Compact results count UTF-8 bytes for `body`, serialized JSON bytes for `jsonBody`, and decoded bytes for `base64Body`. `bodyBytes` is zero when no inline body exists, or null for a `bodyFileName` reference; the tools do not read body files to calculate their size. Response bodies are omitted only from results; stored mappings are unchanged.

Internal mapping transfers, including persistence recovery markers, use the bounded `maxCollectionScanBytes` budget (64 MiB by default). Client-supplied create/update mappings and patches and returned stub results remain subject to `maxPayloadBytes` (1 MiB by default). A compact result can therefore succeed when the full mapping exceeds the tool-output limit. Oversized results return `RESULT_TOO_LARGE`; request compact output or a smaller page. If a write succeeded before its result exceeded the limit, the error reports `stateMayHaveChanged: true`.

## Journal counts

`get_journal_summary` returns `{mockId,total,unmatched,requestJournalDisabled,stubs,page}`. `total` and `unmatched` cover the whole retained journal. Each current stub row is `{stubId,name,proxyStub,count}`; counts measure requests actually served by that stub ID, rather than requests that could match its current matcher. `proxyStub` describes whether the current mapping has a `proxyBaseUrl`.

Use optional `stubId` to inspect a specific proxy catch-all or other stub. Otherwise, `limit` and `cursor` paginate counts for current stubs. Stubs deleted since serving requests are absent from these rows, so row counts need not sum to `total - unmatched`. Disabled journals return `requestJournalDisabled: true`; their zero counts do not prove that no traffic occurred.

The tool requests count metadata with WireMock's `limit=0`, so journal request and response bodies are not transferred. Each page makes one lightweight count request per selected stub, plus total/unmatched count requests and the mapping lookup. Results sample a changing journal across several calls and are not a consistent snapshot or lifetime metrics. Eviction, resets, and entry removal can reduce counts. Use `count_requests` for counts by native request pattern.

## Partial stub updates

`update_stub` replaces a complete mapping. Use `patch_stub` to change a name, matcher, priority, or other mapping fields without sending the response body back through MCP:

```json
{"mockId":"orders","stubId":"d13b8bb8-95bb-4c44-b7d7-601baa333c1c","patch":{"name":"orders lookup","request":{"queryParameters":{"q":{"matches":"(?i)term"}}},"priority":null}}
```

Patches follow JSON Merge Patch semantics: omitted fields are preserved, nested objects are merged, `null` removes a field, and arrays are replaced as a whole. The patch must be an object and cannot contain `id`, `uuid`, or `persistent`; use `persist_stub` or `unpersist_stub` for persistence changes. Outbound policy checks apply to the complete merged mapping before writing it. Persistent patches reuse the recoverable update transaction, including retry reconciliation. Results use the same compact summary and optional `includeBody` as other stub writes. Updates are serialized with other MCP mutations for that mock; external Admin API writers are not covered by that lock.

## Encoded bodies

`send_request` and `put_body_file` accept bytes only through an encoded body object. `sizeBytes` describes the decoded byte length. Body files have no stored media-type metadata; set the serving media type in the response headers of the stub that references the file.

```json
{
  "mockId": "orders",
  "method": "POST",
  "path": "/orders",
  "headers": {"Content-Type": "application/json"},
  "body": {"encoding": "utf8", "data": "{\"id\":42}", "sizeBytes": 9}
}
```

Responses and `get_body_file` use `{body:{encoding,data,sizeBytes}}`. The server emits `utf8` only for strictly decoded printable UTF-8; otherwise it emits base64. `send_request` wraps traffic as `{mockId,response:{status,headers,contentType,body}}`. Sensitive response and journal headers are replaced with `[REDACTED]`.

With persistent S3 storage, `put_body_file` stops and starts the mock after a successful write so the replacement WireMock process reloads the shared persistent state. This replacement resets temporary stubs, the request journal, scenarios, and recording state; persistent stubs remain. The chart configures overwrite support and a minimal metadata TTL to reduce cross-mount staleness. S3 Mountpoint does not coordinate concurrent writes to the same key, so do not race MCP and dashboard mutations of one body file. The write result is successful once that restart has started. A following WireMock tool can temporarily return the normal retry-safe `MOCK_STARTING` error until the mock reaches RUNNING. Non-persistent mocks stay running after the write.

If the replacement lifecycle fails after the file was stored, the tool preserves the lifecycle error code and reports `stateMayHaveChanged: true`, `details.bodyFileStored: true`, and the affected `mockId` and `fileName`. Treat that result as a partial success: retrying the same write is safe for the file content, but it can replace the pod again. The published tool annotation therefore does not claim idempotence.

`delete_body_file` returns `{mockId,fileName,deleted,forced}`. The default reference check stops at the first mapping that uses the file and returns `BODY_FILE_REFERENCED` with its `stubId`. `force=true` skips the reference scan and deletes immediately.

## Persistence, recording, and analysis

Persistent stub mutations use recoverable transactions. A persistent update records before/after state, removes the old mapping, writes and verifies the replacement, then removes its recovery marker. A retry resumes the transaction. If the current mapping matches neither known state, the tool returns a conflict without overwriting it. An indeterminate replacement or rollback returns `PERSISTENT_UPDATE_INCOMPLETE` with reconciliation details and leaves the marker for recovery. Recovery markers never appear in stub lists or analysis.

`start_recording` forces `persist=false` and `outputFormat=IDS`, verifies the recorder status, and rejects disallowed targets before WireMock sees them. `stop_recording` and `snapshot_requests` return:

```json
{
  "mockId": "orders",
  "candidateIds": ["d13b8bb8-95bb-4c44-b7d7-601baa333c1c"],
  "candidateCount": 1,
  "matchedRequests": true,
  "scenarioStubs": [],
  "journal": {"retainedRequests": 20, "configuredLimit": 20, "possiblyTruncated": true},
  "warnings": ["The journal is at or above its configured limit; earlier requests may have been evicted. The running pod may use older settings, so this does not prove truncation."]
}
```

Set `recording.repeatsAsScenarios: false` in `start_recording`, or `snapshot.repeatsAsScenarios: false` in `snapshot_requests`, to ignore repeated requests instead of generating scenario steps. Native WireMock defaults are retained when this setting is omitted. Each `scenarioStubs` entry has `stubId`, `scenarioName`, nullable `requiredScenarioState`, and nullable `newScenarioState`. A warning explains that removing individual steps can break later requests; candidates are never automatically deduplicated or rewritten to remove scenarios.

Before finalization, MCP samples the retained journal count without event bodies and reads the desired effective journal cap from Fleet configuration. `possiblyTruncated: true` means that the sampled journal is at or above that cap; it is a warning, not proof of eviction. It is null otherwise, including when the cap or count is unavailable. Desired settings can differ from those used by the running pod, and earlier eviction, resets, or concurrent traffic can affect completeness even below the cap. Missing diagnostics produce a warning and do not prevent stopping or snapshotting. A disabled journal also produces a warning.

For intentional permanent deletion of a stub loaded from backing storage, call `persist_stub` and then `delete_stub`. WireMock deletes the saved mapping when the runtime mapping is persistent; deleting a runtime-only loaded copy can leave its source to reload on restart. `unpersist_stub` removes the backing mapping when transitioning a persistent stub to temporary, but is a no-op when it is already temporary. Referenced body files are retained by all of these stub operations.

A zero-match operation returns an empty ID array, `candidateCount: 0`, and `matchedRequests: false`. Use `find_requests`, `count_requests`, `list_unmatched_requests`, `list_unmatched_stubs`, and `get_near_misses` for matched/missed analysis. `send_request` rejects every WireMock Admin path, including encoded or ambiguous variants. Recorder, proxy, webhook, and mapping targets reject private, loopback, link-local, multicast, metadata, and special-use destinations unless both the hostname exception and connection-time CIDR policy allow them.

## Health and provenance

`/__fleet/mcp/health/live` and `/started` report only the MCP process. `/__fleet/mcp/health/ready` also requires the Fleet API and Fleet Proxy readiness endpoints to return HTTP 200 with `status: UP`; each dependency check uses `dependencyHealthTimeout` independently. A dependency outage removes MCP from service without causing Kubernetes to restart it.

`GET /__fleet/mcp/version` returns `{component,version,revision,buildTime}`. `revision` is the full Git commit ID and `buildTime` is an ISO-8601 timestamp embedded in the artifact. Packaging fails if the revision metadata is absent or malformed.
