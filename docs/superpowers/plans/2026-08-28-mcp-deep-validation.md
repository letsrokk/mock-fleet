# MCP Deep Validation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Live steps must remain single-owner because they share one Minikube cluster.

**Goal:** Validate and harden all 32 MCP tools through a repeatable test-fix-redeploy loop.

**Architecture:** Use the existing ownership-safe cluster E2E harness as the canonical all-tool matrix. Add focused unit or integration regressions for each confirmed defect, then rebuild the main local deployment and rerun the same live matrix against fresh disposable state.

**Tech Stack:** Bash, jq, curl, Kubernetes, Helm, Minikube, SeaweedFS S3 CSI, Java 21, Quarkus, Maven, WireMock 3.x.

**Spec:** `docs/superpowers/specs/2026-08-28-mcp-deep-validation-design.md`

## Global Constraints

- Exclude authentication findings; the service is intentionally auth-less in restricted private networks.
- Do not modify the existing `catalog` or `payments` mock state.
- Use unique harness-owned namespaces, buckets, mock IDs, mappings, and files.
- Add a failing regression test before every production behavior change.
- Run live cluster operations serially.
- Completion requires a clean full pass after `make local-deploy REBUILD=all`.

---

### Task 1: Establish the isolated baseline

**Files:**
- Verify: `fleet-mcp/pom.xml`
- Verify: `tests/cluster/static-contracts.sh`
- Verify: `deploy/helm/mock-fleet/tests/render-mcp.sh`

**Interfaces:**
- Consumes: Git revision `05036b77c4927983290693ec9ce164d248a85dc7`.
- Produces: A clean, attributable baseline for later comparisons.

- [x] **Step 1: Run the MCP unit and integration suite**

Run: `./fleet-api/mvnw -B -f fleet-mcp/pom.xml test -Dquarkus.container-image.build=false`

Expected: `Tests run: 232, Failures: 0, Errors: 0, Skipped: 0`.

- [x] **Step 2: Run static cluster and Helm contracts**

Run: `tests/cluster/static-contracts.sh`

Run: `deploy/helm/mock-fleet/tests/render-mcp.sh`

Expected: both exit zero.

### Task 2: Run the disposable all-tool live matrix

**Files:**
- Execute: `bin/cluster-e2e.sh`
- Inspect: `docs/mcp-contract.md`
- Inspect: `docs/exploratory-checklist.md`

**Interfaces:**
- Consumes: SeaweedFS admin credentials from `seaweedfs/seaweedfs-s3-secret` and local `latest` images.
- Produces: Direct evidence for all 32 tools, lifecycle and persistence transitions, and cleanup.

- [ ] **Step 1: Run the ownership-safe cluster harness**

Run:

```bash
MOCK_FLEET_E2E_S3_ACCESS_KEY="$(kubectl -n seaweedfs get secret seaweedfs-s3-secret -o jsonpath='{.data.admin_access_key_id}' | base64 --decode)" \
MOCK_FLEET_E2E_S3_SECRET_KEY="$(kubectl -n seaweedfs get secret seaweedfs-s3-secret -o jsonpath='{.data.admin_secret_access_key}' | base64 --decode)" \
bin/cluster-e2e.sh
```

Expected: every contract phase passes and cleanup removes the harness-owned namespace, bucket, PV, and port-forwards.

- [ ] **Step 2: Reconcile every failure before fixing**

For each failure, record the tool, arguments, structured result, relevant pod logs, Fleet API state, WireMock Admin state, and whether cleanup changed persistent state. Classify it as product defect, test defect, environment limitation, or unresolved before editing production code.

### Task 3: Close the published-schema UX gaps

**Files:**
- Modify: `fleet-mcp/src/test/java/com/github/letsrokk/mcp/McpRegistrationTest.java`
- Modify: `fleet-mcp/src/main/java/com/github/letsrokk/mcp/StrictInputSchemaGenerator.java`
- Modify: `fleet-mcp/src/main/java/com/github/letsrokk/mcp/RichJsonInputSchemaGenerator.java`
- Modify: `fleet-mcp/src/main/java/com/github/letsrokk/mcp/BodyInputSchemaGenerator.java`
- Modify if needed: `fleet-mcp/src/main/java/com/github/letsrokk/mcp/UpdateMockConfigInputSchemaGenerator.java`

