package com.github.letsrokk.mockops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ConfigMapList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogReconcilerTest {

    @Test
    void reconcilesOneSnapshotAndRetainsOnlyReferencedOldVersions() {
        KubernetesFixture fixture = fixture(
                configMap("catalog", "19", Map.of(
                        "defaultVersion", "3.13.2",
                        "selectable.3.13.2", "example/wiremock:3.13.2-7",
                        "selectable.3.12.1", "example/wiremock:3.12.1-2",
                        "retained.3.11.0", "example/wiremock:3.11.0-1",
                        "retained.3.10.0", "example/wiremock:3.10.0-4")),
                new ConfigMapBuilder().withNewMetadata().withName("baseline").endMetadata()
                        .withData(Map.of("wiremock.yaml", """
                                metadata:
                                  version: 3.9.0
                                wiremock:
                                  default:
                                    version: 3.8.0
                                  mocks:
                                    - id: demo
                                      version: 3.12.1
                                """)).build(),
                configDocument("user", "other", "3.10.0"));
        CatalogSelection.Selection selection = CatalogSelection.select("example/wiremock",
                List.of("3.15.1-2", "3.14.4-3", "3.13.9-5"), 2);

        new CatalogReconciler(fixture.client, yaml()).reconcile(
                "test", "catalog", "baseline", "user", "wiremock.yaml", "example/wiremock",
                selection, "3.13.x");

        ArgumentCaptor<ConfigMap> updated = ArgumentCaptor.forClass(ConfigMap.class);
        verify(fixture.namespaced).resource(updated.capture());
        assertEquals("19", updated.getValue().getMetadata().getResourceVersion());
        assertEquals(Map.of(
                "defaultVersion", "3.13.9",
                "selectable.3.15.1", "example/wiremock:3.15.1-2",
                "selectable.3.14.4", "example/wiremock:3.14.4-3",
                "selectable.3.13.9", "example/wiremock:3.13.9-5",
                "retained.3.12.1", "example/wiremock:3.12.1-2",
                "retained.3.13.2", "example/wiremock:3.13.2-7",
                "retained.3.10.0", "example/wiremock:3.10.0-4"), updated.getValue().getData());
        verify(fixture.catalog, times(1)).get();
        verify(fixture.baseline, times(1)).get();
        verify(fixture.user, times(1)).get();
        verify(fixture.updated, times(1)).update();
        verify(fixture.client, never()).pods();
    }

    @Test
    void conflictFailsWithoutRetryOrReread() {
        KubernetesFixture fixture = fixture(
                configMap("catalog", "19", Map.of(
                        "defaultVersion", "3.13.2",
                        "selectable.3.13.2", "example/wiremock:3.13.2-7")),
                configDocument("baseline", null, null), configDocument("user", null, null));
        when(fixture.updated.update()).thenThrow(new KubernetesClientException("conflict", 409, null));
        CatalogSelection.Selection selection = CatalogSelection.select("example/wiremock", List.of("3.14.1"), 1);

        assertThrows(KubernetesClientException.class, () -> new CatalogReconciler(fixture.client, yaml()).reconcile(
                "test", "catalog", "baseline", "user", "wiremock.yaml", "example/wiremock",
                selection, "3.x"));

        verify(fixture.catalog, times(1)).get();
        verify(fixture.baseline, times(1)).get();
        verify(fixture.user, times(1)).get();
        verify(fixture.updated, times(1)).update();
    }

    @Test
    void malformedYamlFailsAfterSingleReadsAndBeforeUpdate() {
        KubernetesFixture fixture = fixture(
                configMap("catalog", "19", Map.of(
                        "defaultVersion", "3.13.2",
                        "selectable.3.13.2", "example/wiremock:3.13.2-7")),
                new ConfigMapBuilder().withNewMetadata().withName("baseline").endMetadata()
                        .withData(Map.of("wiremock.yaml", "wiremock:\n  mocks: not-a-list\n")).build(),
                configDocument("user", "demo", "3.13.2"));
        CatalogSelection.Selection selection = CatalogSelection.select("example/wiremock", List.of("3.14.1"), 1);

        assertThrows(IllegalStateException.class, () -> new CatalogReconciler(fixture.client, yaml()).reconcile(
                "test", "catalog", "baseline", "user", "wiremock.yaml", "example/wiremock",
                selection, "3.x"));

        verify(fixture.catalog, times(1)).get();
        verify(fixture.baseline, times(1)).get();
        verify(fixture.user, times(1)).get();
        verify(fixture.namespaced, never()).resource(any());
    }

    @Test
    void neverDowngradesCurrentDefault() {
        KubernetesFixture fixture = fixture(
                configMap("catalog", "21", Map.of(
                        "defaultVersion", "3.14.2",
                        "selectable.3.14.2", "example/wiremock:3.14.2-8")),
                configDocument("baseline", null, null), configDocument("user", null, null));
        CatalogSelection.Selection selection = CatalogSelection.select("example/wiremock",
                List.of("3.14.1-9", "3.15.0-1"), 1);

        new CatalogReconciler(fixture.client, yaml()).reconcile(
                "test", "catalog", "baseline", "user", "wiremock.yaml", "example/wiremock",
                selection, "3.14.x");

        ArgumentCaptor<ConfigMap> updated = ArgumentCaptor.forClass(ConfigMap.class);
        verify(fixture.namespaced).resource(updated.capture());
        assertEquals("3.14.2", updated.getValue().getData().get("defaultVersion"));
        assertEquals("example/wiremock:3.14.2-8",
                updated.getValue().getData().get("selectable.3.14.2"));
    }

    @Test
    void constrainedCandidateOutsideMinorLinesUsesItsExactNewerRevision() {
        KubernetesFixture fixture = fixture(
                configMap("catalog", "22", Map.of(
                        "defaultVersion", "3.13.2",
                        "selectable.3.13.2", "example/wiremock:3.13.2-2")),
                configDocument("baseline", null, null), configDocument("user", null, null));
        CatalogSelection.Selection selection = CatalogSelection.select("example/wiremock",
                List.of("3.15.1-2", "3.14.4-3", "3.13.2-7"), 2);

        new CatalogReconciler(fixture.client, yaml()).reconcile(
                "test", "catalog", "baseline", "user", "wiremock.yaml", "example/wiremock",
                selection, "3.13.x");

        ArgumentCaptor<ConfigMap> updated = ArgumentCaptor.forClass(ConfigMap.class);
        verify(fixture.namespaced).resource(updated.capture());
        assertEquals("example/wiremock:3.13.2-7",
                updated.getValue().getData().get("selectable.3.13.2"));
    }

    @Test
    void missingReferencedVersionFailsBeforeCatalogUpdate() {
        KubernetesFixture fixture = fixture(
                configMap("catalog", "23", Map.of(
                        "defaultVersion", "3.13.2",
                        "selectable.3.13.2", "example/wiremock:3.13.2-2")),
                configDocument("baseline", "demo", "3.12.1"), configDocument("user", null, null));
        CatalogSelection.Selection selection = CatalogSelection.select("example/wiremock", List.of("3.14.1"), 1);

        assertThrows(IllegalStateException.class, () -> new CatalogReconciler(fixture.client, yaml()).reconcile(
                "test", "catalog", "baseline", "user", "wiremock.yaml", "example/wiremock",
                selection, "3.x"));

        verify(fixture.namespaced, never()).resource(any());
    }

    @Test
    void invalidConstraintFailsBeforeUpdateEvenWhenRegistryHasNoStableCandidates() {
        KubernetesFixture fixture = fixture(
                configMap("catalog", "24", Map.of(
                        "defaultVersion", "3.13.2",
                        "selectable.3.13.2", "example/wiremock:3.13.2-2")),
                configDocument("baseline", null, null), configDocument("user", null, null));
        CatalogSelection.Selection selection = CatalogSelection.select("example/wiremock", List.of(), 1);

        assertThrows(IllegalArgumentException.class, () -> new CatalogReconciler(fixture.client, yaml()).reconcile(
                "test", "catalog", "baseline", "user", "wiremock.yaml", "example/wiremock",
                selection, "3.13"));

        verify(fixture.namespaced, never()).resource(any());
    }

    @Test
    void malformedUnreferencedCatalogEntryFailsBeforeUpdate() {
        assertInvalidCatalog(Map.of(
                "defaultVersion", "3.13.2",
                "selectable.3.13.2", "example/wiremock:3.13.2-2",
                "selectable.3.9.0", "example/wiremock:latest"), null);
    }

    @Test
    void malformedReferencedCatalogImageFailsBeforeUpdate() {
        assertInvalidCatalog(Map.of(
                "defaultVersion", "3.13.2",
                "selectable.3.13.2", "example/wiremock:3.13.2-2",
                "retained.3.12.1", "example/wiremock:3.11.0-1"), "3.12.1");
    }

    @Test
    void unknownDuplicateAndNonSelectableDefaultCatalogEntriesFailBeforeUpdate() {
        assertInvalidCatalog(Map.of(
                "defaultVersion", "3.13.2",
                "selectable.3.13.2", "example/wiremock:3.13.2-2",
                "metadata", "unexpected"), null);
        assertInvalidCatalog(Map.of(
                "defaultVersion", "3.13.2",
                "selectable.3.13.2", "example/wiremock:3.13.2-2",
                "selectable.3.12.1", "example/wiremock:3.12.1-2",
                "retained.3.12.1", "example/wiremock:3.12.1-2"), null);
        assertInvalidCatalog(Map.of(
                "defaultVersion", "3.13.2",
                "retained.3.13.2", "example/wiremock:3.13.2-2"), null);
    }

    @Test
    void userRowsOverrideBaselinePinsByMockIdIncludingOmittedAndNullVersions() {
        KubernetesFixture fixture = fixture(
                configMap("catalog", "26", Map.of(
                        "defaultVersion", "3.13.2",
                        "selectable.3.13.2", "example/wiremock:3.13.2-2",
                        "retained.3.12.1", "example/wiremock:3.12.1-2",
                        "retained.3.11.0", "example/wiremock:3.11.0-4")),
                configYaml("baseline", """
                        wiremock:
                          mocks:
                            - id: demo
                              version: 3.12.1
                            - id: other
                              version: 3.11.0
                        """),
                configYaml("user", """
                        wiremock:
                          mocks:
                            - id: demo
                            - id: other
                              version: null
                        """));
        CatalogSelection.Selection selection = CatalogSelection.select(
                "example/wiremock", List.of("3.14.1"), 1);

        new CatalogReconciler(fixture.client, yaml()).reconcile(
                "test", "catalog", "baseline", "user", "wiremock.yaml", "example/wiremock",
                selection, "3.x");

        ArgumentCaptor<ConfigMap> updated = ArgumentCaptor.forClass(ConfigMap.class);
        verify(fixture.namespaced).resource(updated.capture());
        assertEquals(Map.of(
                "defaultVersion", "3.14.1",
                "selectable.3.14.1", "example/wiremock:3.14.1",
                "retained.3.13.2", "example/wiremock:3.13.2-2"), updated.getValue().getData());
    }

    @Test
    void newlyUnselectedVersionGetsOneUnreferencedRetainedCycle() {
        KubernetesFixture fixture = fixture(
                configMap("catalog", "27", Map.of(
                        "defaultVersion", "3.13.2",
                        "selectable.3.13.2", "example/wiremock:3.13.2-2",
                        "selectable.3.12.1", "example/wiremock:3.12.1-7",
                        "retained.3.11.0", "example/wiremock:3.11.0-3")),
                configDocument("baseline", null, null), configDocument("user", null, null));
        CatalogSelection.Selection selection = CatalogSelection.select(
                "example/wiremock", List.of("3.14.1"), 1);

        new CatalogReconciler(fixture.client, yaml()).reconcile(
                "test", "catalog", "baseline", "user", "wiremock.yaml", "example/wiremock",
                selection, "3.x");

        ArgumentCaptor<ConfigMap> updated = ArgumentCaptor.forClass(ConfigMap.class);
        verify(fixture.namespaced).resource(updated.capture());
        assertEquals(Map.of(
                "defaultVersion", "3.14.1",
                "selectable.3.14.1", "example/wiremock:3.14.1",
                "retained.3.13.2", "example/wiremock:3.13.2-2",
                "retained.3.12.1", "example/wiremock:3.12.1-7"), updated.getValue().getData());
    }

    @Test
    void apiSaveAfterConfigReadCanReferenceGraceVersionOnNextCycle() {
        CatalogSelection.Selection selection = CatalogSelection.select(
                "example/wiremock", List.of("3.14.1"), 1);
        KubernetesFixture firstCycle = fixture(
                configMap("catalog", "28", Map.of(
                        "defaultVersion", "3.13.2",
                        "selectable.3.13.2", "example/wiremock:3.13.2-2",
                        "selectable.3.12.1", "example/wiremock:3.12.1-7")),
                configDocument("baseline", null, null), configDocument("user", null, null));
        new CatalogReconciler(firstCycle.client, yaml()).reconcile(
                "test", "catalog", "baseline", "user", "wiremock.yaml", "example/wiremock",
                selection, "3.x");
        ArgumentCaptor<ConfigMap> firstUpdate = ArgumentCaptor.forClass(ConfigMap.class);
        verify(firstCycle.namespaced).resource(firstUpdate.capture());

        KubernetesFixture nextCycle = fixture(
                configMap("catalog", "29", firstUpdate.getValue().getData()),
                configDocument("baseline", null, null), configDocument("user", "saved-after-read", "3.12.1"));
        new CatalogReconciler(nextCycle.client, yaml()).reconcile(
                "test", "catalog", "baseline", "user", "wiremock.yaml", "example/wiremock",
                selection, "3.x");

        ArgumentCaptor<ConfigMap> secondUpdate = ArgumentCaptor.forClass(ConfigMap.class);
        verify(nextCycle.namespaced).resource(secondUpdate.capture());
        assertEquals("example/wiremock:3.12.1-7",
                secondUpdate.getValue().getData().get("retained.3.12.1"));
    }

    private static void assertInvalidCatalog(Map<String, String> catalogData, String referencedVersion) {
        KubernetesFixture fixture = fixture(configMap("catalog", "25", catalogData),
                configDocument("baseline", referencedVersion == null ? null : "demo", referencedVersion),
                configDocument("user", null, null));
        CatalogSelection.Selection selection = CatalogSelection.select("example/wiremock", List.of("3.14.1"), 1);

        assertThrows(IllegalStateException.class, () -> new CatalogReconciler(fixture.client, yaml()).reconcile(
                "test", "catalog", "baseline", "user", "wiremock.yaml", "example/wiremock",
                selection, "3.x"));

        verify(fixture.namespaced, never()).resource(any());
    }

    private static ObjectMapper yaml() {
        return new ObjectMapper(new YAMLFactory());
    }

    private static ConfigMap configMap(String name, String resourceVersion, Map<String, String> data) {
        return new ConfigMapBuilder().withNewMetadata().withName(name).withResourceVersion(resourceVersion)
                .endMetadata().withData(data).build();
    }

    private static ConfigMap configDocument(String name, String mockId, String version) {
        String mock = mockId == null ? "[]" : "\n    - id: " + mockId + "\n      version: " + version;
        return configYaml(name, "wiremock:\n  mocks: " + mock + "\n");
    }

    private static ConfigMap configYaml(String name, String yaml) {
        return new ConfigMapBuilder().withNewMetadata().withName(name).endMetadata()
                .withData(Map.of("wiremock.yaml", yaml)).build();
    }

    @SuppressWarnings("unchecked")
    private static KubernetesFixture fixture(ConfigMap catalogValue, ConfigMap baselineValue, ConfigMap userValue) {
        KubernetesClient client = mock(KubernetesClient.class);
        MixedOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> configMaps = mock(MixedOperation.class);
        NonNamespaceOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> namespaced = mock(NonNamespaceOperation.class);
        Resource<ConfigMap> catalog = mock(Resource.class);
        Resource<ConfigMap> baseline = mock(Resource.class);
        Resource<ConfigMap> user = mock(Resource.class);
        Resource<ConfigMap> updated = mock(Resource.class);
        when(client.configMaps()).thenReturn(configMaps);
        when(configMaps.inNamespace("test")).thenReturn(namespaced);
        when(namespaced.withName("catalog")).thenReturn(catalog);
        when(namespaced.withName("baseline")).thenReturn(baseline);
        when(namespaced.withName("user")).thenReturn(user);
        when(catalog.get()).thenReturn(catalogValue);
        when(baseline.get()).thenReturn(baselineValue);
        when(user.get()).thenReturn(userValue);
        when(namespaced.resource(any(ConfigMap.class))).thenReturn(updated);
        return new KubernetesFixture(client, namespaced, catalog, baseline, user, updated);
    }

    private record KubernetesFixture(KubernetesClient client,
                                     NonNamespaceOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> namespaced,
                                     Resource<ConfigMap> catalog, Resource<ConfigMap> baseline,
                                     Resource<ConfigMap> user, Resource<ConfigMap> updated) {
    }
}
