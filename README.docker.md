# mock-fleet Docker image

Published image:

```bash
docker pull ghcr.io/letsrokk/mock-fleet:<version>
```

Stable releases also publish `latest`:

```bash
docker pull ghcr.io/letsrokk/mock-fleet:latest
```

`mock-fleet` is a Quarkus service that routes incoming HTTP requests to per-mock WireMock pods in Kubernetes. It creates or reuses WireMock pods for each mock ID, then proxies requests to the selected pod.

## Runtime

The container listens on port `8080`. Port `5005` is exposed for optional remote debugging when Java debug settings are enabled.

Common runtime settings:

- `mock-fleet.routing.mode`: `HOST` or `PATH`
- `mock-fleet.routing.host`: public host used by `HOST` routing
- `mock-fleet.namespace`: fallback namespace used for created mock pods when the Kubernetes client has no active namespace
- `mock-fleet.wiremock-container-name`: WireMock container name used for spawned mock pods
- `mock-fleet.wiremock-image`: WireMock image used for spawned mock pods
- `mock-fleet.wiremock-config-path`: optional YAML file with default and per-mock WireMock CLI options

The service expects Kubernetes credentials inside the running environment and a Hazelcast client configuration at `/etc/hazelcast/hazelcast-client.yaml` for Kubernetes deployments.

## Kubernetes usage

The repository includes a Helm chart under `deploy/helm/mock-fleet`. A typical install uses the chart and points it at this image:

```bash
helm upgrade --install mock-fleet deploy/helm/mock-fleet \
  --namespace mock-fleet \
  --create-namespace \
  --set image.repository=ghcr.io/letsrokk/mock-fleet \
  --set image.tag=<version>
```

See the main repository README for local development, routing examples, chart values, and test details:

https://github.com/letsrokk/mock-fleet
