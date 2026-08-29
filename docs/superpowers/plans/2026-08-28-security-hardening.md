# Mock Fleet Security Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remediate the nine validated non-authentication security findings in Mock Fleet application code and its Helm/Minikube deployment, verify the controls, and open a pull request from `codex/security-hardening`.

**Architecture:** Keep the existing Proxy → API → managed WireMock topology. Enforce request-origin integrity in Proxy, capacity and traversal budgets in API, immutable Kubernetes boundaries through RBAC and admission, private-network isolation through chart-rendered policies, and supported dependency versions. The cluster owner remains responsible for installing and verifying a NetworkPolicy-capable CNI.

**Tech Stack:** Java 21, Quarkus 3.33.3.1, Hazelcast 5.7.0, Vert.x, Fabric8 Kubernetes Client, JUnit 5/Mockito/RestAssured, React/TypeScript/Vitest, Helm 3, Kubernetes `admissionregistration.k8s.io/v1`, Bash cluster contracts, Minikube.

**Spec:** `docs/superpowers/specs/2026-08-28-security-hardening-design.md`

## Global Constraints

- Do not add, remove, or change authentication for API or MCP endpoints.
- Do not inspect secret values or S3 CSI credential material.
- Do not add CNI capability checks, CNI installation, cluster reconfiguration, or deployment rejection to `make local-deploy` or `bin/local/deploy.sh`.
- Render NetworkPolicy resources with secure defaults and document that enforcement is the cluster owner's responsibility.
- Keep a dedicated WireMock service account and preserve `wiremock.serviceAccount.annotations` for IRSA/EKS Pod Identity. Set `automountServiceAccountToken: false` only for the general Kubernetes API token; allow an identity webhook to inject its own audience-bound token volume, mount, and environment variables.
- Use red-green-refactor for each production behavior. Never weaken a test to accommodate vulnerable behavior.
- Preserve unrelated user changes. Commit each completed task only after its focused tests pass.
- Treat the completed replacement scan `1dfae1c1-3daf-4b72-aaa7-11a4e36ac26c` as canonical: nine findings, five high and four medium.

---

## Task 1: Pin Remediated Runtime Dependencies

**Files:**

- Add: `tests/security/dependency-contracts.sh`
- Modify: `fleet-api/pom.xml`
- Modify: `fleet-proxy/pom.xml`
- Modify: `fleet-mcp/pom.xml`
- Modify: `.github/workflows/build.yml`

- [ ] Add `tests/security/dependency-contracts.sh` with exact assertions that all three Quarkus platform properties equal `3.33.3.1`, API Hazelcast equals `5.7.0`, and no runtime dependency tree resolves an older Quarkus REST or Hazelcast artifact.
- [ ] Run `tests/security/dependency-contracts.sh`; expect failure on Quarkus `3.18.1` and Hazelcast `5.5.0`.
- [ ] Update the three Quarkus platform properties to `3.33.3.1` and API Hazelcast to `5.7.0`.
- [ ] Add the dependency contract to the Helm/security portion of `.github/workflows/build.yml` so drift fails CI.
- [ ] Run:

  ```bash
  tests/security/dependency-contracts.sh
  ./fleet-api/mvnw -B -f fleet-api/pom.xml test -Dquarkus.container-image.build=false
  ./fleet-proxy/mvnw -B -f fleet-proxy/pom.xml test -Dquarkus.container-image.build=false
  ./fleet-api/mvnw -B -f fleet-mcp/pom.xml test -Dquarkus.container-image.build=false
  ```

  Expect all contracts and module tests to pass, including Hazelcast member startup and MCP registration tests.
- [ ] Commit: `git commit -m "fix: upgrade vulnerable runtime dependencies"`

## Task 2: Preserve the Fleet-Selected Proxy Authority

**Files:**

