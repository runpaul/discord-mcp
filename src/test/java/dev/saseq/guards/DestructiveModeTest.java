package dev.saseq.guards;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DestructiveModeTest {

    @Test
    void parse_recognizesLowercaseValues() {
        assertEquals(DestructiveMode.ALLOW, DestructiveMode.parse("allow"));
        assertEquals(DestructiveMode.DRY_RUN, DestructiveMode.parse("dry_run"));
        assertEquals(DestructiveMode.DENY, DestructiveMode.parse("deny"));
        assertEquals(DestructiveMode.APPROVAL, DestructiveMode.parse("approval"));
    }

    @Test
    void parse_blankOrMissing_defaultsToDryRun() {
        assertEquals(DestructiveMode.DRY_RUN, DestructiveMode.parse(""));
        assertEquals(DestructiveMode.DRY_RUN, DestructiveMode.parse(null));
    }

    @Test
    void parse_unknownValue_failsStartup() {
        assertThrows(IllegalStateException.class, () -> DestructiveMode.parse("nuke"));
    }
}
