# Deployment security

## Network policies

The chart provides network policies for API ingress and managed-WireMock egress. These boundaries are effective only when the cluster network plugin enforces Kubernetes `NetworkPolicy`; Kubernetes accepts these objects even when the plugin ignores them. The cluster operator must enable and verify enforcement for the installed plugin version and configuration.

Run the [chart's controlled internal-connectivity test](../deploy/helm/mock-fleet/README.md#verify-networkpolicy-enforcement) before using a cluster for untrusted mocks. An unselected positive control must connect before a selected denial can prove enforcement.

## Admission and workload isolation

The chart enables admission policies, namespace quota, and restricted workload security contexts by default. Its `ValidatingAdmissionPolicy` requires Kubernetes 1.30 or newer. Disabling admission, network policy, or quota removes a separate security boundary; see the [upgrade and security notes](../deploy/helm/mock-fleet/README.md#upgrade-and-security-notes) before changing these switches.

## Service accounts

Managed WireMock pods use a dedicated service account and do not receive the general Kubernetes API token. `wiremock.serviceAccount.annotations` supports IRSA or EKS Pod Identity. With `storage.s3.authenticationSource=pod`, the S3 CSI driver uses pod-level identity; an identity integration may inject a separate audience-bound projected token, mount, and AWS environment variables without re-enabling the general API token.

Private registries use existing Secrets. `wiremock.serviceAccount.imagePullSecrets` attaches pull-secret references to the managed WireMock service account; configure these references yourself when using an external service account. `mockOps.registry.credentialsSecretName` supplies separate `username`/`password` discovery credentials. See the [private-registry example](../deploy/helm/mock-fleet/README.md#fleet-mock-ops).

## Ingress protection

Protect externally reachable Fleet API, WireMock Admin, and MCP routes at the platform ingress boundary. Enabling MCP does not add authentication to direct WireMock Admin access.
