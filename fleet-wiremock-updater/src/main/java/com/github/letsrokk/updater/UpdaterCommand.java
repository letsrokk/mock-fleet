package com.github.letsrokk.updater;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@QuarkusMain
public final class UpdaterCommand implements QuarkusApplication {
    private static final String REPOSITORY_COMPONENT =
            "[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*";
    private static final String DNS_LABEL = "[a-z0-9](?:[a-z0-9-]*[a-z0-9])?";
    private static final Pattern IMAGE_REPOSITORY = Pattern.compile(
            "^(?:" + DNS_LABEL + "(?:\\." + DNS_LABEL + ")*(?::[1-9][0-9]*)?/)?"
                    + REPOSITORY_COMPONENT + "(?:/" + REPOSITORY_COMPONENT + ")*$");

    @Inject
    UpdaterConfig config;

    @Inject
    KubernetesClient kubernetes;

    @Inject
    ObjectMapper json;

    @Override
    public int run(String... args) {
        URI registryUri = URI.create(config.registryUrl());
        RegistryV2Client registry = new RegistryV2Client(
                HttpClient.newHttpClient(), json, registryCredentials());
        String imageRepository = imageRepository(registryUri, config.repository(), config.imageRepository());
        CatalogSelection.Selection selection = CatalogSelection.select(
                imageRepository,
                registry.tags(registryUri, config.repository(), config.pageSize()),
                config.minorLines());

        new CatalogReconciler(kubernetes, new ObjectMapper(new YAMLFactory())).reconcile(
                config.namespace(),
                config.catalogConfigMapName(),
                config.baselineConfigMapName(),
                config.userConfigMapName(),
                config.configKey(),
                imageRepository,
                selection,
                config.defaultVersionConstraint());
        return 0;
    }

    static String imageRepository(URI registry, String repository, Optional<String> configured) {
        Optional<String> override = configured.filter(value -> !value.isBlank());
        if (override.isEmpty() && registry.getRawAuthority() != null
                && registry.getRawAuthority().startsWith("[")) {
            throw unsupportedIpv6ImageRepository();
        }
        String result = override.orElseGet(() -> {
            if ("https".equalsIgnoreCase(registry.getScheme())
                    && "registry-1.docker.io".equalsIgnoreCase(registry.getHost())
                    && registry.getPort() == -1) {
                return repository;
            }
            if (registry.getHost() == null || registry.getUserInfo() != null
                    || !("http".equalsIgnoreCase(registry.getScheme())
                    || "https".equalsIgnoreCase(registry.getScheme()))) {
                throw new IllegalArgumentException("registry URL must be an absolute HTTP(S) origin.");
            }
            return registry.getRawAuthority().toLowerCase(Locale.ROOT) + "/" + repository;
        });
        if (result.startsWith("[")) {
            throw unsupportedIpv6ImageRepository();
        }
        if (!IMAGE_REPOSITORY.matcher(result).matches()) {
            throw new IllegalArgumentException("imageRepository must be a pullable image repository without a tag.");
        }
        return result;
    }

    private static IllegalArgumentException unsupportedIpv6ImageRepository() {
        return new IllegalArgumentException("Bracketed IPv6 imageRepository authorities are not supported.");
    }

    private RegistryV2Client.Credentials registryCredentials() {
        return config.registryUsername()
                .map(username -> new RegistryV2Client.Credentials(username,
                        config.registryPassword().orElseThrow(() -> new IllegalArgumentException(
                                "registryPassword is required with registryUsername."))))
                .orElse(null);
    }
}
