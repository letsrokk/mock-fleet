package com.github.letsrokk.mockops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;

import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

final class VersionCatalogInitializer {
    private static final Logger LOG = Logger.getLogger(VersionCatalogInitializer.class);
    static void initialize(KubernetesClient client, ObjectMapper json, String namespace, String name, String seedText) {
        ConfigMap seed;
        AllowedVersionRange range;
        try {
            seed = json.readValue(seedText, ConfigMap.class);
            range = AllowedVersionRange.parse(json.readTree(seed.getMetadata().getAnnotations()
                    .get(CatalogReconciler.IMAGE_POLICY)).path("allowedVersionRange").textValue());
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Invalid version catalog seed.", error);
        }
        CatalogReconciler.validateCatalog(seed.getData());
        var resource = client.configMaps().inNamespace(namespace).withName(name);
        ConfigMap current = resource.get();
        if (current == null) {
            ConfigMap initial = UserConfigInitializer.protect(new ConfigMapBuilder(seed)
                    .editMetadata().withName(name).withNamespace(namespace).endMetadata().build());
            try {
                client.configMaps().inNamespace(namespace).resource(initial).create();
                LOG.infof("Created version catalog %s/%s with default %s.",
                        namespace, name, initial.getData().get("defaultVersion"));
                return;
            } catch (KubernetesClientException error) {
                if (error.getCode() != 409) throw error;
                current = resource.get();
                if (current == null) throw error;
            }
        }

        Map<String, String> data = new LinkedHashMap<>(current.getData());
        int repairedDuplicates = 0;
        for (String key : Map.copyOf(data).keySet()) {
            if (!key.startsWith("retained.")) continue;
            String selectable = "selectable." + key.substring("retained.".length());
            if (!data.containsKey(selectable)) continue;
            if (!data.get(key).equals(data.get(selectable))) {
                throw new IllegalStateException("WireMock catalog version has conflicting image mappings.");
            }
            data.remove(key);
            repairedDuplicates++;
        }
        String defaultVersion = CatalogReconciler.validateCatalog(data);
        for (var entry : Map.copyOf(data).entrySet()) {
            if (entry.getKey().startsWith("selectable.")
                    && !range.contains(CatalogReconciler.tagFromImage(entry.getValue(), null))) {
                data.remove(entry.getKey());
                data.put("retained." + entry.getKey().substring("selectable.".length()), entry.getValue());
            }
        }
        String fallback = seed.getData().get("defaultVersion");
        if (!data.containsKey("selectable." + defaultVersion)) {
            data.put("defaultVersion", fallback);
            data.remove("retained." + fallback);
            data.put("selectable." + fallback, seed.getData().get("selectable." + fallback));
        } else if (!data.containsKey("selectable." + fallback) && !data.containsKey("retained." + fallback)) {
            data.put("selectable." + fallback, seed.getData().get("selectable." + fallback));
        }
        CatalogReconciler.validateCatalog(data);
        ConfigMap update = UserConfigInitializer.protect(new ConfigMapBuilder(current).withData(data)
                .editMetadata().addToAnnotations(CatalogReconciler.IMAGE_POLICY,
                        seed.getMetadata().getAnnotations().get(CatalogReconciler.IMAGE_POLICY))
                .endMetadata().build());
        if (!data.equals(current.getData())
                || !update.getMetadata().getAnnotations().equals(current.getMetadata().getAnnotations())) {
            client.configMaps().inNamespace(namespace).resource(update).update();
            LOG.infof("Initialized version catalog %s/%s: default=%s -> %s, identicalDuplicatesRemoved=%d; policy and protection updated.",
                    namespace, name, defaultVersion, data.get("defaultVersion"), repairedDuplicates);
        } else {
            LOG.infof("Version catalog %s/%s already initialized; default %s and runtime choices preserved.",
                    namespace, name, defaultVersion);
        }
    }
}
