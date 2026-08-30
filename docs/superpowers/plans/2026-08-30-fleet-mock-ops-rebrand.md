# Fleet Mock Ops Rebrand Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename the catalog reconciliation component to Fleet Mock Ops everywhere, give it an independent Maven Wrapper, and preserve its configurable daily CronJob schedule.

**Architecture:** Perform one clean-break identity migration across the module, Java namespace, Helm chart, Kubernetes resources, CI publication, cluster automation, tests, and current documentation. Preserve reconciliation behavior and failure semantics; only public and internal component identity changes. Keep scheduling under top-level `mockOps`, defaulting to `0 2 * * *` in `Etc/UTC`.

**Tech Stack:** Java 21, Quarkus 3.33.3.1, Maven Wrapper 3.3.2, Maven 3.9.9, Helm 3, Kubernetes CronJob/RBAC, GitHub Actions, Bash, Ruby contract tests.

**Spec:** `docs/superpowers/specs/2026-08-30-fleet-mock-ops-rebrand-design.md`

## Global Constraints

- This is a clean break: do not add aliases, deprecated keys, forwarding scripts, duplicate images, or compatibility resource names.
- Use `fleet-mock-ops` for the module and Maven artifact, `mock-ops` for container/Kubernetes/image identities, `mockOps` for Helm values, and `com.github.letsrokk.mockops` / `MockOps` for Java.
- Keep reconciliation behavior, Registry V2 validation, catalog safety, Kubernetes conflict handling, RBAC scope, `concurrencyPolicy: Forbid`, and `backoffLimit: 0` unchanged.
- Keep `mockOps.schedule: "0 2 * * *"` and `mockOps.timeZone: Etc/UTC` as defaults; accept a non-empty Kubernetes cron schedule string.
- The module owns Maven Wrapper 3.3.2 and Maven 3.9.9 and must not invoke another module's wrapper.
- Historical files under `docs/superpowers/specs` and `docs/superpowers/plans` may retain factual references to the former name. Active documentation and executable surfaces may not.

---

## File structure

### Module identity

- Move `fleet-wiremock-updater/` to `fleet-mock-ops/`.
- Move Java source and test packages from `com/github/letsrokk/updater` to `com/github/letsrokk/mockops`.
- Rename `UpdaterCommand.java` / `UpdaterCommandTest.java` to `MockOpsCommand.java` / `MockOpsCommandTest.java`.
- Rename `UpdaterConfig.java` to `MockOpsConfig.java`.
- Add `fleet-mock-ops/mvnw.cmd` and `fleet-mock-ops/.mvn/wrapper/*`; replace the forwarding `mvnw` with the standard independent launcher.
- Add `tests/mock-ops-module-contract.rb` to guard module identity and wrapper ownership.

### Helm identity and schedule

- Move `deploy/helm/mock-fleet/templates/wiremock-updater-*.yaml` to `deploy/helm/mock-fleet/templates/mock-ops-*.yaml`.
- Move `deploy/helm/mock-fleet/tests/render-updater.sh` to `deploy/helm/mock-fleet/tests/render-mock-ops.sh`.
- Modify `values.yaml`, `values.schema.json`, `_helpers.tpl`, and the chart README for top-level `mockOps` and `mock-ops` resources.

### Workflows and operations

- Move `tests/workflow-updater-contract.rb` to `tests/workflow-mock-ops-contract.rb`.
- Modify build, publish, and cluster E2E workflows for the new job, artifact, directory, image, step, and variable names.
- Modify `bin/cluster-e2e.sh`, root `README.md`, and `docs/exploratory-checklist.md` for the new Helm and Kubernetes contracts.

---

### Task 1: Rename the module and give it an independent wrapper

