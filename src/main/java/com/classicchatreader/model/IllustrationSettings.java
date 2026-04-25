package com.classicchatreader.model;

public record IllustrationSettings(
    String style,
    String promptPrefix,
    String setting,
    String reasoning
) {
    public static IllustrationSettings defaults() {
        return new IllustrationSettings(
            "vintage book illustration",
            "vintage book illustration style, detailed pen and ink with subtle watercolor tints,",
            null,
            "Default classic illustration style"
        );
    }
}
