# mock-fleet Helm Chart

This chart deploys `mock-fleet` as three Kubernetes services:

- `fleet-proxy`: routes incoming HTTP requests to per-mock WireMock pods.
- `fleet-api`: manages mock pods, WireMock config, lifecycle cleanup, Hazelcast state, and persisted mappings.
- `fleet-dash`: serves the dashboard under `/__fleet/`.

## Install

Install from GHCR:

```bash
helm upgrade --install mock-fleet oci://ghcr.io/letsrokk/charts/mock-fleet \
  --version <version> \
  --namespace mock-fleet \
  --create-namespace
```

Install from this repository:

```bash
helm upgrade --install mock-fleet deploy/helm/mock-fleet \
  --namespace mock-fleet \
  --create-namespace
```

## Routing And Ingress

Ingress is disabled by default. When enabled, the chart routes:

- `/__fleet/api/*` to `fleet-api`
- `/__fleet/proxy/health/*` to `fleet-proxy`
- `/__fleet/dash/health/*` to `fleet-dash`
- `/__fleet/*` to `fleet-dash`
- `/` to `fleet-proxy`
- `*.ingress.host` to `fleet-proxy` when `fleet.proxy.routing.mode=HOST`

Example:

```bash
helm upgrade --install mock-fleet oci://ghcr.io/letsrokk/charts/mock-fleet \
  --version <version> \
  --namespace mock-fleet \
  --create-namespace \
  --set ingress.enabled=true \
  --set ingress.host=mock-fleet.example.com
```

## Persistent Mappings

Persistent mappings are disabled by default. To enable them, set `storage.persistent=true`, use `storage.type=s3`, and provide `storage.s3.bucket`.

The chart creates a static S3 CSI PV/PVC and mounts it into:

- spawned WireMock pods at `/home/wiremock`
- `fleet-api` at `storage.mappingsPath`

`storage.s3.mountOptions` includes `allow-delete` by default because the dashboard can delete mapping files and folders.

## Values

### Global

| Value | Default | Description |
| --- | --- | --- |
| `nameOverride` | `""` | Override the chart name used in resource names. |
| `fullnameOverride` | `""` | Override the full release resource name. |
| `namespaceOverride` | `""` | Override the namespace rendered into namespaced resources. |

### Proxy

| Value | Default | Description |
| --- | --- | --- |
| `fleet.proxy.dev.enabled` | `false` | Run proxy in Quarkus dev mode and force one replica. |
| `fleet.proxy.dev.quarkusLaunchDevmode` | `"true"` | Value for `QUARKUS_LAUNCH_DEVMODE` when proxy dev mode is enabled. |
| `fleet.proxy.dev.javaToolOptions` | `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005` | Proxy debug JVM options used in dev mode. |
| `fleet.proxy.image.repository` | `ghcr.io/letsrokk/mock-fleet/proxy` | Proxy image repository. |
| `fleet.proxy.image.tag` | `""` | Proxy image tag. Defaults to the chart `appVersion` when empty. |
| `fleet.proxy.image.pullPolicy` | `IfNotPresent` | Proxy image pull policy. |
| `fleet.proxy.routing.mode` | `HOST` | Mock routing mode: `HOST` or `PATH`. |
| `fleet.proxy.replicas` | `2` | Proxy replica count when dev mode is disabled. |
| `fleet.proxy.service.type` | `ClusterIP` | Proxy service type. |
| `fleet.proxy.service.ports.http` | `80` | Proxy service HTTP port. |
| `fleet.proxy.service.ports.targetHttp` | `8080` | Proxy container HTTP port. |
| `fleet.proxy.service.ports.debug` | `5005` | Proxy service debug port. |
| `fleet.proxy.service.ports.targetDebug` | `5005` | Proxy container debug port. |
| `fleet.proxy.probes.liveness.path` | `/__fleet/proxy/health/live` | Proxy liveness probe path. |
| `fleet.proxy.probes.liveness.initialDelaySeconds` | `15` | Proxy liveness probe initial delay. |
| `fleet.proxy.probes.liveness.periodSeconds` | `20` | Proxy liveness probe period. |
| `fleet.proxy.probes.liveness.timeoutSeconds` | `5` | Proxy liveness probe timeout. |
| `fleet.proxy.probes.liveness.successThreshold` | `1` | Proxy liveness probe success threshold. |
| `fleet.proxy.probes.liveness.failureThreshold` | `3` | Proxy liveness probe failure threshold. |
| `fleet.proxy.probes.readiness.path` | `/__fleet/proxy/health/ready` | Proxy readiness probe path. |
| `fleet.proxy.probes.readiness.initialDelaySeconds` | `5` | Proxy readiness probe initial delay. |
| `fleet.proxy.probes.readiness.periodSeconds` | `10` | Proxy readiness probe period. |
| `fleet.proxy.probes.readiness.timeoutSeconds` | `5` | Proxy readiness probe timeout. |
| `fleet.proxy.probes.readiness.successThreshold` | `1` | Proxy readiness probe success threshold. |
| `fleet.proxy.probes.readiness.failureThreshold` | `3` | Proxy readiness probe failure threshold. |
| `fleet.proxy.probes.startup.path` | `/__fleet/proxy/health/started` | Proxy startup probe path. |
| `fleet.proxy.probes.startup.initialDelaySeconds` | `5` | Proxy startup probe initial delay. |
| `fleet.proxy.probes.startup.periodSeconds` | `10` | Proxy startup probe period. |
| `fleet.proxy.probes.startup.timeoutSeconds` | `10` | Proxy startup probe timeout. |
| `fleet.proxy.probes.startup.successThreshold` | `1` | Proxy startup probe success threshold. |
| `fleet.proxy.probes.startup.failureThreshold` | `3` | Proxy startup probe failure threshold. |
| `fleet.proxy.resources.requests.cpu` | `"0.25"` | Proxy CPU request. |
| `fleet.proxy.resources.requests.memory` | `256Mi` | Proxy memory request. |
| `fleet.proxy.resources.limits.cpu` | `"1"` | Proxy CPU limit. |
| `fleet.proxy.resources.limits.memory` | `1Gi` | Proxy memory limit. |

