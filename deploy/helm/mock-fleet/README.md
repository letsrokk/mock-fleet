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

- `fleet.routing.mode=HOST`: requests for single-label subdomains of `ingress.host` route to matching mock IDs
- `fleet.routing.mode=PATH`: the first URL path segment is used as the mock ID

Ingress is disabled by default. To expose the service through an ingress controller:

```bash
helm upgrade --install mock-fleet oci://ghcr.io/letsrokk/charts/mock-fleet \
  --version <version> \
  --namespace mock-fleet \
  --create-namespace \
  --set ingress.enabled=true \
  --set ingress.host=mock-fleet.example.com
```

When `fleet.routing.mode=HOST`, the rendered ingress includes both the fleet host and wildcard mock subdomains for that host.

## Common values

| Value | Default | Description |
| --- | --- | --- |
| `image.repository` | `ghcr.io/letsrokk/mock-fleet` | Application image repository |
| `image.tag` | `latest` | Application image tag |
| `image.pullPolicy` | `IfNotPresent` | Kubernetes image pull policy |
| `wiremock.containerName` | `wiremock` | Container name used in spawned WireMock pods |
| `wiremock.containerImage` | `wiremock/wiremock:latest` | Image used by spawned WireMock pods |
| `wiremock.containerImagePullPolicy` | `IfNotPresent` | Image pull policy used by spawned WireMock pods |
| `wiremock.config.default.options` | `[]` | WireMock CLI options applied to every spawned mock pod |
| `wiremock.config.default.resources` | CPU `0.5`/`1`, memory `512Mi`/`1Gi` | Default resources applied to every spawned mock pod |
| `wiremock.config.mocks` | `[]` | Per-mock WireMock CLI options and resource overrides keyed by mock ID |
| `fleet.replicas` | `2` | Number of mock-fleet application replicas |
| `fleet.podInactivityThreshold` | `1M` | How long an inactive mock pod may live before cleanup |
| `fleet.podCreationTimeout` | `1M` | How long to wait for a newly created mock pod to become ready |
| `fleet.routing.mode` | `HOST` | Routing strategy, `HOST` or `PATH` |
| `fleet.resources` | CPU `0.5`/`2`, memory `512Mi`/`2Gi` | Resources applied to the mock-fleet application container |
| `ingress.enabled` | `false` | Create an ingress resource |
| `ingress.host` | `mock-fleet.localhost` | Public fleet host |
| `service.ports.http` | `80` | Service HTTP port |
| `service.ports.debug` | `5005` | Service debug port |
| `storage.persistent` | `false` | Enable persistent WireMock mappings storage |
| `storage.type` | `s3` | Persistent storage type. Only `s3` is supported for now |
| `storage.annotations` | `{}` | Annotations added to the persistent storage volume |
| `storage.s3.bucket` | `""` | S3 bucket used by the S3 CSI persistent volume. Required when `storage.persistent=true` |
| `storage.s3.provisioner` | `s3.csi.aws.com` | CSI driver used by the S3 persistent volume |
| `storage.s3.storageClassName` | `""` | Storage class name used by the S3 PV and PVC |
| `storage.s3.path` | `/mock-fleet` | Path where the S3-backed storage is mounted while preparing per-mock mapping directories |
| `storage.s3.authenticationSource` | `driver` | Mountpoint S3 CSI authentication source, `driver` or `pod` |
| `storage.s3.cacheSize` | `1Gi` | Mountpoint S3 CSI `emptyDir` cache size limit |
| `storage.s3.mountOptions` | `[]` | Mount options added to the S3 CSI persistent volume |
| `rbac.create` | `true` | Create RBAC resources for pod and service management |
| `serviceAccount.create` | `true` | Create a service account |
| `serviceAccount.annotations` | `{}` | Annotations added to the created service account |
| `hazelcast.client.clusterName` | `dev` | Hazelcast client cluster name used by mock-fleet |
| `hazelcast.cluster.memberCount` | `2` | Hazelcast dependency member count |
| `hazelcast.mancenter.enabled` | `false` | Enable Hazelcast Management Center |

See `values.yaml` and `values.schema.json` in the chart for the complete value surface.

Helm deployments derive the runtime namespace from the mock-fleet pod metadata. Direct or custom deployments can still set `MOCK_FLEET_NAMESPACE` when the Kubernetes client has no active namespace.

WireMock CLI options and mock pod resource settings are rendered into a ConfigMap. Helm upgrades roll the mock-fleet Deployment when that ConfigMap changes.

```yaml
wiremock:
  config:
    default:
      options:
        - --global-response-templating
      resources:
        requests:
          cpu: "0.5"
          memory: 512Mi
        limits:
          cpu: "1"
          memory: 1Gi
    mocks:
      - id: demo
        options:
          - --verbose
        resources:
          requests:
            cpu: "2"
            memory: 2Gi
          limits:
            cpu: "3"
            memory: 3Gi
      - id: empty-options
        options: []
```

When `storage.s3.authenticationSource=pod`, Mountpoint S3 CSI uses the workload pod's ServiceAccount credentials. In that mode, configure the ServiceAccount for the AWS identity mechanism in use, for example by setting `serviceAccount.annotations.eks.amazonaws.com/role-arn` for IRSA. The default `storage.s3.authenticationSource=driver` continues to use driver-level credentials.

## Local Minikube values

The repository includes `values.minikube.yaml` for the local Minikube workflow. It enables ingress at `mock-fleet.localhost`, keeps `HOST` routing, uses larger local resource requests, and keeps persistent storage disabled by default.

```bash
helm upgrade --install mock-fleet deploy/helm/mock-fleet \
  --namespace mock-fleet \
  --create-namespace \
  -f deploy/helm/mock-fleet/values.minikube.yaml
```

## Runtime notes

The service expects Kubernetes API access so it can create, reuse, and delete WireMock pods. The chart creates the required service account and RBAC resources by default.

The chart configures Hazelcast through the bundled Hazelcast dependency and passes the client configuration location to the application with `env.javaOpts`.

For application behavior, local development, and routing examples, see the repository README:

https://github.com/letsrokk/mock-fleet
