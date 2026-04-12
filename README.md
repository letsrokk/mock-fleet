# mock-fleet

`mock-fleet` is a Quarkus service that routes incoming HTTP requests to per-mock WireMock pods in Kubernetes. The target pod is selected either from the request `Host` header or from the first URL path segment, depending on configuration, and the service creates or reuses a WireMock pod for that mock ID.

The service also exposes a small internal dashboard under `/__fleet/` for inspecting and manually deleting active mock pods. Its management API and static assets remain reserved there as well.

## How routing works

- `HOST` mode:
  - `demo.example.test` routes to mock ID `demo`
  - `demo.example.test:8080` also routes to mock ID `demo`
  - single-label hosts like `localhost` are rejected and do not spawn mocks
  - invalid or empty `Host` headers are rejected with HTTP `400`
- `PATH` mode:
  - `/demo` routes to mock ID `demo` and is forwarded upstream as `/`
  - `/demo/nested/path?alpha=1` routes to mock ID `demo` and is forwarded upstream as `/nested/path?alpha=1`
  - requests without a first path segment are rejected with HTTP `400`

The proxy forwards method, path, query string, request body, and incoming headers to the selected WireMock pod on port `8080`.

Reserved local routes:

- `/__fleet/` serves the dashboard UI
- `/__fleet/assets/...` serves dashboard static assets
- `/__fleet/api/mocks` lists active mock pods
- `DELETE /__fleet/api/mocks/{mockId}` deletes an active mock pod manually
- `/favicon.ico` is handled locally and does not create or proxy a mock request

## Requirements

- Java 21
- Maven, or the checked-in Maven wrapper with working network access to download wrapper dependencies
- Access to a Kubernetes cluster for real pod management
- Hazelcast available through the configured client config map when running in Kubernetes

## Local development

Run the app in dev mode:

```bash
./mvnw quarkus:dev
```

Important runtime expectations:

- in `HOST` mode, requests must include a multi-label `Host` header that contains the mock ID in the first label
- in `PATH` mode, requests must include the mock ID as the first URL path segment
- Kubernetes credentials must be available to the Fabric8 client
- Hazelcast client configuration is loaded from `/etc/hazelcast/hazelcast-client.yaml` in Kubernetes deployments

## Configuration

Main application settings live in [`application.yaml`](/home/dmitrymayer/projects/github/mock-fleet/src/main/resources/application.yaml).

- `mock-fleet.inactivity-threshold`: how long an inactive mock pod may live before cleanup
- `mock-fleet.pod-creation-timeout`: how long to wait for a newly created pod to reach `Running`
- `mock-fleet.wiremock-image`: pinned WireMock image used for spawned mock pods
- `mock-fleet.namespace`: default namespace used for runtime-created mock pods and services when the Kubernetes client has no active namespace
- `mock-fleet.routing.mode`: routing strategy, either `HOST` or `PATH`
- `quarkus.quinoa.*`: frontend build/serve settings for the internal React dashboard
  `quarkus.quinoa.ui-dir` defaults to `${user.dir}/src/main/webui` and can be overridden with `QUINOA_UI_DIR`
- `quarkus.kubernetes.*`: generated Deployment, Service, RBAC, probes, resource requests/limits, and ingress settings
- `quarkus.helm.*`: generated Helm chart settings and additional values/schema mappings

## Kubernetes and Helm

- generated Kubernetes manifests now include readiness and liveness probes via SmallRye Health
- the application Deployment declares explicit CPU and memory requests/limits
- pod security defaults require the application to run as non-root
- app RBAC is narrowed to `get`, `list`, `create`, and `delete` for pods and services
- generated manifests default to namespace `mock-fleet`
- the Helm chart disables the Ingress resource by default; enable it with `--set app.ingress.enabled=true`
- Helm values expose image pull policy, resource requests/limits, and selected `mock-fleet.*` runtime settings through environment variables

Namespace behavior:

- runtime-created mock pods and services use the Fabric8 client namespace when one is available
- otherwise, the app falls back to `mock-fleet.namespace`, which defaults to `mock-fleet`
- generated Quarkus Kubernetes manifests also target `mock-fleet` by default

## Tests

The test suite now covers:

- host-header parsing and validation
- path-based routing and prefix stripping
- dashboard UI rooted at `/__fleet/` with management API and assets isolated there
- proxy dispatch and nested path forwarding
- request-header forwarding
- idle/orphan pod cleanup decisions
- pod deletion result handling

Run tests with:

```bash
./mvnw test
```

## Minikube workflow

The primary local Kubernetes workflow uses Quarkus remote dev. First deploy the app to Minikube with the standalone `dev` profile:

```bash
./mvnw package -DskipTests -Dquarkus.profile=dev
helm dependency build target/helm/kubernetes/mock-fleet
helm upgrade --install mock-fleet target/helm/kubernetes/mock-fleet \
  --namespace mock-fleet \
  --create-namespace \
  --set app.ingress.enabled=true
kubectl wait --namespace mock-fleet --for=condition=Ready pod --timeout=1m -l app.kubernetes.io/name=mock-fleet
kubectl patch ingress mock-fleet --namespace mock-fleet --type merge \
  -p '{"spec":{"rules":[{"host":"mock-fleet.localhost","http":{"paths":[{"backend":{"service":{"name":"mock-fleet","port":{"name":"http"}}},"path":"/","pathType":"Prefix"}]}},{"host":"*.mock-fleet.localhost","http":{"paths":[{"backend":{"service":{"name":"mock-fleet","port":{"name":"http"}}},"path":"/","pathType":"Prefix"}]}}]}}'
```

Then connect from your workstation with Quarkus remote dev:

```bash
./mvnw quarkus:remote-dev -Dquarkus.profile=dev -Dquarkus.live-reload.url=http://mock-fleet.localhost
```
In the `dev` profile, the container image includes Node.js and the frontend workspace so Quinoa can start inside the pod during remote dev. The `/__fleet/` dashboard remains available in this profile.

If you are not using Minikube ingress, expose the app first and then point remote dev at the port-forwarded URL:

```bash
kubectl port-forward --namespace mock-fleet service/mock-fleet 8080:8080
./mvnw quarkus:remote-dev -Dquarkus.profile=dev -Dquarkus.live-reload.url=http://127.0.0.1:8080
```

Optional helpers:

```bash
kubectl port-forward --namespace mock-fleet service/mock-fleet 5005:5005
kubectl logs --namespace mock-fleet -f -l app.kubernetes.io/name=mock-fleet
```

If you want to reach the service through Minikube Ingress on `mock-fleet.localhost`, enable the Minikube ingress addon first:

```bash
minikube addons enable ingress
```

[`bin/local/debug-minikube.sh`](/home/dmitrymayer/projects/github/mock-fleet/bin/local/debug-minikube.sh) remains as a thin wrapper around that flow. It now:

- deploys into namespace `mock-fleet` by default
- packages the in-cluster app with profile `dev` by default
- builds images through the Docker-based Quarkus container-image path
- creates the namespace if needed
- can enable Ingress for a host with `--ingress-host mock-fleet.localhost`
  this enables the generated Ingress and then patches both `mock-fleet.localhost` and `*.mock-fleet.localhost`
- prints the exact `quarkus:remote-dev` command to run locally after deployment
- keeps logs and remote debug port-forwarding opt-in via `--logs` and `--port-forward`
- only uninstalls the release when explicitly requested with `--cleanup`
