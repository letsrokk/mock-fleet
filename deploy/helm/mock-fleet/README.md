# mock-fleet Helm Chart

This chart deploys `mock-fleet` as three core Kubernetes services and one optional service:

- `fleet-proxy`: routes incoming HTTP requests to per-mock WireMock pods.
- `fleet-api`: manages mock pods, WireMock config, lifecycle cleanup, Hazelcast state, and persisted mappings.
- `fleet-dash`: serves the dashboard under `/__fleet/`.
- `fleet-mcp`: exposes typed MCP tools under `/__fleet/mcp` when enabled.

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

## Cluster Security Prerequisites

The chart renders `NetworkPolicy` objects, but Kubernetes accepts them even when the installed network plugin does not enforce them. A NetworkPolicy-capable CNI, such as Calico or Cilium, is a deployment prerequisite. Installing and configuring that CNI, and proving that it enforces the rendered selectors, belongs to the cluster operator. `make local-deploy` neither checks nor changes CNI capability. The bridge CNI in the Minikube cluster reviewed for this release accepted the policies but did not enforce them.

The chart installs `admissionregistration.k8s.io/v1` `ValidatingAdmissionPolicy` and `ValidatingAdmissionPolicyBinding` objects by default. Use Kubernetes 1.30 or newer, where [Validating Admission Policy is stable](https://kubernetes.io/docs/reference/access-authn-authz/validating-admission-policy/). The current policy, including both persistent and non-persistent pod shapes, was server-side compiled and exercised on Kubernetes 1.36.4.

The local deployment helper labels its target namespace with `restricted` Pod Security Admission enforce, audit, and warn labels pinned to the API server's current major and minor version. For other deployment workflows, the cluster operator must apply an equivalent namespace policy. Mock Fleet workloads need writable runtime paths, so the hardened manifests do not require a read-only root filesystem.

### Verify NetworkPolicy enforcement

Run this two-pod test after installation. The first pod is an otherwise equivalent unselected positive control. It must reach the cluster-internal Kubernetes service on TCP 443. If it cannot, the result is inconclusive: fix baseline pod-to-service networking before evaluating NetworkPolicy. The second pod has exactly the two labels selected by the managed-WireMock egress policy. Only a successful control followed by a denied selected connection proves enforcement.

The commands use explicit exit states. `mock-fleet-networkpolicy-control` exits 0 only when baseline connectivity works and exits 2 when the test is inconclusive. `mock-fleet-networkpolicy-selected` exits 0 only when policy denies the connection and exits 1 when the internal connection succeeds. `set -e` stops the procedure before the selected test if the control is inconclusive.

```bash
set -euo pipefail
NAMESPACE=mock-fleet
KUBE_API_IP="$(kubectl -n default get service kubernetes -o jsonpath='{.spec.clusterIP}')"

kubectl -n "$NAMESPACE" apply -f - <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: mock-fleet-networkpolicy-control
spec:
  automountServiceAccountToken: false
  restartPolicy: Never
  securityContext:
    runAsNonRoot: true
    seccompProfile:
      type: RuntimeDefault
  containers:
    - name: probe
      image: busybox:1.36
      command:
        - sh
        - -ec
        - |
          if nc -z -w 5 ${KUBE_API_IP} 443; then
            echo "CONTROL PASS: baseline cluster-internal TCP connection succeeded"
            exit 0
          fi
          echo "INCONCLUSIVE: baseline cluster-internal TCP connection failed"
          exit 2
      securityContext:
        runAsNonRoot: true
        runAsUser: 1000
        allowPrivilegeEscalation: false
        capabilities:
          drop: ["ALL"]
      resources:
        requests:
          cpu: 10m
          memory: 16Mi
        limits:
          cpu: 50m
          memory: 32Mi
EOF

kubectl -n "$NAMESPACE" wait --for=jsonpath='{.status.containerStatuses[0].state.terminated}' pod/mock-fleet-networkpolicy-control --timeout=60s
kubectl -n "$NAMESPACE" logs pod/mock-fleet-networkpolicy-control
test "$(kubectl -n "$NAMESPACE" get pod mock-fleet-networkpolicy-control -o jsonpath='{.status.phase}')" = Succeeded

kubectl -n "$NAMESPACE" apply -f - <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: mock-fleet-networkpolicy-selected
  labels:
    app.kubernetes.io/name: mock-fleet-wiremock
    app.kubernetes.io/managed-by: mock-fleet
spec:
  automountServiceAccountToken: false
  restartPolicy: Never
  securityContext:
    runAsNonRoot: true
    seccompProfile:
      type: RuntimeDefault
  containers:
    - name: probe
      image: busybox:1.36
      command:
        - sh
        - -ec
        - |
          if nc -z -w 5 ${KUBE_API_IP} 443; then
            echo "POLICY FAIL: selected cluster-internal TCP connection succeeded"
            exit 1
          fi
          echo "POLICY PASS: selected cluster-internal TCP connection was denied"
          exit 0
      securityContext:
        runAsNonRoot: true
        runAsUser: 1000
        allowPrivilegeEscalation: false
        capabilities:
          drop: ["ALL"]
      resources:
        requests:
          cpu: 10m
          memory: 16Mi
        limits:
          cpu: 50m
          memory: 32Mi
EOF

kubectl -n "$NAMESPACE" wait --for=jsonpath='{.status.containerStatuses[0].state.terminated}' pod/mock-fleet-networkpolicy-selected --timeout=60s
kubectl -n "$NAMESPACE" logs pod/mock-fleet-networkpolicy-selected
test "$(kubectl -n "$NAMESPACE" get pod mock-fleet-networkpolicy-selected -o jsonpath='{.status.phase}')" = Succeeded
```

Remove both pods after a pass, failure, or inconclusive result:

```bash
kubectl -n "$NAMESPACE" delete pod \
  mock-fleet-networkpolicy-control \
  mock-fleet-networkpolicy-selected \
  --ignore-not-found
```

## Routing And Ingress

Ingress is disabled by default. When enabled, the chart routes:

- `/__fleet/api/*` to `fleet-api`
- `/__fleet/mcp` to `fleet-mcp` when `fleet.mcp.enabled=true`
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

`fleet-mcp` reaches the Fleet API and Fleet Proxy through helper-derived ClusterIP DNS names. It does not call the mock-pod resolver. In `PATH` mode it sends `/<mockId>/__admin/...` to Fleet Proxy. In `HOST` mode it connects to the same Proxy ClusterIP and sets `Host: <mockId>.<fleetHost>`.

Fleet Proxy continues to expose direct WireMock `/__admin` requests on ordinary mock URLs without authentication. MCP uses the same external access boundary as the Fleet API and does not change that behavior.

## Lifecycle and API contracts

`fleet-api` initializes its editable ConfigMap before it becomes ready, so either API replica can serve a fresh configuration view. Config rows contain `lifecycle` (`STOPPED`, `STARTING`, `RUNNING`, or `FAILED`) rather than a boolean active flag. Config PUT and DELETE responses are `{config,apply:{mockId,mode,lifecycle}}`. The `restartActive` mode restarts only a STARTING or RUNNING mock and normally returns STARTING while replacement continues.

The chart renders a named WireMock version-catalog ConfigMap and reconciles it with one image policy. With `mockOps.enabled=false`, `wiremock.supportedImageTags` is the exact allowlist, using the repository from `wiremock.containerImage`. With `mockOps.enabled=true`, `mockOps.allowedVersionRange` controls every selectable version and the supported tags seed initial choices within that range. A connected Helm upgrade preserves allowed runtime defaults and in-range discovered choices, applies policy changes, and retains excluded versions for existing pins. When the current default is excluded, `wiremock.containerImage` is the configured fallback. Offline rendering cannot read runtime choices through `lookup` and starts from chart values. With Helm 4, use `helm upgrade --server-side=false` for this shared catalog; server-side apply can conflict with Fleet Mock Ops ownership of runtime catalog fields. Connected client-side upgrades explicitly remove obsolete keys added by the controller when moving versions between selectable and retained entries.

The catalog's `mock-fleet/image-policy` annotation contains JSON with `defaultImage`, `allowedImages`, and `allowedVersionRange`. Static mode writes the complete image allowlist and an empty range; discovered mode writes an empty image list and the interval. The API independently enforces this policy for defaults and new pins. `GET /__fleet/api/config` exposes `defaultVersion`, `versions` as `{version,image,selectable}`, and `catalogResourceVersion`. Retained entries keep an existing baseline or user pin runnable but cannot be selected for a different mock. The catalog has one image per semantic version: changing the allowed image revision for that version replaces its mapping. Active pods are never restarted by policy reconciliation.

Each configuration row exposes the baseline, user, and effective `version`, plus top-level desired `wireMockVersion` and nullable active `runtimeVersion`. `futureOnly` saves the change without replacing an active pod, so desired and runtime versions can differ until the next start. `restartActive` replaces only a STARTING or RUNNING pod; it does not start a STOPPED or FAILED mock. A version absent from the selectable catalog, except the same mock's existing retained pin, returns `UNSUPPORTED_WIREMOCK_VERSION`. A known option outside the desired version's compatibility range returns `UNSUPPORTED_WIREMOCK_OPTION`. Both failures happen before the user ConfigMap changes.

`POST /__fleet/api/mocks/{mockId}/start` is idempotent. It returns 200 for RUNNING or 202 for STARTING with `retryAfterMs: 1000`. Poll the same endpoint until RUNNING. DELETE is also idempotent. It waits for an existing pod to be removed before returning STOPPED and returns STOPPED directly for already stopped or absent mocks. Lifecycle and config failures use `ApiError {code,message,retryable,stateMayHaveChanged,details}`. Optimistic config conflicts use `CONFIG_CONFLICT` and include `expectedVersion` and `currentVersion`.

The full REST schema is checked in at `fleet-api/src/main/resources/META-INF/openapi.yaml` and is served by the running API at `/__fleet/api/openapi?format=json`.

## Prometheus metrics

API, proxy, and MCP expose Prometheus metrics on their existing HTTP listener (the chart's named container port `http`, default `8080`):

| Service | Direct pod scrape path |
| --- | --- |
| `fleet-api` | `/__fleet/api/metrics` |
| `fleet-proxy` | `/__fleet/proxy/metrics` |
| `fleet-mcp` (when enabled) | `/mcp/metrics` |

Each includes JVM memory, garbage collection, threads, class loading, and process/system metrics through [Quarkus Micrometer's standard binders](https://quarkus.io/guides/telemetry-micrometer/), plus HTTP metrics. MCP retains its existing tool metrics. The dashboard, managed WireMock containers, and short-lived Mock Ops command are excluded.

Scrape every replica directly: a load-balanced Service address hides individual process counters. These paths work with a pod IP as the host in both HOST and PATH routing modes; they require no mock subdomain and do not resolve or start a mock. The paths above are direct pod endpoints, not ingress route guarantees.

### Scrape configuration

Prometheus annotations are omitted by default. Set `metrics.enabled=true` to add all four annotations to API, proxy, and MCP pod templates: `prometheus.io/scrape: "true"`, `prometheus.io/port` from the component's `service.ports.targetHttp`, the metrics path above, and `prometheus.io/scheme: "http"`. Use `--set metrics.enabled=true` or a values file:

```yaml
metrics:
  enabled: true
```

The Prometheus scraper or OTel Collector Prometheus receiver must be configured to honor these annotations. Setting `metrics.enabled` changes discovery metadata; it does not disable the metrics endpoints. The example below honors the toggle and annotated path/scheme, using the named `http` container port advertised by `prometheus.io/port`.

Add this job to an existing in-cluster Prometheus configuration. Its service account needs permission to list/watch pods in the target namespace and network access to their HTTP ports. Replace namespace `mock-fleet`, instance `mock-fleet` (the Helm release name), and name `mock-fleet` (the chart name or `nameOverride`) with your deployment values. No Prometheus installation or ServiceMonitor is supplied by this chart. The configuration uses [Prometheus Kubernetes pod discovery and relabeling](https://prometheus.io/docs/prometheus/latest/configuration/configuration/#kubernetes_sd_config).

```yaml
scrape_configs:
  - job_name: mock-fleet
    kubernetes_sd_configs:
      - role: pod
        namespaces:
          names: [mock-fleet]
        selectors:
          - role: pod
            label: app.kubernetes.io/name=mock-fleet,app.kubernetes.io/instance=mock-fleet,app.kubernetes.io/component in (api,proxy,mcp)
    relabel_configs:
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_scrape]
        regex: "true"
        action: keep
      - source_labels: [__meta_kubernetes_pod_phase]
        regex: Running
        action: keep
      - source_labels: [__meta_kubernetes_pod_container_port_name]
        regex: http
        action: keep
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_path]
        target_label: __metrics_path__
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_scheme]
        target_label: __scheme__
      - source_labels: [__meta_kubernetes_namespace]
        target_label: namespace
      - source_labels: [__meta_kubernetes_pod_label_app_kubernetes_io_instance]
        target_label: fleet
      - source_labels: [__meta_kubernetes_pod_label_app_kubernetes_io_component]
        target_label: component
      - source_labels: [__meta_kubernetes_pod_name]
        target_label: pod
```

Confirm one target per API/proxy/MCP replica in Prometheus and `up{job="mock-fleet"} == 1` for each. A direct request with `Accept: text/plain` returns HTTP 200 and Prometheus text containing `jvm_memory_used_bytes`; API scrapes also contain `mock_fleet_mocks`. A missing target indicates discovery/label selection trouble; a discovered target with `up == 0` needs its scrape error inspected for connectivity or endpoint failures.

### API lifecycle metrics

All fixed label combinations are registered before their first event. Labels omit mock IDs, pod names, URLs, and exception messages to bound the number of time series, following [Prometheus instrumentation guidance](https://prometheus.io/docs/practices/instrumentation/).

| Metric | Type | Meaning |
| --- | --- | --- |
| `mock_fleet_mocks{state="running\|starting\|failed"}` | Shared gauge | Current mock counts. A published pod takes precedence over lifecycle state. Starting includes queued and executing starts. Failed state expires after 30 seconds; use the error counter for historical failures. |
| `mock_fleet_capacity_used` | Shared gauge | Distinct mock IDs in the union of capacity reservations, published pods, and RUNNING lifecycle records. |
| `mock_fleet_capacity_limit` | Gauge | Configured cluster-wide maximum active mocks. |
| `mock_fleet_start_queue_depth` | Local gauge | Tasks waiting in this API replica's startup executor. |
| `mock_fleet_start_workers_active` | Local gauge | Startup tasks executing on this replica. |
| `mock_fleet_start_attempts_total{outcome="success\|error\|rejected\|cancelled"}` | Counter | One terminal outcome per submitted startup attempt; callers sharing an existing attempt add no event. Executor rejection is `rejected`; shutdown cancellation of queued tasks is `cancelled`; interrupted executing starts are `error`. |
| `mock_fleet_start_rejections_total{reason="capacity\|queue_full"}` | Counter | Admission rejections at the cluster capacity limit or the local executor queue. |
| `mock_fleet_start_duration_seconds{outcome="success\|error\|cancelled"}` | Histogram | Accepted startup time from submission through completion, including time in the queue. Rejected attempts are excluded. |
| `mock_fleet_pod_deletions_total{outcome="deleted\|already_absent\|error"}` | Counter | Deletion operation outcomes, including idempotent requests; not a count of distinct deleted pods. |
| `mock_fleet_start_reservations_reclaimed_total` | Counter | Stale/expired startup reservations successfully removed by reconciliation. |

The `|` notation in the table lists allowed label values. Startup duration exports `_bucket`, `_count`, and `_sum` series, with bucket boundaries of 0.1, 0.5, 1, 2, 5, 10, 30, 60, and 120 seconds, plus `+Inf`.

Shared gauges read Hazelcast state without reconciliation, state changes, or Kubernetes calls. They are non-atomic observations and can temporarily include stale reservations until ordinary reconciliation removes them. There is no stopped gauge because retained stopped records are not a complete mock inventory. Counters and histograms record events on the handling replica and reset on process restart; they are operational telemetry, not a durable audit log.

### PromQL examples

The discovery labels above identify a deployment by `namespace` and `fleet`. Use `max` across API replicas for shared gauges, and sum local gauges or per-replica counter rates. If querying several clusters, also retain your externally supplied cluster label in every grouping.

```promql
# Active mocks; use state="starting" for pending mocks (queued + executing).
max by (namespace, fleet) (mock_fleet_mocks{state="running"})

# Capacity utilization, percent.
100 * max by (namespace, fleet) (mock_fleet_capacity_used)
  / max by (namespace, fleet) (mock_fleet_capacity_limit)

# Total queued starts across API replicas.
sum by (namespace, fleet) (mock_fleet_start_queue_depth)

# Startup errors per second; a failed gauge can miss short-lived failures.
sum by (namespace, fleet) (rate(mock_fleet_start_attempts_total{outcome="error"}[5m]))

# Admission rejections per second, split by reason.
sum by (namespace, fleet, reason) (rate(mock_fleet_start_rejections_total[5m]))

# Pod deletion errors per second.
sum by (namespace, fleet) (rate(mock_fleet_pod_deletions_total{outcome="error"}[5m]))

# Successful startup p95 in seconds, including queue time.
histogram_quantile(0.95,
  sum by (namespace, fleet, le) (
    rate(mock_fleet_start_duration_seconds_bucket{outcome="success"}[5m])
  )
)
```

The latency query returns `NaN` when no successful starts occurred in the window. Rates need at least two scrapes; choose a window appropriate to your scrape interval.

## Upgrade And Security Notes

- Runtime dependencies now use Quarkus 3.33.3.1 and Hazelcast 5.7.0. Fleet Proxy also rejects absolute, scheme-relative, fragmented, malformed-percent, and backslash-bearing request targets before resolution or outbound I/O, and it removes inbound authority, framing, and hop-by-hop headers before forwarding. These changes do not add API, Admin-route, or MCP authentication.
- The plaintext WireMock options `--ca-keystore-password`, `--keystore-password`, `--key-manager-password`, and `--truststore-password` are unsupported in both `--name=value` and split-argument forms. New writes are rejected before persistence. Existing values are redacted from API output and fail closed when a mock starts. Before upgrading, remove these entries from `wiremock.default.options` and every `wiremock.mocks[].options` list in `<release-fullname>-wiremock-user-config`, and from `wiremock.config.default.options` or `wiremock.config.mocks[].options` in Helm values. Password-protected keystores are unsupported until a Secret-backed path exists. Treat the retained ConfigMap as sensitive until cleanup is complete.
- Saved overrides in `<release-fullname>-wiremock-user-config` are runtime data, not a ConfigMap rendered into desired state. An initialization job runs before Helm installs/upgrades and Argo CD full syncs. It creates an empty configuration only if the object is missing, otherwise preserves its data and adds deletion/pruning protection. The API still requires this object and returns HTTP 503 `CONFIG_UNAVAILABLE` if it is absent; it cannot create ConfigMaps.
- In static mode, `wiremock.containerImage` must match an exact tag in `wiremock.supportedImageTags`, including its numeric image revision. In discovered mode, its semantic version must be inside `mockOps.allowedVersionRange`. All candidate images use exact stable 3.x.y tags with optional numeric image revisions; digests, floating tags, prereleases, Alpine variants, and duplicate semantic versions in the seed list are rejected. Replace the removed `mockOps.defaultVersionConstraint` key with `mockOps.allowedVersionRange`, for example `[3.13,3.14)` instead of `3.13.x`. The old key is rejected by the chart schema.
- Managed resources accept only `cpu` and `memory`. Missing or partial keys inherit the chart baseline; an empty override cannot erase it. Effective requests must meet `wiremock.resourcePolicy.requestFloor`, limits must not exceed `wiremock.resourcePolicy.limitCeiling`, and each request must not exceed its limit. Numeric-looking quantities such as `4` or `0.5` must be quoted so YAML supplies strings to chart validation; unit-bearing values such as `128Mi` are already strings and need no quotes. DecimalSI, `Ki` through `Pi`, and bounded exponent forms are supported; `Ei` is rejected because its comparison differs from the Kubernetes client used by the API.
- Numeric WireMock workload-shaping options now accept only catalog-advertised integers and bounds. Existing fractional, negative, or oversized values fail validation and must be corrected before a mock can start.
- Mock starts reserve cluster-wide capacity across replicas. `fleet.api.maxActiveMocks` is the cluster-wide maximum for distinct reserved, starting, or running mocks; exceeding it returns HTTP 429 `MOCK_CAPACITY_EXHAUSTED`. Each API replica has its own executor with `fleet.api.maxConcurrentStarts` workers and `fleet.api.queuedStartCapacity` waiting positions. Aggregate executor capacity is each value multiplied by the API replica count, while the cluster-wide active limit still applies. The default Minikube deployment has two API replicas, so it provides up to 8 start workers and 32 queue positions, subject to the 20-mock cluster-wide limit. A full replica-local queue returns HTTP 503 `MOCK_START_QUEUE_FULL`. Explicit successful starts initialize their idle-cleanup timestamp.
- Mapping tree reads and recursive folder deletion apply inclusive `fleet.api.mappings.maxDepth` and `maxEntries` budgets. Overflow returns HTTP 400 `MAPPINGS_TRAVERSAL_LIMIT`; storage or unsupported secure-traversal behavior returns retryable HTTP 503 `MAPPINGS_STORAGE_ERROR`. Recursive deletion discovers and validates the bounded set before deleting, so a traversal-limit failure deletes nothing.
- Managed-pod deletion performs a fresh Kubernetes GET, checks the two stable ownership labels plus the expected `mock-fleet/mock-id`, and deletes with the fetched UID as a precondition. Missing pods remain idempotent; a wrong label, missing UID, or same-name replacement fails closed. The API Role has no Deployment authority and can mutate only the named retained user ConfigMap.
- `resourceQuota.enabled=true` supplies a second boundary if application admission fails. Size pod, CPU, and memory quota for `fleet.api.maxActiveMocks`, all API replicas, the aggregate per-replica start burst, every enabled fixed workload, rollout surge, and expected operational or probe pods. Quota may intentionally cap the usable mock count below `maxActiveMocks`; undersizing it can also block starts, upgrades, and probes.
- `make local-deploy` applies `restricted` PSA enforce, audit, and warn labels at the current server minor. Before upgrading a shared namespace, verify that every non-Mock-Fleet workload also satisfies that profile; PSA can reject unrelated noncompliant pods. A direct Helm install does not manage namespace labels, so its operator must apply and maintain the equivalent policy.
- The admission policy requires the dedicated WireMock service account, exact generated pod shape, restricted security context, an image present under `selectable.*` or `retained.*` in the named version-catalog ConfigMap, the resource envelope, and one supported workload-identity mode. Its binding uses that exact ConfigMap as a parameter, `failurePolicy: Fail`, and `parameterNotFoundAction: Deny`; a missing or unavailable parameter denies managed-pod creation. `wiremock.admissionPolicy.enabled=false` removes the Kubernetes boundary that constrains a compromised API service account. Kubernetes 1.30 or newer is required while it is enabled; compatibility was verified on 1.36.4.
- `fleet.api.networkPolicy.enabled=false` removes API/Hazelcast ingress isolation. `fleet.mcp.outbound.networkPolicy.enabled=false` removes managed-WireMock private/cluster-network egress isolation even when MCP itself is disabled. `resourceQuota.enabled=false` removes the namespace resource backstop. Disable any of these only when another cluster control provides the same boundary.

Edit the retained configuration in place before the first post-upgrade start when it contains a prohibited password option. Replace `<release-fullname>` with the chart's resolved full name; do not copy the old plaintext values into issue trackers or command history.

```bash
NAMESPACE=mock-fleet
kubectl -n "$NAMESPACE" edit configmap <release-fullname>-wiremock-user-config
```

## Persistent Mappings

Persistent mappings are disabled by default. To enable them, set `storage.persistent=true`, use `storage.type=s3`, and provide `storage.s3.bucket`.

The chart creates a static S3 CSI PV/PVC and mounts it into:

- spawned WireMock pods at `/home/wiremock`
- `fleet-api` at `storage.mappingsPath`

`storage.s3.authenticationSource=pod` asks the S3 CSI driver to use the mounting pod's identity. API replicas use the API service account and its `serviceAccount.annotations`; managed WireMock pods use the separate service account selected by `wiremock.serviceAccount.name` and its `wiremock.serviceAccount.annotations`. IRSA or EKS Pod Identity admission can inject one audience-bound projected token plus its read-only mount and AWS credential environment variables. This token is separate from the general Kubernetes API token: managed WireMock pods keep `automountServiceAccountToken=false`.

The admission policy accepts zero identity projections or exactly one projection with an audience in `wiremock.admissionPolicy.workloadIdentity.allowedTokenAudiences` and an expiry from 600 through 86400 seconds. A configured non-EKS audience must use the standard read-only `/var/run/secrets/eks.amazonaws.com/serviceaccount` mount plus one nonempty `AWS_ROLE_ARN` and one matching `AWS_WEB_IDENTITY_TOKEN_FILE`. The reserved `pods.eks.amazonaws.com` mode requires the `eks.amazonaws.com/pod-identity=enabled` label, volume and token path `eks-pod-identity-token`, the read-only `/var/run/secrets/pods.eks.amazonaws.com/serviceaccount` mount, and the standard `AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE` and `AWS_CONTAINER_CREDENTIALS_FULL_URI=http://169.254.170.23/v1/credentials` values. Mixed modes, extra identity volumes or mounts, duplicate credential variables, unconfigured audiences, and credential variables without their token are denied. The application container and the persistent mappings initializer must independently have the selected identity shape.

`storage.s3.mountOptions` includes `allow-delete`, `allow-overwrite`, and `metadata-ttl minimal` by default. The first two options support dashboard and MCP file mutations. The minimal metadata TTL reduces cross-mount staleness while the CSI data cache is enabled; Mountpoint does not coordinate concurrent writes to the same key, so clients must not race those writes.

## Values

### Global

| Value | Default | Description |
| --- | --- | --- |
| `nameOverride` | `""` | Override the chart name used in resource names. |
| `fullnameOverride` | `""` | Override the full release resource name. |
| `namespaceOverride` | `""` | Override the namespace rendered into namespaced resources. |
| `clusterDomain` | `cluster.local` | Kubernetes cluster DNS suffix used for internal API and Proxy service URLs. |
| `metrics.enabled` | `false` | Add Prometheus scrape annotations to API, proxy, and MCP pods only when enabled. Requires a scraper configured to honor pod annotations. |

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
| `fleet.proxy.logging.json` | `false` | Enable JSON console logging for the proxy. |
| `fleet.proxy.logging.level` | `INFO` | Proxy `com.github.letsrokk` log level; use `TRACE` to log proxied requests. |
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
| `fleet.api.maxActiveMocks` | `20` | Maximum distinct mocks that may be reserved, starting, or running across API replicas. Exhaustion returns HTTP 429 `MOCK_CAPACITY_EXHAUSTED`. |
| `fleet.api.maxConcurrentStarts` | `4` | Pod-start workers per API replica; must not exceed cluster-wide `maxActiveMocks`. Aggregate workers equal this value times the API replica count. |
| `fleet.api.queuedStartCapacity` | `16` | Waiting start positions per API replica. Aggregate positions equal this value times the API replica count; replica-local saturation returns HTTP 503 `MOCK_START_QUEUE_FULL`. |
| `fleet.api.mappings.maxDepth` | `32` | Inclusive maximum relative depth for mapping tree reads and recursive deletion. |
| `fleet.api.mappings.maxEntries` | `10000` | Inclusive maximum examined entries, including the selected mock root, for one mapping traversal. |
| `fleet.api.networkPolicy.enabled` | `true` | Render API ingress isolation. Disabling it exposes HTTP and Hazelcast to any connectivity the cluster otherwise permits. |
| `fleet.api.logging.json` | `false` | Enable JSON console logging for the API. |
| `fleet.api.logging.level` | `INFO` | API `com.github.letsrokk` log level. |
| `fleet.api.replicas` | `2` | API replica count when dev mode is disabled; must be at least two for embedded Hazelcast redundancy. |
| `fleet.api.terminationGracePeriodSeconds` | `30` | Time allowed for graceful Hazelcast member shutdown and partition migration. |
| `fleet.api.updateStrategy.type` | `Recreate` | API deployment update strategy. The default stops the embedded Hazelcast cluster before starting a new version because Hazelcast Community Edition does not support mixed-version rolling member upgrades. |
| `fleet.api.updateStrategy.rollingUpdate.maxUnavailable` | `1` | Maximum unavailable API pods when `updateStrategy.type=RollingUpdate`; ignored by the default `Recreate` strategy. |
| `fleet.api.updateStrategy.rollingUpdate.maxSurge` | `1` | Maximum additional API pods when `updateStrategy.type=RollingUpdate`; ignored by the default `Recreate` strategy. |
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

### MCP

MCP is disabled by default. It uses Streamable HTTP and must run with one replica. MCP verifies each target mock runtime with `/__admin/version`. WireMock 3.0.x does not expose that endpoint, so MCP verifies the legacy Admin mapping response and resolves that mock's runtime version from Fleet API. If neither source reports a runtime version, version-gated tools fail closed.

| Value | Default | Description |
| --- | --- | --- |
| `fleet.mcp.enabled` | `false` | Deploy the MCP service and add its ingress route. |
| `fleet.mcp.image.repository` | `ghcr.io/letsrokk/mock-fleet/mcp` | MCP image repository. |
| `fleet.mcp.image.tag` | `""` | MCP image tag. Defaults to the chart `appVersion` when empty. |
| `fleet.mcp.image.pullPolicy` | `IfNotPresent` | MCP image pull policy. |
| `fleet.mcp.replicas` | `1` | MCP replica count. The stable transport requires exactly one. |
| `fleet.mcp.apiBaseUrl` | `""` | Fleet API base URL override. Empty renders the API ClusterIP FQDN. |
| `fleet.mcp.proxyBaseUrl` | `""` | Fleet Proxy base URL override. Empty renders the Proxy ClusterIP FQDN. |
| `fleet.mcp.routing.mode` | `""` | `PATH` or `HOST`; empty inherits `fleet.proxy.routing.mode`. |
| `fleet.mcp.routing.fleetHost` | `""` | Host suffix used to select a mock in `HOST` mode; empty inherits `ingress.host`. |
| `fleet.mcp.allowedOrigins` | `[]` | Browser origins allowed to initialize MCP sessions; empty derives the ingress origin. |
| `fleet.mcp.outbound.exceptions` | `[]` | Explicit target hosts allowed through recorder, proxy, and webhook target checks. |
| `fleet.mcp.outbound.allowedListeners` | `[]` | WireMock serve-event listener names allowed in mappings, such as `webhook`. |
| `fleet.mcp.outbound.networkPolicy.enabled` | `true` | Render managed-WireMock egress isolation independently of `fleet.mcp.enabled`. A NetworkPolicy-capable CNI must enforce it. |
| `fleet.mcp.outbound.networkPolicy.dnsNamespace` | `kube-system` | Namespace containing the cluster DNS pods allowed by the WireMock egress policy. |
| `fleet.mcp.outbound.networkPolicy.dnsPodSelector` | `{k8s-app: kube-dns}` | Labels selecting the cluster DNS pods allowed by the WireMock egress policy. |
| `fleet.mcp.outbound.networkPolicy.allowedCidrs` | `[]` | Private or special-use CIDRs explicitly allowed at connection time. Configure the corresponding host in `outbound.exceptions` too. |
| `fleet.mcp.sensitiveHeaders` | common credential headers | Headers redacted from traffic, journal, and recorder results. |
| `fleet.mcp.timeout` | `10S` | Normal internal Fleet API and Proxy request timeout. |
| `fleet.mcp.lifecycleTimeout` | `70S` | Timeout for pod deletion through `stop_mock`; keep this longer than `fleet.api.podCreationTimeout`. |
| `fleet.mcp.defaultPageSize` | `50` | Default collection page size. |
| `fleet.mcp.maxPageSize` | `200` | Maximum collection page size. |
| `fleet.mcp.maxPayloadBytes` | `1048576` | Maximum complete structured result size. |
| `fleet.mcp.includedBodyBytes` | `262144` | Maximum body content included in a result. |
| `fleet.mcp.dependencyHealthTimeout` | `1S` | Timeout for each Fleet API and Fleet Proxy readiness check. |
| `fleet.mcp.maxCollectionScanBytes` | `67108864` | Maximum upstream bytes scanned while producing one collection page or checking references and recovery state. |
| `fleet.mcp.maxCollectionScanItems` | `100000` | Maximum upstream items scanned while producing one collection page or checking references and recovery state. |
| `fleet.mcp.service.type` | `ClusterIP` | MCP service type. |
| `fleet.mcp.service.ports.http` | `80` | MCP service HTTP port. |
| `fleet.mcp.service.ports.targetHttp` | `8080` | MCP container HTTP port. |

The MCP pod does not receive Kubernetes RBAC or a mounted service-account token. Only HTTP and HTTPS targets are accepted. MCP validates target hostnames before configuration, and the enabled-by-default WireMock egress `NetworkPolicy` renders independently of MCP. A capable CNI must enforce that policy when WireMock connects. Private, loopback, link-local, multicast, metadata, and special-use destinations remain blocked unless both the host is listed in `outbound.exceptions` and its address range is listed in `outbound.networkPolicy.allowedCidrs`. Configure the DNS namespace and pod selector for clusters that do not label CoreDNS as `k8s-app=kube-dns`; disable this policy only when another connection-time egress control provides the same boundary.

MCP publishes 31 tools, including `start_mock` and `get_recording_status`; `recording_status` is not an alias. `list_mocks` includes configured inactive and active mocks with desired/runtime version state and a saved-config flag. Every WireMock Admin and traffic tool starts or checks the mock first. STARTING becomes a structured, retryable `MOCK_STARTING` result without proxying Admin traffic. Tool successes use strict tool-specific wrappers, and every failure uses `{error:{code,message,retryable,stateMayHaveChanged,details}}` with `isError: true`. Byte-bearing inputs and results use `{body:{encoding:utf8|base64,data,sizeBytes}}`. Recording stop/snapshot returns candidate IDs, count, and explicit match status. See `docs/mcp-contract.md` for examples and recovery behavior.

### WireMock

| Value | Default | Description |
| --- | --- | --- |
| `wiremock.podNamePrefix` | `mock-fleet` | Base prefix used for spawned WireMock pod names. |
| `wiremock.containerName` | `wiremock` | Container name used for spawned WireMock pods. |
| `wiremock.containerImage` | `wiremock/wiremock:3.13.2-2` | Exact initial/fallback default image. Must match a static allowed tag or fall within the discovered interval. |
| `wiremock.supportedImageTags` | `[3.13.2-2, 3.12.1-2, 3.11.0-1, 3.10.0-1, 3.9.2-1]` | Exact static allowlist; initial seeds when Mock Ops is enabled. Each semantic version must be unique. |
| `wiremock.containerImagePullPolicy` | `IfNotPresent` | Image pull policy for spawned WireMock pods. |
| `wiremock.terminationGracePeriodSeconds` | `5` | Graceful shutdown window for spawned WireMock pods. Fleet waits for full pod removal before replacement. |
| `wiremock.serviceAccount.create` | `true` | Create a dedicated service account for managed WireMock pods. |
| `wiremock.serviceAccount.name` | `""` | Service account name. A generated name is used when creation is enabled and this is empty. |
| `wiremock.serviceAccount.annotations` | `{}` | Dedicated WireMock service-account annotations for IRSA, EKS Pod Identity, or another workload-identity integration. |
| `wiremock.serviceAccount.imagePullSecrets` | `[]` | Existing pull-secret references, such as `[{name: wiremock-pull}]`, attached only to the chart-managed WireMock ServiceAccount. |
| `wiremock.admissionPolicy.enabled` | `true` | Install fail-closed admission policy and binding for API-created managed pods. Requires Kubernetes 1.30 or newer; disabling it removes pod-shape enforcement. |
| `wiremock.admissionPolicy.workloadIdentity.allowedTokenAudiences` | `[sts.amazonaws.com, pods.eks.amazonaws.com]` | Audiences accepted for one injected identity token. Adding an audience expands the admitted workload-identity boundary. Non-EKS audiences use the IRSA-shaped contract; `pods.eks.amazonaws.com` is reserved for the exact EKS Pod Identity shape. |
| `wiremock.resourcePolicy.requestFloor.cpu` | `"100m"` | Minimum effective WireMock CPU request. |
| `wiremock.resourcePolicy.requestFloor.memory` | `128Mi` | Minimum effective WireMock memory request. |
| `wiremock.resourcePolicy.limitCeiling.cpu` | `"4"` | Maximum effective WireMock CPU limit. |
| `wiremock.resourcePolicy.limitCeiling.memory` | `4Gi` | Maximum effective WireMock memory limit. |
| `wiremock.config.default.options` | `[]` | Default WireMock CLI options for all mocks. |
| `wiremock.config.default.resources.requests.cpu` | `"0.5"` | Default WireMock CPU request. |
| `wiremock.config.default.resources.requests.memory` | `512Mi` | Default WireMock memory request. |
| `wiremock.config.default.resources.limits.cpu` | `"1"` | Default WireMock CPU limit. |
| `wiremock.config.default.resources.limits.memory` | `1Gi` | Default WireMock memory limit. |
| `wiremock.config.mocks` | `[]` | Per-mock WireMock config overrides. |

Set `wiremock.serviceAccount.create=false` with a name to use an existing dedicated service account. When admission is enabled, the resolved WireMock and API service-account names must differ, and WireMock must resolve to a nonempty name. The chart rejects a shared identity instead of falling back to the namespace default service account.

### Fleet Mock Ops

| Value | Default | Description |
| --- | --- | --- |
| `mockOps.enabled` | `false` | Deploy the Fleet Mock Ops CronJob, ServiceAccount, and least-privilege RBAC. |
| `mockOps.schedule` | `"0 2 * * *"` | Cron schedule. Jobs use `Forbid` concurrency and make one attempt with no same-Job retry. |
| `mockOps.timeZone` | `Etc/UTC` | Kubernetes CronJob time zone. |
| `mockOps.allowedVersionRange` | `"[3.0,4.0)"` | Numeric interval for every discovered selectable version and the default. Supports `[`, `(`, `]`, and `)`; omitted patch means `.0`. |
| `mockOps.minorLines` | `5` | Number of latest stable 3.x minor lines kept selectable, from 1 through 50. |
| `mockOps.registry.url` | `https://registry-1.docker.io` | Registry V2 HTTP(S) origin. |
| `mockOps.registry.repository` | `wiremock/wiremock` | Repository path used by the Registry V2 tag-discovery API. |
| `mockOps.registry.imageRepository` | `""` | Pullable image repository written to the catalog. Empty derives it from the registry origin and API repository. |
| `mockOps.registry.credentialsSecretName` | `""` | Existing Secret whose `username` and `password` keys provide optional registry credentials. |
| `mockOps.image.repository` | `ghcr.io/letsrokk/mock-fleet/mock-ops` | Fleet Mock Ops image repository. |
| `mockOps.image.tag` | `""` | Fleet Mock Ops image tag. Defaults to the chart `appVersion` when empty. |
| `mockOps.image.pullPolicy` | `IfNotPresent` | Fleet Mock Ops image pull policy. |
| `mockOps.serviceAccount.create` | `true` | Create a dedicated Fleet Mock Ops ServiceAccount. |
| `mockOps.serviceAccount.name` | `""` | Fleet Mock Ops ServiceAccount name. A generated name is used when creation is enabled and this is empty. |
| `mockOps.serviceAccount.annotations` | `{}` | Annotations for the Fleet Mock Ops ServiceAccount. |
| `mockOps.resources.requests.cpu` | `"0.05"` | Fleet Mock Ops CPU request. |
| `mockOps.resources.requests.memory` | `128Mi` | Fleet Mock Ops memory request. |
| `mockOps.resources.limits.cpu` | `"0.5"` | Fleet Mock Ops CPU limit. |
| `mockOps.resources.limits.memory` | `512Mi` | Fleet Mock Ops memory limit. |

Fleet Mock Ops implements the Registry V2 tag-list API, including pagination, optional HTTP Basic credentials, and Bearer-token challenges. If credentials are configured, the Secret must contain both exact keys. An HTTPS registry accepts only HTTPS Bearer realms, including legitimate cross-origin services such as `auth.docker.io`. An HTTP registry accepts only a same-origin HTTP realm and is intended for a controlled local test registry. Realm userinfo is always rejected, and configured credentials are never sent cross-origin over HTTP. With an empty `imageRepository`, the Docker Hub defaults remain `wiremock/wiremock:<tag>`; another registry derives `<registry-host[:port]>/<repository>:<tag>`. Bracketed IPv6 image-repository authorities are not supported; use a DNS registry name. Set `imageRepository` when the pullable image name uses a different host or path from tag discovery.

For a private registry, configure discovery credentials and image-pull credentials separately:

```yaml
wiremock:
  containerImage: registry.example.com/team/wiremock:3.13.2-2
  serviceAccount:
    imagePullSecrets:
      - name: wiremock-pull
mockOps:
  enabled: true
  allowedVersionRange: "[3.0,4.0)"
  registry:
    url: https://registry.example.com
    repository: team/wiremock
    imageRepository: registry.example.com/team/wiremock
    credentialsSecretName: wiremock-discovery
```

Create `wiremock-pull` as a `kubernetes.io/dockerconfigjson` Secret in the deployment namespace. Kubernetes inherits its reference from the managed WireMock ServiceAccount when it creates pods. Create `wiremock-discovery` in the same namespace with `username` and `password` keys for registry tag discovery. The chart only references existing Secrets; it does not create them or copy credentials into the catalog. If `wiremock.serviceAccount.create=false`, attach `imagePullSecrets` to that external ServiceAccount yourself; the chart does not modify it. Static mode needs only pull credentials and an exact `supportedImageTags` allowlist.

Each run reads the named baseline, user, and catalog ConfigMaps exactly once. It computes references from the effective per-mock merge: a baseline row applies only when no user row has the same ID, and a user row with an omitted or null version clears a baseline pin. It ignores unstable tags, filters candidates by `allowedVersionRange`, and selects the newest image revision for the latest patch in each of the newest `minorLines` eligible stable 3.x lines. The default advances to a newer eligible candidate; an excluded current default falls back to `wiremock.containerImage`. An allowed current default outside that latest-minor window stays selectable. Interval endpoints are numeric `major.minor` or `major.minor.patch`, with no whitespace; all four bracket combinations work, and equal endpoints require both brackets closed. Image revisions do not affect interval comparison.

A version leaving the selectable set receives one reconciliation cycle as `retained.*`, which lets a concurrent API save keep referencing it. A referenced retained version remains; an unreferenced version already retained is removed on the next reconciliation. Fleet Mock Ops preserves each retained version's exact image. It does not mutate configuration and has no Pod permissions, so it cannot restart active mocks or resolve desired/runtime drift. The chart passes `MOCK_FLEET_WIREMOCK_ALLOWED_VERSION_RANGE` and `MOCK_FLEET_WIREMOCK_DEFAULT_IMAGE` to the job.

Reconciliation validates the complete registry result, both configuration documents, every referenced version, and every current catalog entry before it performs one catalog update. The catalog may contain only `defaultVersion`, `selectable.<exact-version>`, and `retained.<exact-version>` keys; every image must be exact and match its key, a version cannot occur in both sections, and the default must be selectable. A malformed response, document, or catalog entry, missing reference, invalid interval, or other precondition failure leaves the catalog unchanged. The update carries the ConfigMap's observed `resourceVersion`; a concurrent write produces a Kubernetes conflict and the Job fails without overwriting the newer catalog. `backoffLimit: 0` gives each scheduled Job one attempt and no same-Job retry. The next scheduled Job reconciles from a fresh snapshot.

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
| `storage.s3.mountOptions` | `[allow-delete, allow-overwrite, metadata-ttl minimal]` | S3 CSI mount options for shared API/WireMock mutations and reduced cross-mount staleness. They do not coordinate concurrent writes to one key. |

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
| `env.javaOpts` | `""` | Java options passed to proxy, API, and MCP pods. |
| `env.javaToolOptions` | unset | Optional non-dev `JAVA_TOOL_OPTIONS` for proxy, API, and MCP pods. |
| `env.userDir` | `/workspace` | Value for `user.dir` in proxy, API, and MCP pods. |
| `serviceAccount.create` | `true` | Create a service account for `fleet-api`. |
| `serviceAccount.name` | `""` | Existing service account name, or generated name when empty. |
| `serviceAccount.annotations` | `{}` | Service account annotations. |
| `rbac.create` | `true` | Create role and role binding for mock pod management. |
| `resourceQuota.enabled` | `true` | Create the namespace quota backstop. Disabling it removes the cluster-side aggregate pod/CPU/memory boundary. |
| `resourceQuota.hard.pods` | `30` | Namespace pod quota; size for active mocks, fixed workloads, and rollout or operator pods. |
| `resourceQuota.hard.requests.cpu` | `"16"` | Aggregate namespace CPU-request quota. |
| `resourceQuota.hard.requests.memory` | `16Gi` | Aggregate namespace memory-request quota. |
| `resourceQuota.hard.limits.cpu` | `"36"` | Aggregate namespace CPU-limit quota. |
| `resourceQuota.hard.limits.memory` | `36Gi` | Aggregate namespace memory-limit quota. |
| `hazelcast.clusterName` | `mock-fleet` | Cluster name used by embedded Hazelcast members. |
| `hazelcast.port` | `5701` | Embedded Hazelcast member and headless-service port. |
| `hazelcast.backupCount` | `1` | Synchronous backup count for distributed mock state. |
| `hazelcast.gracefulShutdownMaxWaitSeconds` | `30` | Maximum wait for graceful member shutdown. |

## Saved User Configuration

The chart initializes `<fullname>-wiremock-user-config` through a Helm `pre-install,pre-upgrade` / Argo CD `PreSync` job. The job uses the existing Mock Ops image with `initialize-user-config`; it runs even when scheduled discovery is disabled. It creates missing configuration, preserves every existing data value, and marks the object with `helm.sh/resource-policy: keep` and `argocd.argoproj.io/sync-options: Prune=false,Delete=false`. `argocd.argoproj.io/compare-options: IgnoreExtraneous` prevents a formerly tracked ConfigMap from leaving the application OutOfSync. Updates carry the observed resource version, so a concurrent API save causes a retry instead of data loss. The user ConfigMap is absent from the chart's rendered desired resources.

For Argo CD upgrades from v1.5.1 or v2.0–v2.2, run a full sync with hooks enabled. The initializer protects the existing ConfigMap before the sync phase can prune a formerly tracked object. No application-level `ignoreDifferences` rule is required for this ConfigMap with this chart. Selective sync does not run hooks; do not use it for the first install or migration. Other render/apply tools must execute the initializer before applying or pruning resources. A missing ConfigMap can be recreated empty by the next initialization run, but deleted saved overrides require a backup restore.

The initializer has its own `<fullname>-user-config-init` ServiceAccount and temporary hook RBAC. Kubernetes cannot restrict `create` by resource name, so only this bootstrap identity gets namespace-scoped ConfigMap creation; its `get` and `update` permissions are restricted to the user ConfigMap. Successful hooks are cleaned up. The API retains only named `get`, `watch`, `update`, and `patch` operations on user configuration, and cannot create, list, or delete ConfigMaps. The initializer uses `mockOps.image` and `mockOps.resources`; custom images must include the initialization command.

If `rbac.create=false`, provision the initializer's Role/RoleBinding with those permissions before syncing. Grant the API service account its four named ConfigMap operations and pod `get`, `list`, `create`, and `delete`. When Fleet Mock Ops is enabled, grant its service account named `get` on `<fullname>-wiremock-config` and `<fullname>-wiremock-user-config`, plus named `get`, `update`, and `patch` on `<fullname>-wiremock-version-catalog`. Do not restore namespace-wide ConfigMap or Deployment authority to the API.

## Local Minikube Values

`values.minikube.yaml` enables a Traefik ingress, MCP, and Fleet Mock Ops at `mock-fleet.minikube.localhost`, attaches the ingress to the `websecure` entrypoint with router TLS enabled, sets `fleet.proxy.routing.mode=PATH`, and configures local persistent S3 storage values. Run `minikube tunnel` while using the deployment and trust the Minikube local CA in clients. The dashboard is available at `https://mock-fleet.minikube.localhost/__fleet/`; MCP uses `https://mock-fleet.minikube.localhost/__fleet/mcp`; the default WireMock mock is available at `https://mock-fleet.minikube.localhost/wiremock`. `make local-deploy` applies restricted PSA labels but does not inspect or alter Minikube's CNI; run the denial probe above before relying on NetworkPolicy.

```bash
helm upgrade --install mock-fleet deploy/helm/mock-fleet \
  --namespace mock-fleet \
  --create-namespace \
  -f deploy/helm/mock-fleet/values.minikube.yaml
```

The repository Makefile builds local images directly in the Minikube Docker daemon. Force a specific image rebuild after pulling clean source changes with `make local-deploy REBUILD=mcp`, replacing `mcp` with `dash`, `api`, `proxy`, or `mock-ops` as needed. Use `REBUILD=all` to rebuild every local image.
