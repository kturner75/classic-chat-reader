package com.classicchatreader.model;

public record GenerationPipelineStatus(
        long pending,
        long retryScheduled,
        long generating,
        long completed,
        long failed
) {
    public static GenerationPipelineStatus of(
            long pending,
            long retryScheduled,
            long generating,
            long completed,
            long failed) {
        return new GenerationPipelineStatus(
                Math.max(0L, pending),
                Math.max(0L, retryScheduled),
                Math.max(0L, generating),
                Math.max(0L, completed),
                Math.max(0L, failed)
        );
    }

    public long total() {
        return pending + retryScheduled + generating + completed + failed;
    }
}