- Add: `fleet-proxy/src/main/java/com/github/letsrokk/OriginFormRequestTarget.java`
- Add: `fleet-proxy/src/test/java/com/github/letsrokk/OriginFormRequestTargetTest.java`
- Modify: `fleet-proxy/src/main/java/com/github/letsrokk/ProxyForwarder.java`
- Modify: `fleet-proxy/src/test/java/com/github/letsrokk/PathRoutingProxyResourceTest.java`
- Modify: `fleet-proxy/src/test/java/com/github/letsrokk/HostRoutingProxyResourceTest.java`

- [ ] Add failing unit cases for accepted `/`, `/path`, `/path?x=1`, and UTF-8 percent encodings; reject `//host`, absolute URI forms, fragments, backslashes, encoded backslashes (`%5c` in either case), malformed percent encodings, user info, and non-origin request targets.
- [ ] Add failing route tests in PATH and HOST modes proving a rejected target returns HTTP 400 before `FleetApiClient` or any alternate upstream receives a request.
- [ ] Implement a single validator with this contract:

  ```java
  record OriginFormRequestTarget(String rawPathAndQuery) {
      static OriginFormRequestTarget parse(String requestTarget);
      URI appendTo(URI trustedOrigin);
  }
  ```

  `appendTo` must copy only the trusted scheme, host, and port and the validated raw path/query; it must not call `URI.resolve` with caller input.
- [ ] In `ProxyForwarder`, remove inbound `Host`, `Connection`, `Proxy-Connection`, `Keep-Alive`, `Transfer-Encoding`, `TE`, `Trailer`, `Upgrade`, `Proxy-Authorization`, and all headers named by the inbound `Connection` header before copying request headers. Let Vert.x generate the outbound authority and framing headers.
- [ ] Add tests that normal end-to-end request bodies, duplicate application headers, queries, and response headers still work, while authority-sensitive and hop-by-hop headers are not forwarded.
- [ ] Run `./fleet-proxy/mvnw -B -f fleet-proxy/pom.xml -Dtest=OriginFormRequestTargetTest,RequestRoutingResolverTest,PathRoutingProxyResourceTest,HostRoutingProxyResourceTest test`; expect pass.
- [ ] Commit: `git commit -m "fix: preserve proxy upstream authority"`

## Task 3: Enforce WireMock Secret, Numeric, and Resource Policy

**Files:**

- Add: `fleet-api/src/main/java/com/github/letsrokk/WireMockResourcePolicy.java`
- Add: `fleet-api/src/test/java/com/github/letsrokk/WireMockResourcePolicyTest.java`
- Modify: `fleet-api/src/main/java/com/github/letsrokk/MockFleetConfig.java`
- Modify: `fleet-api/src/main/java/com/github/letsrokk/WireMockOptionCatalog.java`
- Modify: `fleet-api/src/main/java/com/github/letsrokk/WireMockConfigService.java`
- Modify: `fleet-api/src/main/java/com/github/letsrokk/WireMockConfigDocument.java`
- Modify: `fleet-api/src/main/java/com/github/letsrokk/WireMockOptions.java`
- Modify: `fleet-api/src/main/java/com/github/letsrokk/PodFactory.java`
- Modify: `fleet-api/src/main/resources/application.yaml`
- Modify: `fleet-api/src/test/java/com/github/letsrokk/WireMockOptionValidationTest.java`
- Modify: `fleet-api/src/test/java/com/github/letsrokk/WireMockConfigServiceTest.java`
- Modify: `fleet-api/src/test/java/com/github/letsrokk/WireMockOptionsTest.java`
- Modify: `fleet-dash/src/configOptions.ts`
- Modify: `fleet-dash/src/configOptions.test.ts`
- Modify: `fleet-dash/src/App.tsx`
- Modify: `fleet-mcp/src/main/java/com/github/letsrokk/mcp/UpdateMockConfigInputSchemaGenerator.java`
- Modify: affected MCP config tests under `fleet-mcp/src/test/java/com/github/letsrokk/mcp/`

