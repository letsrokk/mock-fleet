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

Run the app against a real local Kubernetes cluster from your IDE with the dedicated profile override:

```bash
./mvnw quarkus:dev -Dquarkus.profile=dev,local-k8s
```

Important runtime expectations:

- in `HOST` mode, requests must include a multi-label `Host` header that contains the mock ID in the first label
- in `PATH` mode, requests must include the mock ID as the first URL path segment
- Kubernetes credentials must be available to the Fabric8 client
- Hazelcast client configuration is loaded from `/etc/hazelcast/hazelcast-client.yaml` in Kubernetes deployments
- include both `dev` and `local-k8s` in `quarkus.profile`; `%local-k8s` augments the dev configuration rather than replacing it
- in `%local-k8s`, upstream mock access uses localhost service port-forwards and an embedded Hazelcast instance

## Configuration

Main application settings live in [`application.yaml`](/C:/Users/Dmitry%20Mayer/projects/github/mock-fleet/src/main/resources/application.yaml).

- `mock-fleet.inactivity-threshold`: how long an inactive mock pod may live before cleanup
- `mock-fleet.pod-creation-timeout`: how long to wait for a newly created pod to reach `Running`
- `mock-fleet.wiremock-image`: pinned WireMock image used for spawned mock pods
- `mock-fleet.routing.mode`: routing strategy, either `HOST` or `PATH`
- `quarkus.quinoa.*`: frontend build/serve settings for the internal React dashboard
- `quarkus.kubernetes.*`: generated Deployment, Service, RBAC, probes, resource requests/limits, and ingress settings
- `quarkus.helm.*`: generated Helm chart settings and additional values/schema mappings

## Kubernetes and Helm

- generated Kubernetes manifests now include readiness and liveness probes via SmallRye Health
- the application Deployment declares explicit CPU and memory requests/limits
- pod security defaults require the application to run as non-root
- app RBAC is narrowed to `get`, `list`, `create`, and `delete` for pods and services
- the Helm chart disables the Ingress resource by default; enable it with `--set app.ingress.enabled=true`
- Helm values expose image pull policy, resource requests/limits, and selected `mock-fleet.*` runtime settings through environment variables

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

## Minikube helper

The local helper script at [`debug-minikube.sh`](/C:/Users/Dmitry%20Mayer/projects/github/mock-fleet/bin/local/debug-minikube.sh) builds the app with Maven, updates the generated Helm chart, deploys it into Minikube, then tails logs and opens remote debugging on port `5005`.
