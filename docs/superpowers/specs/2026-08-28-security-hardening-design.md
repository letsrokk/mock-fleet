# Mock Fleet Security Hardening Design

## Goal

Remove the validated non-authentication vulnerabilities in the Mock Fleet codebase and its local Minikube deployment without changing the API or MCP authentication model. The change must make trust boundaries enforceable, bound attacker-controlled work, stop plaintext password propagation, and move affected dependencies to fixed releases.

The implementation targets the repository revision reviewed by the canonical replacement Codex Security scan (`f9a0c786333d25945231a2cb6227e70bd4c22cb4`). Authentication for API and MCP endpoints remains explicitly out of scope.

## Validated Risks

The design covers these nine findings:

1. Fleet Proxy accepts absolute and scheme-relative request targets that can replace the configured upstream authority.
2. Before this change, WireMock egress controls depended on MCP being enabled; the reviewed Minikube bridge CNI also did not enforce the rendered `NetworkPolicy`.
3. Mock provisioning uses an unbounded executor and has no hard limit on starting or active mocks. Explicitly started mocks also miss the access timestamp used by idle cleanup.
4. Mapping-tree reads and recursive folder deletion have no depth or entry budget.
5. Password-bearing WireMock options are stored in ConfigMaps, returned by the API, and exposed in process arguments.
6. The API service account can create arbitrary pods, mutate all ConfigMaps, and patch Deployments in the namespace. The namespace has no Pod Security Admission policy.
7. Hazelcast 5.5.0 exposes its member protocol on TCP 5701 to the namespace and contains known memory-access and remote-code-execution vulnerabilities.
8. Quarkus REST 3.18.1 can exhaust worker threads with crafted requests and is no longer supported.
9. Editable WireMock configuration can replace the chart baseline with empty or attacker-selected pod resources, and workload-shaping numeric options have no safe ranges.

## Security Boundaries

Mock Fleet accepts untrusted HTTP request components, mock identifiers, mappings, and WireMock options. These values cross into outbound HTTP requests, Kubernetes objects, object storage, and child-process arguments. The Fleet API service account is the only workload that needs Kubernetes API access. Managed WireMock pods need outbound internet access for user-defined mock behavior, but they must not reach private or cluster networks.

The local deployment is secure only when all of these controls hold:

- Fleet Proxy preserves the configured upstream scheme and authority.
- Kubernetes enforces ingress and egress policy for the exact labels used by running pods.
- The API service account can create only constrained Mock Fleet WireMock pods and can mutate only the release-owned configuration object.
- Attacker-controlled concurrency, object counts, traversal depth, and traversal size have fixed limits.
- Secrets do not enter ConfigMaps, API responses, or process arguments.

## Proxy Target Validation

Fleet Proxy will validate the inbound target at the shared forwarding boundary used by both path-based and host-based routing. It will accept origin-form paths only. A valid target starts with one `/`, has no URI authority, scheme, user information, or fragment, and retains a query string when present.

The validator will reject:

- absolute targets such as `https://attacker.example/path`;
- scheme-relative targets such as `//attacker.example/path`;
- backslashes and encoded backslashes that different parsers can normalize into authority separators;
- malformed percent encodings;
- fragments and other request-target forms that the proxy does not implement.

Forwarding will construct the destination from the trusted upstream scheme and authority plus the validated path and query. It will not use URI resolution semantics that allow the request target to replace the upstream origin. Invalid targets return a client error before any outbound connection.

Regression tests will cover both routing modes, accepted paths and queries, every rejected target class, mixed encoding, and the invariant that the configured authority never changes.

## Enforced Network Isolation

The Helm chart renders managed-WireMock egress policy whenever `fleet.mcp.outbound.networkPolicy.enabled=true`, independent of the MCP feature flag. The compatibility value remains under the MCP path, but the policy selects exactly the stable `app.kubernetes.io/name=mock-fleet-wiremock` and `app.kubernetes.io/managed-by=mock-fleet` labels placed on managed WireMock pods. It has no release label so that it also selects upgrade-era managed pods. The policy retains only the intended DNS and public-network egress.

The chart will also render an API ingress policy. HTTP traffic on port 8080 remains reachable from the deployment ingress path. Hazelcast member traffic on port 5701 is allowed only between API pods from the same Helm release. Other namespace workloads cannot initiate member-protocol connections.

All non-API workloads disable the default Kubernetes API token mount. Managed WireMock pods use the dedicated service account selected by `wiremock.serviceAccount.name` or created by the chart. The chart preserves `wiremock.serviceAccount.annotations` so a cluster owner can attach IRSA, EKS Pod Identity, or another workload identity. When the S3 CSI volume uses `storage.s3.authenticationSource: pod`, that identity remains available to the CSI driver for the mounted PV/PVC. The API service account retains its independent annotations because API replicas mount the same volume. Disabling the default Kubernetes token does not block the separate, audience-bound token volume injected by the IRSA or EKS Pod Identity admission integration.

