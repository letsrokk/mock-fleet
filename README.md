# mock-fleet

`mock-fleet` routes HTTP requests to on-demand WireMock pods in Kubernetes and provides a dashboard and optional MCP server for operating those mocks.

It is deployed as three core services and one optional service:

- `fleet-proxy`: routes incoming mock traffic.
- `fleet-api`: manages WireMock pods, configuration, state, and persisted mappings.
- `fleet-dash`: dashboard served under `/__fleet/`.
- `fleet-mcp`: optional MCP server for operating mocks with AI tools.

## Main Functions

### Active Mocks

Inspect currently active mocks.

![Active Mocks tab](docs/screenshots/active-mocks.png)

### Configuration

Edit per-mock startup options, resources, and WireMock versions.

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

For all chart values and deployment options, see the [Helm chart README](deploy/helm/mock-fleet/README.md).

## MCP

MCP lets AI clients operate mocks through the Model Context Protocol. Enable it by adding `--set fleet.mcp.enabled=true --set ingress.enabled=true` to the Helm command above, then connect your client to `/__fleet/mcp` on your ingress host.

See the [MCP contract and examples](docs/mcp-contract.md) for client usage.

## Mock Ops

Mock Ops discovers WireMock releases and updates the available versions and default within the configured version range, without restarting active mocks. Enable it by adding `--set mockOps.enabled=true` to the Helm command above.

See the [Mock Ops chart options](deploy/helm/mock-fleet/README.md#fleet-mock-ops) for version ranges and private registries. With Helm 4, use `--server-side=false` when upgrading.

## Further Reading

- [Deployment security](docs/SECURITY.md)
- [Local development](docs/DEVELOPMENT.md)

## License And Copyright

Copyright 2026 letsrokk.

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
