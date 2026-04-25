package com.classicchatreader.model;

import java.util.List;

public record Book(
    String id,
    String title,
    String author,
    String description,
    String coverUrl,
    List<Chapter> chapters,
    boolean ttsEnabled,
    boolean illustrationEnabled,
    boolean characterEnabled
) {
    public record Chapter(String id, String title) {}
}
