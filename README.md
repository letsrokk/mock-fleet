# mock-fleet

`mock-fleet` routes HTTP requests to on-demand WireMock pods in Kubernetes and provides a small dashboard for operating those mocks.

It is deployed as three services:

- `fleet-proxy`: routes incoming mock traffic.
- `fleet-api`: manages WireMock pods, configuration, state, and persisted mappings.
- `fleet-dash`: dashboard served under `/__fleet/`.

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
  --create-namespace \
  --set fleet.proxy.image.tag=<version> \
  --set fleet.api.image.tag=<version> \
  --set fleet.dash.image.tag=<version>
```

Install from this repository:

```bash
helm dependency build deploy/helm/mock-fleet
helm upgrade --install mock-fleet deploy/helm/mock-fleet \
  --namespace mock-fleet \
  --create-namespace
```

For all chart values and deployment options, see the [Helm chart README](deploy/helm/mock-fleet/README.md).

For local Minikube development:

```bash
bin/local/deploy.sh
```

For an example Minikube cluster setup for local deployment and development, see [letsrokk/minikube](https://github.com/letsrokk/minikube).

## License And Copyright

Copyright 2026 letsrokk.

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
