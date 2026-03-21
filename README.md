# mock-fleet

`mock-fleet` is a Quarkus service that routes incoming HTTP requests to per-mock WireMock pods in Kubernetes. The target pod is selected from the request `Host` header: the first hostname label becomes the mock ID, and the service creates or reuses a WireMock pod for that ID.

## How routing works

- `demo.example.test` routes to mock ID `demo`
- `demo.example.test:8080` also routes to mock ID `demo`
- invalid or empty `Host` headers are rejected with HTTP `400`

The proxy forwards method, path, query string, request body, and incoming headers to the selected WireMock pod on port `8080`.

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

- requests must include a `Host` header that contains the mock ID in the first label
- Kubernetes credentials must be available to the Fabric8 client
- Hazelcast client configuration is loaded from `/etc/hazelcast/hazelcast-client.yaml` in Kubernetes deployments

## Configuration

Main application settings live in [`application.yaml`](/C:/Users/Dmitry%20Mayer/projects/github/mock-fleet/src/main/resources/application.yaml).

- `mock-fleet.inactivity-threshold`: how long an inactive mock pod may live before cleanup
- `mock-fleet.pod-creation-timeout`: how long to wait for a newly created pod to reach `Running`
- `mock-fleet.wiremock-image`: pinned WireMock image used for spawned mock pods

## Tests

The test suite now covers:

- host-header parsing and validation
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
