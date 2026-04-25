package com.classicchatreader.model;

import java.time.LocalDateTime;

public record GenerationJobStatusResponse(
        String scope,
        String bookId,
        LocalDateTime asOf,
        GenerationPipelineStatus illustrations,
        GenerationPipelineStatus portraits,
        GenerationPipelineStatus analyses,
        GenerationPipelineStatus recaps,
        GenerationPipelineStatus totals
) {
}
