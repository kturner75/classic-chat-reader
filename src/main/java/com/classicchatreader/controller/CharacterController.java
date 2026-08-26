package com.classicchatreader.controller;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.CharacterEntity;
import com.classicchatreader.entity.CharacterStatus;
import com.classicchatreader.entity.CharacterType;
import com.classicchatreader.model.CharacterInfo;
import com.classicchatreader.model.ChatMessage;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.service.CharacterChatService;
import com.classicchatreader.service.AccountAuthService;
import com.classicchatreader.service.AccountChatHistoryService;
import com.classicchatreader.service.CharacterExtractionService;
import com.classicchatreader.service.CharacterPrefetchService;
import com.classicchatreader.service.CharacterService;
import com.classicchatreader.service.CharacterVoiceCallService;
import com.classicchatreader.service.ComfyUIService;
import com.classicchatreader.service.CdnAssetService;
import com.classicchatreader.service.LiveAssetUploads;
import com.classicchatreader.service.LiveAssetWriteResult;
import com.classicchatreader.service.UnsupportedImageTypeException;
import com.classicchatreader.service.llm.LlmProviderException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/characters")
public class CharacterController {

    private static final Logger log = LoggerFactory.getLogger(CharacterController.class);

    @Value("${character.enabled:true}")
    private boolean characterEnabled;

    @Value("${ai.reasoning.enabled:true}")
    private boolean reasoningEnabled;

    @Value("${ai.chat.enabled:false}")
    private boolean chatEnabled;

    @Value("${generation.cache-only:false}")
    private boolean cacheOnly;

    private final CharacterService characterService;
    private final CharacterChatService chatService;
    private final CharacterVoiceCallService voiceCallService;
    private final CharacterExtractionService extractionService;
    private final CharacterPrefetchService prefetchService;
    private final ComfyUIService comfyUIService;
    private final CdnAssetService cdnAssetService;
    private final BookRepository bookRepository;
    private final ChapterRepository chapterRepository;
    private final AccountAuthService accountAuthService;
    private final AccountChatHistoryService accountChatHistoryService;