**Files:**
- Create: `tests/mock-ops-module-contract.rb`
- Move: `fleet-wiremock-updater/` → `fleet-mock-ops/`
- Move: `fleet-mock-ops/src/main/java/com/github/letsrokk/updater/` → `fleet-mock-ops/src/main/java/com/github/letsrokk/mockops/`
- Move: `fleet-mock-ops/src/test/java/com/github/letsrokk/updater/` → `fleet-mock-ops/src/test/java/com/github/letsrokk/mockops/`
- Rename: `UpdaterCommand.java` → `MockOpsCommand.java`
- Rename: `UpdaterConfig.java` → `MockOpsConfig.java`
- Rename: `UpdaterCommandTest.java` → `MockOpsCommandTest.java`
- Modify: all moved Java sources and tests, `fleet-mock-ops/pom.xml`, `fleet-mock-ops/src/main/resources/application.yaml`
- Create: `fleet-mock-ops/mvnw.cmd`, `fleet-mock-ops/.mvn/wrapper/.gitignore`, `MavenWrapperDownloader.java`, and `maven-wrapper.properties`
- Replace: `fleet-mock-ops/mvnw`

**Interfaces:**
- Consumes: existing Registry V2, catalog selection, and Kubernetes reconciliation behavior.
- Produces: self-contained `fleet-mock-ops` Maven module; Java package `com.github.letsrokk.mockops`; `MockOpsCommand`; `MockOpsConfig`; configuration prefix `mock-fleet.mock-ops`.

- [ ] **Step 1: Write the failing module identity contract**

Create `tests/mock-ops-module-contract.rb` with:

```ruby
#!/usr/bin/env ruby
# frozen_string_literal: true

def require_contract(condition, message)
  abort(message) unless condition
end

module_root = "fleet-mock-ops"
required = [
  "#{module_root}/pom.xml",
  "#{module_root}/mvnw",
  "#{module_root}/mvnw.cmd",
  "#{module_root}/.mvn/wrapper/MavenWrapperDownloader.java",
  "#{module_root}/.mvn/wrapper/maven-wrapper.properties",
  "#{module_root}/src/main/java/com/github/letsrokk/mockops/MockOpsCommand.java",
  "#{module_root}/src/main/java/com/github/letsrokk/mockops/MockOpsConfig.java",
  "#{module_root}/src/test/java/com/github/letsrokk/mockops/MockOpsCommandTest.java"
]
required.each { |path| require_contract(File.file?(path), "Missing #{path}") }
require_contract(!Dir.exist?("fleet-wiremock-updater"), "Former module directory still exists")

wrapper = File.read("#{module_root}/mvnw")
require_contract(!wrapper.include?("../fleet-api/mvnw"), "Mock Ops wrapper delegates to Fleet API")
properties = File.read("#{module_root}/.mvn/wrapper/maven-wrapper.properties")
require_contract(properties.include?("wrapperVersion=3.3.2"), "Wrong Maven Wrapper version")
require_contract(properties.include?("apache-maven-3.9.9-bin.zip"), "Wrong Maven distribution")

pom = File.read("#{module_root}/pom.xml")
require_contract(pom.include?("<artifactId>fleet-mock-ops</artifactId>"), "Wrong Maven artifact")
puts "Fleet Mock Ops module contract passed."
```

- [ ] **Step 2: Run the contract and verify it fails on the absent module**

Run: `ruby tests/mock-ops-module-contract.rb`

Expected: exit 1 with `Missing fleet-mock-ops/pom.xml`.

- [ ] **Step 3: Move the module, packages, and public Java types**

Run the moves with `git mv`, then apply these exact identity changes:

```text
fleet-wiremock-updater                         -> fleet-mock-ops
com.github.letsrokk.updater                    -> com.github.letsrokk.mockops
UpdaterCommand                                 -> MockOpsCommand
UpdaterConfig                                  -> MockOpsConfig
<artifactId>fleet-wiremock-updater</artifactId> -> <artifactId>fleet-mock-ops</artifactId>
mock-fleet.wiremock-updater                    -> mock-fleet.mock-ops
```

Keep `CatalogReconciler`, `CatalogSelection`, `RegistryV2Client`, and `WireMockTag` class names unchanged inside the new package. Update all moved tests to compile against the new package and type names.

- [ ] **Step 4: Replace the forwarding wrapper with the repository-standard wrapper**

Copy the tracked wrapper sources from `fleet-mcp` into the moved module using repository-safe file edits:

```text
fleet-mcp/mvnw                                      -> fleet-mock-ops/mvnw
fleet-mcp/mvnw.cmd                                  -> fleet-mock-ops/mvnw.cmd
fleet-mcp/.mvn/wrapper/.gitignore                   -> fleet-mock-ops/.mvn/wrapper/.gitignore
fleet-mcp/.mvn/wrapper/MavenWrapperDownloader.java -> fleet-mock-ops/.mvn/wrapper/MavenWrapperDownloader.java
fleet-mcp/.mvn/wrapper/maven-wrapper.properties    -> fleet-mock-ops/.mvn/wrapper/maven-wrapper.properties
```

Set `fleet-mock-ops/mvnw` to mode `100755`. Do not add `maven-wrapper.jar`; `.gitignore` keeps the downloaded runtime out of Git.

- [ ] **Step 5: Run the focused contract and module verification**

Run:

```bash
ruby tests/mock-ops-module-contract.rb
cd fleet-mock-ops
./mvnw --version
./mvnw -Dquarkus.container-image.build=false package
```

Expected: contract passes; Maven reports 3.9.9; all 38 existing module tests pass; Quarkus package build succeeds.

- [ ] **Step 6: Inspect and commit the module slice**

Run: `git diff --check` and inspect all module moves for accidental content loss.

Commit:

```bash
git add fleet-mock-ops fleet-wiremock-updater tests/mock-ops-module-contract.rb
git commit -m "Rename updater module to Fleet Mock Ops"
```

---

### Task 2: Rename the Helm contract and preserve configurable daily scheduling

**Files:**
- Rename: `deploy/helm/mock-fleet/tests/render-updater.sh` → `deploy/helm/mock-fleet/tests/render-mock-ops.sh`
- Rename: `deploy/helm/mock-fleet/templates/wiremock-updater-cronjob.yaml` → `mock-ops-cronjob.yaml`
- Rename: `deploy/helm/mock-fleet/templates/wiremock-updater-serviceaccount.yaml` → `mock-ops-serviceaccount.yaml`
- Rename: `deploy/helm/mock-fleet/templates/wiremock-updater-role.yaml` → `mock-ops-role.yaml`
- Rename: `deploy/helm/mock-fleet/templates/wiremock-updater-rolebinding.yaml` → `mock-ops-rolebinding.yaml`
- Modify: `deploy/helm/mock-fleet/values.yaml`
- Modify: `deploy/helm/mock-fleet/values.schema.json`
- Modify: `deploy/helm/mock-fleet/templates/_helpers.tpl`

**Interfaces:**
- Consumes: image `ghcr.io/letsrokk/mock-fleet/mock-ops`; existing WireMock catalog ConfigMaps and environment variables.
- Produces: top-level `mockOps` Helm object and Kubernetes resources suffixed `mock-ops`.

- [ ] **Step 1: Rename and update the Helm render test before templates**

Move the test to `render-mock-ops.sh`, then change its Helm inputs and assertions to:

```bash
--set mockOps.enabled=true
--set mockOps.schedule='15 */6 * * *'
--set mockOps.timeZone=Europe/Belgrade
--set mockOps.image.repository=example.test/mock-fleet/mock-ops
```

The default render must assert:

```text
schedule: "0 2 * * *"
timeZone: "Etc/UTC"
app.kubernetes.io/component: mock-ops
serviceAccountName: custom-fleet-mock-ops
```

Keep the current constraint, registry, credentials, resources, security context, RBAC, and invalid-value assertions, changing only their value paths and component-facing messages.

- [ ] **Step 2: Run the renamed render test and verify it fails against the old chart contract**

Run: `deploy/helm/mock-fleet/tests/render-mock-ops.sh`

Expected: exit 1 because `.Values.mockOps` and `templates/mock-ops-cronjob.yaml` do not exist.

- [ ] **Step 3: Move templates and rename Helm helpers and identities**

Use these exact helper mappings in `_helpers.tpl`:

```text
mock-fleet.wiremockUpdaterFullname             -> mock-fleet.mockOpsFullname
mock-fleet.wiremockUpdaterSelectorLabels       -> mock-fleet.mockOpsSelectorLabels
mock-fleet.wiremockUpdaterServiceAccountName   -> mock-fleet.mockOpsServiceAccountName
mock-fleet.wiremockUpdaterRoleName             -> mock-fleet.mockOpsRoleName
mock-fleet.wiremockUpdaterRoleBindingName      -> mock-fleet.mockOpsRoleBindingName
```

Every moved template must read `.Values.mockOps`, use `app.kubernetes.io/component: mock-ops`, and use `mock-ops` for the container and generated resource suffix. Change the required-value message to `mockOps.serviceAccount.name is required when serviceAccount.create=false`.

- [ ] **Step 4: Move the values block and schema to top-level `mockOps`**

Move the entire former version-updater object out of `wiremock` without changing nested option semantics. Set these defaults:

```yaml
mockOps:
  enabled: false
  schedule: "0 2 * * *"
  timeZone: Etc/UTC
  defaultVersionConstraint: "3.x"
  minorLines: 5
  image:
    repository: ghcr.io/letsrokk/mock-fleet/mock-ops
```

In `values.schema.json`, define top-level `mockOps` with `additionalProperties: false`, require all existing nested fields, and retain `schedule` and `timeZone` as strings with `minLength: 1`. Remove `versionUpdater` from the `wiremock` properties and required list. Add `mockOps` to the root required list so the old key cannot satisfy the chart contract.

- [ ] **Step 5: Run focused Helm checks**

Run:

```bash
deploy/helm/mock-fleet/tests/render-mock-ops.sh
helm lint deploy/helm/mock-fleet
helm template mock-fleet deploy/helm/mock-fleet --set mockOps.enabled=true
```

Expected: render contract passes, lint passes, and the rendered CronJob uses the daily default, `mock-ops` identities, unchanged security context, and unchanged least-privilege RBAC.

- [ ] **Step 6: Inspect and commit the Helm slice**

Run: `git diff --check` and inspect the rendered output for any former component names.

Commit:

```bash
git add deploy/helm/mock-fleet
git commit -m "Rebrand updater Helm resources as Mock Ops"
```

---

### Task 3: Rename build, publish, and cluster workflow contracts

**Files:**
- Rename: `tests/workflow-updater-contract.rb` → `tests/workflow-mock-ops-contract.rb`
- Modify: `.github/workflows/build.yml`
- Modify: `.github/workflows/publish.yml`
- Modify: `.github/workflows/cluster-e2e.yml`

**Interfaces:**
- Consumes: `fleet-mock-ops`, its independent `./mvnw`, Dockerfile, and package output.
- Produces: `fleet-mock-ops-package` artifact and `ghcr.io/letsrokk/mock-fleet/mock-ops` image.

- [ ] **Step 1: Rename and update the workflow contract first**

Move the contract and make it assert these exact workflow identities:

```ruby
mock_ops_job = build.fetch("jobs", {}).fetch("fleet-mock-ops", nil)
fail_contract("Build workflow does not package fleet-mock-ops") unless mock_ops_job

fail_contract("Publish workflow has no Mock Ops image name") unless
  publish.dig("env", "MOCK_OPS_IMAGE_NAME") == "ghcr.io/letsrokk/mock-fleet/mock-ops"

mock_ops_metadata_step = publish_steps.find { |step| step["name"] == "Extract Mock Ops Docker metadata" }
mock_ops_image_step = publish_steps.find { |step| step["name"] == "Build and push Mock Ops Docker image" }
```

Require artifact name `fleet-mock-ops-package`, path `fleet-mock-ops/target/quarkus-app`, Docker context `fleet-mock-ops`, Dockerfile `fleet-mock-ops/src/main/docker/Dockerfile.jvm`, annotation title `mock-fleet-mock-ops`, and cluster image `mock-fleet/mock-ops:${MOCK_FLEET_E2E_IMAGE_TAG}`. Require the Helm job to run `render-mock-ops.sh` and `workflow-mock-ops-contract.rb`.

- [ ] **Step 2: Run the workflow contract and verify it fails on the old workflows**

Run: `ruby tests/workflow-mock-ops-contract.rb`