**Interfaces:**
- Consumes: `FleetMcpConfig.maxPageSize()` and `maxPayloadBytes()` plus existing runtime validators.
- Produces: Closed input schemas whose descriptions and stable constraints match runtime behavior.

- [ ] **Step 1: Add failing discovery assertions**

Add assertions that every top-level property has a non-blank description; all `mockId` properties publish pattern `[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?`; every pagination `limit` publishes minimum `1` and configured maximum `200`; `delete_mock_config.applyMode` publishes `futureOnly` and `restartActive`; body fields describe their encoding contract; and `sizeBytes` publishes minimum `0` and configured maximum `1048576`.

- [ ] **Step 2: Verify the new assertions fail against the current generators**

Run: `./fleet-api/mvnw -B -f fleet-mcp/pom.xml -Dtest=McpRegistrationTest test`

Expected: failures identify the missing descriptions and constraints in the published schemas.

- [ ] **Step 3: Add only the schema metadata already enforced at runtime**

Inject `FleetMcpConfig` into generators that need configured bounds. Keep native WireMock mapping, recorder, snapshot, and request-pattern objects open. Do not add validation rules that the executor does not enforce.

- [ ] **Step 4: Verify focused and full MCP suites**

Run: `./fleet-api/mvnw -B -f fleet-mcp/pom.xml -Dtest=McpRegistrationTest test`

Run: `./fleet-api/mvnw -B -f fleet-mcp/pom.xml test -Dquarkus.container-image.build=false`

Expected: all discovery assertions and all MCP tests pass.

### Task 4: Resolve live findings in test-backed slices

**Files:**
- Test owner: the existing `fleet-mcp/src/test/java/com/github/letsrokk/mcp/*Test.java` class nearest each reproduced boundary.
- Production owner: the existing `fleet-mcp/src/main/java/com/github/letsrokk/mcp/` class that first creates the bad state or response.
- Harness owner: `bin/cluster-e2e.sh` only when the live oracle or isolation is wrong.

**Interfaces:**
- Consumes: The exact Task 2 reproduction and direct upstream state.
- Produces: One regression and one root-cause fix per accepted finding.

- [ ] **Step 1: Create a failing focused regression for the first accepted finding**

The test must exercise the public tool or transport boundary and assert independently derived output or state. Run only that test and confirm the expected failure.

- [ ] **Step 2: Implement the smallest root-cause fix and verify it**

Run the focused test until it passes, then run the affected test class. Repeat Steps 1 and 2 separately for each remaining accepted finding.

- [ ] **Step 3: Rerun the full all-tool matrix**

Use the Task 2 command with a new harness-owned namespace and bucket. Continue only when it exits zero and cleanup succeeds.

### Task 5: Verify, rebuild, deploy, and repeat

**Files:**
- Execute: `Makefile`
- Execute: `bin/local/deploy.sh`
- Verify: all files changed by Tasks 3 and 4.

**Interfaces:**
- Consumes: Test-backed fixes and a clean live matrix.
- Produces: A rebuilt main `mock-fleet` deployment and a clean post-deployment matrix.

- [ ] **Step 1: Run broad repository verification**

Run the MCP suite, WireMock Admin contract, Helm rendering contract, and cluster static contracts. Expected: every command exits zero.

- [ ] **Step 2: Rebuild and deploy all local components**

Run: `make local-deploy REBUILD=all`

Expected: build and Helm upgrade exit zero; API, dashboard, proxy, and MCP workloads become ready.

- [ ] **Step 3: Verify deployed provenance and health**

Check MCP `/version`, MCP readiness, Kubernetes rollout status, and running image IDs. The version revision must match the committed fix revision used for the image build.

- [ ] **Step 4: Repeat the complete live matrix from clean state**

Run the Task 2 command with a new run ID. Expected: all 32 tool contracts pass and ownership-safe cleanup succeeds.

- [ ] **Step 5: Audit completion against the design**

Map each completion criterion in the design to fresh command output. Leave the goal active if any low-or-higher non-auth finding, material UX gap, unavailable live path, cleanup residue, or unverified post-deployment behavior remains.
