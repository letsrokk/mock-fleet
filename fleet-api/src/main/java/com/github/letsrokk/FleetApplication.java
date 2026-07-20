package com.github.letsrokk;

import jakarta.ws.rs.core.Application;
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.info.Info;

@OpenAPIDefinition(info = @Info(title = "Mock Fleet API", version = "1.5.1"))
public class FleetApplication extends Application {
}