Expected: exit 1 because build job `fleet-mock-ops` is absent.

- [ ] **Step 3: Update build and cluster workflows**

Apply these exact workflow mappings:

```text
fleet-wiremock-updater job/directory/artifact -> fleet-mock-ops
render-updater.sh                             -> render-mock-ops.sh
workflow-updater-contract.rb                  -> workflow-mock-ops-contract.rb
mock-fleet/wiremock-updater image             -> mock-fleet/mock-ops
```

Keep the same Java setup, Maven flags, artifact retention, Docker build location, and cluster E2E sequencing.

- [ ] **Step 4: Update publish workflow variables, steps, and image metadata**

Use:

```text
UPDATER_IMAGE_NAME       -> MOCK_OPS_IMAGE_NAME
UPDATER_TAGS             -> MOCK_OPS_TAGS
updater_docker_metadata  -> mock_ops_docker_metadata
WireMock updater         -> Mock Ops
mock-fleet-wiremock-updater -> mock-fleet-mock-ops
wiremock.versionUpdater.enabled=true -> mockOps.enabled=true
```

Publish only `ghcr.io/letsrokk/mock-fleet/mock-ops`. Keep tag validation and push behavior unchanged.

- [ ] **Step 5: Run the workflow contract and YAML parse checks**

Run:

```bash
ruby tests/workflow-mock-ops-contract.rb
ruby -e 'require "yaml"; Dir[".github/workflows/*.yml"].each { |path| YAML.safe_load(File.read(path), aliases: true) }'
```

Expected: contract passes and every workflow parses.

- [ ] **Step 6: Inspect and commit the workflow slice**

Run: `git diff --check` and inspect all changed job dependencies, artifact names, and step IDs.

Commit:

```bash
git add .github/workflows tests/workflow-mock-ops-contract.rb tests/workflow-updater-contract.rb
git commit -m "Publish Fleet Mock Ops artifacts and image"
```

---

### Task 4: Rename cluster automation and active documentation

**Files:**
- Modify: `tests/mock-ops-module-contract.rb`
- Modify: `bin/cluster-e2e.sh`
- Modify: `README.md`
- Modify: `deploy/helm/mock-fleet/README.md`
- Modify: `docs/exploratory-checklist.md`

**Interfaces:**
- Consumes: `mockOps` Helm values and `${release}-mock-ops` CronJob/ServiceAccount names.
- Produces: current commands and documentation with no former active identity.

- [ ] **Step 1: Extend the identity contract with an active-surface stale-name audit**

Append this contract logic, constructing former names so the contract does not match itself:

```ruby
former_module = ["fleet", "wiremock", "updater"].join("-")
former_component = ["wiremock", "updater"].join("-")
former_helm_key = ["wiremock", "versionUpdater"].join(".")
former_role = ["up", "dater"].join
active_paths = [
  ".github/workflows",
  "README.md",
  "bin",
  "deploy/helm/mock-fleet",
  "docs/exploratory-checklist.md",
  "fleet-mock-ops",
  "tests"
]
forbidden = [former_module, former_component, former_helm_key,
             "com.github.letsrokk.updater", "UpdaterCommand", "UpdaterConfig", "UPDATER_", former_role]
active_paths.each do |path|
  files = File.directory?(path) ? Dir.glob("#{path}/**/*", File::FNM_DOTMATCH) : [path]
  files.select { |file| File.file?(file) && file != __FILE__ }.each do |file|
    next if file.include?("/target/")
    content = File.binread(file)
    next if content.include?("\x00")
    forbidden.each do |term|
      require_contract(!content.downcase.include?(term.downcase), "#{file} still contains #{term}")
    end
  end
end
```

- [ ] **Step 2: Run the contract and verify it reports the first active stale name**

Run: `ruby tests/mock-ops-module-contract.rb`

Expected: exit 1 naming `bin/cluster-e2e.sh`, `README.md`, the chart README, or exploratory checklist.

- [ ] **Step 3: Rename cluster E2E identities and values**

Apply these exact mappings in `bin/cluster-e2e.sh`:

