# WireMock option compatibility

Mock Fleet supports exact WireMock versions from 3.0.0 through the latest researched stable 3.x release. The packaged matrix currently covers every stable release through 3.13.2. A future exact 3.x pin is allowed, but every known option reports `unknown` compatibility until the matrix is advanced.

Fleet API owns the matrix in `fleet-api/src/main/resources/wiremock-option-compatibility.json`. The dashboard and `list_option_definitions` MCP tool consume the resolved API response. They do not maintain separate version rules. Compatibility is advisory: `unsupported`, `known_broken`, and `unknown` options can still be saved and passed to WireMock. Unknown option names are rejected.

The following direct credential options are catalogued but unavailable with `SECRET_STORAGE_REQUIRED`: `--admin-api-basic-auth`, `--ca-keystore-password`, `--keystore-password`, `--key-manager-password`, and `--truststore-password`. Mock Fleet rejects them before ConfigMap persistence or pod creation. `--proxy-via` remains available.

To advance the researched maximum:

1. Enumerate every intervening stable WireMock 3.x tag and exclude prereleases.
2. Compare tagged `CommandLineOptions`, standalone documentation, release notes, and container `--help` output.
3. Add every stable tag to `stableReleases`, then update option bounds and reviewed known issues.
4. Run `fleet-api/tests/verify-wiremock-option-matrix.sh` and all API, dashboard, MCP, Helm, Docker Admin API, and Minikube suites.
5. Update the documented researched maximum and release notes in the same change.

Use `fleet-api/tests/verify-wiremock-option-matrix.sh --metadata-only` for a fast schema and inventory check. The no-argument command pulls every published researched official image and fails with an option-name diff when upstream evidence and the checked-in matrix disagree. The release inventory records stable tags for which WireMock published no image and the early 3.0 image help-rendering defect; those rows use tagged source evidence instead.
