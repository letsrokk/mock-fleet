package com.github.letsrokk;

import io.fabric8.kubernetes.api.model.Service;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServiceFactoryTest {

    @Test
    void createsStablePerMockServiceSpec() {
        ServiceFactory serviceFactory = new ServiceFactory();

        Service service = serviceFactory.createServiceSpec("demo");

        assertEquals("mock-fleet-demo", service.getMetadata().getName());
        assertEquals(PodFactory.APP_NAME_VALUE, service.getMetadata().getLabels().get(PodFactory.LABEL_APP_NAME));
        assertEquals(PodFactory.MANAGED_BY_VALUE, service.getMetadata().getLabels().get(PodFactory.LABEL_MANAGED_BY));
        assertEquals("demo", service.getMetadata().getLabels().get(PodFactory.LABEL_MOCK_ID));
        assertEquals("demo", service.getSpec().getSelector().get(PodFactory.LABEL_MOCK_ID));
        assertEquals(PodFactory.MANAGED_BY_VALUE, service.getSpec().getSelector().get(PodFactory.LABEL_MANAGED_BY));
        assertEquals(8080, service.getSpec().getPorts().getFirst().getPort());
        assertEquals(8080, service.getSpec().getPorts().getFirst().getTargetPort().getIntVal());
    }
}
