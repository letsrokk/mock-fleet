# mock-fleet

`mock-fleet` routes HTTP requests to on-demand WireMock pods in Kubernetes and provides a dashboard and optional MCP server for operating those mocks.

It is deployed as three core services and one optional service:

- `fleet-proxy`: routes incoming mock traffic.
- `fleet-api`: manages WireMock pods, configuration, state, and persisted mappings.
- `fleet-dash`: dashboard served under `/__fleet/`.
- `fleet-mcp`: exposes typed fleet, stub, traffic, recorder, body-file, and scenario tools under `/__fleet/mcp`.

`fleet-mcp` talks only to the internal Fleet API and Fleet Proxy ClusterIP services. It never resolves mock pods or uses pod IPs. Fleet Proxy keeps ownership of resolving or starting a WireMock pod before it forwards an Admin API or mock request.

## Main Functions

### Active Mocks

Inspect currently active mocks.

![Active Mocks tab](docs/screenshots/active-mocks.png)

### Configuration

Edit per-mock startup options.

![Configuration tab](docs/screenshots/configuration.png)

### Persisted Mappings

Inspect persisted mock mapping files.

![Persisted Mappings tab](docs/screenshots/persisted-mappings.png)

## Quick Start

Install the published chart from GHCR:

```bash
helm upgrade --install mock-fleet oci://ghcr.io/letsrokk/charts/mock-fleet \
  --version <version> \
  --namespace mock-fleet \
  --create-namespace
```

Install from this repository:

```bash
helm upgrade --install mock-fleet deploy/helm/mock-fleet \
  --namespace mock-fleet \
  --create-namespace
```

For all chart values and deployment options, see the [Helm chart README](deploy/helm/mock-fleet/README.md).

## Security Deployment Requirements

The chart ships secure defaults for Kubernetes admission, namespace quota, restricted workload security contexts, API ingress, and managed-WireMock egress. The network boundary is effective only when the cluster network plugin enforces Kubernetes `NetworkPolicy`. Kubernetes accepts these objects even when the plugin ignores them. Calico and Cilium are examples of capable CNIs, but the cluster operator must enable and verify enforcement for the installed version and configuration.

