# mock-fleet Helm chart

This chart installs `mock-fleet`, a Quarkus service that routes HTTP requests to per-mock WireMock pods in Kubernetes. It deploys the application, service, RBAC, probes, optional ingress, WireMock mappings storage, and a Hazelcast dependency used by the service.

## Install from GHCR

Install a specific chart version from the OCI registry:

```bash
helm upgrade --install mock-fleet oci://ghcr.io/letsrokk/charts/mock-fleet \
  --version <version> \
  --namespace mock-fleet \
  --create-namespace
```

The chart defaults to the published application image:

```yaml
image:
  repository: ghcr.io/letsrokk/mock-fleet
  tag: latest
```

Pin the application image to the same release version when installing a release chart:

```bash
helm upgrade --install mock-fleet oci://ghcr.io/letsrokk/charts/mock-fleet \
  --version <version> \
  --namespace mock-fleet \
  --create-namespace \
  --set image.tag=<version>
```

## Routing and ingress

The chart supports two routing modes:

- `routing.mode=HOST`: requests for single-label subdomains of `ingress.host` route to matching mock IDs
- `routing.mode=PATH`: the first URL path segment is used as the mock ID

Ingress is disabled by default. To expose the service through an ingress controller:

```bash
helm upgrade --install mock-fleet oci://ghcr.io/letsrokk/charts/mock-fleet \
  --version <version> \
  --namespace mock-fleet \
  --create-namespace \
  --set ingress.enabled=true \
  --set ingress.host=mock-fleet.example.com
```

When `routing.mode=HOST`, the rendered ingress includes both the fleet host and wildcard mock subdomains for that host.

## Common values

| Value | Default | Description |
| --- | --- | --- |
| `image.repository` | `ghcr.io/letsrokk/mock-fleet` | Application image repository |
| `image.tag` | `latest` | Application image tag |
| `image.pullPolicy` | `IfNotPresent` | Kubernetes image pull policy |
| `routing.mode` | `HOST` | Routing strategy, `HOST` or `PATH` |
| `ingress.enabled` | `false` | Create an ingress resource |
| `ingress.host` | `mock-fleet.localhost` | Public fleet host |
| `service.ports.http` | `80` | Service HTTP port |
| `service.ports.debug` | `5005` | Service debug port |
| `storage.enabled` | `true` | Mount WireMock mappings storage |
| `storage.createPersistentVolume` | `true` | Create a chart-managed persistent volume |
| `storage.pvcName` | `""` | Existing PVC name when not creating the default claim |
| `rbac.create` | `true` | Create RBAC resources for pod and service management |
| `serviceAccount.create` | `true` | Create a service account |
| `hazelcast.cluster.memberCount` | `1` | Hazelcast dependency member count |

See `values.yaml` and `values.schema.json` in the chart for the complete value surface.

## Local Minikube values

The repository includes `values.minikube.yaml` for the local Minikube workflow. It enables ingress at `mock-fleet.localhost`, keeps `HOST` routing, uses larger local resource requests, and points storage at an existing PVC named `mock-fleet-wiremock-mappings`.

```bash
helm upgrade --install mock-fleet deploy/helm/mock-fleet \
  --namespace mock-fleet \
  --create-namespace \
  -f deploy/helm/mock-fleet/values.minikube.yaml
```

## Runtime notes

The service expects Kubernetes API access so it can create, reuse, and delete WireMock pods and services. The chart creates the required service account and RBAC resources by default.

The chart configures Hazelcast through the bundled Hazelcast dependency and passes the client configuration location to the application with `env.javaOpts`.

For application behavior, local development, and routing examples, see the repository README:

https://github.com/letsrokk/mock-fleet
