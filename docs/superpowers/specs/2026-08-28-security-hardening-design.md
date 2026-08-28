# Mock Fleet Security Hardening Design

## Goal

Remove the validated non-authentication vulnerabilities in the Mock Fleet codebase and its local Minikube deployment without changing the API or MCP authentication model. The change must make trust boundaries enforceable, bound attacker-controlled work, stop plaintext password propagation, and move affected dependencies to fixed releases.

The implementation targets the repository revision reviewed by Codex Security (`70679f0974d85081cf2b70ba3cf1037126d5c2ba`). Authentication for API and MCP endpoints remains explicitly out of scope.

## Validated Risks

The design covers these eight findings:

1. Fleet Proxy accepts absolute and scheme-relative request targets that can replace the configured upstream authority.
2. WireMock egress controls depend on MCP being enabled, and the current Minikube bridge CNI does not enforce the rendered `NetworkPolicy`.
3. Mock provisioning uses an unbounded executor and has no hard limit on starting or active mocks. Explicitly started mocks also miss the access timestamp used by idle cleanup.
4. Mapping-tree reads and recursive folder deletion have no depth or entry budget.
5. Password-bearing WireMock options are stored in ConfigMaps, returned by the API, and exposed in process arguments.
6. The API service account can create arbitrary pods, mutate all ConfigMaps, and patch Deployments in the namespace. The namespace has no Pod Security Admission policy.
7. Hazelcast 5.5.0 exposes its member protocol on TCP 5701 to the namespace and contains known memory-access and remote-code-execution vulnerabilities.
8. Quarkus REST 3.18.1 can exhaust worker threads with crafted requests and is no longer supported.

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

The Helm chart will render managed-WireMock egress policy whenever network policy is enabled, independent of the MCP feature flag. The policy will select the same stable labels placed on managed WireMock pods and will retain only the intended DNS and public-network egress.

The chart will also render an API ingress policy. HTTP traffic on port 8080 remains reachable from the deployment ingress path. Hazelcast member traffic on port 5701 is allowed only between API pods from the same Helm release. Other namespace workloads cannot initiate member-protocol connections.

All non-API workloads, including managed WireMock pods, will set `automountServiceAccountToken: false`. The API workload keeps its token because it manages WireMock pods and release configuration.

`make local-deploy` will run a network-policy enforcement preflight before treating the deployment as secure. The check will temporarily apply a deny-egress policy to a uniquely labelled probe pod and attempt a TCP connection to a known ClusterIP. A successful connection means the CNI does not enforce `NetworkPolicy`, so deployment stops with an actionable error. The probe and policy use run-unique names and are removed on success or failure.

The existing bridge CNI is therefore not a supported secure configuration. The local Minikube profile must use a network-policy-capable CNI such as Calico or Cilium. This change will not recreate or replace the user's Minikube cluster automatically.

## Kubernetes Authority

The API role will drop all Deployment permissions. The chart will pre-create the release-owned user ConfigMap. The role will grant `get`, `watch`, `update`, and `patch` only for that ConfigMap through `resourceNames`; it will not grant namespace-wide ConfigMap `create`, `list`, or mutation.

Pod creation cannot be constrained by ordinary RBAC. The chart will install a `ValidatingAdmissionPolicy` and binding that apply only to CREATE operations by the API service-account username in the release namespace. The policy will admit only managed WireMock pods that satisfy all of these properties:

- the expected release ownership and managed-WireMock labels are present;
- the pod uses the dedicated WireMock service account;
- each container image matches the configured WireMock image and digest or tag policy;
- host networking, host PID, host IPC, privileged containers, privilege escalation, host paths, host ports, and added Linux capabilities are absent;
- containers use the chart-defined resource limits and hardened security context;
- pod and container fields outside the generated WireMock shape cannot add an alternate execution path.

The policy will fail closed for the scoped service account. The implementation will generate validation expressions from stable chart values where necessary and will include Helm-render and live admission tests for an accepted managed pod and rejected privileged, host-path, wrong-image, wrong-service-account, and label-spoofed pods.

