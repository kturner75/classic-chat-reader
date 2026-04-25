package com.classicchatreader.model;

public record ChatMessage(
    String role,
    String content,
    long timestamp
) {
    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, System.currentTimeMillis());
    }

    public static ChatMessage character(String content) {
        return new ChatMessage("character", content, System.currentTimeMillis());
    }
}
