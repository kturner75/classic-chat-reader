package com.classicchatreader.controller;

import jakarta.servlet.http.HttpServletRequest;
import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.ParagraphEntity;
import com.classicchatreader.model.VoiceSettings;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.ParagraphRepository;
import com.classicchatreader.service.AssetKeyService;
import com.classicchatreader.service.CdnAssetService;
import com.classicchatreader.service.PublicSessionAuthService;
import com.classicchatreader.service.TtsService;
import com.classicchatreader.service.VoiceAnalysisService;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/tts")
public class TtsController {

  private static final Logger log = LoggerFactory.getLogger(TtsController.class);

  private final TtsService ttsService;
  private final VoiceAnalysisService voiceAnalysisService;
  private final BookRepository bookRepository;
  private final ChapterRepository chapterRepository;
  private final ParagraphRepository paragraphRepository;
  private final AssetKeyService assetKeyService;
  private final CdnAssetService cdnAssetService;
  private final PublicSessionAuthService sessionAuthService;
  private final String deploymentMode;
  private final String publicApiKey;

  public TtsController(TtsService ttsService, VoiceAnalysisService voiceAnalysisService,
                       BookRepository bookRepository, ChapterRepository chapterRepository,
                       ParagraphRepository paragraphRepository,
                       AssetKeyService assetKeyService,
                       CdnAssetService cdnAssetService,
                       PublicSessionAuthService sessionAuthService,
                       @Value("${deployment.mode:local}") String deploymentMode,
                       @Value("${security.public.api-key:}") String publicApiKey) {
    this.ttsService = ttsService;
    this.voiceAnalysisService = voiceAnalysisService;
    this.bookRepository = bookRepository;
    this.chapterRepository = chapterRepository;
    this.paragraphRepository = paragraphRepository;
    this.assetKeyService = assetKeyService;
    this.cdnAssetService = cdnAssetService;
    this.sessionAuthService = sessionAuthService;
    this.deploymentMode = deploymentMode == null ? "local" : deploymentMode;
    this.publicApiKey = publicApiKey == null ? "" : publicApiKey;
  }

  @GetMapping("/status")
  public Map<String, Object> getStatus() {
    Map<String, Object> status = new HashMap<>();
    boolean cacheOnly = ttsService.isCacheOnly();
    boolean cachedAvailable = cacheOnly && cdnAssetService.isEnabled();
    status.put("openaiConfigured", ttsService.isConfigured());
    status.put("cachedAvailable", cachedAvailable);
    status.put("ollamaAvailable", voiceAnalysisService.isOllamaAvailable());
    status.put("voices", TtsService.AVAILABLE_VOICES);
    status.put("cacheOnly", cacheOnly);
    return status;
  }

  @GetMapping("/voices")
  public List<Map<String, String>> getVoices() {
    return TtsService.AVAILABLE_VOICES;
  }

