# WireMock option compatibility

Mock Fleet supports exact WireMock versions from 3.0.0 through the latest researched stable 3.x release. The packaged matrix currently covers every stable release through 3.13.2. A future exact 3.x pin is allowed; it uses the option set from the latest researched version and returns the catalog-level status `newer_unresearched` until the matrix is advanced.

Fleet API owns the matrix in `fleet-api/src/main/resources/wiremock-option-compatibility.json`. `GET /__fleet/api/config/options` returns `{wireMockVersion,catalogStatus,options}`. The dashboard and `list_option_definitions` MCP tool consume that response without separate version rules. Options outside the selected version's supported range, and sensitive options, are omitted and rejected if submitted directly. Options advertised by WireMock remain available without per-option compatibility warnings. Unknown option names are rejected.

`--timeout` is exposed as a required-value text option and is emitted only when its field contains a value. WireMock 3.13.2 advertises the option without declaring its required argument even though startup reads a numeric value, so a submitted value can still fail in WireMock itself.

Direct credential options are excluded from the public catalog until Mock Fleet provides Secret-backed storage. Mock Fleet rejects them before ConfigMap persistence or pod creation. `--proxy-via` remains public.

To advance the researched maximum:

1. Enumerate every intervening stable WireMock 3.x tag and exclude prereleases.
2. Compare tagged `CommandLineOptions`, standalone documentation, release notes, and container `--help` output.
3. Add every stable tag to `stableReleases`, then update option bounds and reviewed upstream behavior.
4. Run `fleet-api/tests/verify-wiremock-option-matrix.sh` and all API, dashboard, MCP, Helm, Docker Admin API, and Minikube suites.
5. Update the documented researched maximum and release notes in the same change.

Use `fleet-api/tests/verify-wiremock-option-matrix.sh --metadata-only` for a fast schema and inventory check. The no-argument command pulls every published researched official image and fails with an option-name diff when upstream evidence and the checked-in matrix disagree. The release inventory records stable tags for which WireMock published no image and the early 3.0 image help-rendering defect; those rows use tagged source evidence instead.
