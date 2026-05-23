# mock-fleet

`mock-fleet` is a monorepo with two deployable applications:

- `fleet/`: Quarkus backend that routes incoming HTTP requests to per-mock WireMock pods in Kubernetes.
- `dash/`: React/Vite dashboard served as a separate static Nginx container.

The backend selects a WireMock pod either from the request `Host` header or from the first URL path segment, depending on configuration. The dashboard remains available under `/__fleet/`, while backend management APIs and health checks remain under `/__fleet/api/`.

## Routing

- `HOST` mode:
  - `demo.mock-fleet.localhost` routes to mock ID `demo`
  - `mock-fleet.localhost` is treated as the fleet host
  - `GET /` and `HEAD /` on the fleet host redirect to `/__fleet/`
  - wildcard mock hosts proxy all paths to WireMock
- `PATH` mode:
  - `/demo` routes to mock ID `demo` and forwards upstream as `/`
  - `/demo/nested/path?alpha=1` forwards upstream as `/nested/path?alpha=1`
  - `GET /` and `HEAD /` redirect to `/__fleet/`
  - fleet subdomain hosts are rejected

Reserved backend routes:

- `/__fleet/api/mocks`: list active mock pods
- `DELETE /__fleet/api/mocks/{mockId}`: delete an active mock pod
- `/__fleet/api/health/live`, `/ready`, `/started`: backend health probes

The dashboard is served by the `dash` deployment at `/__fleet/`. Kubernetes Ingress routes `/__fleet/api/*` to `fleet` and `/__fleet/*` to `dash`.

## Requirements

- Java 21
- Maven, or the checked-in Maven wrapper under `fleet/`
- Node.js 22 for dashboard development
- Docker for container builds
- Access to a Kubernetes cluster for real pod management
- Hazelcast available through the configured client ConfigMap in Kubernetes

## Local Development

Run the backend:

```bash
cd fleet
./mvnw quarkus:dev
```

Run the dashboard:

```bash
cd dash
npm install
npm run dev
```

The Vite dev server proxies `/__fleet/api/*` to `http://localhost:8080`.

Run tests/builds:

```bash
cd fleet && ./mvnw test
cd dash && npm run build
```

## Containers

Build the backend image:

```bash
cd fleet
./mvnw package -DskipTests
docker build -f src/main/docker/Dockerfile.jvm -t ghcr.io/letsrokk/mock-fleet:latest .
```

Build the dashboard image:

```bash
docker build -t ghcr.io/letsrokk/mock-fleet-dash:latest dash
```

## Kubernetes And Helm

The chart lives in `deploy/helm/mock-fleet` and deploys both apps plus the backend RBAC, Hazelcast client config, WireMock config, and optional storage.

```bash
helm dependency build deploy/helm/mock-fleet
helm upgrade --install mock-fleet deploy/helm/mock-fleet \
  --namespace mock-fleet \
  --create-namespace
```

Important values:

- `fleet.image.*`: backend image
- `dash.image.*`: dashboard image
- `fleet.routing.mode`: `HOST` or `PATH`
- `ingress.host`: public fleet host
- `wiremock.*`: spawned WireMock pod settings
- `storage.*`: optional persistent mapping storage

Local Minikube deployment is wrapped by:

```bash
bin/local/deploy.sh
```

The script builds both local images, deploys the chart with `values.minikube.yaml`, and waits for both deployments.
