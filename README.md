# mock-fleet

`mock-fleet` is a monorepo with three deployable applications:

- `fleet-proxy/`: Quarkus request router/proxy.
- `fleet-api/`: Quarkus management API and WireMock pod lifecycle service.
- `fleet-dash/`: React/Vite dashboard served by Nginx.

The dashboard remains available under `/__fleet/`. Public management APIs stay under `/__fleet/api/`, while proxy and dashboard health endpoints use their own namespaces.

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

Reserved routes:

- `/__fleet/api/mocks`: list active mock pods through `fleet-api`
- `DELETE /__fleet/api/mocks/{mockId}`: delete an active mock pod through `fleet-api`
- `/__fleet/api/health/*`: `fleet-api` health
- `/__fleet/proxy/health/*`: `fleet-proxy` health
- `/__fleet/dash/health/*`: `fleet-dash` health

Kubernetes Ingress explicitly routes `/__fleet/api/*` to `fleet-api`, `/__fleet/*` to `fleet-dash`, and proxy traffic to `fleet-proxy`.

## Requirements

- Java 21
- Maven, or the checked-in Maven wrappers under `fleet-proxy/` and `fleet-api/`
- Node.js 22 for dashboard development
- Docker for container builds
- Access to a Kubernetes cluster for real pod management
- Hazelcast available through the configured client ConfigMap in Kubernetes

## Local Development

Run the API:

```bash
cd fleet-api
./mvnw quarkus:dev
```

Run the proxy:

```bash
cd fleet-proxy
MOCK_FLEET_API_BASE_URL=http://localhost:8081 ./mvnw quarkus:dev
```

Run the dashboard:

```bash
cd fleet-dash
npm install
npm run dev
```

The Vite dev server proxies `/__fleet/api/*` to `http://localhost:8081`.

Run tests/builds:

```bash
cd fleet-api && ./mvnw test
cd fleet-proxy && ./mvnw test
cd fleet-dash && npm run build
```

## Containers

Build the proxy image:

```bash
cd fleet-proxy
./mvnw package -DskipTests
docker build -f src/main/docker/Dockerfile.jvm -t ghcr.io/letsrokk/mock-fleet-proxy:latest .
```

Build the API image:

```bash
cd fleet-api
./mvnw package -DskipTests
docker build -f src/main/docker/Dockerfile.jvm -t ghcr.io/letsrokk/mock-fleet-api:latest .
```

Build the dashboard image:

```bash
docker build -t ghcr.io/letsrokk/mock-fleet-dash:latest fleet-dash
```

## Kubernetes And Helm

The chart lives in `deploy/helm/mock-fleet` and deploys all three apps plus `fleet-api` RBAC, Hazelcast client config, WireMock config, and optional storage.

```bash
helm dependency build deploy/helm/mock-fleet
helm upgrade --install mock-fleet deploy/helm/mock-fleet \
  --namespace mock-fleet \
  --create-namespace
```

Important values:

- `fleet.proxy.*`: proxy image, service, routing, probes, and resources
- `fleet.api.*`: API image, service, probes, resources, WireMock, lifecycle, and storage settings
- `fleet.dash.*`: dashboard image, service, probes, and resources
- `ingress.host`: public fleet host

Local Minikube deployment is wrapped by:

```bash
bin/local/deploy.sh
```

The script builds all three local images, deploys the chart with `values.minikube.yaml`, and waits for all deployments.
