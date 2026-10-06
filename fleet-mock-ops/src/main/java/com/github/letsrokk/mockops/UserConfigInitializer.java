package com.github.letsrokk.mockops;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;

import org.jboss.logging.Logger;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class UserConfigInitializer {
    private static final Logger LOG = Logger.getLogger(UserConfigInitializer.class);
    private static final String KEEP = "helm.sh/resource-policy";
    private static final String COMPARE_OPTIONS = "argocd.argoproj.io/compare-options";
    private static final String SYNC_OPTIONS = "argocd.argoproj.io/sync-options";

    static void initialize(KubernetesClient kubernetes, String namespace, String name, String configKey) {
        var resource = kubernetes.configMaps().inNamespace(namespace).withName(name);
        ConfigMap current = resource.get();
        if (current == null) {
            ConfigMap initial = new ConfigMapBuilder()
                    .withNewMetadata().withName(name).withNamespace(namespace)
                        .addToAnnotations(KEEP, "keep")
                        .addToAnnotations(COMPARE_OPTIONS, "IgnoreExtraneous")
                        .addToAnnotations(SYNC_OPTIONS, "Prune=false,Delete=false")
                    .endMetadata()
                    .addToData(configKey, "wiremock:\n  default:\n    options: []\n  mocks: []\n")
                    .build();
            try {
                kubernetes.configMaps().inNamespace(namespace).resource(initial).create();
                LOG.infof("Created user configuration ConfigMap %s/%s.", namespace, name);
                return;
            } catch (KubernetesClientException error) {
                if (error.getCode() != 409) {
                    throw error;
                }
                current = resource.get();
                if (current == null) {
                    throw error;
                }
            }
        }
        ConfigMap protectedConfig = protect(current);
        if (!protectedConfig.getMetadata().getAnnotations().equals(current.getMetadata().getAnnotations())) {
            kubernetes.configMaps().inNamespace(namespace).resource(protectedConfig).update();
            LOG.infof("Protected user configuration ConfigMap %s/%s; saved data preserved.", namespace, name);
        } else {
            LOG.infof("User configuration ConfigMap %s/%s already initialized; saved data preserved.", namespace, name);
        }
    }

    static ConfigMap protect(ConfigMap current) {
        Map<String, String> annotations = current.getMetadata().getAnnotations();
        Set<String> options = new LinkedHashSet<>();
        if (annotations != null) {
            for (String option : annotations.getOrDefault(SYNC_OPTIONS, "").split(",")) {
                String value = option.trim();
                if (!value.isEmpty() && !value.startsWith("Prune=") && !value.startsWith("Delete=")) {
                    options.add(value);
                }
            }
        }
        options.add("Prune=false");
        options.add("Delete=false");
        String syncOptions = String.join(",", options);
        // Preserve saved data and use resourceVersion to reject a concurrent API write.
        return new ConfigMapBuilder(current)
                .editMetadata()
                    .addToAnnotations(KEEP, "keep")
                    .addToAnnotations(COMPARE_OPTIONS, "IgnoreExtraneous")
                    .addToAnnotations(SYNC_OPTIONS, syncOptions)
                .endMetadata().build();
    }
}