  @GetMapping("/settings/{bookId}")
  public ResponseEntity<VoiceSettings> getVoiceSettings(@PathVariable String bookId) {
    Optional<BookEntity> bookOpt = bookRepository.findById(bookId);
    if (bookOpt.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    BookEntity book = bookOpt.get();
    if (!isTtsEnabled(book)) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    // Return saved settings if they exist
    if (book.getTtsVoice() != null) {
      VoiceSettings settings = new VoiceSettings(
          book.getTtsVoice(),
          book.getTtsSpeed() != null ? book.getTtsSpeed() : 1.0,
          book.getTtsInstructions(),
          book.getTtsReasoning()
      );
      return ResponseEntity.ok(settings);
    }

    return ResponseEntity.noContent().build();
  }

  @PostMapping("/analyze/{bookId}")
  public ResponseEntity<VoiceSettings> analyzeBook(
      @PathVariable String bookId,
      @RequestParam(required = false, defaultValue = "false") boolean force) {
    Optional<BookEntity> bookOpt = bookRepository.findById(bookId);
    if (bookOpt.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    BookEntity book = bookOpt.get();
    if (!isTtsEnabled(book)) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
    if (ttsService.isCacheOnly()) {
      return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    // Return existing settings if already analyzed (unless force=true)
    if (!force && book.getTtsVoice() != null) {
      VoiceSettings settings = new VoiceSettings(
          book.getTtsVoice(),
          book.getTtsSpeed() != null ? book.getTtsSpeed() : 1.0,
          book.getTtsInstructions(),
          book.getTtsReasoning()
      );
      return ResponseEntity.ok(settings);
    }

    // Get opening text from first chapter
    String openingText = "";
    List<ChapterEntity> chapters = chapterRepository.findByBookIdOrderByChapterIndex(book.getId());
    if (!chapters.isEmpty()) {
      List<ParagraphEntity> paragraphs = paragraphRepository
          .findByChapterIdOrderByParagraphIndex(chapters.get(0).getId());
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < Math.min(10, paragraphs.size()); i++) {
        sb.append(extractPlainText(paragraphs.get(i).getContent())).append("\n\n");
      }
      openingText = sb.toString();
    }

    VoiceSettings settings = voiceAnalysisService.analyzeBookForVoice(
        book.getTitle(), book.getAuthor(), openingText);

    // Save settings to database
    book.setTtsVoice(settings.voice());
    book.setTtsSpeed(settings.speed());
    book.setTtsInstructions(settings.instructions());
    book.setTtsReasoning(settings.reasoning());
    bookRepository.save(book);

    log.info("Saved voice settings for book {}: voice={}, speed={}",
             book.getTitle(), settings.voice(), settings.speed());

    return ResponseEntity.ok(settings);
  }

  @PostMapping("/speak")
  public ResponseEntity<byte[]> speak(@RequestBody SpeakRequest request) {
    if (!ttsService.isConfigured()) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body("OpenAI API key not configured".getBytes());
    }

    String text = extractPlainText(request.text());
    if (text.isBlank()) {
      return ResponseEntity.badRequest().body("No text to speak".getBytes());
    }

    VoiceSettings settings = new VoiceSettings(
        request.voice(),
        request.speed() > 0 ? request.speed() : 1.0,
        request.instructions(),
        null
    );

    byte[] audio = ttsService.generateSpeech(text, settings);
    if (audio == null) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, "audio/mpeg")
        .header(HttpHeaders.CACHE_CONTROL, "max-age=86400")
        .body(audio);
  }

  @GetMapping("/speak/{bookId}/{chapterId}/{paragraphIndex}")
  public ResponseEntity<byte[]> speakParagraph(
      @PathVariable String bookId,
      @PathVariable String chapterId,
      @PathVariable int paragraphIndex,
      HttpServletRequest request,
      @RequestHeader(value = "X-API-Key", required = false) String providedApiKey,
      @RequestParam(required = false) String voice,
      @RequestParam(required = false, defaultValue = "1.0") double speed,
      @RequestParam(required = false) String instructions) {

    Optional<BookEntity> bookOpt = bookRepository.findById(bookId);
    if (bookOpt.isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    if (!isTtsEnabled(bookOpt.get())) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    // Get paragraph text
    Optional<ChapterEntity> chapterOpt = chapterRepository.findById(chapterId);
    if (chapterOpt.isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    ChapterEntity chapter = chapterOpt.get();
    String bookKey = assetKeyService.buildBookKey(chapter.getBook());

    List<ParagraphEntity> paragraphs = paragraphRepository
        .findByChapterIdOrderByParagraphIndex(chapter.getId());

    if (paragraphIndex < 0 || paragraphIndex >= paragraphs.size()) {
      return ResponseEntity.notFound().build();
    }

    String text = extractPlainText(paragraphs.get(paragraphIndex).getContent());
    if (text.isBlank()) {
      // Return empty audio for blank paragraphs
      return ResponseEntity.ok()
          .header(HttpHeaders.CONTENT_TYPE, "audio/mpeg")
          .body(new byte[0]);
    }

    String resolvedVoice = ttsService.resolveVoice(voice);
    boolean cacheOnly = ttsService.isCacheOnly();
    if (cacheOnly && cdnAssetService.isEnabled()) {
      String audioKey = assetKeyService.buildAudioKey(bookOpt.get(), resolvedVoice,
          chapter.getChapterIndex(), paragraphIndex);
      return cdnAssetService.buildAssetUrl("audio", audioKey)
          .map(url -> ResponseEntity.status(HttpStatus.FOUND)
              .header(HttpHeaders.LOCATION, url)
              .body(new byte[0]))
          .orElseGet(() -> ResponseEntity.notFound().build());
    }

    byte[] cachedAudio = ttsService.getCachedSpeechForParagraph(
        bookKey, chapter.getChapterIndex(), paragraphIndex, resolvedVoice);
    if (cachedAudio != null && cachedAudio.length > 0) {
      return ResponseEntity.ok()
          .header(HttpHeaders.CONTENT_TYPE, "audio/mpeg")
          .header(HttpHeaders.CACHE_CONTROL, "max-age=604800")
          .body(cachedAudio);
    }

    if (isPublicMode() && !isSensitiveTtsAuthorized(request, providedApiKey)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body("Authentication required for uncached TTS generation".getBytes(StandardCharsets.UTF_8));
    }

    if (!ttsService.isConfigured()) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body("OpenAI API key not configured".getBytes());
    }

    VoiceSettings settings = new VoiceSettings(resolvedVoice, speed, instructions, null);
    byte[] audio = ttsService.generateSpeechForParagraph(
        bookKey, chapter.getChapterIndex(), paragraphIndex, text, settings);
    if (audio == null) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, "audio/mpeg")
        .header(HttpHeaders.CACHE_CONTROL, "max-age=604800")
        .body(audio);
  }