    public CharacterController(
            CharacterService characterService,
            CharacterChatService chatService,
            CharacterVoiceCallService voiceCallService,
            CharacterExtractionService extractionService,
            CharacterPrefetchService prefetchService,
            ComfyUIService comfyUIService,
            CdnAssetService cdnAssetService,
            BookRepository bookRepository,
            ChapterRepository chapterRepository,
            AccountAuthService accountAuthService,
            AccountChatHistoryService accountChatHistoryService) {
        this.characterService = characterService;
        this.chatService = chatService;
        this.voiceCallService = voiceCallService;
        this.extractionService = extractionService;
        this.prefetchService = prefetchService;
        this.comfyUIService = comfyUIService;
        this.cdnAssetService = cdnAssetService;
        this.bookRepository = bookRepository;
        this.chapterRepository = chapterRepository;
        this.accountAuthService = accountAuthService;
        this.accountChatHistoryService = accountChatHistoryService;
    }

    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("enabled", characterEnabled);
        status.put("reasoningEnabled", reasoningEnabled);
        status.put("chatEnabled", chatEnabled);
        status.put("reasoningProviderAvailable", extractionService.isReasoningProviderAvailable());
        status.put("chatProviderAvailable", chatService.isChatProviderAvailable());
        status.put("voiceCallEnabled", voiceCallService.isVoiceCallEnabled());
        status.put("voiceCallAvailable", voiceCallService.isVoiceCallAvailable());
        // Legacy field for backwards compatibility
        status.put("ollamaAvailable", extractionService.isReasoningProviderAvailable());
        status.put("comfyuiAvailable", comfyUIService.isAvailable());
        status.put("available", characterEnabled && characterService.isAvailable());
        status.put("cacheOnly", cacheOnly);
        return status;
    }

    @GetMapping("/book/{bookId}")
    public List<CharacterInfo> getCharactersForBook(@PathVariable String bookId) {
        requireCharacterEnabledBook(bookId);
        return characterService.getCharactersForBook(bookId);
    }

    @GetMapping("/book/{bookId}/up-to")
    public List<CharacterInfo> getCharactersUpToPosition(
            @PathVariable String bookId,
            @RequestParam int chapterIndex,
            @RequestParam int paragraphIndex) {
        requireCharacterEnabledBook(bookId);
        return characterService.getCharactersUpToPosition(bookId, chapterIndex, paragraphIndex);
    }

    @GetMapping("/book/{bookId}/new-since")
    public List<CharacterInfo> getNewCharactersSince(
            @PathVariable String bookId,
            @RequestParam long sinceTimestamp) {
        requireCharacterEnabledBook(bookId);
        LocalDateTime sinceTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(sinceTimestamp),
                ZoneId.systemDefault()
        );
        return characterService.getNewlyCompletedSince(bookId, sinceTime);
    }

    @DeleteMapping("/book/{bookId}")
    public ResponseEntity<Map<String, Object>> deleteCharactersForBook(@PathVariable String bookId) {
        if (!characterEnabled) {
            return ResponseEntity.status(403).build();
        }
        requireCharacterEnabledBook(bookId);

        log.info("Deleting all characters for book: {}", bookId);
        int deletedCount = characterService.deleteCharactersForBook(bookId);

        Map<String, Object> response = new HashMap<>();
        response.put("bookId", bookId);
        response.put("deletedCount", deletedCount);
        response.put("message", "Characters deleted. Re-open the book to regenerate.");

        return ResponseEntity.ok(response);
    }

    @PostMapping("/reindex")
    public ResponseEntity<Map<String, Object>> reindexPrimaryCharacters() {
        if (!characterEnabled) {
            return ResponseEntity.status(403).build();
        }

        int updatedCount = prefetchService.refreshPrimaryCharacterPositionsForAll();
        Map<String, Object> response = new HashMap<>();
        response.put("updatedCount", updatedCount);
        response.put("message", "Primary character first appearances refreshed.");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/book/{bookId}/reindex")
    public ResponseEntity<Map<String, Object>> reindexPrimaryCharactersForBook(@PathVariable String bookId) {
        if (!characterEnabled) {
            return ResponseEntity.status(403).build();
        }
        requireCharacterEnabledBook(bookId);

        int updatedCount = prefetchService.refreshPrimaryCharacterPositionsForBook(bookId);
        Map<String, Object> response = new HashMap<>();
        response.put("bookId", bookId);
        response.put("updatedCount", updatedCount);
        response.put("message", "Primary character first appearances refreshed for book.");
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{characterId}")
    public ResponseEntity<CharacterInfo> getCharacter(@PathVariable String characterId) {
        Optional<CharacterEntity> characterOpt = characterService.getCharacter(characterId);
        if (characterOpt.isPresent() && !isCharacterEnabled(characterOpt.get().getBook())) {
            return ResponseEntity.status(403).build();
        }
        return characterOpt
                .map(c -> ResponseEntity.ok(characterService.toChatAwareInfo(c)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{characterId}/portrait")
    public ResponseEntity<byte[]> getPortrait(@PathVariable String characterId) {
        Optional<CharacterEntity> characterOpt = characterService.getCharacter(characterId);
        if (characterOpt.isPresent() && !isCharacterEnabled(characterOpt.get().getBook())) {
            return ResponseEntity.status(403).build();
        }

        if (cdnAssetService.isEnabled()) {
            return characterOpt
                    .filter(c -> c.getStatus() == CharacterStatus.COMPLETED)
                    .flatMap(c -> cdnAssetService.buildAssetUrl(
                            "character-portraits",
                            new CdnAssetService.VersionedAsset(c.getPortraitFilename(), c.getCompletedAt())))
                    .map(url -> ResponseEntity.status(302)
                            .header(HttpHeaders.LOCATION, url)
                            .body(new byte[0]))
                    .orElseGet(() -> ResponseEntity.notFound().build());
        }

        byte[] image = characterOpt.map(c -> characterService.getPortrait(c.getId())).orElse(null);
        if (image == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "image/png")
                .header(HttpHeaders.CACHE_CONTROL, "max-age=604800")
                .body(image);
    }

    @GetMapping("/{characterId}/portrait/status")
    public Map<String, Object> getPortraitStatus(@PathVariable String characterId) {
        Optional<CharacterEntity> characterOpt = characterService.getCharacter(characterId);
        if (characterOpt.isPresent() && !isCharacterEnabled(characterOpt.get().getBook())) {
            return Map.of(
                    "characterId", characterId,
                    "status", "DISABLED",
                    "ready", false
            );
        }
        CharacterStatus status = characterOpt
                .map(character -> characterService.getPortraitStatus(character.getId()))
                .orElse(null);

        Map<String, Object> response = new HashMap<>();
        response.put("characterId", characterId);
        response.put("status", status != null ? status.name() : "NOT_FOUND");
        response.put("ready", status == CharacterStatus.COMPLETED);
        response.put("generatedPrompt", characterOpt.map(CharacterEntity::getPortraitPrompt).orElse(null));

        return response;
    }

    @PutMapping(path = "/{characterId}/portrait", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadPortrait(
            @PathVariable String characterId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "generated_prompt", required = false) String generatedPrompt,
            @RequestParam(value = "prompt_override", required = false) String promptOverride) throws java.io.IOException {
        if (!characterEnabled) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (cacheOnly) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(getPortraitStatus(characterId));
        }
        Optional<CharacterEntity> characterOpt = characterService.getCharacter(characterId);
        if (characterOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (!isCharacterEnabled(characterOpt.get().getBook())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            LiveAssetWriteResult result = characterService.saveUploadedPortrait(
                    characterId,
                    file.getBytes(),
                    source,
                    generatedPrompt,
                    promptOverride);
            if (result == LiveAssetWriteResult.CACHE_ONLY
                    || result == LiveAssetWriteResult.GENERATION_IN_PROGRESS) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(getPortraitStatus(characterId));
            }
            if (result == LiveAssetWriteResult.NOT_FOUND) {
                return ResponseEntity.notFound().build();
            }
        } catch (UnsupportedImageTypeException e) {
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(Map.of(
                    "characterId", characterId,
                    "status", "INVALID",
                    "ready", false,
                    "errorMessage", e.getMessage()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "characterId", characterId,
                    "status", "INVALID",
                    "ready", false,
                    "errorMessage", e.getMessage()
            ));
        }
        Map<String, Object> response = new HashMap<>(getPortraitStatus(characterId));
        response.put("source", LiveAssetUploads.resolveSource(source));
        return ResponseEntity.ok(response);
    }

    @PostMapping("/chapter/{chapterId}/analyze")
    public ResponseEntity<Void> requestChapterAnalysis(@PathVariable String chapterId) {
        if (!characterEnabled) {
            return ResponseEntity.status(403).build();
        }
        if (cacheOnly) {
            return ResponseEntity.status(409).build();
        }
        requireCharacterEnabledChapter(chapterId);
        characterService.requestChapterAnalysis(chapterId);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/chapter/{chapterId}/prefetch-next")
    public ResponseEntity<Void> prefetchNextChapter(@PathVariable String chapterId) {
        if (!characterEnabled) {
            return ResponseEntity.status(403).build();
        }
        if (cacheOnly) {
            return ResponseEntity.status(409).build();
        }
        requireCharacterEnabledChapter(chapterId);
        characterService.prefetchNextChapter(chapterId);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/book/{bookId}/prefetch")
    public ResponseEntity<Void> prefetchBookCharacters(@PathVariable String bookId) {
        if (!characterEnabled) {
            return ResponseEntity.status(403).build();
        }
        if (cacheOnly) {
            return ResponseEntity.status(409).build();
        }
        requireCharacterEnabledBook(bookId);
        // Run asynchronously to not block book opening
        java.util.concurrent.CompletableFuture.runAsync(() ->
            prefetchService.prefetchCharactersForBook(bookId)
        );
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{characterId}/chat")
    public ResponseEntity<ChatResponse> chat(
            @PathVariable String characterId,
            @RequestBody ChatRequest request,
            HttpServletRequest servletRequest) {

        if (!characterEnabled) {
            return ResponseEntity.status(403).build();
        }
        if (!chatEnabled) {
            return ResponseEntity.status(403).body(new ChatResponse(
                    "Chat is disabled in this environment.",
                    characterId,
                    System.currentTimeMillis()
            ));
        }

        // Chat is PRIMARY only. Empty PRIMARY means nobody to call.
        Optional<CharacterEntity> characterOpt = characterService.getCharacter(characterId);
        if (characterOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        CharacterEntity character = characterOpt.get();
        if (!isCharacterEnabled(character.getBook())) {
            return ResponseEntity.status(403).build();
        }
        if (!characterService.isChatEligible(character)) {
            return ResponseEntity.ok(new ChatResponse(
                    "Chat is only available for main characters.",
                    characterId,
                    System.currentTimeMillis()
            ));
        }

        if (request.message() == null || request.message().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        String response = chatService.chat(
                characterId,
                request.message(),
                request.conversationHistory(),
                request.readerChapterIndex(),
                request.readerParagraphIndex()
        );

        String sessionId = accountAuthService.resolveAuthenticatedPrincipal(servletRequest)
                .map(principal -> accountChatHistoryService.recordExchange(
                        principal.userId(),
                        characterId,
                        request.message(),
                        response,
                        request.readerChapterIndex(),
                        request.readerParagraphIndex()))
                .orElse(null);

        return ResponseEntity.ok(new ChatResponse(
                response,
                characterId,
                System.currentTimeMillis(),
                sessionId
        ));
    }

    @PostMapping("/{characterId}/call-session")
    public ResponseEntity<?> createCallSession(
            @PathVariable String characterId,
            @RequestBody CallSessionRequest request) {

        if (!characterEnabled || !chatEnabled || !voiceCallService.isVoiceCallAvailable()) {
            return ResponseEntity.status(403)
                    .body(Map.of("error", "Voice calls are not available."));
        }

        Optional<CharacterEntity> characterOpt = characterService.getCharacter(characterId);
        if (characterOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        CharacterEntity character = characterOpt.get();
        if (!isCharacterEnabled(character.getBook())) {
            return ResponseEntity.status(403)
                    .body(Map.of("error", "Voice calls are not available."));
        }
        if (character.getCharacterType() != CharacterType.PRIMARY) {
            return ResponseEntity.status(403)
                    .body(Map.of("error", "Voice calls are only available for main characters."));
        }

        try {
            CharacterVoiceCallService.VoiceCallSession session = voiceCallService.createSession(
                    characterId,
                    request.conversationHistory(),
                    request.readerChapterIndex(),
                    request.readerParagraphIndex()
            );
            return ResponseEntity.ok(session);
        } catch (IllegalArgumentException e) {
            // Character was removed between the check above and the service load
            return ResponseEntity.notFound().build();
        } catch (LlmProviderException e) {
            log.error("Failed to create voice call session for character {}", characterId, e);
            return ResponseEntity.status(503)
                    .body(Map.of("error", "Voice calls are unavailable right now."));
        }
    }

    public record CallSessionRequest(
            List<ChatMessage> conversationHistory,
            int readerChapterIndex,
            int readerParagraphIndex
    ) {}

    public record ChatRequest(
            String message,
            List<ChatMessage> conversationHistory,
            int readerChapterIndex,
            int readerParagraphIndex
    ) {}

    public record ChatResponse(
            String response,
            String characterId,
            long timestamp,
            String sessionId
    ) {
        public ChatResponse(String response, String characterId, long timestamp) {
            this(response, characterId, timestamp, null);
        }
    }

    private void requireCharacterEnabledBook(String bookId) {
        if (!characterEnabled) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Character feature disabled");
        }
        BookEntity book = bookRepository.findById(bookId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Book not found"));
        if (!isCharacterEnabled(book)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Character mode disabled for book");
        }
    }

    private void requireCharacterEnabledChapter(String chapterId) {
        if (!characterEnabled) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Character feature disabled");
        }
        BookEntity book = chapterRepository.findById(chapterId)
                .map(chapter -> chapter.getBook())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chapter not found"));
        if (!isCharacterEnabled(book)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Character mode disabled for book");
        }
    }

    private boolean isCharacterEnabled(BookEntity book) {
        return characterEnabled && Boolean.TRUE.equals(book.getCharacterEnabled());
    }
}