- [ ] Add failing option tests for all four prohibited password options in split and `--name=value` form. Assert errors contain the option name but never the submitted value, and legacy persisted values are redacted from views and rejected before pod creation.
- [ ] Add failing numeric tests for negative values, fractions, zero where the option requires a positive value, and values above the catalog bound. Extend `OptionDefinition` with nullable integer `minimum` and `maximum`; make API metadata and dashboard `<input type="number">` use the same bounds and `step="1"`.
- [ ] Add failing resource tests for explicit empty resources, partial maps, unsupported names, malformed quantities, limit below request, request below the configured floor, and limit above the configured ceiling. Assert omitted keys inherit baseline values rather than erasing them.
- [ ] Implement `WireMockResourcePolicy` with this boundary:

  ```java
  ResourceRequirements normalizeAndValidate(
      ResourceRequirements baseline,
      WireMockConfigService.ResourceData requested);
  void validateEffective(ResourceRequirements effective);
  ```

  Permit only `cpu` and `memory`; require an effective request and limit for each; enforce chart-provided request floors and limit ceilings; require request `<=` limit; and return a complete normalized object.
- [ ] Classify the password option names as sensitive and reject them during API normalization. Redact legacy pairs in `ConfigView` without mutating the retained ConfigMap, and fail closed if effective startup options still contain one.
- [ ] Replace arbitrary `BigDecimal` acceptance with integral per-option bounds. Use these catalog maxima: thread pools `256`, Jetty container threads `512`, HTTP connections and accept queue `10000`, cache/journal entries `100000`, header buffers `1048576`, logged/message bodies `16777216`, and timeouts `3600000` ms. Use a minimum of `1`, except cache/journal/log limits may be `0`.
- [ ] Update dashboard and MCP schema descriptions so callers see that only `cpu` and `memory` within the cluster-owned envelope are accepted. Keep the existing API/MCP authentication behavior unchanged.
- [ ] Run focused API, dashboard, and MCP tests, then:

  ```bash
  ./fleet-api/mvnw -B -f fleet-api/pom.xml -Dtest=WireMockOptionValidationTest,WireMockResourcePolicyTest,WireMockConfigServiceTest,WireMockOptionsTest test
  npm --prefix fleet-dash test
  ./fleet-api/mvnw -B -f fleet-mcp/pom.xml -Dtest=FleetMcpToolsConfigTest,McpConfigContractTest test
  ```

- [ ] Commit: `git commit -m "fix: enforce wiremock configuration policy"`

## Task 4: Bound Mapping Traversal Before Materialization or Deletion

**Files:**

- Add: `fleet-api/src/main/java/com/github/letsrokk/TraversalBudget.java`
- Modify: `fleet-api/src/main/java/com/github/letsrokk/MockFleetConfig.java`
- Modify: `fleet-api/src/main/java/com/github/letsrokk/MappingsService.java`
- Modify: `fleet-api/src/main/resources/application.yaml`
- Modify: `fleet-api/src/test/java/com/github/letsrokk/MappingsServiceTest.java`
- Modify: `fleet-api/src/test/java/com/github/letsrokk/MappingsResourceTest.java`

- [ ] Add failing tests at exactly `maxDepth`/`maxEntries`, one over each limit, a broad directory, a deep directory, and a storage failure during delete. Assert an over-budget delete removes no path and returns a stable `MAPPINGS_TRAVERSAL_LIMIT` client error with the applicable limit.
- [ ] Implement an iterative `TraversalBudget` that counts every visited directory and regular file, tracks relative depth, and throws before appending an over-budget entry.
- [ ] Replace recursive `node` construction with iterative bounded discovery followed by bounded tree assembly. Do not call unbounded `Files.walk(...).toList()` or recursively call Java for attacker-controlled depth.
- [ ] Make recursive delete a two-phase operation: discover and validate one bounded immutable manifest, then delete exactly that manifest deepest-first. A traversal or budget failure must happen before the first delete.
- [ ] Add `mock-fleet.mappings.max-depth` default `32` and `max-entries` default `10000`; expose them through chart values and validated environment variables in Task 8.
- [ ] Run `./fleet-api/mvnw -B -f fleet-api/pom.xml -Dtest=MappingsServiceTest,MappingsResourceTest test`; expect pass.
- [ ] Commit: `git commit -m "fix: bound mapping tree operations"`

