package com.github.letsrokk;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WireMockConfigServiceOptionDefinitionsTest {

    @Test
    void exposesOperationalDocumentedCliOptions() throws Exception {
        Map<String, WireMockConfigService.OptionDefinition> options = optionDefinitions().stream()
                .collect(Collectors.toMap(WireMockConfigService.OptionDefinition::name, option -> option));

        assertOption(options, "--proxy-all", "Proxying", "input");
        assertOption(options, "--enable-browser-proxying", "Browser Proxy and Certificates", "flag");
        assertOption(options, "--max-request-journal-entries", "Request Journal and Recording", "number");
        assertOption(options, "--disable-response-templating", "Templating", "flag");
        assertOption(options, "--websocket-max-text-message-size", "Webhooks and WebSockets", "number");
        assertEquals(List.of("always", "never", "body_file"), options.get("--use-chunked-encoding").values());
    }

    @Test
    void omitsCliOptionsThatBreakMockFleetPodContractOrExitImmediately() throws Exception {
        List<String> optionNames = optionDefinitions().stream()
                .map(WireMockConfigService.OptionDefinition::name)
                .toList();

        assertFalse(optionNames.contains("--port"));
        assertFalse(optionNames.contains("--https-port"));
        assertFalse(optionNames.contains("--disable-http"));
        assertFalse(optionNames.contains("--bind-address"));
        assertFalse(optionNames.contains("--root-dir"));
        assertFalse(optionNames.contains("--help"));
        assertFalse(optionNames.contains("--version"));
    }

    @SuppressWarnings("unchecked")
    private List<WireMockConfigService.OptionDefinition> optionDefinitions() throws Exception {
        Method method = WireMockConfigService.class.getDeclaredMethod("optionDefinitions");
        method.setAccessible(true);
        return (List<WireMockConfigService.OptionDefinition>) method.invoke(new WireMockConfigService());
    }

    private void assertOption(Map<String, WireMockConfigService.OptionDefinition> options,
                              String name, String group, String kind) {
        assertTrue(options.containsKey(name), "Expected option " + name);
        assertEquals(group, options.get(name).group());
        assertEquals(kind, options.get(name).kind());
    }
}