`make local-deploy` neither checks nor changes CNI capability. The bridge CNI in the Minikube cluster reviewed for this release accepted the policies but did not enforce them. Do not treat that profile as providing the private-network egress boundary. Run the [chart's controlled internal-connectivity test](deploy/helm/mock-fleet/README.md#verify-networkpolicy-enforcement) before using a cluster for untrusted mocks; it requires an unselected positive control to connect before a selected denial can prove enforcement.

The chart also installs a `ValidatingAdmissionPolicy` by default. This requires Kubernetes 1.30 or newer; the rendered policy was verified against Kubernetes 1.36.4. Local deployment labels its namespace for the `restricted` Pod Security Admission profile at the current server minor version. Disabling admission, network policy, or quota removes a separate defense-in-depth boundary; see the [upgrade and security notes](deploy/helm/mock-fleet/README.md#upgrade-and-security-notes) before upgrading or changing those switches.

Managed WireMock pods use a dedicated service account and do not receive the general Kubernetes API token. `wiremock.serviceAccount.annotations` remains available for IRSA or EKS Pod Identity. With `storage.s3.authenticationSource=pod`, the S3 CSI driver uses pod-level identity; an identity integration may inject a separate audience-bound projected token, mount, and AWS environment variables without re-enabling the general API token.

Authentication for the Fleet API, Fleet Proxy Admin routes, and MCP is unchanged and remains outside this hardening release. Protect externally reachable routes at the platform ingress boundary.

Enable MCP explicitly:

```bash
helm upgrade --install mock-fleet deploy/helm/mock-fleet \
  --namespace mock-fleet \
  --create-namespace \
  --set ingress.enabled=true \
  --set fleet.mcp.enabled=true
```

The MCP service uses stateful Streamable HTTP and therefore runs one replica. The chart seeds a WireMock version catalog from `wiremock.containerImage` and `wiremock.supportedImageTags`. `wiremock.containerImage` remains the compatibility setting for the exact initial default image; its semantic version becomes `defaultVersion`, and its repository is used with the other supported tags. The default catalog contains five selectable 3.x versions. A mock can inherit `defaultVersion` or pin an exact catalog version, so one deployment can run different WireMock versions at the same time. Catalog entries kept only because baseline or user configuration still references them are marked retained: existing pins continue to work, but new selections cannot use them.

`GET /__fleet/api/config` returns `defaultVersion`, every `{version,image,selectable}` catalog entry, and `catalogResourceVersion`. Each mock row exposes its inherited or pinned desired `wireMockVersion`, nullable active `runtimeVersion`, and baseline, user, and effective version fields. Saving with `futureOnly` leaves an active pod unchanged; `restartActive` replaces only a STARTING or RUNNING pod. Unsupported pins fail with `UNSUPPORTED_WIREMOCK_VERSION`, and options incompatible with the desired version fail with `UNSUPPORTED_WIREMOCK_OPTION` before the saved ConfigMap changes.

`GET /__fleet/api/config/options?version=<3.x.y>` resolves the public option catalog for the selected exact version as `{wireMockVersion,catalogStatus,options}`. The dashboard loads that catalog separately from configuration, and MCP exposes the same lookup through `list_option_definitions`. An exact future 3.x version above the researched matrix returns `catalogStatus: newer_unresearched` and uses the latest researched public option set. Options absent from the selected version are hidden and rejected.

The optional Fleet Mock Ops CronJob is disabled by default. Enable it with `mockOps.enabled=true`. It reads Registry V2 tags, advances the catalog default within the configured constraint, keeps the latest configured minor lines plus any constrained default selectable, and retains exact versions still referenced by baseline or user configuration. It updates one ConfigMap with Kubernetes `resourceVersion` concurrency and never restarts active mocks. See the [Fleet Mock Ops chart values and reconciliation contract](deploy/helm/mock-fleet/README.md#fleet-mock-ops).

The REST lifecycle is asynchronous and idempotent. `POST /__fleet/api/mocks/{mockId}/start` returns 200 for RUNNING or 202 for STARTING with `retryAfterMs`; poll the same endpoint until RUNNING. DELETE waits for an existing pod to be removed before it returns STOPPED, and also returns STOPPED when the mock is starting, failed, already stopped, or absent. Config rows expose `lifecycle`, and config mutations return `{config,apply:{mockId,mode,lifecycle}}`. All lifecycle/config errors use `ApiError {code,message,retryable,stateMayHaveChanged,details}`; `CONFIG_CONFLICT` includes expected and current versions.

The checked-in [OpenAPI contract](fleet-api/src/main/resources/META-INF/openapi.yaml) documents the exact REST shapes. The running API serves the merged document at `/__fleet/api/openapi?format=json` and Swagger UI at `/__fleet/api/swagger-ui`.

MCP publishes 31 schema-backed tools. `list_mocks` includes configured inactive and active mocks with desired/runtime version state and a saved-config flag. The tool set also includes `start_mock` and `get_recording_status`; the old `recording_status` name is absent. WireMock tools preflight the lifecycle and return retryable `MOCK_STARTING` while a cold pod starts. Successes use tool-specific structured objects. Failures use `{error:{code,message,retryable,stateMayHaveChanged,details}}` with `isError: true`. Native WireMock JSON stays native, while byte inputs and outputs use `{body:{encoding:utf8|base64,data,sizeBytes}}`.

See the [MCP contract and examples](docs/mcp-contract.md) for tool names, lifecycle polling, config application, body encoding, recording candidates, matched/missed analysis, redaction, SSRF controls, Admin-path guards, and persistent mutation recovery.

The existing Fleet Proxy still forwards direct `/__admin` requests from normal mock URLs without authentication. Enabling MCP does not add an authorization boundary to those routes. Protect the ingress at the platform layer when public Admin API access is not acceptable.

For local Minikube development:

```bash
make local-deploy
```

For an example Minikube cluster setup for local deployment and development, see [letsrokk/minikube](https://github.com/letsrokk/minikube).

The local profile uses Traefik, HTTPS, PATH routing, and enables MCP. Run `minikube tunnel` while using the deployment, and trust the Minikube local CA in curl, your browser, and any development JVM. The dashboard is available at `https://mock-fleet.minikube.localhost/__fleet/`; MCP uses `https://mock-fleet.minikube.localhost/__fleet/mcp`; the default WireMock mock is available at `https://mock-fleet.minikube.localhost/wiremock`.

The local lifecycle targets assume that Minikube is already running. Optional Make variables select deployment behavior without adding separate targets:

```bash
make local-deploy LOGS=true
make local-deploy DEV=true                 # API remote development
make local-deploy DEV=proxy
make local-deploy DEV=proxy PORT_FORWARD=true
make local-deploy REBUILD=mcp            # Force one local image rebuild
make local-deploy REBUILD=mock-ops       # Force the Mock Ops image rebuild
make local-deploy REBUILD=all            # Force all local image rebuilds
make local-deploy NAMESPACE=test-fleet

make local-destroy
make local-destroy RELEASE=mock-fleet NAMESPACE=test-fleet
make local-destroy DELETE_NAMESPACE=true
```

`DEV=true` and `DEV=api` both select API remote development. Use `DEV=proxy` for proxy remote development. The Minikube profile enables Fleet Mock Ops. `REBUILD` accepts `dash`, `api`, `proxy`, `mcp`, `mock-ops`, or `all` and combines the forced rebuild with modules detected from working-tree changes. Run `make help` for the complete target and variable summary.

## License And Copyright

Copyright 2026 letsrokk.

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
