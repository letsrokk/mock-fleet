package com.github.letsrokk.mockops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

final class DockerConfigCredentials {
    static RegistryV2Client.Credentials read(ObjectMapper json, URI registry, List<String> files) {
        for (String file : files) {
            JsonNode auths;
            try (var input = Files.newInputStream(Path.of(file))) {
                byte[] bytes = input.readNBytes(1_048_577);
                if (bytes.length > 1_048_576) throw invalidConfig();
                JsonNode root = json.readTree(bytes);
                auths = root == null ? null : root.get("auths");
            } catch (IOException exception) {
                throw invalidConfig();
            }
            if (auths == null || !auths.isObject()) throw invalidConfig();
            for (var entry : auths.properties()) {
                if (!authority(entry.getKey()).equals(authority(registry.getRawAuthority()))) continue;
                JsonNode value = entry.getValue();
                if (!value.isObject()) throw invalidConfig();
                JsonNode username = value.get("username");
                JsonNode password = value.get("password");
                if (username != null || password != null) {
                    if (username == null || !username.isTextual() || password == null || !password.isTextual()) {
                        throw invalidConfig();
                    }
                    return new RegistryV2Client.Credentials(username.textValue(), password.textValue());
                }
                JsonNode auth = value.get("auth");
                if (auth == null || !auth.isTextual()) throw invalidConfig();
                String decoded;
                try {
                    decoded = new String(Base64.getDecoder().decode(auth.textValue()), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException exception) {
                    throw invalidConfig();
                }
                int colon = decoded.indexOf(':');
                if (colon < 1) throw invalidConfig();
                return new RegistryV2Client.Credentials(decoded.substring(0, colon), decoded.substring(colon + 1));
            }
        }
        return null;
    }

    private static String authority(String key) {
        String value = key.toLowerCase(Locale.ROOT).replaceFirst("^https?://", "").split("/", 2)[0];
        return switch (value) {
            case "docker.io", "index.docker.io", "registry-1.docker.io" -> "docker.io";
            default -> value;
        };
    }

    private static IllegalArgumentException invalidConfig() {
        return new IllegalArgumentException("Cannot read valid registry credentials from image pull secret.");
    }
}
