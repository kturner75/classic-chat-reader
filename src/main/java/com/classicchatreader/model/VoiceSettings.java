package com.classicchatreader.model;

public record VoiceSettings(
    String voice,
    double speed,
    String instructions,
    String reasoning,
    String provider
) {
    public VoiceSettings(String voice, double speed, String instructions, String reasoning) {
        this(voice, speed, instructions, reasoning, null);
    }

    public static VoiceSettings defaults() {
        return new VoiceSettings("orion", 1.0, null, "Default settings", "xai");
    }
}