## Task 5: Bound Mock Provisioning and Close Cleanup Gaps

**Files:**

- Add: `fleet-api/src/main/java/com/github/letsrokk/MockCapacity.java`
- Add: `fleet-api/src/test/java/com/github/letsrokk/MockCapacityTest.java`
- Modify: `fleet-api/src/main/java/com/github/letsrokk/MockFleetConfig.java`
- Modify: `fleet-api/src/main/java/com/github/letsrokk/InternalMockResource.java`
- Modify: `fleet-api/src/main/java/com/github/letsrokk/PodManager.java`
- Modify: `fleet-api/src/main/java/com/github/letsrokk/PodState.java`
- Modify: `fleet-api/src/main/resources/application.yaml`
- Modify: `fleet-api/src/test/java/com/github/letsrokk/PodManagerTest.java`
- Modify: `fleet-api/src/test/java/com/github/letsrokk/PodStateTest.java`
- Modify: `fleet-api/src/test/java/com/github/letsrokk/FleetResourceTest.java`
- Modify: `fleet-api/src/test/java/com/github/letsrokk/InternalMockResourceTest.java`

- [ ] Add failing tests that concurrently claim distinct IDs and prove limits on active mocks, start workers, and queued starts. Assert active exhaustion returns 429 `MOCK_CAPACITY_EXHAUSTED`; executor saturation returns 503 `MOCK_START_QUEUE_FULL`; same-ID deduplication remains unchanged.
- [ ] Add failing lifecycle tests proving every reservation is released after create rejection, timeout, readiness failure, supersession, stop, cleanup, and normal deletion.
- [ ] Add failing cleanup tests proving explicit successful starts initialize `lastAccess`, failed starts delete their reserved/created pod, all failed lifecycles expire, and expired FAILED/STARTING entries do not shield orphan pods.
- [ ] Implement one application-scoped `MockCapacity` backed by Hazelcast lifecycle/reservation state and a cluster-wide lock. Its atomic accounting includes STARTING and RUNNING mocks across both API replicas. Reconcile stale reservations on startup and make reserve/release idempotent by mock ID and attempt ID.
- [ ] Replace `newCachedThreadPool` with a `ThreadPoolExecutor` configured from `max-concurrent-starts` and `queued-start-capacity`; route both explicit and proxy-triggered starts through that bounded executor. Return a `Uni`/completion stage from `InternalMockResource` so the proxy-start path waits asynchronously and never performs pod creation on a REST worker.
- [ ] Use defaults `max-active-mocks=20`, `max-concurrent-starts=4`, and `queued-start-capacity=16`; validate all as positive and require `max-concurrent-starts <= max-active-mocks`.
- [ ] On startup failure, attempt deletion before publishing FAILED. Record a bounded failure message even when deletion itself fails, give every failed lifecycle a TTL, and let orphan cleanup reclaim a pod after that TTL.
- [ ] Run `./fleet-api/mvnw -B -f fleet-api/pom.xml -Dtest=MockCapacityTest,PodManagerTest,PodStateTest,FleetResourceTest,InternalMockResourceTest test`; expect pass without leaked executor threads.
- [ ] Commit: `git commit -m "fix: bound mock provisioning lifecycle"`

## Task 6: Harden Generated Pods and Verify Ownership Before Deletion

**Files:**

- Modify: `fleet-api/src/main/java/com/github/letsrokk/PodFactory.java`
- Modify: `fleet-api/src/main/java/com/github/letsrokk/PodManager.java`
- Modify: `fleet-api/src/test/java/com/github/letsrokk/PodManagerTest.java`
- Add or modify the existing PodFactory-focused tests under `fleet-api/src/test/java/com/github/letsrokk/`