### API

| Value | Default | Description |
| --- | --- | --- |
| `fleet.api.dev.enabled` | `false` | Run API in Quarkus dev mode and force one replica. |
| `fleet.api.dev.quarkusLaunchDevmode` | `"true"` | Value for `QUARKUS_LAUNCH_DEVMODE` when API dev mode is enabled. |
| `fleet.api.dev.javaToolOptions` | `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005` | API debug JVM options used in dev mode. |
| `fleet.api.image.repository` | `ghcr.io/letsrokk/mock-fleet/api` | API image repository. |
| `fleet.api.image.tag` | `""` | API image tag. Defaults to the chart `appVersion` when empty. |
| `fleet.api.image.pullPolicy` | `IfNotPresent` | API image pull policy. |
| `fleet.api.podInactivityThreshold` | `1M` | Time before inactive mock pods are eligible for cleanup. |
| `fleet.api.podCreationTimeout` | `1M` | Time to wait for a spawned WireMock pod to become ready. |
| `fleet.api.replicas` | `2` | API replica count when dev mode is disabled; must be at least two for embedded Hazelcast redundancy. |
| `fleet.api.terminationGracePeriodSeconds` | `300` | Time allowed for graceful Hazelcast member shutdown and partition migration. |
| `fleet.api.updateStrategy.type` | `RollingUpdate` | API deployment update strategy. |
| `fleet.api.updateStrategy.rollingUpdate.maxUnavailable` | `1` | Maximum unavailable API pods during rollout. |
| `fleet.api.updateStrategy.rollingUpdate.maxSurge` | `1` | Maximum additional API pods during rollout. |
| `fleet.api.pdb.enabled` | `true` | Create a PodDisruptionBudget for API/Hazelcast members. |
| `fleet.api.pdb.minAvailable` | `1` | Minimum API pods available during voluntary disruptions. |
| `fleet.api.service.type` | `ClusterIP` | API service type. |
| `fleet.api.service.ports.http` | `80` | API service HTTP port. |
| `fleet.api.service.ports.targetHttp` | `8080` | API container HTTP port. |
| `fleet.api.service.ports.debug` | `5006` | API service debug port. |
| `fleet.api.service.ports.targetDebug` | `5005` | API container debug port. |
| `fleet.api.probes.liveness.path` | `/__fleet/api/health/live` | API liveness probe path. |
| `fleet.api.probes.liveness.initialDelaySeconds` | `15` | API liveness probe initial delay. |
| `fleet.api.probes.liveness.periodSeconds` | `20` | API liveness probe period. |
| `fleet.api.probes.liveness.timeoutSeconds` | `5` | API liveness probe timeout. |
| `fleet.api.probes.liveness.successThreshold` | `1` | API liveness probe success threshold. |
| `fleet.api.probes.liveness.failureThreshold` | `3` | API liveness probe failure threshold. |
| `fleet.api.probes.readiness.path` | `/__fleet/api/health/ready` | API readiness probe path. |
| `fleet.api.probes.readiness.initialDelaySeconds` | `5` | API readiness probe initial delay. |
| `fleet.api.probes.readiness.periodSeconds` | `10` | API readiness probe period. |
| `fleet.api.probes.readiness.timeoutSeconds` | `5` | API readiness probe timeout. |
| `fleet.api.probes.readiness.successThreshold` | `1` | API readiness probe success threshold. |
| `fleet.api.probes.readiness.failureThreshold` | `3` | API readiness probe failure threshold. |
| `fleet.api.probes.startup.path` | `/__fleet/api/health/started` | API startup probe path. |
| `fleet.api.probes.startup.initialDelaySeconds` | `5` | API startup probe initial delay. |
| `fleet.api.probes.startup.periodSeconds` | `10` | API startup probe period. |
| `fleet.api.probes.startup.timeoutSeconds` | `10` | API startup probe timeout. |
| `fleet.api.probes.startup.successThreshold` | `1` | API startup probe success threshold. |
| `fleet.api.probes.startup.failureThreshold` | `30` | API startup probe failure threshold. |
| `fleet.api.resources.requests.cpu` | `"0.5"` | API CPU request. |
| `fleet.api.resources.requests.memory` | `512Mi` | API memory request. |
| `fleet.api.resources.limits.cpu` | `"2"` | API CPU limit. |
| `fleet.api.resources.limits.memory` | `2Gi` | API memory limit. |

