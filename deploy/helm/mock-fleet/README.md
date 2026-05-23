# mock-fleet Helm chart

This chart installs `mock-fleet` as three services:

- `fleet-proxy`: request routing and proxying
- `fleet-api`: management API, WireMock pod lifecycle, Hazelcast state, cleanup jobs, and Kubernetes access
- `fleet-dash`: static dashboard

## Install from GHCR

```bash
helm upgrade --install mock-fleet oci://ghcr.io/letsrokk/charts/mock-fleet \
  --version <version> \
  --namespace mock-fleet \
  --create-namespace
```

Pin images when installing a release chart:

```bash
helm upgrade --install mock-fleet oci://ghcr.io/letsrokk/charts/mock-fleet \
  --version <version> \
  --namespace mock-fleet \
  --create-namespace \
  --set fleet.proxy.image.tag=<version> \
  --set fleet.api.image.tag=<version> \
  --set fleet.dash.image.tag=<version>
```

## Routing And Ingress

Ingress is disabled by default. When enabled, path routing is explicit:

- `/__fleet/api/health` -> `fleet-api`
- `/__fleet/api` -> `fleet-api`
- `/__fleet/proxy/health` -> `fleet-proxy`
- `/__fleet/dash/health` -> `fleet-dash`
- `/__fleet` -> `fleet-dash`
- `/` -> `fleet-proxy`
- `*.ingress.host` -> `fleet-proxy` only when `fleet.proxy.routing.mode=HOST`

```bash
helm upgrade --install mock-fleet oci://ghcr.io/letsrokk/charts/mock-fleet \
  --version <version> \
  --namespace mock-fleet \
  --create-namespace \
  --set ingress.enabled=true \
  --set ingress.host=mock-fleet.example.com
```

## Common Values

| Value | Default | Description |
| --- | --- | --- |
| `fleet.proxy.image.repository` | `ghcr.io/letsrokk/mock-fleet-proxy` | Proxy image repository |
| `fleet.proxy.image.tag` | `latest` | Proxy image tag |
| `fleet.proxy.routing.mode` | `HOST` | Routing strategy, `HOST` or `PATH` |
| `fleet.proxy.probes.*.path` | `/__fleet/proxy/health/*` | Proxy health probe paths |
| `fleet.api.image.repository` | `ghcr.io/letsrokk/mock-fleet-api` | API image repository |
| `fleet.api.image.tag` | `latest` | API image tag |
| `fleet.api.podInactivityThreshold` | `1M` | How long an inactive mock pod may live before cleanup |
| `fleet.api.podCreationTimeout` | `1M` | How long to wait for a new mock pod to become ready |
| `fleet.api.wiremock.containerImage` | `wiremock/wiremock:latest` | Image used by spawned WireMock pods |
| `fleet.api.storage.persistent` | `false` | Enable persistent WireMock mappings storage |
| `fleet.api.probes.*.path` | `/__fleet/api/health/*` | API health probe paths |
| `fleet.dash.enabled` | `true` | Deploy dashboard |
| `fleet.dash.image.repository` | `ghcr.io/letsrokk/mock-fleet-dash` | Dashboard image repository |
| `fleet.dash.probes.*.path` | `/__fleet/dash/health/*` | Dashboard health probe paths |
| `ingress.enabled` | `false` | Create an ingress resource |
| `ingress.host` | `mock-fleet.localhost` | Public fleet host |
| `rbac.create` | `true` | Create RBAC resources for `fleet-api` pod management |
| `serviceAccount.create` | `true` | Create a service account for `fleet-api` |
| `hazelcast.client.clusterName` | `dev` | Hazelcast client cluster name used by `fleet-api` |

See `values.yaml` and `values.schema.json` for the complete value surface.

## Local Minikube Values

The repository includes `values.minikube.yaml` for local Minikube. It enables ingress at `mock-fleet.localhost`, sets `fleet.proxy.routing.mode=PATH`, and configures local persistent S3 storage values.

```bash
helm upgrade --install mock-fleet deploy/helm/mock-fleet \
  --namespace mock-fleet \
  --create-namespace \
  -f deploy/helm/mock-fleet/values.minikube.yaml
```
