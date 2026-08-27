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

Enable MCP explicitly:

```bash
helm upgrade --install mock-fleet deploy/helm/mock-fleet \
  --namespace mock-fleet \
  --create-namespace \
  --set ingress.enabled=true \
  --set fleet.mcp.enabled=true
```

The MCP service uses stateful Streamable HTTP and therefore runs one replica. Enabling it requires a pinned WireMock 3.x image; the default is `wiremock/wiremock:3.13.2-2`.

MCP tools that accept native WireMock JSON, including `mapping`, `headers`, `requestPattern`, `recording`, and `snapshot`, accept ordinary JSON objects directly. Do not wrap them in a `map` property.

`get_mock_config` returns only `resourceVersion`, the selected `mock`, and `routing`. It returns a structured `NOT_FOUND` error with `details.mockId` when the mock has no configuration. `list_option_definitions` returns the option metadata separately as `{ "optionDefinitions": [...] }`.

`update_mock_config` accepts `applyMode` as `futureOnly` or `restartActive`. Its optional `resources` value has required `requests` and `limits` string maps. Omit `resources` to inherit the baseline; provide both maps, including two empty maps, to replace the inherited values. The response contains only `resourceVersion`, the updated `mock`, and `applyMode`. `delete_mock_config` returns only `resourceVersion`, `mockId`, `deleted`, and `applyMode`.

Configuration views use `null` for inherited `user.resources`. The `baseline.resources` and `effective.resources` fields always contain resolved request and limit maps.

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
make local-deploy REBUILD=all            # Force all local image rebuilds
make local-deploy NAMESPACE=test-fleet

make local-destroy
make local-destroy RELEASE=mock-fleet NAMESPACE=test-fleet
make local-destroy DELETE_NAMESPACE=true
```

`DEV=true` and `DEV=api` both select API remote development. Use `DEV=proxy` for proxy remote development. `REBUILD` accepts `dash`, `api`, `proxy`, `mcp`, or `all` and combines the forced rebuild with modules detected from working-tree changes. Run `make help` for the complete target and variable summary.

## License And Copyright

Copyright 2026 letsrokk.

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