The chart and project documentation will state that Kubernetes accepts `NetworkPolicy` resources even when the installed CNI does not enforce them. It will identify a network-policy-capable CNI, such as Calico or Cilium, as a prerequisite for this boundary and give the cluster owner a small verification procedure. Mock Fleet will not inspect, reconfigure, or reject a cluster through `make local-deploy`; cluster capability and lifecycle remain the cluster owner's responsibility.

## Kubernetes Authority

The API role drops all Deployment permissions. The chart pre-creates and retains the release-owned user ConfigMap. A connected-cluster `lookup` suppresses that resource when an upgrade finds the legacy API-created object, which prevents Helm adoption or overwrite; offline rendering still emits it and requires the apply tool to reconcile it safely. The role grants `get`, `watch`, `update`, and `patch` only for that ConfigMap through `resourceNames`; it does not grant namespace-wide ConfigMap `create`, `list`, or mutation. The API returns `CONFIG_UNAVAILABLE` rather than recreating a missing object.

Pod creation cannot be constrained by ordinary RBAC. The chart will install a `ValidatingAdmissionPolicy` and binding that apply only to CREATE operations by the API service-account username in the release namespace. The policy will admit only managed WireMock pods that satisfy all of these properties:

- the expected release ownership and managed-WireMock labels are present;
- the pod uses the dedicated WireMock service account;
- each container image matches the configured WireMock image and digest or tag policy;
- host networking, host PID, host IPC, privileged containers, privilege escalation, host paths, host ports, and added Linux capabilities are absent;
- containers remain inside the chart-defined resource envelope and use the hardened security context;
- pod and container fields outside the generated WireMock shape cannot add an alternate execution path.

The policy fails closed for the scoped service account. It allows at most one configured audience-bound workload-identity mode. A non-EKS audience uses the IRSA-shaped read-only mount and credential environment; the reserved `pods.eks.amazonaws.com` audience requires the exact current EKS Pod Identity token name, path, mount, credential environment, and `eks.amazonaws.com/pod-identity=enabled` label. Mixed modes, extra projections, duplicate credential variables, and general Kubernetes API tokens are denied. The implementation generates validation expressions from stable chart values and includes Helm-render and live admission tests for accepted base, IRSA, and EKS shapes plus rejected privileged, host-path, wrong-image, wrong-service-account, label-spoofed, resource, sidecar, and identity-shape pods.

The local namespace will receive Pod Security Admission labels for the `restricted` profile at the cluster's current Kubernetes version. Existing Mock Fleet workload templates and managed WireMock pods will be updated to comply: no privilege escalation, all capabilities dropped, and `RuntimeDefault` seccomp. A read-only root filesystem is not required because WireMock and supporting images may need writable runtime paths.

Deletion remains limited by the role to pods. Application code must verify release ownership and the managed-WireMock label before deleting a pod. This preserves lifecycle cleanup while preventing deletion of unrelated pods through a forged identifier.

## Provisioning Capacity

Mock startup will use a bounded executor with a bounded queue. Configuration will define maximum active mocks, maximum concurrent starts, and queued start capacity. Values will have conservative defaults and explicit validation.

A start request reserves capacity atomically before asynchronous work begins. It releases the reservation on every success, rejection, timeout, and failure path. Requests above the active-mock limit return HTTP 429. Requests rejected because the start queue or worker pool is saturated return HTTP 503 with a stable error code. Concurrent requests cannot exceed either limit through a check-then-act race.

A successful explicit start will set `lastAccess` immediately so the existing idle-cleanup policy can reclaim it. Tests will cover races, queue saturation, failed starts, reservation release, and idle cleanup.

The chart will add a namespace `ResourceQuota` for pod count and aggregate CPU and memory. Quota values remain configurable because local cluster sizes differ. This is a second boundary if application admission fails; it does not replace application limits.

## Managed Pod Resource Policy

The chart will define administrator-owned CPU and memory request floors and limit ceilings for managed WireMock pods. Editable mock configuration may select values only inside that envelope. Omitted keys inherit the chart baseline, an empty resource object cannot erase it, only `cpu` and `memory` are accepted, and every limit must be greater than or equal to its request.

The API validates the effective resource set before persistence and again before pod creation. The `ValidatingAdmissionPolicy` enforces the same request floors and limit ceilings at the Kubernetes boundary so a compromised API cannot bypass the application check. Chart rendering fails when the baseline lies outside the configured envelope. Chart quantities accept positive DecimalSI, `Ki` through `Pi`, and bounded exponent forms; `Ei` is rejected because Fabric8 7.5.2 does not compare that multiplier with the chart's exact binary value.

WireMock options that directly allocate threads, connections, queues, caches, headers, messages, journals, or timeout state will accept bounded integers rather than arbitrary `BigDecimal` values. The option catalog will own the bounds so API metadata, dashboard inputs, MCP schemas, validation, and tests use one definition.

Tests will cover inherited values, empty and partial overrides, unsupported resource names, limits below requests, values outside the envelope, numeric fractions and signs, and admission rejection of a generated pod outside the envelope.

