package com.classicchatreader.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class IllustrationSettingsTest {

    @Test
    void clipTrimsAndCapsToMax() {
        assertNull(IllustrationSettings.clip(null, 8));
        assertEquals("oil", IllustrationSettings.clip("  oil  ", 8));
        assertEquals("painterl", IllustrationSettings.clip("painterly oil", 8));
        assertEquals("a".repeat(IllustrationSettings.PREFIX_MAX),
                IllustrationSettings.clip("a".repeat(IllustrationSettings.PREFIX_MAX + 50),
                        IllustrationSettings.PREFIX_MAX));
    }
}