```text
updater_image / MOCK_FLEET_E2E_UPDATER_IMAGE -> mock_ops_image / MOCK_FLEET_E2E_MOCK_OPS_IMAGE
${release}-wiremock-updater                  -> ${release}-mock-ops
${release}-wiremock-updater-e2e              -> ${release}-mock-ops-e2e
wiremock.versionUpdater                      -> mockOps
mock-fleet/wiremock-updater                  -> mock-fleet/mock-ops
WireMock updater                             -> Mock Ops
```

Preserve the intentionally impossible E2E schedule `0 0 31 2 *`, which prevents the CronJob from racing the manually created reconciliation Job.

- [ ] **Step 4: Update current documentation**

In the root README and exploratory checklist, replace the optional updater wording with Fleet Mock Ops and use `mockOps.enabled`. In the chart README, rename the section and every values row to `mockOps.*`, document image `ghcr.io/letsrokk/mock-fleet/mock-ops`, default schedule `"0 2 * * *"`, default time zone `Etc/UTC`, and the same one-attempt reconciliation behavior.

Do not rewrite historical specs or plans that describe the former implementation.

- [ ] **Step 5: Run the identity, cluster, and chart documentation contracts**

Run:

```bash
ruby tests/mock-ops-module-contract.rb
tests/cluster/static-contracts.sh
deploy/helm/mock-fleet/tests/render-mock-ops.sh
```

Expected: all contracts pass and the active-surface audit finds no former identity.

- [ ] **Step 6: Inspect and commit the operational slice**

Run: `git diff --check` and inspect help text, environment override names, Helm commands, and documentation links.

Commit:

```bash
git add bin/cluster-e2e.sh README.md deploy/helm/mock-fleet/README.md docs/exploratory-checklist.md tests/mock-ops-module-contract.rb
git commit -m "Update operations for Fleet Mock Ops"
```

---

### Task 5: Run integrated verification and update PR #114

**Files:**
- Verify only; modify files only to correct an in-scope failure discovered by these checks.

**Interfaces:**
- Consumes: the combined module, chart, workflow, automation, and documentation rename.
- Produces: one verified clean-break rebrand on `codex/repository-simplification`.

- [ ] **Step 1: Run the complete component build**

Run:

```bash
cd fleet-mock-ops
./mvnw --version
./mvnw -Dquarkus.container-image.build=false package
```

Expected: Maven 3.9.9, 38 tests with zero failures/errors/skips, and `BUILD SUCCESS`.

- [ ] **Step 2: Run all affected repository contracts**

Run from the repository root:

```bash
ruby tests/mock-ops-module-contract.rb
ruby tests/workflow-mock-ops-contract.rb
deploy/helm/mock-fleet/tests/render-mock-ops.sh
tests/cluster/static-contracts.sh
tests/security/dependency-contracts.sh
helm lint deploy/helm/mock-fleet
```

Expected: every command exits 0.

- [ ] **Step 3: Verify the clean-break stale-name boundary independently**

Run:

```bash
rg -n --hidden -g '!.git/**' -g '!**/target/**' -g '!docs/superpowers/specs/**' -g '!docs/superpowers/plans/**' -i 'fleet-wiremock-updater|wiremock-updater|versionUpdater|UpdaterCommand|UpdaterConfig|com\.github\.letsrokk\.updater|UPDATER_|updater' .
```

Expected: exit 1 with no matches.

- [ ] **Step 4: Inspect repository state and combined diff**

Run:

```bash
git diff --check
git status --short --branch
git diff master...HEAD --stat
```

Expected: no unstaged changes, no ignored wrapper JAR staged, and only the approved rebrand plus the preceding simplification work in PR #114.

- [ ] **Step 5: Push and verify the PR**

Push `codex/repository-simplification`, then verify PR #114 reports the new commits and remains based on `master`.

```bash
git push https://github.com/letsrokk/mock-fleet.git codex/repository-simplification
gh pr view 114 --json url,state,baseRefName,headRefName,commits
```

Expected: PR state `OPEN`, base `master`, head `codex/repository-simplification`, and all Fleet Mock Ops commits present.
