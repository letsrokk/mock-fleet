# mock-fleet

`mock-fleet` is a Quarkus service that routes incoming HTTP requests to per-mock WireMock pods in Kubernetes. The target pod is selected either from the request `Host` header or from the first URL path segment, depending on configuration, and the service creates or reuses a WireMock pod for that mock ID.

The service also exposes a small internal dashboard under `/__fleet/` for inspecting and manually deleting active mock pods. Its management API and static assets remain reserved there as well.

## How routing works

- `HOST` mode:
  - `demo.mock-fleet.localhost` routes to mock ID `demo`
  - `demo.mock-fleet.localhost:8080` also routes to mock ID `demo`
  - `mock-fleet.localhost` is treated as mock-fleet's own host and never spawns a mock
  - `GET /` and `HEAD /` on the fleet host redirect to `/__fleet/`
  - only a single subdomain label of the configured fleet host is accepted as a mock ID
  - invalid or empty `Host` headers are rejected with HTTP `400`
- `PATH` mode:
  - `/demo` routes to mock ID `demo` and is forwarded upstream as `/`
  - `/demo/nested/path?alpha=1` routes to mock ID `demo` and is forwarded upstream as `/nested/path?alpha=1`
  - `GET /` and `HEAD /` on the fleet host redirect to `/__fleet/`
  - requests without a first path segment are rejected with HTTP `400`

The proxy forwards method, path, query string, request body, and incoming headers to the selected WireMock pod on port `8080`.

Reserved local routes:

- `/__fleet/` serves the dashboard UI on the fleet host
- `/__fleet/assets/...` serves dashboard static assets on the fleet host
- `/__fleet/api/mocks` lists active mock pods on the fleet host
- `DELETE /__fleet/api/mocks/{mockId}` deletes an active mock pod manually on the fleet host
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

- in `HOST` mode, requests must target either the exact fleet host for local handling or a single-label subdomain of `mock-fleet.routing.host` for mock routing
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
- `mock-fleet.routing.host`: public host name of mock-fleet itself, used by `HOST` mode to distinguish local requests from mock subdomains
- `quarkus.quinoa.*`: frontend build/serve settings for the internal React dashboard
  `quarkus.quinoa.ui-dir` defaults to `${user.dir}/src/main/webui` and can be overridden with `QUINOA_UI_DIR`
- `deploy/helm/mock-fleet`: the source-controlled Helm chart used for Kubernetes deployment

## Kubernetes and Helm

- the source-controlled Helm chart preserves the current Deployment, Service, RBAC, probes, and Hazelcast wiring
- the chart keeps ingress enabled by default for the local `dev` workflow at `mock-fleet.localhost`
- routing-aware ingress behavior is chart-owned:
  `routing.mode=HOST` adds both `mock-fleet.localhost` and `*.mock-fleet.localhost`
  `routing.mode=PATH` adds only `mock-fleet.localhost`
- chart values are exposed through a cleaner manual interface such as `image.*`, `routing.mode`, `ingress.*`, `resources.*`, and `env.*`
- the `/` to `/__fleet/` redirect is handled by the application, so it works regardless of ingress controller

Namespace behavior:

- runtime-created mock pods and services use the Fabric8 client namespace when one is available
- otherwise, the app falls back to `mock-fleet.namespace`, which defaults to `mock-fleet`
- the Helm chart defaults to namespace `mock-fleet`, while still allowing namespace overrides at install time

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
helm dependency build deploy/helm/mock-fleet
helm upgrade --install mock-fleet deploy/helm/mock-fleet \
  --namespace mock-fleet \
  --create-namespace
kubectl wait --namespace mock-fleet --for=condition=Ready pod --timeout=1m -l app.kubernetes.io/name=mock-fleet
```

Then connect from your workstation with Quarkus remote dev:

```bash
./mvnw quarkus:remote-dev -Dquarkus.profile=dev
```
The generic chart defaults now leave ingress disabled. Local Minikube deploys stay ingress-enabled because [`bin/local/deploy.sh`](/home/dmitrymayer/projects/github/mock-fleet/bin/local/deploy.sh) applies [`values.minikube.yaml`](/home/dmitrymayer/projects/github/mock-fleet/deploy/helm/mock-fleet/values.minikube.yaml), which keeps `ingress.host=mock-fleet.localhost` and `routing.mode=HOST` unless you override routing on the CLI. The deployed image reference is `ghcr.io/letsrokk/mock-fleet:latest`, while `./mvnw package` also keeps the version tag derived from `pom.xml`. The container image includes Node.js and the frontend workspace so Quinoa can start inside the pod during remote dev. The `/__fleet/` dashboard remains available in this profile.

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

[`bin/local/deploy.sh`](/home/dmitrymayer/projects/github/mock-fleet/bin/local/deploy.sh) remains as a thin wrapper around that flow. It now:

- deploys into namespace `mock-fleet` by default
- packages the in-cluster app with profile `dev` by default
- builds images through the Docker-based Quarkus container-image path
- creates the namespace if needed
- deploys the source-controlled chart from `deploy/helm/mock-fleet`
- applies [`values.minikube.yaml`](/home/dmitrymayer/projects/github/mock-fleet/deploy/helm/mock-fleet/values.minikube.yaml) so local Minikube deploys keep ingress enabled even though the generic chart defaults do not
- checks that Minikube is running before doing any build or deploy work
- relies on `./mvnw package` to build `ghcr.io/letsrokk/mock-fleet` locally and imports only `ghcr.io/letsrokk/mock-fleet:latest` into Minikube with `minikube image load`
- allows switching routing mode with `--routing HOST|PATH`, defaulting to `HOST`
- renders both `mock-fleet.localhost` and `*.mock-fleet.localhost` when `routing.mode=HOST`
- prints the exact `quarkus:remote-dev` command to run locally after deployment
- keeps logs and remote debug port-forwarding opt-in via `--logs` and `--port-forward`
- only uninstalls the release when explicitly requested with `--cleanup`
