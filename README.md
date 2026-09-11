<p align="center">
  <img src="fleet-dash/public/favicon.svg" width="220" height="220" alt="Mock Fleet logo">
</p>

# mock-fleet

Run HTTP mocks on demand in Kubernetes. `mock-fleet` starts a WireMock pod when a request arrives for a mock, routes traffic to it, and removes idle pods. Use the dashboard to manage mocks, or connect an AI client through the optional Model Context Protocol (MCP) server.

## Main functions

- **Start mocks on first use.** Each mock ID gets its own WireMock pod; subsequent requests reuse it while it is running.
- **Route by path or hostname.** Use `/orders/api/items` or `orders.mock-fleet.example.com/api/items` to reach an `orders` mock.
- **Configure each mock.** Choose a WireMock version from the allowed catalog and set startup options, CPU, and memory resources.
- **Keep mappings when needed.** Optional S3-backed storage lets you persist mappings across pod restarts.
- **Operate through MCP.** Manage mocks and stubs, inspect requests, and record traffic from an AI client.

### Supported mock kinds

WireMock is the only supported mock kind. The chart accepts stable WireMock 3.x image tags from its configured version catalog; available startup options depend on the selected version.

## Quick start

You need Helm, curl, access to a Kubernetes cluster, and an ingress controller with a hostname pointing to it. The default chart requires Kubernetes 1.30 or newer and a network plugin that enforces NetworkPolicy. Apply the namespace policy and verify network enforcement described in the [cluster security prerequisites](deploy/helm/mock-fleet/README.md#cluster-security-prerequisites).

Protect externally reachable Fleet API, WireMock Admin, and MCP routes at your ingress boundary: these routes do not provide built-in authentication. See [deployment security](docs/SECURITY.md).

### Install

Install the published chart from GHCR. Replace `<version>` with the chart release you want to deploy and `mock-fleet.example.com` with your ingress hostname. This example enables ingress and selects path routing so all mocks share one hostname:

```bash
helm upgrade --install mock-fleet oci://ghcr.io/letsrokk/charts/mock-fleet \
  --version <version> \
  --namespace mock-fleet \
  --create-namespace \
  --set ingress.enabled=true \
  --set ingress.host=mock-fleet.example.com \
  --set fleet.proxy.routing.mode=PATH
```

To install from this checkout, replace the OCI chart URL with `deploy/helm/mock-fleet` and omit `--version`. Configure your ingress class, TLS, and other deployment settings through the [Helm chart values](deploy/helm/mock-fleet/README.md).

Once the services are ready, open `/__fleet/` on your ingress host to view the dashboard.

### Create your first stub

Set `FLEET_URL` to your ingress URL, including its configured HTTP or HTTPS scheme. The following request starts a mock named `orders` on demand and creates a stub for `GET /hello`. The first request waits for the WireMock pod to start.

```bash
FLEET_URL=http://mock-fleet.example.com

curl --fail --silent --show-error \
  --request POST "$FLEET_URL/orders/__admin/mappings" \
  --header 'Content-Type: application/json' \
  --data '{"request":{"method":"GET","url":"/hello"},"response":{"status":200,"body":"Hello from orders"}}'

curl --fail --silent --show-error "$FLEET_URL/orders/hello"
```

The POST returns the created mapping. The GET returns `Hello from orders`. In path mode, the proxy removes `/orders` before forwarding the request, so the stub matches `/hello`.

Persistence is disabled by default: this example's stub is lost when its pod is removed. Enable storage and explicitly persist mappings when they must survive restarts; see [persistent mappings](deploy/helm/mock-fleet/README.md#persistent-mappings).

For a local Minikube deployment, follow the [local development guide](docs/DEVELOPMENT.md).

## Routing and lifecycle

| Mode | Request URL | Mock ID | Path received by WireMock |
| --- | --- | --- | --- |
| `PATH` (quick start above) | `http://mock-fleet.example.com/orders/hello` | `orders` | `/hello` |
| `HOST` (chart default) | `http://orders.mock-fleet.example.com/hello` | `orders` | `/hello` |

Host routing requires DNS for the mock subdomains. Ingress is disabled in the default chart values; the quick start enables it explicitly. See [routing and ingress](deploy/helm/mock-fleet/README.md#routing-and-ingress) for details.

Idle pods are checked every five minutes and removed when they exceed `fleet.api.podInactivityThreshold` (one minute by default). A later request starts the mock again. Startup also depends on available cluster resources and the configured capacity limits; see [lifecycle and API contracts](deploy/helm/mock-fleet/README.md#lifecycle-and-api-contracts).

## Dashboard

### Active mocks

Inspect currently active mocks.

![Active Mocks tab](docs/screenshots/active-mocks.png)

### Configuration

Edit per-mock startup options, resources, and WireMock versions.

![Configuration tab](docs/screenshots/configuration.png)

### Persisted mappings

Inspect persisted mock mapping files.

![Persisted Mappings tab](docs/screenshots/persisted-mappings.png)

## Components

| Component | Role |
| --- | --- |
| `fleet-proxy` | Routes incoming HTTP traffic to the correct WireMock pod. |
| `fleet-api` | Manages mock pods, configuration, lifecycle, shared state, and persisted mappings. |
| `fleet-dash` | Serves the dashboard under `/__fleet/`; enabled by default. |
| `fleet-mcp` | Optional MCP server for AI clients. |
| `fleet-mock-ops` | Optional scheduled WireMock version discovery; its image also initializes saved configuration during installation and upgrades. |

## MCP

Enable MCP by adding `--set fleet.mcp.enabled=true --set ingress.enabled=true` to your Helm command, then connect your client to `/__fleet/mcp` on your ingress host. The server uses Streamable HTTP.

See the [MCP contract and examples](docs/mcp-contract.md) for tools, session setup, and client usage.

## Mock Ops

Mock Ops discovers WireMock releases and updates the available versions and default within the configured version range, without restarting active mocks. Enable it by adding `--set mockOps.enabled=true` to your Helm command.

See the [Mock Ops chart options](deploy/helm/mock-fleet/README.md#fleet-mock-ops) for version ranges and private registries. With Helm 4, use `--server-side=false` when upgrading to avoid conflicts with runtime version-catalog updates.

## Further reading

- [Helm chart reference](deploy/helm/mock-fleet/README.md) — deployment values, storage, upgrades, and cluster prerequisites.
- [Deployment security](docs/SECURITY.md) — network policies, service accounts, and ingress protection.
- [Local development](docs/DEVELOPMENT.md) — Minikube setup and build commands.
- [REST API schema](fleet-api/src/main/resources/META-INF/openapi.yaml) — API operations and response contracts.
- [MCP contract and examples](docs/mcp-contract.md) — AI client integration.

## License and copyright

Copyright 2026 letsrokk.

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