  @GetMapping("/estimate/{bookId}")
  public Map<String, Object> estimateCost(@PathVariable String bookId) {
    Optional<BookEntity> bookOpt = bookRepository.findById(bookId);
    if (bookOpt.isEmpty()) {
      return Map.of("error", "Book not found");
    }

    BookEntity book = bookOpt.get();
    if (!isTtsEnabled(book)) {
      return Map.of("error", "TTS disabled for book");
    }
    List<ChapterEntity> chapters = chapterRepository.findByBookIdOrderByChapterIndex(book.getId());

    int totalCharacters = 0;
    int totalParagraphs = 0;

    for (ChapterEntity chapter : chapters) {
      List<ParagraphEntity> paragraphs = paragraphRepository
          .findByChapterIdOrderByParagraphIndex(chapter.getId());
      for (ParagraphEntity p : paragraphs) {
        totalCharacters += extractPlainText(p.getContent()).length();
        totalParagraphs++;
      }
    }

    int costCents = ttsService.estimateCost(totalCharacters);

    return Map.of(
        "bookId", bookId,
        "title", book.getTitle(),
        "totalCharacters", totalCharacters,
        "totalParagraphs", totalParagraphs,
        "estimatedCostCents", costCents,
        "estimatedCostDisplay", String.format("$%.2f", costCents / 100.0)
    );
  }

  private String extractPlainText(String html) {
    if (html == null) return "";
    return Jsoup.parse(html).text();
  }

  private boolean isTtsEnabled(BookEntity book) {
    return Boolean.TRUE.equals(book.getTtsEnabled());
  }

  private boolean isPublicMode() {
    return "public".equalsIgnoreCase(deploymentMode);
  }

  private boolean isSensitiveTtsAuthorized(HttpServletRequest request, String providedApiKey) {
    boolean apiKeyAuthenticated = constantTimeEquals(publicApiKey, providedApiKey);
    boolean sessionAuthenticated = sessionAuthService != null && sessionAuthService.isAuthenticated(request);
    return apiKeyAuthenticated || sessionAuthenticated;
  }

  private boolean constantTimeEquals(String expected, String provided) {
    if (expected == null || expected.isBlank() || provided == null) {
      return false;
    }
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8),
        provided.getBytes(StandardCharsets.UTF_8)
    );
  }

  public record SpeakRequest(
      String text,
      String voice,
      double speed,
      String instructions
  ) {
  }
}
