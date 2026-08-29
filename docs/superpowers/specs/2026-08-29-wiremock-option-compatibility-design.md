# WireMock 3.x Option Compatibility Design

## Goal

Mock Fleet exposes a complete, version-aware WireMock 3.x command-line option catalog through Fleet API, the dashboard, and MCP. The catalog is resolved against the deployment's pinned WireMock image and remains honest about unsupported, broken, unresearched, and secret-bearing options.

## Supported versions

- The minimum supported WireMock version is `3.0.0`.
- The initial maximum researched version is `3.13.2`, the latest stable 3.x release when this design was approved.
- Exact, immutable WireMock 3.x image tags remain required. Docker image revisions such as `3.13.2-2` resolve to WireMock `3.13.2`.
- Future 3.x versions above the researched maximum may run. Their known catalog entries use the latest known control shape and report compatibility as `unknown`.
- WireMock 2.x and 4.x are outside this design.

## Matrix behavior

Fleet API owns a checked-in compatibility matrix derived from tagged WireMock source, release notes, documentation, CLI help, and container smoke tests for stable 3.x releases. Each option records its UI metadata, argument shape, version ranges, security availability, and known defects.

The resolved catalog contains every known option:

- `supported`: WireMock accepts the option for the pinned version.
- `unsupported`: the option belongs to another researched 3.x version.
- `known_broken`: upstream advertises or parses the option incorrectly for the pinned version and a smoke test reproduces the failure.
- `unknown`: the pinned version is newer than the researched maximum.

Compatibility is advisory. The dashboard warns with a `(!)` indicator, but the API accepts catalogued unsupported, known-broken, and unknown options and passes them to WireMock. Users can therefore exercise upstream behavior at their discretion. Unknown option names remain invalid.

## Secret handling

The security review remains authoritative for `--ca-keystore-password`, `--keystore-password`, `--key-manager-password`, and `--truststore-password`. The expanded catalog also classifies `--admin-api-basic-auth` as unavailable because its value is directly credential-bearing.

These entries remain visible but disabled with `SECRET_STORAGE_REQUIRED`. Fleet rejects their values before persistence or pod creation, redacts prohibited legacy values, and never returns or logs the secret. Mixed-use `--proxy-via` retains the behavior approved during planning; this work does not classify it as unavailable.

## Consumers

Fleet API publishes the configured image, resolved version, supported bounds, range status, and resolved option definitions. The dashboard renders this response. MCP's `list_option_definitions` forwards it without maintaining a second option catalog.

MCP Admin API capability gates remain distinct from CLI option compatibility. They continue to hide and reject tools unavailable in the configured/runtime WireMock version. The implementation adds regression coverage for every MCP tool family and for the known `get_body_file` 3.7+ and `list_unmatched_stubs` 3.13+ boundaries.

## Identified regressions

The first matrix must encode and test these live findings:

- `--timeout` has the actual version-specific argument shape; on 3.13.2 it must not be emitted with the invalid numeric value shape used by the old catalog.
- `--disable-optimize-xml-factories-loading`, `--websocket-idle-timeout`, `--websocket-max-text-message-size`, and `--websocket-max-binary-message-size` are unsupported on 3.13.2.
- `--trust-all-proxy-targets` is known-broken on 3.13.2 because the pinned image fails during startup despite advertising it.
- Recording filename examples use WireMock's triple-brace form, for example `{{{method}}}-{{{url}}}.json`.

## Acceptance

Automated verification covers matrix integrity, API and OpenAPI contracts, dashboard behavior, MCP schemas and capability gates, upstream Admin API behavior, and Minikube tool flows. The final local Minikube acceptance run creates multiple configurations and mocks, exercises all MCP tool families, records and persists mappings, restarts mocks, verifies readback, and leaves the resulting resources in place for inspection.
