package com.classicchatreader.controller;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.ParagraphEntity;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.ParagraphRepository;
import com.classicchatreader.service.AssetKeyService;
import com.classicchatreader.service.CdnAssetService;
import com.classicchatreader.service.PublicSessionAuthService;
import com.classicchatreader.service.TtsService;
import com.classicchatreader.service.VoiceAnalysisService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        value = TtsController.class,
        properties = {
                "deployment.mode=public",
                "security.public.api-key=test-api-key"
        })
class TtsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TtsService ttsService;

    @MockitoBean
    private VoiceAnalysisService voiceAnalysisService;

    @MockitoBean
    private BookRepository bookRepository;

    @MockitoBean
    private ChapterRepository chapterRepository;

    @MockitoBean
    private ParagraphRepository paragraphRepository;

    @MockitoBean
    private AssetKeyService assetKeyService;

    @MockitoBean
    private CdnAssetService cdnAssetService;

    @MockitoBean
    private PublicSessionAuthService sessionAuthService;

    @Test
    void getStatus_cacheOnlyWithCdn_setsCachedAvailable() throws Exception {
        when(ttsService.isCacheOnly()).thenReturn(true);
        when(ttsService.isConfigured()).thenReturn(false);
        when(voiceAnalysisService.isOllamaAvailable()).thenReturn(true);
        when(cdnAssetService.isEnabled()).thenReturn(true);

        mockMvc.perform(get("/api/tts/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cacheOnly", is(true)))
                .andExpect(jsonPath("$.openaiConfigured", is(false)))
                .andExpect(jsonPath("$.cachedAvailable", is(true)))
                .andExpect(jsonPath("$.ollamaAvailable", is(true)))
                .andExpect(jsonPath("$.voices[0].id", is("ash")));
    }

    @Test
    void analyzeBook_existingSettingsWithoutForce_returnsSavedSettings() throws Exception {
        BookEntity book = new BookEntity("Book One", "Author One", "gutenberg");
        book.setId("book-1");
        book.setTtsEnabled(true);
        book.setTtsVoice("fable");
        book.setTtsSpeed(1.15);
        book.setTtsInstructions("Speak warmly");
        book.setTtsReasoning("Matches tone");

        when(bookRepository.findById("book-1")).thenReturn(Optional.of(book));
        when(ttsService.isCacheOnly()).thenReturn(false);

        mockMvc.perform(post("/api/tts/analyze/book-1")
                        .header("X-API-Key", "test-api-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voice", is("fable")))
                .andExpect(jsonPath("$.speed", is(1.15)))
                .andExpect(jsonPath("$.instructions", is("Speak warmly")))
                .andExpect(jsonPath("$.reasoning", is("Matches tone")));

        verify(bookRepository, never()).save(org.mockito.ArgumentMatchers.any(BookEntity.class));
        verify(voiceAnalysisService, never()).analyzeBookForVoice(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void speak_whenNotConfigured_returnsServiceUnavailable() throws Exception {
        when(ttsService.isConfigured()).thenReturn(false);

        mockMvc.perform(post("/api/tts/speak")
                        .header("X-API-Key", "test-api-key")
                        .contentType("application/json")
                        .content("""
                                {
                                  "text": "Hello world",
                                  "voice": "fable",
                                  "speed": 1.0,
                                  "instructions": ""
                                }
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string("OpenAI API key not configured"));
    }

    @Test
    void speakParagraph_cacheOnlyWithCdn_redirectsToCachedAsset() throws Exception {
        BookEntity book = new BookEntity("Book One", "Author One", "gutenberg");
        book.setId("book-1");
        book.setTtsEnabled(true);

        ChapterEntity chapter = new ChapterEntity(2, "Chapter Three");
        chapter.setId("chapter-1");
        chapter.setBook(book);

        ParagraphEntity paragraph = new ParagraphEntity();
        paragraph.setContent("<p>Hello from paragraph.</p>");

        when(bookRepository.findById("book-1")).thenReturn(Optional.of(book));
        when(chapterRepository.findById("chapter-1")).thenReturn(Optional.of(chapter));
        when(paragraphRepository.findByChapterIdOrderByParagraphIndex("chapter-1")).thenReturn(List.of(paragraph));
        when(assetKeyService.buildBookKey(book)).thenReturn("book-one");
        when(ttsService.isCacheOnly()).thenReturn(true);
        when(cdnAssetService.isEnabled()).thenReturn(true);
        when(ttsService.resolveVoice("fable")).thenReturn("fable");
        when(assetKeyService.buildAudioKey(book, "fable", 2, 0)).thenReturn("audio-key");
        when(cdnAssetService.buildAssetUrl("audio", "audio-key"))
                .thenReturn(Optional.of("https://cdn.example.com/audio-key.mp3"));

        mockMvc.perform(get("/api/tts/speak/book-1/chapter-1/0").param("voice", "fable"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://cdn.example.com/audio-key.mp3"));

        verify(ttsService, never()).generateSpeechForParagraph(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void speakParagraph_publicMode_cacheHitWithoutAuth_returnsCachedAudio() throws Exception {
        BookEntity book = createTtsEnabledBook();
        ChapterEntity chapter = createChapter(book);
        ParagraphEntity paragraph = createParagraph("<p>Hello from paragraph.</p>");
        byte[] cachedAudio = "cached-audio".getBytes();

        stubSpeakParagraphLookup(book, chapter, paragraph);
        when(ttsService.resolveVoice("fable")).thenReturn("fable");
        when(ttsService.isCacheOnly()).thenReturn(false);
        when(ttsService.getCachedSpeechForParagraph("book-one", 2, 0, "fable")).thenReturn(cachedAudio);

        mockMvc.perform(get("/api/tts/speak/book-1/chapter-1/0").param("voice", "fable"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "audio/mpeg"))
                .andExpect(content().bytes(cachedAudio));

        verify(ttsService, never()).generateSpeechForParagraph(anyString(), anyInt(), anyInt(), anyString(), any());
    }

    @Test
    void speakParagraph_publicMode_cacheMissWithoutAuth_returnsUnauthorized() throws Exception {
        BookEntity book = createTtsEnabledBook();
        ChapterEntity chapter = createChapter(book);
        ParagraphEntity paragraph = createParagraph("<p>Hello from paragraph.</p>");

        stubSpeakParagraphLookup(book, chapter, paragraph);
        when(ttsService.resolveVoice("fable")).thenReturn("fable");
        when(ttsService.isCacheOnly()).thenReturn(false);
        when(ttsService.getCachedSpeechForParagraph("book-one", 2, 0, "fable")).thenReturn(null);
        when(sessionAuthService.isAuthenticated(any())).thenReturn(false);

        mockMvc.perform(get("/api/tts/speak/book-1/chapter-1/0").param("voice", "fable"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("Authentication required for uncached TTS generation"));

        verify(ttsService, never()).generateSpeechForParagraph(anyString(), anyInt(), anyInt(), anyString(), any());
    }

    @Test
    void speakParagraph_publicMode_cacheMissWithApiKey_generatesAudio() throws Exception {
        BookEntity book = createTtsEnabledBook();
        ChapterEntity chapter = createChapter(book);
        ParagraphEntity paragraph = createParagraph("<p>Hello from paragraph.</p>");
        byte[] generatedAudio = "generated-audio".getBytes();

        stubSpeakParagraphLookup(book, chapter, paragraph);
        when(ttsService.resolveVoice("fable")).thenReturn("fable");
        when(ttsService.isCacheOnly()).thenReturn(false);
        when(ttsService.getCachedSpeechForParagraph("book-one", 2, 0, "fable")).thenReturn(null);
        when(ttsService.isConfigured()).thenReturn(true);
        when(ttsService.generateSpeechForParagraph(anyString(), anyInt(), anyInt(), anyString(), any()))
                .thenReturn(generatedAudio);

        mockMvc.perform(get("/api/tts/speak/book-1/chapter-1/0")
                        .param("voice", "fable")
                        .header("X-API-Key", "test-api-key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "audio/mpeg"))
                .andExpect(content().bytes(generatedAudio));
    }

    private BookEntity createTtsEnabledBook() {
        BookEntity book = new BookEntity("Book One", "Author One", "gutenberg");
        book.setId("book-1");
        book.setTtsEnabled(true);
        return book;
    }

    private ChapterEntity createChapter(BookEntity book) {
        ChapterEntity chapter = new ChapterEntity(2, "Chapter Three");
        chapter.setId("chapter-1");
        chapter.setBook(book);
        return chapter;
    }

    private ParagraphEntity createParagraph(String content) {
        ParagraphEntity paragraph = new ParagraphEntity();
        paragraph.setContent(content);
        return paragraph;
    }

    private void stubSpeakParagraphLookup(BookEntity book, ChapterEntity chapter, ParagraphEntity paragraph) {
        when(bookRepository.findById("book-1")).thenReturn(Optional.of(book));
        when(chapterRepository.findById("chapter-1")).thenReturn(Optional.of(chapter));
        when(paragraphRepository.findByChapterIdOrderByParagraphIndex("chapter-1")).thenReturn(List.of(paragraph));
        when(assetKeyService.buildBookKey(book)).thenReturn("book-one");
    }
}