- [ ] Add failing pod-shape tests for the dedicated service account, `automountServiceAccountToken=false`, release/managed/mock labels, `runAsNonRoot`, `allowPrivilegeEscalation=false`, dropped `ALL` capabilities, and `RuntimeDefault` seccomp on both WireMock and the mappings init container.
- [ ] Add failing deletion tests for an absent pod, unrelated labels, wrong mock ID label, and an owned managed pod. An unrelated pod must never receive a DELETE request even if its name exists in distributed state.
- [ ] Build generated pods with the hardened pod/container security contexts and complete normalized resources from Task 3. Keep the PVC volume and the configured dedicated service-account name; do not add a general projected API token.
- [ ] Before every delete-by-name path, fetch the pod and require `app.kubernetes.io/name=mock-fleet-wiremock`, `app.kubernetes.io/managed-by=mock-fleet`, and the expected `mock-fleet/mock-id`. Treat an already absent pod as successful cleanup.
- [ ] Run focused PodFactory and `PodManagerTest`; expect pass.
- [ ] Commit: `git commit -m "fix: constrain managed wiremock pods"`

## Task 7: Minimize Workload Tokens, RBAC, and Mutable Configuration

**Files:**

- Add: `deploy/helm/mock-fleet/templates/wiremock-user-configmap.yaml`
- Modify: `deploy/helm/mock-fleet/templates/role.yaml`
- Modify: `deploy/helm/mock-fleet/templates/api-deployment.yaml`
- Modify: `deploy/helm/mock-fleet/templates/proxy-deployment.yaml`
- Modify: `deploy/helm/mock-fleet/templates/dash-deployment.yaml`
- Modify: `deploy/helm/mock-fleet/templates/mcp-deployment.yaml`
- Modify: `deploy/helm/mock-fleet/templates/serviceaccount.yaml`
- Modify: `deploy/helm/mock-fleet/templates/wiremock-serviceaccount.yaml`
- Modify: `fleet-api/src/main/java/com/github/letsrokk/WireMockConfigService.java`
- Modify: `fleet-api/src/test/java/com/github/letsrokk/WireMockConfigServiceTest.java`
- Modify: `deploy/helm/mock-fleet/tests/render-mcp.sh`

- [ ] Add failing chart assertions that Deployment permissions, namespace-wide ConfigMap create/list/mutation, and tokens on Proxy/Dash/MCP are absent; assert the API role keeps pod lifecycle verbs and named ConfigMap get/watch/update/patch only.
- [ ] Render the user ConfigMap before API startup with the retained `helm.sh/resource-policy: keep` behavior. Change the API to require this release-owned ConfigMap instead of creating an arbitrary missing object.
- [ ] Put ConfigMap mutation verbs in a separate RBAC rule with `resourceNames: [<release>-wiremock-user-config]`. Keep watch-by-name and conflict handling tests.
- [ ] Set `automountServiceAccountToken: false` on Proxy, Dash, MCP, and the WireMock service account/pod. Keep API token mounting and both API and WireMock service-account annotations.
- [ ] Apply restricted-compatible security contexts to API, Proxy, Dash, and MCP containers: non-root, no privilege escalation, drop `ALL`, `RuntimeDefault`; retain writable root filesystems.
- [ ] Run `helm lint deploy/helm/mock-fleet`, `deploy/helm/mock-fleet/tests/render-mcp.sh`, and focused API ConfigMap tests; expect pass.
- [ ] Commit: `git commit -m "fix: minimize kubernetes workload authority"`

## Task 8: Render Capacity, Resource, Quota, and Network Boundaries

**Files:**

- Add: `deploy/helm/mock-fleet/templates/api-networkpolicy.yaml`
- Add: `deploy/helm/mock-fleet/templates/resourcequota.yaml`
- Add: `deploy/helm/mock-fleet/tests/render-security.sh`
- Modify: `deploy/helm/mock-fleet/templates/wiremock-egress-networkpolicy.yaml`
- Modify: `deploy/helm/mock-fleet/templates/api-deployment.yaml`
- Modify: `deploy/helm/mock-fleet/templates/_helpers.tpl`
- Modify: `deploy/helm/mock-fleet/values.yaml`
- Modify: `deploy/helm/mock-fleet/values.minikube.yaml`
- Modify: `deploy/helm/mock-fleet/values.schema.json`
- Modify: `.github/workflows/build.yml`

