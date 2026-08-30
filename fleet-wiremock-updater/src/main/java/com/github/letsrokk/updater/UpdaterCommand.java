package com.github.letsrokk.updater;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;

@QuarkusMain
public final class UpdaterCommand implements QuarkusApplication {
    @Inject UpdaterConfig config;
    @Inject KubernetesClient kubernetes;
    @Inject ObjectMapper json;
    @Override public int run(String... args) {
        RegistryV2Client.Credentials credentials = config.registryUsername().map(username ->
                new RegistryV2Client.Credentials(username, config.registryPassword().orElseThrow(
                        () -> new IllegalArgumentException("registryPassword is required with registryUsername.")))).orElse(null);
        CatalogSelection.Selection selection = CatalogSelection.select(config.repository(),
                new RegistryV2Client(HttpClient.newHttpClient(), json, credentials).tags(URI.create(config.registryUrl()), config.repository(), config.pageSize()), config.minorLines());
        var selectable = selection.selectable();
        String current = kubernetes.configMaps().inNamespace(config.namespace()).withName(config.catalogConfigMapName()).get()
                .getData().get("defaultVersion");
        String next = selection.candidates().stream().filter(tag -> CatalogSelection.matchesConstraint(config.defaultVersionConstraint(), tag.version()))
                .max(WireMockTag.ORDER).map(WireMockTag::version)
                .filter(candidate -> current == null || WireMockTag.ORDER.compare(WireMockTag.parse(candidate).orElseThrow(), WireMockTag.parse(current).orElseThrow()) >= 0)
                .orElse(current);
        if (next == null) throw new IllegalStateException("No selectable version satisfies defaultVersionConstraint.");
        new CatalogReconciler(kubernetes).reconcile(config.namespace(), config.catalogConfigMapName(), config.baselineConfigMapName(),
                config.userConfigMapName(), config.configKey(), selectable, next);
        return 0;
    }
}
