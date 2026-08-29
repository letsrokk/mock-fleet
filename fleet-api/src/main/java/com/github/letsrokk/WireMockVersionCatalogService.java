package com.github.letsrokk;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Startup
@ApplicationScoped
public class WireMockVersionCatalogService {

    private static final Logger LOG = Logger.getLogger(WireMockVersionCatalogService.class);
    private static final long MAX_WATCH_RESTART_DELAY_SECONDS = 30;

    @Inject
    MockFleetConfig config;

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    WireMockVersionCatalogParser parser;

    private final ScheduledExecutorService watchExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "wiremock-version-catalog-watch");
        thread.setDaemon(true);
        return thread;
    });
    private volatile WireMockVersionCatalog catalog;
    private volatile Watch watch;
    private volatile boolean shuttingDown;
    private int watchRestartAttempts;

    @PostConstruct
    void loadCatalog() {
        ConfigMap configMap = catalogResource().get();
        if (configMap == null) {
            throw new IllegalStateException("WireMock version catalog ConfigMap is missing.");
        }
        catalog = parser.parse(configMap);
        startWatch();
    }

    WireMockVersionCatalog catalog() {
        WireMockVersionCatalog current = catalog;
        if (current == null) {
            throw new IllegalStateException("WireMock version catalog is unavailable.");
        }
        return current;
    }

    synchronized void startWatch() {
        if (shuttingDown) {
            return;
        }
        try {
            watch = catalogResource().watch(catalogWatcher());
            watchRestartAttempts = 0;
        } catch (RuntimeException error) {
            LOG.warnf(error, "Failed to start WireMock version catalog watch.");
            scheduleWatchRestart();
        }
    }

    Watcher<ConfigMap> catalogWatcher() {
        return new Watcher<>() {
            @Override
            public void eventReceived(Action action, ConfigMap resource) {
                handleCatalogWatchEvent(action, resource);
            }

            @Override
            public void onClose(WatcherException cause) {
                watch = null;
                if (cause != null) {
                    LOG.warnf(cause, "WireMock version catalog watch closed with an error.");
                }
                scheduleWatchRestart();
            }
        };
    }

    void handleCatalogWatchEvent(Watcher.Action action, ConfigMap resource) {
        if (action == Watcher.Action.ERROR || action == Watcher.Action.DELETED || resource == null) {
            return;
        }
        try {
            catalog = parser.parse(resource);
        } catch (IllegalArgumentException error) {
            LOG.warnf(error, "Ignoring invalid WireMock version catalog resourceVersion=%s.",
                    resource.getMetadata() == null ? null : resource.getMetadata().getResourceVersion());
        }
    }

    private synchronized void scheduleWatchRestart() {
        if (shuttingDown) {
            return;
        }
        int attempt = Math.min(watchRestartAttempts++, 5);
        long delaySeconds = Math.min(MAX_WATCH_RESTART_DELAY_SECONDS, 1L << attempt);
        watchExecutor.schedule(this::startWatch, delaySeconds, TimeUnit.SECONDS);
    }

    private io.fabric8.kubernetes.client.dsl.Resource<ConfigMap> catalogResource() {
        String name = config.wiremockVersionCatalogConfigMapName()
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalStateException("WireMock version catalog ConfigMap name is required."));
        return kubernetesClient.configMaps()
                .inNamespace(config.namespace())
                .withName(name);
    }

    @PreDestroy
    void closeWatch() {
        shuttingDown = true;
        Watch current = watch;
        if (current != null) {
            current.close();
        }
        watchExecutor.shutdownNow();
    }
}
