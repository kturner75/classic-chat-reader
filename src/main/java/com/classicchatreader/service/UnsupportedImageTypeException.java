package com.classicchatreader.service;

/**
 * Thrown when an upload is not a PNG (magic-byte check). Controllers map this to 415.
 */
public class UnsupportedImageTypeException extends IllegalArgumentException {

    public UnsupportedImageTypeException(String message) {
        super(message);
    }
}
