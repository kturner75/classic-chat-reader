package com.classicchatreader.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class StylePreviewServiceTest {
    @Test
    void generatesEachFamilyWithUniqueDisposableKeysAndCleansUp() throws Exception {
        var covers = mock(BookCoverImageGeneratorService.class);
        var portraits = mock(CharacterPortraitImageGeneratorService.class);
        var illustrations = mock(IllustrationImageGeneratorService.class);
        var cache = mock(ComfyUIService.class);
        var service = new StylePreviewService(covers, portraits, illustrations, cache);
        byte[] bytes = {1, 2, 3};
        when(covers.generateBookCover(anyString(), anyString(), anyString())).thenReturn("cover.png");
        when(portraits.generatePortrait(anyString(), anyString(), anyString())).thenReturn("portrait.png");
        when(illustrations.generateIllustration(anyString(), anyString(), anyString())).thenReturn("scene.png");
        when(cache.getBookCoverImage("cover.png")).thenReturn(bytes);
        when(cache.getPortraitImage("portrait.png")).thenReturn(bytes);
        when(cache.getImage("scene.png")).thenReturn(bytes);
        for (String family : new String[]{"cover", "portrait", "illustration"}) {
            assertArrayEquals(bytes, service.generate(family, "Watercolor scene"));
            verify(cache).deleteStylePreview(eq(family), matches("style-preview-[0-9a-f-]{36}"));
        }
    }

    @Test
    void rejectsBadInputAndCleansUpProviderFailures() throws Exception {
        var covers = mock(BookCoverImageGeneratorService.class);
        var cache = mock(ComfyUIService.class);
        var service = new StylePreviewService(covers, mock(CharacterPortraitImageGeneratorService.class), mock(IllustrationImageGeneratorService.class), cache);
        assertThrows(IllegalArgumentException.class, () -> service.generate("other", "Ink"));
        assertThrows(IllegalArgumentException.class, () -> service.generate("cover", " "));
        assertThrows(IllegalArgumentException.class, () -> service.generate("cover", "x".repeat(12001)));
        verifyNoInteractions(covers, cache);
        when(covers.generateBookCover(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("Provider failed"));
        assertThrows(IllegalStateException.class, () -> service.generate("cover", "Ink"));
        verify(cache).deleteStylePreview(eq("cover"), startsWith("style-preview-"));
    }

    @Test
    void cleanupFailureDoesNotDiscardSuccessfulPreview() throws Exception {
        var covers = mock(BookCoverImageGeneratorService.class);
        var cache = mock(ComfyUIService.class);
        var service = new StylePreviewService(covers, mock(CharacterPortraitImageGeneratorService.class), mock(IllustrationImageGeneratorService.class), cache);
        byte[] bytes = {4, 5};
        when(covers.generateBookCover(anyString(), anyString(), anyString())).thenReturn("cover.png");
        when(cache.getBookCoverImage("cover.png")).thenReturn(bytes);
        doThrow(new java.io.IOException("Disk busy")).when(cache).deleteStylePreview(eq("cover"), anyString());
        assertArrayEquals(bytes, service.generate("cover", "Ink"));
        verify(cache).deleteStylePreview(eq("cover"), startsWith("style-preview-"));
    }

    @Test
    void deletesOnlyDisposableCacheFiles(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var cache = new ComfyUIService();
        org.springframework.test.util.ReflectionTestUtils.setField(cache, "bookCoverCacheDir", directory.toString());
        String key = "style-preview-" + java.util.UUID.randomUUID();
        cache.saveBookCoverImage(key, new byte[]{1});
        cache.saveBookCoverImage("live-cover", new byte[]{2});
        cache.deleteStylePreview("cover", key);
        assertFalse(java.nio.file.Files.exists(directory.resolve(key + ".png")));
        assertTrue(java.nio.file.Files.exists(directory.resolve("live-cover.png")));
        assertThrows(IllegalArgumentException.class, () -> cache.deleteStylePreview("cover", "live-cover"));
    }
}
