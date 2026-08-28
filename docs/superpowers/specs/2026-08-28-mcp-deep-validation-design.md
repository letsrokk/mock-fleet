# MCP Deep Validation Design

## Goal

Prove every published Mock Fleet MCP tool against the local Minikube runtime, fix every reproducible non-auth defect of low severity or higher and every material tool-UX defect, rebuild and redeploy all components, and repeat the same matrix until the rebuilt deployment completes a clean pass.

## Scope

The validation covers the 32-tool surface published for MCP protocol `2025-11-25`, the Fleet API and Fleet Proxy boundaries used by those tools, persistent mappings, S3-backed body and mapping files, and WireMock 3.x Admin API behavior. Authentication is out of scope because this deployment is intentionally auth-less inside a restricted private network.

Tests may create and delete uniquely named namespaces, S3 buckets, mock configurations, WireMock pods, stubs, requests, recorder candidates, scenarios, and body files. Cleanup must prove ownership before removing persistent state. Existing `catalog` and `payments` configurations and their mappings must not be changed.

## Test Architecture

`bin/cluster-e2e.sh` is the primary live matrix. It creates an isolated namespace and ownership-token-scoped S3 bucket, deploys the checked-out images, initializes MCP through Streamable HTTP, invokes all 32 tools, cross-checks Fleet API state, exercises persistent restart and recovery behavior, and removes only state whose ownership marker matches the current run.

The live matrix is complemented by:

- the deployed ingress handshake over negotiated HTTP/2;
- health and build-provenance checks;
- live `tools/list` schema and annotation inspection;
- `fleet-mcp/tests/wiremock-admin-contract.sh` against WireMock 3.0.4 and 3.13.2;
- the 232-test MCP unit and integration suite;
- Helm rendering and cluster harness self-tests.

Live checks run serially because they share one Minikube control plane and persistent storage service. Repository-only checks may run concurrently when they do not share build output.

## Finding and Fix Contract

A finding needs a deterministic reproduction or direct contract evidence. Severity reflects impact, likelihood, reach, detectability, and recovery cost. Harmless preferences remain observations. Authentication findings are excluded.

For each accepted defect:

1. Preserve the failing input, output, environment, and first bad boundary.
2. Trace the responsible data or control flow to its root cause.
3. Add the smallest regression test that fails for the reproduced reason.
4. Implement one root-cause fix.
5. Run the focused test, the affected suite, and the relevant live scenario.

The published input schemas are part of the user interface. Every closed top-level argument needs an actionable description and must advertise stable constraints that the server already enforces, including mock ID syntax, pagination bounds, enums, and encoded-body size limits. Native WireMock JSON objects remain open because WireMock owns their schema.

## Completion Criteria

- All 32 tools have successful live coverage where the operation supports success, plus relevant negative and recovery coverage.
- Configuration, lifecycle, persistence, traffic, journal, analysis, recorder, body-file, scenario, pagination, cursor, redaction, SSRF, and Admin-path behaviors match `docs/mcp-contract.md`.
- WireMock Admin contracts pass on both supported versions.
- No unresolved non-auth finding of low severity or higher remains.
- Material schema and tool-description UX gaps are fixed and regression-tested.
- `make local-deploy REBUILD=all` completes, all workloads become ready, and version provenance identifies the rebuilt commit.
- The entire live matrix passes again after that deployment from newly isolated state.
