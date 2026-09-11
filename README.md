<p align="center">
  <img src="fleet-dash/public/favicon.svg" width="220" height="220" alt="Mock Fleet logo">
</p>

# mock-fleet

Run on-demand WireMock mocks in Kubernetes. `mock-fleet` routes each mock's HTTP traffic to its own pod and provides a dashboard to manage mocks, startup configuration, and persisted mappings.

Use it when your tests or development environments need separate mock endpoints without manually managing a WireMock pod for each one. Pods start on demand and are cleaned up after inactivity.

## Quick start

### Prerequisites

- A Kubernetes cluster with `kubectl` access and Helm installed. The chart's default admission policy requires Kubernetes 1.30 or newer.
- A network plugin that enforces NetworkPolicy. Follow the [cluster security prerequisites](deploy/helm/mock-fleet/README.md#cluster-security-prerequisites), including namespace policy and the enforcement check.
- `curl` for the example below.

### Install

Replace `<version>` with the chart release you want to install. This example selects PATH routing so you can try a mock through a local port-forward without configuring ingress or DNS:

```bash
helm upgrade --install mock-fleet oci://ghcr.io/letsrokk/charts/mock-fleet \
  --version <version> \
  --namespace mock-fleet \
  --create-namespace \
  --set fleet.proxy.routing.mode=PATH
```

For installation from source and all chart values, see the [Helm chart README](deploy/helm/mock-fleet/README.md). For a Minikube development deployment, see [Local development](docs/DEVELOPMENT.md).

### Get your first mock response

Once the deployment is ready, forward the proxy service and leave this terminal running:

```bash
kubectl -n mock-fleet port-forward service/mock-fleet-proxy 8080:80
```

In another terminal, register a response on a mock named `demo`. The first request starts its WireMock pod, so it can take longer while the image is pulled and the pod becomes ready:

```bash
curl --fail-with-body --show-error --silent \
  --request POST http://localhost:8080/demo/__admin/mappings \
  --header 'Content-Type: application/json' \
  --data '{"request":{"method":"GET","url":"/hello"},"response":{"status":200,"body":"Hello from mock-fleet"}}'

curl --fail-with-body --show-error --silent http://localhost:8080/demo/hello
```

The second request should return:

```text
Hello from mock-fleet
```

In PATH mode, `/demo/hello` selects the `demo` mock and forwards `/hello` to WireMock. The chart defaults to HOST routing, which selects a mock by subdomain instead; see [Routing and ingress](deploy/helm/mock-fleet/README.md#routing-and-ingress).

Mappings are temporary by default. To retain them across pod replacement, configure [persistent mappings](deploy/helm/mock-fleet/README.md#persistent-mappings) and save them through WireMock's `POST /demo/__admin/mappings/save` endpoint.

## Dashboard

Enable and configure [ingress](deploy/helm/mock-fleet/README.md#routing-and-ingress) to reach the dashboard at `/__fleet/` on your ingress host. The proxy-only port-forward above serves mock traffic; dashboard and API traffic use separate services.

Before exposing the deployment, protect Fleet API, WireMock Admin, and MCP routes at the ingress boundary. These routes do not provide built-in authentication; see [Deployment security](docs/SECURITY.md).

### Active mocks

Inspect currently active mocks.

![Active Mocks tab](docs/screenshots/active-mocks.png)

### Configuration

Edit per-mock startup options, resources, and WireMock versions.

![Configuration tab](docs/screenshots/configuration.png)

### Persisted mappings

Inspect persisted mock mapping files when persistent storage is enabled.

![Persisted Mappings tab](docs/screenshots/persisted-mappings.png)

## Components

| Component | Role |
| --- | --- |
| `fleet-proxy` | Routes incoming mock traffic. |
| `fleet-api` | Manages WireMock pods, configuration, state, and persisted mappings. |
| `fleet-dash` | Serves the dashboard under `/__fleet/`. |
| `fleet-mcp` | Optional MCP server for operating mocks with AI tools. |

WireMock is the only supported mock kind. Supported images use exact stable WireMock 3.x tags from the configured version catalog; see the [version policy](deploy/helm/mock-fleet/README.md#lifecycle-and-api-contracts).

## MCP

MCP lets AI clients operate mocks through the Model Context Protocol. Enable it by adding `--set fleet.mcp.enabled=true --set ingress.enabled=true` to the Helm command above, configure your ingress host, then connect your client to `/__fleet/mcp` on that host.

See the [MCP contract and examples](docs/mcp-contract.md) for client usage.

## Mock Ops

Mock Ops discovers WireMock releases and updates the available versions and default within the configured version range, without restarting active mocks. Enable it by adding `--set mockOps.enabled=true` to the Helm command above.

See the [Mock Ops chart options](deploy/helm/mock-fleet/README.md#fleet-mock-ops) for version ranges and private registries. With Helm 4, use `--server-side=false` when upgrading.

## Further reading

- [Helm chart and deployment options](deploy/helm/mock-fleet/README.md)
- [REST API schema](fleet-api/src/main/resources/META-INF/openapi.yaml)
- [Deployment security](docs/SECURITY.md)
- [Local development](docs/DEVELOPMENT.md)

## License And Copyright

Copyright 2026 letsrokk.

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
