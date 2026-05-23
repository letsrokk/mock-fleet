# mock-fleet Docker images

Published proxy image:

```bash
docker pull ghcr.io/letsrokk/mock-fleet-proxy:<version>
```

Published API image:

```bash
docker pull ghcr.io/letsrokk/mock-fleet-api:<version>
```

Published dashboard image:

```bash
docker pull ghcr.io/letsrokk/mock-fleet-dash:<version>
```

Stable releases also publish `latest` tags:

```bash
docker pull ghcr.io/letsrokk/mock-fleet-proxy:latest
docker pull ghcr.io/letsrokk/mock-fleet-api:latest
docker pull ghcr.io/letsrokk/mock-fleet-dash:latest
```

`mock-fleet` is packaged as separate proxy, API, and dashboard containers. The API creates or reuses per-mock WireMock pods in Kubernetes, while the proxy resolves mock IDs through the API and forwards incoming HTTP requests to the selected pod.

## Runtime

The proxy and API containers listen on port `8080`. Port `5005` is exposed for optional remote debugging when Java debug settings are enabled.

Common runtime settings:

- `mock-fleet.routing.mode`: `HOST` or `PATH`, used by `fleet-proxy`
- `mock-fleet.routing.host`: public host used by `HOST` routing, used by `fleet-proxy`
- `mock-fleet.api.base-url`: internal `fleet-api` URL used by `fleet-proxy`
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
  --set fleet.proxy.image.tag=<version> \
  --set fleet.api.image.tag=<version> \
  --set fleet.dash.image.tag=<version>
```

See the main repository README for local development, routing examples, chart values, and test details:

https://github.com/letsrokk/mock-fleet
