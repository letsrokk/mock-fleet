# WireMock option compatibility

Mock Fleet supports exact WireMock versions from 3.0.0 through the latest researched stable 3.x release. The packaged matrix currently covers every stable release through 3.13.2. A future exact 3.x version can appear in the version catalog; its option lookup uses the latest researched option set and returns the catalog-level status `newer_unresearched` until the matrix is advanced.

Fleet API owns the matrix in `fleet-api/src/main/resources/wiremock-option-compatibility.json`. `GET /__fleet/api/config/options?version=<3.x.y>` returns `{wireMockVersion,catalogStatus,options}` for that selected exact version; an omitted or blank query inherits the version catalog's `defaultVersion`. This endpoint validates semantic 3.x syntax but does not require the version to be selectable, so clients can inspect an exact future version before adding it to the deployment catalog. A malformed or non-3.x query returns `INVALID_WIREMOCK_VERSION`.

The dashboard and `list_option_definitions` MCP tool consume that response without separate version rules. Options outside the selected version's supported range, and sensitive options, are omitted and rejected if submitted directly. A configuration write whose known options conflict with its desired version returns `UNSUPPORTED_WIREMOCK_OPTION` with the exact version and conflicting option names. An absent or newly selected retained pin returns `UNSUPPORTED_WIREMOCK_VERSION`. Both failures occur before the user configuration's `resourceVersion` changes. Options advertised by WireMock remain available without per-option compatibility warnings. Unknown option names are rejected through the normal invalid-option contract.

The deployment version catalog and the compatibility matrix have separate jobs. `GET /__fleet/api/config` reports the catalog `defaultVersion`, `catalogResourceVersion`, and `{version,image,selectable}` entries. Selectable entries are available for new exact per-mock pins. Retained entries keep an existing baseline or user pin runnable after it leaves the selectable window, but a different mock cannot newly select one. Omitting a per-mock pin inherits the current default. Config rows expose the resolved desired `wireMockVersion` and the active pod's nullable `runtimeVersion`, so `futureOnly` changes can show intentional drift until the next start or a `restartActive` replacement.

`--timeout` is exposed as a required-value text option and is emitted only when its field contains a value. WireMock 3.13.2 advertises the option without declaring its required argument even though startup reads a numeric value, so a submitted value can still fail in WireMock itself.

Direct credential options are excluded from the public catalog until Mock Fleet provides Secret-backed storage. Mock Fleet rejects them before ConfigMap persistence or pod creation. `--proxy-via` remains public.

To advance the researched maximum:

1. Enumerate every intervening stable WireMock 3.x tag and exclude prereleases.
2. Compare tagged `CommandLineOptions`, standalone documentation, release notes, and container `--help` output.
3. Add every stable tag to `stableReleases`, then update option bounds and reviewed upstream behavior.
4. Run `fleet-api/tests/verify-wiremock-option-matrix.sh` and all API, dashboard, MCP, Helm, Docker Admin API, and Minikube suites.
5. Update the documented researched maximum and release notes in the same change.

Use `fleet-api/tests/verify-wiremock-option-matrix.sh --metadata-only` for a fast schema and inventory check. The no-argument command pulls every published researched official image and fails with an option-name diff when upstream evidence and the checked-in matrix disagree. The release inventory records stable tags for which WireMock published no image and the early 3.0 image help-rendering defect; those rows use tagged source evidence instead.
