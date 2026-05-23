# mock-fleet Docker images

Published backend image:

```bash
docker pull ghcr.io/letsrokk/mock-fleet:<version>
```

Published dashboard image:

```bash
docker pull ghcr.io/letsrokk/mock-fleet-dash:<version>
```

Stable releases also publish `latest` tags:

```bash
docker pull ghcr.io/letsrokk/mock-fleet:latest
docker pull ghcr.io/letsrokk/mock-fleet-dash:latest
```

`mock-fleet` is a Quarkus service that routes incoming HTTP requests to per-mock WireMock pods in Kubernetes. It creates or reuses WireMock pods for each mock ID, then proxies requests to the selected pod.

## Backend runtime

The container listens on port `8080`. Port `5005` is exposed for optional remote debugging when Java debug settings are enabled.

Common runtime settings:

- `mock-fleet.routing.mode`: `HOST` or `PATH`
- `mock-fleet.routing.host`: public host used by `HOST` routing
- `mock-fleet.namespace`: fallback namespace used for created mock pods when the Kubernetes client has no active namespace
- `mock-fleet.wiremock-container-name`: WireMock container name used for spawned mock pods
- `mock-fleet.wiremock-image`: WireMock image used for spawned mock pods
- `mock-fleet.wiremock-image-pull-policy`: image pull policy used for spawned mock pods
- `mock-fleet.wiremock-config-path`: optional YAML file with default and per-mock WireMock CLI options

The service expects Kubernetes credentials inside the running environment and a Hazelcast client configuration at `/etc/hazelcast/hazelcast-client.yaml` for Kubernetes deployments.

## Kubernetes usage

The repository includes a Helm chart under `deploy/helm/mock-fleet`. A typical install uses the chart and points it at this image:

```bash
helm upgrade --install mock-fleet deploy/helm/mock-fleet \
  --namespace mock-fleet \
  --create-namespace \
  --set fleet.image.repository=ghcr.io/letsrokk/mock-fleet \
  --set fleet.image.tag=<version> \
  --set dash.image.repository=ghcr.io/letsrokk/mock-fleet-dash \
  --set dash.image.tag=<version>
```

See the main repository README for local development, routing examples, chart values, and test details:

https://github.com/letsrokk/mock-fleet
