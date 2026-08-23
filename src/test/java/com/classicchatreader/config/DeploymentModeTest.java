package com.classicchatreader.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeploymentModeTest {

    @Test
    void isPublic_trimsSpacesAndCarriageReturns() {
        assertTrue(DeploymentMode.isPublic("public"));
        assertTrue(DeploymentMode.isPublic("PUBLIC"));
        assertTrue(DeploymentMode.isPublic(" public "));
        assertTrue(DeploymentMode.isPublic("public\r"));
        assertTrue(DeploymentMode.isPublic("public\r\n"));
    }

    @Test
    void isPublic_rejectsLocalAndBlank() {
        assertFalse(DeploymentMode.isPublic("local"));
        assertFalse(DeploymentMode.isPublic(" local "));
        assertFalse(DeploymentMode.isPublic(""));
        assertFalse(DeploymentMode.isPublic("   "));
        assertFalse(DeploymentMode.isPublic(null));
    }
}
