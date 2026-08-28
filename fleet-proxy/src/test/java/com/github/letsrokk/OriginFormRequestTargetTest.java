package com.github.letsrokk;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OriginFormRequestTargetTest {

    @Test
    void acceptsOriginFormPathsAndQueriesWithoutChangingTheirRawForm() {
        assertEquals("/", OriginFormRequestTarget.parse("/").rawPathAndQuery());
        assertEquals("/path", OriginFormRequestTarget.parse("/path").rawPathAndQuery());
        assertEquals("/path?x=1", OriginFormRequestTarget.parse("/path?x=1").rawPathAndQuery());
        assertEquals("/%E2%9C%93?emoji=%F0%9F%9A%80",
                OriginFormRequestTarget.parse("/%E2%9C%93?emoji=%F0%9F%9A%80").rawPathAndQuery());
    }

    @Test
    void rejectsSchemeRelativeAndAbsoluteUriForms() {
        assertThrows(IllegalArgumentException.class, () -> OriginFormRequestTarget.parse("//host/path"));
        assertThrows(IllegalArgumentException.class,
                () -> OriginFormRequestTarget.parse("https://attacker.example/path"));
    }

    @Test
    void rejectsFragmentsAndBackslashes() {
        assertThrows(IllegalArgumentException.class, () -> OriginFormRequestTarget.parse("/path#fragment"));
        assertThrows(IllegalArgumentException.class, () -> OriginFormRequestTarget.parse("/\\attacker"));
        assertThrows(IllegalArgumentException.class, () -> OriginFormRequestTarget.parse("/%5cattacker"));
        assertThrows(IllegalArgumentException.class, () -> OriginFormRequestTarget.parse("/%5Cattacker"));
    }

    @Test
    void rejectsMalformedPercentEncodings() {
        assertThrows(IllegalArgumentException.class, () -> OriginFormRequestTarget.parse("/bad%"));
        assertThrows(IllegalArgumentException.class, () -> OriginFormRequestTarget.parse("/bad%2"));
        assertThrows(IllegalArgumentException.class, () -> OriginFormRequestTarget.parse("/bad%GG"));
    }

    @Test
    void rejectsUserInfoAndNonOriginRequestTargets() {
        assertThrows(IllegalArgumentException.class,
                () -> OriginFormRequestTarget.parse("https://user:password@attacker.example/path"));
        assertThrows(IllegalArgumentException.class, () -> OriginFormRequestTarget.parse("*"));
        assertThrows(IllegalArgumentException.class, () -> OriginFormRequestTarget.parse("host.example:443"));
        assertThrows(IllegalArgumentException.class, () -> OriginFormRequestTarget.parse("?x=1"));
        assertThrows(IllegalArgumentException.class, () -> OriginFormRequestTarget.parse(""));
    }

    @Test
    void appendsOnlyTheValidatedRawPathAndQueryToTheTrustedOrigin() {
        OriginFormRequestTarget target = OriginFormRequestTarget.parse("/%E2%9C%93/path?x=1");

        URI result = target.appendTo(URI.create("https://ignored:secret@127.0.0.1:8443/base?old=1#fragment"));

        assertEquals("https://127.0.0.1:8443/%E2%9C%93/path?x=1", result.toString());
    }
}