## Mapping Traversal Budgets

Mapping traversal will share one configurable budget model with separate maximum depth and maximum entry count. The budget applies to tree responses and recursive folder deletion, including directories and stored body files visited during a traversal.

Tree reads stop and return a stable client error before constructing an oversized response. Recursive deletion first performs a bounded discovery pass. If the traversal exceeds either limit, deletion returns an error without deleting anything. A permitted deletion then removes exactly the discovered set. This two-phase behavior prevents partial deletion when a limit is crossed.

Tests will cover boundary values, depth overflow, width overflow, nested content, storage errors, and the no-partial-delete guarantee. Existing response shapes and behavior remain unchanged for trees within the budget.

## Secret Handling

Mock Fleet will reject WireMock options that carry plaintext passwords, including `--ca-keystore-password`, `--keystore-password`, `--key-manager-password`, and `--truststore-password`, in both `--name=value` and split-argument forms. Matching is case-sensitive according to WireMock option names, and error text names the prohibited option without echoing its value.

This is an intentional compatibility break. Mock Fleet does not yet provide a safe Secret-backed path for these values. Documentation will state that password-protected keystore configuration is unsupported until such a path exists. Existing persisted configurations will redact prohibited option values in API output and will fail closed when started. Operators must remove or replace legacy plaintext values; the upgrade notes will describe the cleanup.

Tests will prove rejection before persistence or pod creation and verify that logs, API responses, ConfigMaps, and generated process arguments do not expose supplied values.

## Dependency Remediation

The API, Proxy, and MCP modules will move to Quarkus 3.33.3.1, the current supported 3.33 LTS emergency release. Hazelcast will move to 5.7.0, which contains the community-edition fixes for the validated 5.5.x advisories. Dependency convergence and module compatibility will be verified across the full Maven reactor.

The dependency upgrade is not accepted solely because the version changed. Tests must exercise proxy request handling, REST worker behavior, Hazelcast cluster startup between both API replicas, health endpoints, and the existing MCP contract.

## Deployment and Upgrade Behavior

The Helm chart remains the source of truth for Kubernetes controls. New values will expose capacity, traversal, resource-policy, quota, network-policy, and admission-policy settings with secure defaults. Disabling network or admission controls must require an explicit value and will be documented as weakening the corresponding security boundary.

The cluster must run Kubernetes 1.30 or newer, where `admissionregistration.k8s.io/v1` `ValidatingAdmissionPolicy` is stable, to install the default admission control. Both rendered policy branches were compiled and exercised on Kubernetes 1.36.4. The chart renders `NetworkPolicy` independently of the installed CNI, and live enforcement remains an operator-owned cluster capability rather than a property Helm can prove. The reviewed Minikube bridge CNI accepted but did not enforce the policy, and `make local-deploy` neither detects nor changes that capability.

## Verification

Implementation will follow red-green-refactor for each production behavior. The minimum verification set is:

- focused unit and integration tests for proxy targets, capacity accounting, mapping budgets, secret-option rejection, and ownership checks;
- full Maven tests for every Java module;
- Helm lint, schema validation, deterministic rendering checks, and chart tests for RBAC, admission expressions, policies, quota, labels, and security contexts;
- dependency vulnerability and dependency-tree checks for the affected runtime artifacts;
- chart tests that prove the egress and Hazelcast policies select the intended pods and ports, plus an operator procedure for verifying live CNI enforcement;
- live negative tests proving admission rejection, restricted Pod Security compliance, quota enforcement, absence of general Kubernetes API tokens, and preservation of the dedicated WireMock service account and any injected workload-identity token;
- the existing cluster end-to-end suite and workload health/provenance checks.

The current Minikube bridge CNI does not enforce the reviewed policy. That deployment limitation will remain explicit in the review results and documentation. Recreating or changing the cluster is outside this implementation's authority.

## Alternatives Not Selected

Application-only validation would reduce direct exploit paths but would leave Kubernetes authority and DNS-rebinding egress risks enforceable only by convention. It does not close the deployment findings.

Moving pod management into a separate controller and namespace would create the strongest long-term isolation boundary. It is not selected for this change because it changes the deployment architecture and operational model far beyond the validated fixes. The admission-policy design provides a bounded, testable control while keeping the current topology.

## Completion Criteria

- All nine validated findings have root-cause fixes and regression coverage.
- No API or MCP authentication behavior changes.
- Secure Helm defaults render the intended RBAC, admission, network, quota, and workload controls.
- The full repository test suite passes after the dependency upgrades.
- Chart tests prove the intended network isolation rules, and the documentation assigns CNI enforcement and live verification to the cluster owner. The current non-enforcing Minikube profile remains a reported deployment limitation.
- Managed WireMock pods retain their dedicated, annotatable service account and support IRSA or EKS Pod Identity injection without receiving a general-purpose Kubernetes API token.
- An independent bypass review finds no practical way around the proxy, capacity, traversal, secret, RBAC, admission, or network controls.
