# Development

The local lifecycle targets require a running Minikube cluster. For an example setup, see [letsrokk/minikube](https://github.com/letsrokk/minikube).

Deploy locally:

```bash
make local-deploy
```

The local profile uses Traefik, HTTPS, PATH routing, and enables MCP. Run `minikube tunnel` while using the deployment, and trust the Minikube local CA in curl, your browser, and any development JVM. The dashboard is available at `https://mock-fleet.minikube.localhost/__fleet/`; MCP uses `https://mock-fleet.minikube.localhost/__fleet/mcp`; the default WireMock mock is available at `https://mock-fleet.minikube.localhost/wiremock`.

Optional Make variables select deployment behavior:

```bash
make local-deploy TINYPROXY=false        # Disable the default local forward proxy
make local-deploy LOGS=true
make local-deploy DEV=true                 # API remote development
make local-deploy DEV=proxy
make local-deploy DEV=proxy PORT_FORWARD=true
make local-deploy REBUILD=mcp            # Force one local image rebuild
make local-deploy REBUILD=mock-ops       # Force the Mock Ops image rebuild
make local-deploy REBUILD=all            # Force all local image rebuilds
make local-deploy NAMESPACE=test-fleet

make local-destroy
make local-destroy RELEASE=mock-fleet NAMESPACE=test-fleet
make local-destroy DELETE_NAMESPACE=true
```

`DEV=true` and `DEV=api` both select API remote development. Use `DEV=proxy` for proxy remote development. The Minikube profile enables Fleet Mock Ops. `REBUILD` accepts `dash`, `api`, `proxy`, `mcp`, `mock-ops`, or `all` and combines the forced rebuild with modules detected from working-tree changes. Run `make help` for the complete target and variable summary.

Local deployments enable Tinyproxy by default. Traefik exposes the HTTP proxy at
`http://tinyproxy.minikube.localhost:8080` and HTTPS proxy at
`https://tinyproxy.minikube.localhost:8433`. Both tunnel HTTPS destinations without
interception. Clients trust the existing ingress CA; there is no web UI or proxy
CA to install. Keep `minikube tunnel` running. Use `TINYPROXY=false` or
`bin/local/deploy.sh --no-tinyproxy` to disable it. See
[proxy configuration and client setup](../deploy/helm/mock-fleet/README.md#optional-forward-proxy-tinyproxy).

## Local security setup

Local deployment labels its namespace for the `restricted` Pod Security Admission profile at the current server minor version.

`make local-deploy` neither checks nor changes CNI capability. The bridge CNI in the Minikube cluster reviewed for this release accepted network policies but did not enforce them. Do not treat that profile as providing the private-network egress boundary. Run the [chart's controlled internal-connectivity test](../deploy/helm/mock-fleet/README.md#verify-networkpolicy-enforcement) before using a cluster for untrusted mocks; it requires an unselected positive control to connect before a selected denial can prove enforcement.
