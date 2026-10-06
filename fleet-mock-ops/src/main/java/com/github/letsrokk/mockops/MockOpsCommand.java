package com.github.letsrokk.mockops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@QuarkusMain
public final class MockOpsCommand implements QuarkusApplication {
    private static final Logger LOG = Logger.getLogger(MockOpsCommand.class);
    private static final String REPOSITORY_COMPONENT =
            "[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*";
    private static final String DNS_LABEL = "[a-z0-9](?:[a-z0-9-]*[a-z0-9])?";
    private static final Pattern IMAGE_REPOSITORY = Pattern.compile(
            "^(?:" + DNS_LABEL + "(?:\\." + DNS_LABEL + ")*(?::[1-9][0-9]*)?/)?"
                    + REPOSITORY_COMPONENT + "(?:/" + REPOSITORY_COMPONENT + ")*$");

    @Inject
    MockOpsConfig config;

    @Inject
    KubernetesClient kubernetes;

    @Inject
    ObjectMapper json;

    @Override
    public int run(String... args) {
        if (args.length == 1 && "initialize-user-config".equals(args[0])) {
            LOG.infof("Initializing saved configuration in namespace %s.", config.namespace());
            UserConfigInitializer.initialize(kubernetes, config.namespace(), config.userConfigMapName(), config.configKey());
            config.catalogSeed().ifPresent(seed -> VersionCatalogInitializer.initialize(
                    kubernetes, json, config.namespace(), config.catalogConfigMapName(), seed));
            LOG.info("Configuration initialization completed.");
            return 0;
        }
        URI registryUri = URI.create(config.registryUrl());
        String imageRepository = imageRepository(registryUri, config.repository(), config.imageRepository());
        LOG.infof("Starting WireMock discovery: namespace=%s, catalog=%s, registry=%s, repository=%s.",
                config.namespace(), config.catalogConfigMapName(), registryUri.getHost(), config.repository());
        new CatalogReconciler(kubernetes, new ObjectMapper(new YAMLFactory())).reconcile(
                config.namespace(),
                config.catalogConfigMapName(),
                config.baselineConfigMapName(),
                config.userConfigMapName(),
                config.configKey(),
                imageRepository,
                () -> {
                    var credentials = DockerConfigCredentials.read(json, registryUri,
                            config.registryConfigFiles().orElseGet(List::of));
                    LOG.infof("Registry authentication: %s.", credentials == null ? "anonymous" : "image pull secret");
                    return new RegistryV2Client(HttpClient.newHttpClient(), json, credentials)
                            .tags(registryUri, config.repository(), config.pageSize());
                },
                config.minorLines(),
                config.allowedVersionRange(),
                config.defaultImage());
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
}
