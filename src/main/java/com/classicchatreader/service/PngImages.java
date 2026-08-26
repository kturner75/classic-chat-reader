package com.classicchatreader.service;

/**
 * PNG magic-byte check for live asset uploads. Studio history is PNG only.
 */
public final class PngImages {

    private static final byte[] PNG_SIGNATURE = new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    private PngImages() {
    }

    public static boolean isPng(byte[] imageData) {
        if (imageData == null || imageData.length < PNG_SIGNATURE.length) {
            return false;
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (imageData[i] != PNG_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    public static void requirePng(byte[] imageData, String message) {
        if (!isPng(imageData)) {
            throw new UnsupportedImageTypeException(message);
        }
    }
}
