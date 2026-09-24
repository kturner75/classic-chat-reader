package com.classicchatreader.cli;

import com.classicchatreader.service.CuratedCatalogService;
import com.classicchatreader.service.PreGenerationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PreGenerationBatchRunnerTest {

    @Test
    void completesWithNoWorkWhenEveryCuratedTitleIsUnlisted() throws Exception {
        PreGenerationService preGeneration = mock(PreGenerationService.class);
        CuratedCatalogService catalog = mock(CuratedCatalogService.class);
        when(catalog.getPopularBooks()).thenReturn(List.of());
        PreGenerationBatchRunner runner = new PreGenerationBatchRunner(preGeneration, catalog);
        ReflectionTestUtils.setField(runner, "batchLimit", 20);
        ReflectionTestUtils.setField(runner, "batchMode", "full");

        runner.run();

        verifyNoInteractions(preGeneration);
    }
}
