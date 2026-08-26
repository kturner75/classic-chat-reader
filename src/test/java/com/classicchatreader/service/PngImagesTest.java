package com.classicchatreader.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PngImagesTest {

    @Test
    void acceptsPngMagicBytesOnly() {
        assertTrue(PngImages.isPng(new byte[] {
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        }));
        assertFalse(PngImages.isPng(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}));
        assertFalse(PngImages.isPng(new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'}));
        assertFalse(PngImages.isPng(new byte[] {0x00}));
        assertFalse(PngImages.isPng(null));
        assertThrows(UnsupportedImageTypeException.class,
                () -> PngImages.requirePng(new byte[] {0x00}, "must be png"));
    }
}
