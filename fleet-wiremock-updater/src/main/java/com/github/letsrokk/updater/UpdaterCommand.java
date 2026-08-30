package com.github.letsrokk.updater;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.Map;

@QuarkusMain
public final class UpdaterCommand implements QuarkusApplication {
    @Inject UpdaterConfig config;
    @Inject KubernetesClient kubernetes;
    @Inject ObjectMapper json;
    @Override public int run(String... args) {
        RegistryV2Client.Credentials credentials = config.registryUsername().map(username ->
                new RegistryV2Client.Credentials(username, config.registryPassword().orElseThrow(
                        () -> new IllegalArgumentException("registryPassword is required with registryUsername.")))).orElse(null);
        Map<String, String> selectable = CatalogSelection.select(config.repository(),
                new RegistryV2Client(HttpClient.newHttpClient(), json, credentials).tags(URI.create(config.registryUrl()), config.repository(), config.pageSize()), config.minorLines());
        String current = kubernetes.configMaps().inNamespace(config.namespace()).withName(config.catalogConfigMapName()).get()
                .getData().get("defaultVersion");
        String next = selectable.keySet().stream().filter(version -> CatalogSelection.matchesConstraint(config.defaultVersionConstraint(), version))
                .max((left, right) -> WireMockTag.ORDER.compare(WireMockTag.parse(left).orElseThrow(), WireMockTag.parse(right).orElseThrow()))
                .filter(candidate -> current == null || WireMockTag.ORDER.compare(WireMockTag.parse(candidate).orElseThrow(), WireMockTag.parse(current).orElseThrow()) >= 0)
                .orElse(current);
        if (next == null) throw new IllegalStateException("No selectable version satisfies defaultVersionConstraint.");
        new CatalogReconciler(kubernetes).reconcile(config.namespace(), config.catalogConfigMapName(), config.baselineConfigMapName(),
                config.userConfigMapName(), config.configKey(), selectable, next);
        return 0;
    }
}