The local namespace will receive Pod Security Admission labels for the `restricted` profile at the cluster's current Kubernetes version. Existing Mock Fleet workload templates and managed WireMock pods will be updated to comply: no privilege escalation, all capabilities dropped, and `RuntimeDefault` seccomp. A read-only root filesystem is not required because WireMock and supporting images may need writable runtime paths.

Deletion remains limited by the role to pods. Application code must verify release ownership and the managed-WireMock label before deleting a pod. This preserves lifecycle cleanup while preventing deletion of unrelated pods through a forged identifier.

## Provisioning Capacity

Mock startup will use a bounded executor with a bounded queue. Configuration will define maximum active mocks, maximum concurrent starts, and queued start capacity. Values will have conservative defaults and explicit validation.

A start request reserves capacity atomically before asynchronous work begins. It releases the reservation on every success, rejection, timeout, and failure path. Requests above the active-mock limit return HTTP 429. Requests rejected because the start queue or worker pool is saturated return HTTP 503 with a stable error code. Concurrent requests cannot exceed either limit through a check-then-act race.

A successful explicit start will set `lastAccess` immediately so the existing idle-cleanup policy can reclaim it. Tests will cover races, queue saturation, failed starts, reservation release, and idle cleanup.

The chart will add a namespace `ResourceQuota` for pod count and aggregate CPU and memory. Quota values remain configurable because local cluster sizes differ. This is a second boundary if application admission fails; it does not replace application limits.

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

The Helm chart remains the source of truth for Kubernetes controls. New values will expose capacity, traversal, quota, network-policy, and admission-policy settings with secure defaults. Disabling network or admission controls must require an explicit value and will make the local security verification fail visibly.

The upgrade may fail until the cluster supports `admissionregistration.k8s.io/v1` `ValidatingAdmissionPolicy` and enforces `NetworkPolicy`. That failure is deliberate. The deployment script will report the unmet capability and the required operator action rather than silently installing a weaker configuration.

## Verification

Implementation will follow red-green-refactor for each production behavior. The minimum verification set is:

- focused unit and integration tests for proxy targets, capacity accounting, mapping budgets, secret-option rejection, and ownership checks;
- full Maven tests for every Java module;
- Helm lint, schema validation, deterministic rendering checks, and chart tests for RBAC, admission expressions, policies, quota, labels, and security contexts;
- dependency vulnerability and dependency-tree checks for the affected runtime artifacts;
- a live Minikube install after the cluster has an enforcing CNI;
- live negative tests proving egress denial, Hazelcast isolation, admission rejection, restricted Pod Security compliance, quota enforcement, and absence of mounted service-account tokens;
- the existing cluster end-to-end suite and workload health/provenance checks.

The current Minikube bridge CNI is expected to fail the new enforcement preflight. Recreating or changing the cluster is outside this implementation's authority and requires a separate explicit operator action.

## Alternatives Not Selected

Application-only validation would reduce direct exploit paths but would leave Kubernetes authority and DNS-rebinding egress risks enforceable only by convention. It does not close the deployment findings.

Moving pod management into a separate controller and namespace would create the strongest long-term isolation boundary. It is not selected for this change because it changes the deployment architecture and operational model far beyond the validated fixes. The admission-policy design provides a bounded, testable control while keeping the current topology.

## Completion Criteria

- All eight validated findings have root-cause fixes and regression coverage.
- No API or MCP authentication behavior changes.
- Secure Helm defaults render the intended RBAC, admission, network, quota, and workload controls.
- The full repository test suite passes after the dependency upgrades.
- Live verification passes on a Minikube profile that enforces `NetworkPolicy`; the current non-enforcing profile is reported as an external prerequisite, not treated as a passing deployment.
- An independent bypass review finds no practical way around the proxy, capacity, traversal, secret, RBAC, admission, or network controls.
