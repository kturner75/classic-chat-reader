package com.classicchatreader.service;

import com.classicchatreader.service.CuratedCatalogService.CuratedCatalogBook;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CuratedCatalogSnapshotTest {

    private static final CuratedCatalogBook ROMEO =
            new CuratedCatalogBook(1513, "Romeo and Juliet", "William Shakespeare", 36_000, List.of(), List.of());

    @Test
    void aWriteThatLandsDuringAReloadIsVisibleOnTheNextRead() {
        CuratedBookStore store = mock(CuratedBookStore.class);
        AtomicReference<CuratedCatalogService> service = new AtomicReference<>();
        when(store.updateStatus(1513, "inactive"))
                .thenReturn(Optional.of(new CuratedBookStore.Entry(ROMEO, "inactive")));
        // The first load reads the old rows, then the unlist commits before the load publishes.
        when(store.findActive())
                .thenAnswer(invocation -> {
                    service.get().setStatus(1513, "inactive");
                    return List.of(ROMEO);
                })
                .thenReturn(List.of());
        service.set(new CuratedCatalogService(store));

        assertEquals(List.of(ROMEO), service.get().getPopularBooks(), "the in-flight read may see the old rows");
        assertTrue(service.get().getPopularBooks().isEmpty(), "the next read must not serve the pre-write snapshot");
    }
}
