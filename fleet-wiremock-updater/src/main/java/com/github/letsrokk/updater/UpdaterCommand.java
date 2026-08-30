package com.github.letsrokk.updater;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.http.HttpClient;

@QuarkusMain
public final class UpdaterCommand implements QuarkusApplication {
    @Inject
    UpdaterConfig config;

    @Inject
    KubernetesClient kubernetes;

    @Inject
    ObjectMapper json;

    @Override
    public int run(String... args) {
        RegistryV2Client registry = new RegistryV2Client(
                HttpClient.newHttpClient(), json, registryCredentials());
        CatalogSelection.Selection selection = CatalogSelection.select(
                config.repository(),
                registry.tags(URI.create(config.registryUrl()), config.repository(), config.pageSize()),
                config.minorLines());

        new CatalogReconciler(kubernetes, new ObjectMapper(new YAMLFactory())).reconcile(
                config.namespace(),
                config.catalogConfigMapName(),
                config.baselineConfigMapName(),
                config.userConfigMapName(),
                config.configKey(),
                config.repository(),
                selection,
                config.defaultVersionConstraint());
        return 0;
    }

    private RegistryV2Client.Credentials registryCredentials() {
        return config.registryUsername()
                .map(username -> new RegistryV2Client.Credentials(username,
                        config.registryPassword().orElseThrow(() -> new IllegalArgumentException(
                                "registryPassword is required with registryUsername."))))
                .orElse(null);
    }
}
