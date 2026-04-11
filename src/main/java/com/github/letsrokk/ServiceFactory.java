package com.github.letsrokk;

import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ServiceFactory {

    static final String SERVICE_NAME_PREFIX = "mock-fleet-";

    public Service createServiceSpec(String mockId) {
        return new ServiceBuilder()
                .withNewMetadata()
                    .withName(serviceName(mockId))
                    .addToLabels(PodFactory.LABEL_APP_NAME, PodFactory.APP_NAME_VALUE)
                    .addToLabels(PodFactory.LABEL_MANAGED_BY, PodFactory.MANAGED_BY_VALUE)
                    .addToLabels(PodFactory.LABEL_MOCK_ID, mockId)
                .endMetadata()
                .withNewSpec()
                    .addToSelector(PodFactory.LABEL_MOCK_ID, mockId)
                    .addToSelector(PodFactory.LABEL_MANAGED_BY, PodFactory.MANAGED_BY_VALUE)
                    .addNewPort()
                        .withPort(8080)
                        .withTargetPort(new IntOrString(8080))
                    .endPort()
                .endSpec()
                .build();
    }

    public String serviceName(String mockId) {
        return SERVICE_NAME_PREFIX + mockId;
    }

}
