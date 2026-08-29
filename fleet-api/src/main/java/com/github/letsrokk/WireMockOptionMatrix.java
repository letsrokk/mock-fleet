package com.github.letsrokk;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class WireMockOptionMatrix {

    private static final String RESOURCE = "/wiremock-option-compatibility.json";
    private final WireMockVersion minimumSupportedVersion;
    private final WireMockVersion maximumResearchedVersion;
    private final List<WireMockVersion> stableReleases;
    private final Map<String, MatrixOption> options;

    private WireMockOptionMatrix(MatrixDocument document) {
        minimumSupportedVersion = WireMockVersion.parse(document.minimumSupportedVersion());
        maximumResearchedVersion = WireMockVersion.parse(document.maximumResearchedVersion());
        stableReleases = document.stableReleases().stream().map(StableRelease::version)
                .map(WireMockVersion::parse).toList();
        options = validateAndIndex(document.options());
        validateBounds();
    }

    static WireMockOptionMatrix loadDefault() {
        return Holder.INSTANCE;
    }

    static WireMockOptionMatrix load(InputStream input) {
        try {
            return new WireMockOptionMatrix(new ObjectMapper().readValue(input, MatrixDocument.class));
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Invalid packaged WireMock option compatibility matrix", error);
        }
    }

    WireMockVersion minimumSupportedVersion() {
        return minimumSupportedVersion;
    }

    WireMockVersion maximumResearchedVersion() {
        return maximumResearchedVersion;
    }

    List<WireMockVersion> stableReleases() {
        return stableReleases;
    }

    ResolvedCatalog resolve(WireMockVersion version) {
        if (version.compareTo(minimumSupportedVersion) < 0) {
            throw new IllegalArgumentException("WireMock version must be at least " + minimumSupportedVersion);
        }
        boolean future = version.compareTo(maximumResearchedVersion) > 0;
        List<WireMockOptionCatalog.OptionDefinition> resolved = WireMockOptionCatalog.baseDefinitions().stream()
                .map(option -> resolve(option, options.get(option.name()), version, future))
                .toList();
        return new ResolvedCatalog(version, minimumSupportedVersion, maximumResearchedVersion,
                future ? "newer_unresearched" : "supported", resolved);
    }

    private WireMockOptionCatalog.OptionDefinition resolve(WireMockOptionCatalog.OptionDefinition presentation,
                                                             MatrixOption matrixOption,
                                                             WireMockVersion version,
                                                             boolean future) {
        WireMockVersion minimum = versionOrDefault(matrixOption.minimumVersion(), minimumSupportedVersion);
        WireMockVersion maximum = versionOrNull(matrixOption.maximumVersion());
        boolean inRange = version.compareTo(minimum) >= 0 && (maximum == null || version.compareTo(maximum) <= 0);
        String compatibility = "supported";
        String message = null;
        if (future) {
            compatibility = "unknown";
            message = "This option has not been verified with WireMock " + version + ".";
        } else if (matrixOption.unsupported()) {
            compatibility = "unsupported";
            message = "This option is not part of the WireMock Java 3.x command line.";
        } else if (!inRange) {
            compatibility = "unsupported";
            message = version.compareTo(minimum) < 0
                    ? "This option was introduced in WireMock " + minimum + "."
                    : "This option is not present after WireMock " + maximum + ".";
        } else if (matrixOption.knownIssue() != null
                && (matrixOption.knownIssueVersion() == null
                || version.equals(WireMockVersion.parse(matrixOption.knownIssueVersion())))) {
            compatibility = "known_broken";
            message = matrixOption.knownIssue();
        }

        String kind = presentation.kind();
        Integer numericMinimum = presentation.minimum();
        Integer numericMaximum = presentation.maximum();
        if ("--timeout".equals(presentation.name())) {
            kind = "flag";
            numericMinimum = null;
            numericMaximum = null;
        }

        boolean available = matrixOption.unavailableReason() == null;
        List<WireMockOptionCatalog.VersionRange> ranges = matrixOption.unsupported()
                ? List.of()
                : List.of(new WireMockOptionCatalog.VersionRange(
                        minimum.toString(), maximum == null ? null : maximum.toString()));
        return new WireMockOptionCatalog.OptionDefinition(
                presentation.name(), presentation.label(), kind, presentation.group(), presentation.description(),
                presentation.values(), numericMinimum, numericMaximum, available,
                matrixOption.unavailableReason(), compatibility, message, ranges);
    }

    private Map<String, MatrixOption> validateAndIndex(List<MatrixOption> entries) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("options must not be empty");
        }
        Map<String, MatrixOption> indexed = new HashMap<>();
        for (MatrixOption option : entries) {
            if (option.name() == null || !option.name().startsWith("--")) {
                throw new IllegalArgumentException("invalid option name");
            }
            if (indexed.put(option.name(), option) != null) {
                throw new IllegalArgumentException("duplicate option: " + option.name());
            }
            WireMockVersion minimum = versionOrDefault(option.minimumVersion(), minimumSupportedVersion);
            WireMockVersion maximum = versionOrNull(option.maximumVersion());
            if (maximum != null && minimum.compareTo(maximum) > 0) {
                throw new IllegalArgumentException("invalid version range: " + option.name());
            }
            if (option.unsupported() && (option.minimumVersion() != null || option.maximumVersion() != null)) {
                throw new IllegalArgumentException("unsupported option has a version range: " + option.name());
            }
        }
        Set<String> catalogNames = new HashSet<>();
        WireMockOptionCatalog.baseDefinitions().forEach(option -> catalogNames.add(option.name()));
        if (!indexed.keySet().equals(catalogNames)) {
            Set<String> missing = new HashSet<>(catalogNames);
            missing.removeAll(indexed.keySet());
            Set<String> unknown = new HashSet<>(indexed.keySet());
            unknown.removeAll(catalogNames);
            throw new IllegalArgumentException("matrix/catalog drift; missing=" + missing + ", unknown=" + unknown);
        }
        return Map.copyOf(indexed);
    }

    private void validateBounds() {
        if (minimumSupportedVersion.compareTo(maximumResearchedVersion) > 0 || stableReleases.isEmpty()) {
            throw new IllegalArgumentException("invalid researched version bounds");
        }
        Set<WireMockVersion> unique = new HashSet<>(stableReleases);
        if (unique.size() != stableReleases.size()
                || !stableReleases.getFirst().equals(minimumSupportedVersion)
                || !stableReleases.getLast().equals(maximumResearchedVersion)) {
            throw new IllegalArgumentException("stable release inventory does not match researched bounds");
        }
        for (int index = 1; index < stableReleases.size(); index++) {
            if (stableReleases.get(index - 1).compareTo(stableReleases.get(index)) >= 0) {
                throw new IllegalArgumentException("stable release inventory must be ordered and unique");
            }
        }
    }

    private static WireMockVersion versionOrDefault(String value, WireMockVersion fallback) {
        return value == null ? fallback : WireMockVersion.parse(value);
    }

    private static WireMockVersion versionOrNull(String value) {
        return value == null ? null : WireMockVersion.parse(value);
    }

    private static final class Holder {
        private static final WireMockOptionMatrix INSTANCE = readDefault();

        private static WireMockOptionMatrix readDefault() {
            try (InputStream input = WireMockOptionMatrix.class.getResourceAsStream(RESOURCE)) {
                if (input == null) {
                    throw new IllegalStateException("Missing packaged WireMock option compatibility matrix");
                }
                return load(input);
            } catch (IOException error) {
                throw new IllegalStateException("Failed to close WireMock option compatibility matrix", error);
            }
        }
    }

    record MatrixDocument(String minimumSupportedVersion, String maximumResearchedVersion,
                          List<StableRelease> stableReleases, List<MatrixOption> options) {
    }

    record StableRelease(String version, String imageTag, boolean helpUnavailable) {
    }

    record MatrixOption(String name, String minimumVersion, String maximumVersion,
                        boolean unsupported, String unavailableReason,
                        String knownIssueVersion, String knownIssue,
                        boolean advertisedWhenUnsupported) {
    }

    record ResolvedCatalog(WireMockVersion version,
                           WireMockVersion minimumSupportedVersion,
                           WireMockVersion maximumResearchedVersion,
                           String rangeStatus,
                           List<WireMockOptionCatalog.OptionDefinition> options) {
    }
}
