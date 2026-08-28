# MCP contract and examples

Mock Fleet exposes stateful Streamable HTTP at `/__fleet/mcp`. Initialize a session, send `notifications/initialized`, then call tools with the returned `Mcp-Session-Id` and protocol version `2025-11-25`.

The server publishes 32 tools:

```text
list_mocks                 list_mock_configs          get_mock_config
list_option_definitions    update_mock_config         delete_mock_config
start_mock                 stop_mock                  list_stubs
list_unmatched_stubs       get_stub                   create_stub
update_stub                delete_stub                persist_stub
unpersist_stub             send_request               find_requests
count_requests             list_unmatched_requests    get_near_misses
reset_request_journal      start_recording            get_recording_status
stop_recording             snapshot_requests          list_body_files
get_body_file              put_body_file              delete_body_file
list_scenarios             reset_scenarios
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

`list_mocks`, `list_mock_configs`, `list_stubs`, `list_unmatched_stubs`, `find_requests`, `list_unmatched_requests`, `get_near_misses`, `list_body_files`, and `list_scenarios` accept `limit` and an optional opaque `cursor`. Do not decode or modify a cursor. A cursor is bound to its tool, mock, and canonical request pattern, so reuse with a different tool or filter returns `INVALID_ARGUMENT`.

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

Poll `start_mock` after `retryAfterMs` until status is RUNNING. `stop_mock` is idempotent and returns all lifecycle fields with status exactly `STOPPED`, for example `{ "mockId": "orders", "status": "STOPPED", "podName": null, "message": null, "retryAfterMs": null }`, for running, starting, failed, already stopped, or absent pods. The MCP wrapper rejects missing, malformed, or mismatched Fleet lifecycle responses as `INVALID_UPSTREAM_RESPONSE`.

## Configuration

`update_mock_config` takes the complete mock-specific option override. The Fleet API is authoritative for option tokenization and validation, including split, combined, quoted, and equals syntax; unknown options, duplicates, stray values, missing values, invalid numbers, and invalid select values fail without persisting a mutation.

```json
{
  "mockId": "orders",
  "resourceVersion": "42",
  "options": ["--verbose --filename-template 'orders {{request.method}}'"],
  "resources": {"requests": {}, "limits": {}},
  "applyMode": "restartActive"
}
```

Omit `resources` to inherit baseline resources. Provide both `requests` and `limits`, including two empty maps, to replace inherited resources. Update success is `{resourceVersion,mock,apply}`; delete success is `{resourceVersion,mockId,deleted,apply}`. `resourceVersion` is a string when the ConfigMap has one and can be null when the Fleet API legitimately has no version. Each `apply` is `{mockId,mode,lifecycle}`. A restart is asynchronous, so `lifecycle` can be STARTING. Configuration rows use `lifecycle`, not `active`, and inherited `user.resources` is null.

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

`delete_body_file` returns `{mockId,fileName,deleted,forced}`. The default reference check stops at the first mapping that uses the file and returns `BODY_FILE_REFERENCED` with its `stubId`. `force=true` skips the reference scan and deletes immediately.

## Persistence, recording, and analysis

Persistent stub mutations use recoverable transactions. A persistent update records before/after state, removes the old mapping, writes and verifies the replacement, then removes its recovery marker. A retry resumes the transaction. If the current mapping matches neither known state, the tool returns a conflict without overwriting it. An indeterminate replacement or rollback returns `PERSISTENT_UPDATE_INCOMPLETE` with reconciliation details and leaves the marker for recovery. Recovery markers never appear in stub lists or analysis.

`start_recording` forces `persist=false` and `outputFormat=IDS`, verifies the recorder status, and rejects disallowed targets before WireMock sees them. `stop_recording` and `snapshot_requests` return:

```json
{
  "mockId": "orders",
  "candidateIds": ["d13b8bb8-95bb-4c44-b7d7-601baa333c1c"],
  "candidateCount": 1,
  "matchedRequests": true
}
```

A zero-match operation returns an empty ID array, `candidateCount: 0`, and `matchedRequests: false`. Use `find_requests`, `count_requests`, `list_unmatched_requests`, `list_unmatched_stubs`, and `get_near_misses` for matched/missed analysis. `send_request` rejects every WireMock Admin path, including encoded or ambiguous variants. Recorder, proxy, webhook, and mapping targets reject private, loopback, link-local, multicast, metadata, and special-use destinations unless both the hostname exception and connection-time CIDR policy allow them.

## Health and provenance

`/__fleet/mcp/health/live` and `/started` report only the MCP process. `/__fleet/mcp/health/ready` also requires the Fleet API and Fleet Proxy readiness endpoints to return HTTP 200 with `status: UP`; each dependency check uses `dependencyHealthTimeout` independently. A dependency outage removes MCP from service without causing Kubernetes to restart it.

`GET /__fleet/mcp/version` returns `{component,version,revision,buildTime}`. `revision` is the full Git commit ID and `buildTime` is an ISO-8601 timestamp embedded in the artifact. Packaging fails if the revision metadata is absent or malformed.
