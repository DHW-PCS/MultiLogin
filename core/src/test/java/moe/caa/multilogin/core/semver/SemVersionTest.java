package moe.caa.multilogin.core.semver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class SemVersionTest {

    @Test
    void acceptsDhwReleaseSuffix() {
        SemVersion version = SemVersion.of("0.6.14-dhw");

        assertNotNull(version);
        assertEquals(0, version.getMajor());
        assertEquals(6, version.getMinor());
        assertEquals(14, version.getPatch());
    }
}