- [ ] Write `render-security.sh` first. Assert the WireMock egress policy renders when MCP is disabled, selects the exact stable WireMock labels, permits DNS plus configured public CIDRs, and denies private/cluster ranges by omission/exclusion.
- [ ] Assert the API ingress policy permits TCP 8080 and permits TCP 5701 only from same-release API labels. Ensure no other ingress rule opens 5701.
- [ ] Assert secure capacity/traversal/resource environment values render on API. Validate defaults and invalid combinations through `values.schema.json` plus Helm helper failures.
- [ ] Add an enabled-by-default namespace `ResourceQuota` with configurable defaults: pod count `30`, requests CPU `8`, requests memory `12Gi`, limits CPU `16`, and limits memory `24Gi`. Document sizing relative to `maxActiveMocks` and API replicas.
- [ ] Move the egress enable switch to a WireMock-owned chart boundary or otherwise remove the `fleet.mcp.enabled` condition. Preserve existing allowed CIDR and DNS settings for compatibility.
- [ ] Add `render-security.sh` to CI and run:

  ```bash
  helm lint deploy/helm/mock-fleet
  deploy/helm/mock-fleet/tests/render-mcp.sh
  deploy/helm/mock-fleet/tests/render-security.sh
  ```

- [ ] Commit: `git commit -m "fix: render kubernetes isolation boundaries"`

## Task 9: Constrain API Pod Creation with Admission and Restricted PSA

**Files:**

- Add: `deploy/helm/mock-fleet/templates/wiremock-validatingadmissionpolicy.yaml`
- Add: `deploy/helm/mock-fleet/templates/wiremock-validatingadmissionpolicybinding.yaml`
- Modify: `deploy/helm/mock-fleet/templates/_helpers.tpl`
- Modify: `deploy/helm/mock-fleet/values.yaml`
- Modify: `deploy/helm/mock-fleet/values.schema.json`
- Modify: `deploy/helm/mock-fleet/tests/render-security.sh`
- Modify: `bin/local/deploy.sh`
- Modify: `bin/cluster-e2e.sh`
- Modify: `tests/cluster/static-contracts.sh`

- [ ] Add failing render assertions that admission is enabled by default, names are release-scoped, and the binding matches CREATE Pod operations in the release namespace.
- [ ] Render a fail-closed `ValidatingAdmissionPolicy` for requests whose `request.userInfo.username` equals `system:serviceaccount:<namespace>:<api-service-account>`. Require the exact managed labels, dedicated WireMock service account, configured image, no host namespaces/ports/path, no privileged or extra-capability path, restricted security context, allowed PVC/projected/service-account-token volume shapes, and resources inside the Task 3 envelope.
- [ ] Make validation tolerant only of IRSA/EKS Pod Identity mutations: additional projected audience-bound token volumes, their mounts, and `AWS_*` workload-identity environment variables are allowed; general Kubernetes API token automount remains false. Do not require an IRSA annotation in clusters that use another identity mechanism.
- [ ] Add deterministic accepted/rejected fixture manifests to `render-security.sh`: generated managed pod accepted; privileged, hostPath, wrong image, wrong service account, label spoofing, missing limits, excessive limits, and an alternate sidecar rejected.
- [ ] Label namespaces created by `bin/local/deploy.sh` and `bin/cluster-e2e.sh` with `pod-security.kubernetes.io/enforce=restricted`, `audit=restricted`, and `warn=restricted` at the cluster's current Kubernetes minor. This is PSA setup, not a CNI capability check.
- [ ] Extend the live cluster harness to apply `kubectl auth can-i` checks and server-side dry-run fixtures for accepted/rejected admission. Assert Proxy/Dash/MCP and managed WireMock pods have no general API token volume, the dedicated service account remains selected, and injected workload-identity volumes are not rejected.
- [ ] Run chart tests, `tests/cluster/static-contracts.sh`, and `bin/cluster-e2e.sh --self-test`; expect pass.
- [ ] Commit: `git commit -m "fix: enforce managed pod admission policy"`

