# Fleet Mock Ops Rebrand Design

## Goal

Rename and rebrand the WireMock catalog reconciliation component from `fleet-wiremock-updater` to `fleet-mock-ops` across the repository. This is a clean break: the repository will not retain compatibility aliases, deprecated values, forwarding scripts, old image names, or old Kubernetes resource names.

The component keeps its current behavior. It runs as a scheduled Kubernetes CronJob, reads WireMock image tags from a Registry V2 endpoint, validates the complete input state, and updates the WireMock version catalog atomically.

## Naming model

Use one name per ecosystem:

| Surface | Current | New |
| --- | --- | --- |
| Module directory | `fleet-wiremock-updater` | `fleet-mock-ops` |
| Maven artifact | `fleet-wiremock-updater` | `fleet-mock-ops` |
| Java package | `com.github.letsrokk.updater` | `com.github.letsrokk.mockops` |
| Main class | `UpdaterCommand` | `MockOpsCommand` |
| Configuration interface | `UpdaterConfig` | `MockOpsConfig` |
| Quarkus configuration prefix | `mock-fleet.wiremock-updater` | `mock-fleet.mock-ops` |
| Helm values root | `wiremock.versionUpdater` | `mockOps` |
| Kubernetes component and resource suffix | `wiremock-updater` | `mock-ops` |
| Container name | `wiremock-updater` | `mock-ops` |
| Image repository | `ghcr.io/letsrokk/mock-fleet/wiremock-updater` | `ghcr.io/letsrokk/mock-fleet/mock-ops` |
| CI job, artifact, and variables | updater-oriented names | mock-ops-oriented names |
| Operational scripts and contract tests | updater-oriented names | mock-ops-oriented names |

Identifiers that describe the WireMock data being reconciled, such as the version catalog ConfigMap and registry repository, remain WireMock-oriented. Only identifiers that name the component change to Mock Ops.

Historical design and plan documents may retain the old name when they describe the repository state at the time. Current documentation, examples, commands, workflow contracts, and chart documentation must use the new name.

## Module and runtime structure

Move the complete module to `fleet-mock-ops`, including production source, tests, resources, Dockerfile, and Maven configuration. Move Java sources and tests to the `com/github/letsrokk/mockops` path and update package declarations and renamed type references.

Give the module a self-contained Maven Wrapper. It will match the repository standard: Maven Wrapper 3.3.2 and Apache Maven 3.9.9, with Unix and Windows launchers and local wrapper metadata. The new wrapper must not delegate to another module.

Registry discovery, selection, catalog validation, Kubernetes conflict handling, and exit behavior remain unchanged. The rebrand must not broaden the service's responsibilities.

## Helm and scheduling contract

Move the full chart configuration block from `wiremock.versionUpdater` to top-level `mockOps`. The block retains the existing fields for enablement, scheduling, Registry V2 access, catalog selection, image configuration, ServiceAccount configuration, and resources.

`mockOps.schedule` accepts a non-empty Kubernetes CronJob schedule string. Its default remains `"0 2 * * *"`. `mockOps.timeZone` remains configurable and defaults to `Etc/UTC`, so the default reconciliation runs daily at 02:00 UTC.

Rename CronJob, ServiceAccount, Role, RoleBinding, template files, helper names, labels, selectors, container names, validation messages, and rendered resource suffixes from `wiremock-updater` to `mock-ops`. Keep `concurrencyPolicy: Forbid`, `backoffLimit: 0`, and the existing least-privilege RBAC rules.

The chart schema rejects missing or empty required schedule and time-zone values. Kubernetes remains responsible for validating cron syntax beyond the chart's non-empty string contract.

## Build, publication, and operational integration

Rename build and publish workflow jobs, working directories, package artifacts, image metadata, image tags, and human-readable step names. Replace updater-specific workflow variables with Mock Ops names. Publish only the new `mock-fleet/mock-ops` image.

Update cluster E2E commands, local variables, image overrides, Kubernetes Job creation, wait conditions, and failure messages to the new module, image, and resource names. Rename workflow and Helm contract scripts so their paths match the component name.

No workflow will publish or consume the old artifact, module path, image repository, Helm key, or Kubernetes name.

## Compatibility and failure behavior

This is an intentional breaking change with no migration layer. Deployments that use the old Helm values, image name, or Kubernetes resource names must switch to the new names in one release. Helm schema validation must reject the old `wiremock.versionUpdater` value because it is no longer part of the chart schema.

Runtime validation and failure behavior remain unchanged. Invalid registry responses, invalid catalog state, invalid constraints, missing referenced versions, authorization failures, and Kubernetes conflicts still fail before an unsafe catalog write. A failed scheduled Job does not retry within the same Job; the next scheduled run starts from a fresh snapshot.

## Verification

Verification must establish both the rename and behavior preservation:

1. Search all active source, configuration, workflows, scripts, tests, and current documentation for stale `fleet-wiremock-updater`, `wiremock-updater`, `versionUpdater`, updater-specific Java package/type names, and updater-specific CI identifiers. Historical design documents are the only allowed factual references.
2. Run `fleet-mock-ops/./mvnw --version` and confirm Maven Wrapper 3.3.2 metadata and Apache Maven 3.9.9 resolution without another module.
3. Run the full `fleet-mock-ops` package build and test suite.
4. Run the renamed Helm render contract, including the default daily `0 2 * * *` schedule and a custom cron schedule and time zone.
5. Run chart schema checks for old-key rejection, required schedule fields, registry configuration, image repositories, and numeric constraints.
6. Run workflow contracts and cluster/static security contracts that consume the module, image, or Kubernetes names.
7. Render the full chart and confirm labels, resource names, RBAC, image coordinates, and environment wiring use the new component identity.
8. Run `git diff --check` and inspect the final diff for accidental generated artifacts or unrelated changes.

## Out of scope

- Adding responsibilities beyond WireMock version-catalog reconciliation.
- Supporting both old and new names during a transition period.
- Migrating live Kubernetes resources or registries.
- Changing the reconciliation algorithm, retry model, security policy, or catalog schema.
