package com.classicchatreader.model;

public record VoiceSettings(
    String voice,
    double speed,
    String instructions,
    String reasoning
) {
    public static VoiceSettings defaults() {
        return new VoiceSettings("fable", 1.0, null, "Default settings");
    }
}