## Task 10: Document Operator Responsibilities and Upgrade Behavior

**Files:**

- Modify: `README.md`
- Modify: `deploy/helm/mock-fleet/README.md`
- Modify: `deploy/helm/mock-fleet/NOTES.txt`
- Modify: `docs/superpowers/specs/2026-08-28-security-hardening-design.md` if implementation details differ without changing the approved boundaries

- [ ] Document that Kubernetes accepts NetworkPolicy objects even when the CNI ignores them. Name Calico and Cilium as examples, make enforcement a deployment prerequisite, and provide a small exact-label internal-connectivity denial probe that the cluster owner can run and remove.
- [ ] State explicitly that `make local-deploy` neither checks nor changes CNI capability and that the supplied bridge CNI in the reviewed Minikube cluster did not enforce the policy.
- [ ] Document the dedicated WireMock service account, `wiremock.serviceAccount.annotations`, pod-level S3 CSI authentication, IRSA/EKS Pod Identity token injection, and the distinction from the disabled general Kubernetes API token.
- [ ] Add upgrade notes for prohibited plaintext password options, legacy ConfigMap cleanup, resource floors/ceilings, new capacity/traversal errors, quota sizing, restricted PSA, and the ValidatingAdmissionPolicy Kubernetes-version prerequisite.
- [ ] Ensure all new values, defaults, disable switches, and security consequences appear in the chart values table.
- [ ] Run `tests/cluster/static-contracts.sh` and search for stale statements that tie egress policy to MCP or claim NetworkPolicy enforcement automatically.
- [ ] Commit: `git commit -m "docs: explain security deployment requirements"`

## Task 11: Full Verification, Bypass Review, and Pull Request

**Files:**

- Modify only defects found by verification; add focused regression tests beside the affected implementation.

- [ ] Run formatting and the full repository suites:

  ```bash
  ./fleet-api/mvnw -B -f fleet-api/pom.xml verify -Dquarkus.container-image.build=false
  ./fleet-proxy/mvnw -B -f fleet-proxy/pom.xml verify -Dquarkus.container-image.build=false
  ./fleet-api/mvnw -B -f fleet-mcp/pom.xml verify -Dquarkus.container-image.build=false
  fleet-mcp/tests/wiremock-admin-contract.sh
  npm --prefix fleet-dash ci
  npm --prefix fleet-dash test
  npm --prefix fleet-dash run build
  helm lint deploy/helm/mock-fleet
  deploy/helm/mock-fleet/tests/render-mcp.sh
  deploy/helm/mock-fleet/tests/render-security.sh
  bin/local/tests/deploy-rebuild.sh
  tests/cluster/static-contracts.sh
  bin/cluster-e2e.sh --self-test
  tests/security/dependency-contracts.sh
  ```

- [ ] Deploy the branch to the existing local Minikube cluster without changing its CNI. Verify workload health, Hazelcast clustering, ingress paths, capacity errors, mapping limits, resource-policy rejection, RBAC, admission negative fixtures, restricted PSA, and token mounts. Record the CNI non-enforcement limitation; do not present the live egress denial as passing on this cluster.
- [ ] Perform an independent security diff/bypass review from the merge base. Re-test absolute/scheme-relative targets, header smuggling, same-ID and distinct-ID capacity races, cleanup failure paths, traversal off-by-one cases, legacy secret forms, resource omissions, forged labels, wrong service accounts, sidecars/init containers/ephemeral containers, host fields, projected identity tokens, and NetworkPolicy selectors.
- [ ] Fix every validated bypass with a failing regression test, rerun the focused and full suites, and keep unrelated findings out of this PR.
- [ ] Check `git diff --check`, `git status --short`, and the branch diff. Commit any final verification-only corrections with a narrow conventional subject.
- [ ] Push `codex/security-hardening`, open a PR with the nine findings, design decisions, verification evidence, live-cluster limitation, IRSA compatibility, and review hotspots, then confirm PR checks start successfully.