### WireMock

| Value | Default | Description |
| --- | --- | --- |
| `wiremock.containerName` | `wiremock` | Container name used for spawned WireMock pods. |
| `wiremock.containerImage` | `wiremock/wiremock:latest` | Image used for spawned WireMock pods. |
| `wiremock.containerImagePullPolicy` | `IfNotPresent` | Image pull policy for spawned WireMock pods. |
| `wiremock.serviceAccount.create` | `true` | Create a dedicated service account for managed WireMock pods. |
| `wiremock.serviceAccount.name` | `""` | Service account name. A generated name is used when creation is enabled and this is empty. |
| `wiremock.serviceAccount.annotations` | `{}` | Annotations for workload identity or other integrations. |
| `wiremock.config.default.options` | `[]` | Default WireMock CLI options for all mocks. |
| `wiremock.config.default.resources.requests.cpu` | `"0.5"` | Default WireMock CPU request. |
| `wiremock.config.default.resources.requests.memory` | `512Mi` | Default WireMock memory request. |
| `wiremock.config.default.resources.limits.cpu` | `"1"` | Default WireMock CPU limit. |
| `wiremock.config.default.resources.limits.memory` | `1Gi` | Default WireMock memory limit. |
| `wiremock.config.mocks` | `[]` | Per-mock WireMock config overrides. |

Set `wiremock.serviceAccount.create=false` with a name to use an existing service account. If both creation and the name are disabled, managed pods use the namespace's default service account.

### Dashboard

| Value | Default | Description |
| --- | --- | --- |
| `fleet.dash.enabled` | `true` | Deploy the dashboard service and deployment. |
| `fleet.dash.image.repository` | `ghcr.io/letsrokk/mock-fleet/dash` | Dashboard image repository. |
| `fleet.dash.image.tag` | `""` | Dashboard image tag. Defaults to the chart `appVersion` when empty. |
| `fleet.dash.image.pullPolicy` | `IfNotPresent` | Dashboard image pull policy. |
| `fleet.dash.replicas` | `1` | Dashboard replica count. |
| `fleet.dash.service.type` | `ClusterIP` | Dashboard service type. |
| `fleet.dash.service.ports.http` | `80` | Dashboard service HTTP port. |
| `fleet.dash.service.ports.targetHttp` | `8080` | Dashboard container HTTP port. |
| `fleet.dash.probes.liveness.path` | `/__fleet/dash/health/live` | Dashboard liveness probe path. |
| `fleet.dash.probes.liveness.initialDelaySeconds` | `5` | Dashboard liveness probe initial delay. |
| `fleet.dash.probes.liveness.periodSeconds` | `20` | Dashboard liveness probe period. |
| `fleet.dash.probes.liveness.timeoutSeconds` | `5` | Dashboard liveness probe timeout. |
| `fleet.dash.probes.liveness.successThreshold` | `1` | Dashboard liveness probe success threshold. |
| `fleet.dash.probes.liveness.failureThreshold` | `3` | Dashboard liveness probe failure threshold. |
| `fleet.dash.probes.readiness.path` | `/__fleet/dash/health/ready` | Dashboard readiness probe path. |
| `fleet.dash.probes.readiness.initialDelaySeconds` | `2` | Dashboard readiness probe initial delay. |
| `fleet.dash.probes.readiness.periodSeconds` | `10` | Dashboard readiness probe period. |
| `fleet.dash.probes.readiness.timeoutSeconds` | `5` | Dashboard readiness probe timeout. |
| `fleet.dash.probes.readiness.successThreshold` | `1` | Dashboard readiness probe success threshold. |
| `fleet.dash.probes.readiness.failureThreshold` | `3` | Dashboard readiness probe failure threshold. |
| `fleet.dash.probes.startup.path` | `/__fleet/dash/health/started` | Dashboard startup probe path. |
| `fleet.dash.probes.startup.initialDelaySeconds` | `2` | Dashboard startup probe initial delay. |
| `fleet.dash.probes.startup.periodSeconds` | `10` | Dashboard startup probe period. |
| `fleet.dash.probes.startup.timeoutSeconds` | `10` | Dashboard startup probe timeout. |
| `fleet.dash.probes.startup.successThreshold` | `1` | Dashboard startup probe success threshold. |
| `fleet.dash.probes.startup.failureThreshold` | `3` | Dashboard startup probe failure threshold. |
| `fleet.dash.resources.requests.cpu` | `"0.05"` | Dashboard CPU request. |
| `fleet.dash.resources.requests.memory` | `64Mi` | Dashboard memory request. |
| `fleet.dash.resources.limits.cpu` | `"0.25"` | Dashboard CPU limit. |
| `fleet.dash.resources.limits.memory` | `128Mi` | Dashboard memory limit. |

### Storage

| Value | Default | Description |
| --- | --- | --- |
| `storage.persistent` | `false` | Enable persistent WireMock mappings storage. |
| `storage.type` | `s3` | Persistent storage type. Only `s3` is supported. |
| `storage.mappingsPath` | `/workspace/mappings` | Path where `fleet-api` mounts persisted mappings. |
| `storage.annotations` | `{}` | Annotations added to the mappings PV and PVC. |
| `storage.s3.provisioner` | `s3.csi.aws.com` | S3 CSI driver name. |
| `storage.s3.storageClassName` | `""` | Storage class name used by the static PV and PVC. |
| `storage.s3.bucket` | `""` | S3 bucket name. Required when `storage.persistent=true`. |
| `storage.s3.path` | `/mock-fleet` | Path mounted inside WireMock init containers before per-mock subpaths are created. |
| `storage.s3.authenticationSource` | `driver` | S3 CSI authentication source. |
| `storage.s3.cacheSize` | `1Gi` | S3 CSI cache emptyDir size limit. |
| `storage.s3.mountOptions` | `[allow-delete]` | S3 CSI mount options. Keep `allow-delete` for dashboard delete actions. |

### Ingress

| Value | Default | Description |
| --- | --- | --- |
| `ingress.enabled` | `false` | Create an ingress resource. |
| `ingress.host` | `mock-fleet.localhost` | Public fleet host. |
| `ingress.className` | `""` | Ingress class name. |
| `ingress.annotations` | `{}` | Ingress annotations. |
| `ingress.tls` | `[]` | Ingress TLS entries. |
| `ingress.path` | `/` | Catch-all proxy ingress path. |
| `ingress.pathType` | `Prefix` | Ingress path type. |

### Runtime, RBAC, And Hazelcast

| Value | Default | Description |
| --- | --- | --- |
| `securityContext.runAsNonRoot` | `true` | Set `runAsNonRoot` on app pods. |
| `env.javaOpts` | `""` | Java options passed to proxy and API pods. |
| `env.javaToolOptions` | unset | Optional non-dev `JAVA_TOOL_OPTIONS` for proxy and API pods. |
| `env.userDir` | `/workspace` | Value for `user.dir` in proxy and API pods. |
| `serviceAccount.create` | `true` | Create a service account for `fleet-api`. |
| `serviceAccount.name` | `""` | Existing service account name, or generated name when empty. |
| `serviceAccount.annotations` | `{}` | Service account annotations. |
| `rbac.create` | `true` | Create role and role binding for mock pod management. |
| `hazelcast.clusterName` | `mock-fleet` | Cluster name used by embedded Hazelcast members. |
| `hazelcast.port` | `5701` | Embedded Hazelcast member and headless-service port. |
| `hazelcast.backupCount` | `1` | Synchronous backup count for distributed mock state. |
| `hazelcast.gracefulShutdownMaxWaitSeconds` | `300` | Maximum wait for graceful member shutdown. |

## Local Minikube Values

`values.minikube.yaml` enables ingress at `mock-fleet.localhost`, sets `fleet.proxy.routing.mode=PATH`, and configures local persistent S3 storage values.

```bash
helm upgrade --install mock-fleet deploy/helm/mock-fleet \
  --namespace mock-fleet \
  --create-namespace \
  -f deploy/helm/mock-fleet/values.minikube.yaml
```
