package com.classicchatreader.service;

import com.classicchatreader.entity.CharacterChatMessageEntity;
import com.classicchatreader.entity.CharacterChatSessionEntity;
import com.classicchatreader.entity.CharacterStatus;
import com.classicchatreader.entity.CharacterEntity;
import com.classicchatreader.model.AccountChatModels.BookIdentity;
import com.classicchatreader.model.AccountChatModels.ChatContext;
import com.classicchatreader.model.AccountChatModels.CharacterIdentity;
import com.classicchatreader.model.AccountChatModels.ContinueRequest;
import com.classicchatreader.model.AccountChatModels.ContinueResponse;
import com.classicchatreader.model.AccountChatModels.Message;
import com.classicchatreader.model.AccountChatModels.PageInfo;
import com.classicchatreader.model.AccountChatModels.Resume;
import com.classicchatreader.model.AccountChatModels.SessionDetail;
import com.classicchatreader.model.AccountChatModels.SessionDetailResponse;
import com.classicchatreader.model.AccountChatModels.SessionListResponse;
import com.classicchatreader.model.AccountChatModels.SessionSummary;
import com.classicchatreader.model.ClassroomContextResponse;
import com.classicchatreader.model.ChatMessage;
import com.classicchatreader.repository.CharacterChatMessageRepository;
import com.classicchatreader.repository.CharacterChatSessionRepository;
import com.classicchatreader.repository.CharacterRepository;
import com.classicchatreader.repository.ChapterRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

@Service
public class AccountChatHistoryService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;
    private static final int MAX_QUERY_LENGTH = 100;
    private static final int MAX_PREVIEW_CODE_POINTS = 160;
    private static final byte CURSOR_VERSION = 1;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final CharacterChatSessionRepository sessionRepository;
    private final CharacterChatMessageRepository messageRepository;
    private final CharacterRepository characterRepository;
    private final ChapterRepository chapterRepository;
    private final CharacterChatService characterChatService;
    private final ClassroomContextService classroomContextService;
    private final boolean chatEnabled;
    private final boolean characterEnabled;
    private final byte[] cursorKey;

    public AccountChatHistoryService(
            CharacterChatSessionRepository sessionRepository,
            CharacterChatMessageRepository messageRepository,
            CharacterRepository characterRepository,
            ChapterRepository chapterRepository,
            CharacterChatService characterChatService,
            ClassroomContextService classroomContextService,
            @Value("${ai.chat.enabled:false}") boolean chatEnabled,
            @Value("${character.enabled:true}") boolean characterEnabled,
            @Value("${account.chat.cursor-secret:}") String configuredCursorSecret) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.characterRepository = characterRepository;
        this.chapterRepository = chapterRepository;
        this.characterChatService = characterChatService;
        this.classroomContextService = classroomContextService;
        this.chatEnabled = chatEnabled;
        this.characterEnabled = characterEnabled;
        this.cursorKey = cursorKey(configuredCursorSecret);
    }

    @Transactional(readOnly = true)
    public SessionListResponse list(String ownerUserId, ListRequest request) {
        Objects.requireNonNull(ownerUserId, "ownerUserId");
        ValidatedListRequest valid = validate(ownerUserId, request == null ? ListRequest.empty() : request);
        CursorPosition cursor = decodeCursor(valid.cursor(), valid.fingerprint());

        List<CharacterChatSessionEntity> fetched = sessionRepository.findVisiblePage(
                ownerUserId,
                valid.queryPattern(),
                valid.bookId(),
                valid.characterId(),
                valid.activeAfter(),
                valid.activeBefore(),
                cursor == null ? null : cursor.lastMessageAt(),
                cursor == null ? null : cursor.sessionId(),
                PageRequest.of(0, valid.limit() + 1)
        );

        boolean hasMore = fetched.size() > valid.limit();
        List<CharacterChatSessionEntity> sessions = hasMore
                ? new ArrayList<>(fetched.subList(0, valid.limit()))
                : new ArrayList<>(fetched);
        Map<String, MessageStats> messageStats = loadMessageStats(sessions);
        ClassroomContextResponse classroom = classroomContextService.getContext(ownerUserId);

        List<SessionSummary> items = sessions.stream()
                .map(session -> toSummary(session, messageStats.getOrDefault(session.getId(), MessageStats.empty()), classroom))
                .toList();
        String nextCursor = hasMore ? encodeCursor(sessions.get(sessions.size() - 1), valid.fingerprint()) : null;
        return new SessionListResponse(items, new PageInfo(valid.limit(), nextCursor, hasMore));
    }

    @Transactional(readOnly = true)
    public SessionDetailResponse get(String ownerUserId, String sessionId) {
        Objects.requireNonNull(ownerUserId, "ownerUserId");
        if (sessionId == null || sessionId.isBlank()) return null;
        CharacterChatSessionEntity session = sessionRepository
                .findByIdAndOwnerUserIdAndDeletedFalse(sessionId, ownerUserId)
                .orElse(null);
        if (session == null) return null;

        List<CharacterChatMessageEntity> entities = messageRepository.findOwnedTranscript(sessionId, ownerUserId);
        if (entities.stream().noneMatch(m -> CharacterChatMessageEntity.ROLE_USER.equals(m.getRole()))) return null;
        ClassroomContextResponse classroom = classroomContextService.getContext(ownerUserId);
        SessionDetail detail = new SessionDetail(
                session.getId(),
                characterIdentity(session),
                bookIdentity(session),
                toInstant(session.getCreatedAt()),
                toInstant(session.getLastMessageAt()),
                context(session),
                resume(session, classroom)
        );
        List<Message> messages = entities.stream()
                .map(m -> new Message(m.getId(), m.getRole(), m.getContent(), toInstant(m.getCreatedAt())))
                .toList();
        return new SessionDetailResponse(detail, messages);
    }

    @Transactional
    public String recordExchange(
            String ownerUserId,
            String characterId,
            String userContent,
            String characterContent,
            int chapterIndex,
            int paragraphIndex) {
        if (ownerUserId == null || ownerUserId.isBlank()) return null;
        CharacterEntity character = characterRepository.findByIdWithBookAndChapter(characterId).orElse(null);
        if (character == null) return null;
        var book = character.getBook();
        var chapter = chapterRepository.findByBookIdAndChapterIndex(book.getId(), chapterIndex).orElse(null);
        if (chapter == null) return null;

        CharacterChatSessionEntity session = sessionRepository
                .findByOwnerUserIdAndBookIdAndCharacterIdAndDeletedFalse(ownerUserId, book.getId(), characterId)
                .orElseGet(CharacterChatSessionEntity::new);
        if (session.getId() == null) {
            session.setOwnerUserId(ownerUserId);
            session.setBook(book);
            session.setCharacter(character);
            session.setBookTitleSnapshot(book.getTitle());
            session.setBookAuthorSnapshot(book.getAuthor());
            session.setCharacterNameSnapshot(character.getName());
            session.setPortraitAvailableSnapshot(character.getPortraitFilename() != null);
            session.setDeleted(false);
        }
        updateContext(session, chapter, paragraphIndex);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        session.setLastMessageAt(now);
        sessionRepository.save(session);
        messageRepository.saveAll(List.of(
                messageEntity(session, CharacterChatMessageEntity.ROLE_USER, userContent, now),
                messageEntity(session, CharacterChatMessageEntity.ROLE_CHARACTER, characterContent, now.plusNanos(1))
        ));
        return session.getId();
    }

    @Transactional
    public ContinueResponse continueConversation(String ownerUserId, String sessionId, ContinueRequest request) {
        String content = request == null ? null : normalizeMessage(request.content());
        if (content == null) throw new ChatHistoryValidationException("INVALID_MESSAGE", "Message content is required.");
        if (content.codePointCount(0, content.length()) > 4000) {
            throw new ChatHistoryValidationException("INVALID_MESSAGE", "Message content must be at most 4000 characters.");
        }
        CharacterChatSessionEntity session = sessionRepository
                .findByIdAndOwnerUserIdAndDeletedFalse(sessionId, ownerUserId)
                .orElse(null);
        if (session == null) return null;
        if (!resume(session, classroomContextService.getContext(ownerUserId)).available()) {
            throw new ChatHistoryValidationException("CHAT_UNAVAILABLE", "This conversation cannot be continued.");
        }

        List<CharacterChatMessageEntity> transcript = messageRepository.findOwnedTranscript(sessionId, ownerUserId);
        List<ChatMessage> history = transcript.stream()
                .map(message -> new ChatMessage(
                        CharacterChatMessageEntity.ROLE_USER.equals(message.getRole()) ? "user" : "character",
                        message.getContent(),
                        toInstant(message.getCreatedAt()).toEpochMilli()))
                .toList();
        String reply = characterChatService.chat(
                session.getCharacter().getId(),
                content,
                history,
                session.getContextChapterIndex(),
                session.getContextParagraphIndex()
        );
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        CharacterChatMessageEntity userMessage = messageEntity(
                session, CharacterChatMessageEntity.ROLE_USER, content, now);
        CharacterChatMessageEntity characterMessage = messageEntity(
                session, CharacterChatMessageEntity.ROLE_CHARACTER, reply, now.plusNanos(1));
        messageRepository.saveAll(List.of(userMessage, characterMessage));
        session.setLastMessageAt(characterMessage.getCreatedAt());
        sessionRepository.save(session);
        return new ContinueResponse(
                toMessage(userMessage),
                toMessage(characterMessage),
                context(session),
                toInstant(session.getLastMessageAt())
        );
    }

    private void updateContext(CharacterChatSessionEntity session, com.classicchatreader.entity.ChapterEntity chapter,
                               int paragraphIndex) {
        session.setContextChapter(chapter);
        session.setContextChapterIndex(chapter.getChapterIndex());
        session.setContextChapterTitle(chapter.getTitle());
        session.setContextParagraphIndex(Math.max(0, paragraphIndex));
    }

    private CharacterChatMessageEntity messageEntity(
            CharacterChatSessionEntity session,
            String role,
            String content,
            LocalDateTime createdAt) {
        CharacterChatMessageEntity message = new CharacterChatMessageEntity();
        message.setSession(session);
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(createdAt);
        return message;
    }

    private Message toMessage(CharacterChatMessageEntity message) {
        return new Message(message.getId(), message.getRole(), message.getContent(), toInstant(message.getCreatedAt()));
    }

    private ValidatedListRequest validate(String ownerUserId, ListRequest request) {
        int limit = parseLimit(request.limit());
        String q = normalizeQuery(request.q());
        if (q != null) q = q.toLowerCase(Locale.ROOT);
        String bookId = validateId("bookId", request.bookId());
        String characterId = validateId("characterId", request.characterId());
        if (bookId != null && characterId != null) {
            CharacterEntity character = characterRepository.findByIdWithBookAndChapter(characterId)
                    .orElseThrow(() -> invalid("characterId is not valid."));
            if (!bookId.equals(character.getBook().getId())) {
                throw invalid("characterId must belong to bookId.");
            }
        }
        LocalDateTime activeAfter = parseInstant("activeAfter", request.activeAfter());
        LocalDateTime activeBefore = parseInstant("activeBefore", request.activeBefore());
        if (activeAfter != null && activeBefore != null && !activeBefore.isAfter(activeAfter)) {
            throw invalid("activeBefore must be after activeAfter.");
        }
        String sort = request.sort() == null || request.sort().isBlank() ? "recent" : request.sort().trim();
        if (!"recent".equals(sort)) throw invalid("sort must be recent.");
        String cursor = blankToNull(request.cursor());
        String fingerprint = fingerprint(ownerUserId, q, bookId, characterId, activeAfter, activeBefore, sort);
        return new ValidatedListRequest(
                limit,
                q == null ? null : "%" + escapeLike(q) + "%",
                bookId,
                characterId,
                activeAfter,
                activeBefore,
                cursor,
                fingerprint
        );
    }

    private int parseLimit(String value) {
        if (value == null || value.isBlank()) return DEFAULT_LIMIT;
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > MAX_LIMIT) throw invalid("limit must be between 1 and 50.");
            return parsed;
        } catch (NumberFormatException ex) {
            throw invalid("limit must be an integer between 1 and 50.");
        }
    }

    private String normalizeQuery(String value) {
        String normalized = value == null ? null : WHITESPACE.matcher(value.trim()).replaceAll(" ");
        if (normalized == null || normalized.isEmpty()) return null;
        if (normalized.codePointCount(0, normalized.length()) > MAX_QUERY_LENGTH) {
            throw invalid("q must be at most 100 characters.");
        }
        return normalized;
    }

    private String normalizeMessage(String value) {
        String normalized = value == null ? null : value.trim();
        return normalized == null || normalized.isEmpty() ? null : normalized;
    }

    private String validateId(String name, String value) {
        String normalized = blankToNull(value);
        if (value != null && normalized == null) throw invalid(name + " must not be blank.");
        if (normalized != null && normalized.length() > 255) throw invalid(name + " is too long.");
        return normalized;
    }

    private LocalDateTime parseInstant(String name, String value) {
        String normalized = blankToNull(value);
        if (normalized == null) return null;
        try {
            return LocalDateTime.ofInstant(Instant.parse(normalized), ZoneOffset.UTC);
        } catch (DateTimeException ex) {
            throw invalid(name + " must be a UTC ISO-8601 instant.");
        }
    }

    private Map<String, MessageStats> loadMessageStats(List<CharacterChatSessionEntity> sessions) {
        if (sessions.isEmpty()) return Map.of();
        List<String> ids = sessions.stream().map(CharacterChatSessionEntity::getId).toList();
        Map<String, MessageStats> stats = new HashMap<>();
        for (CharacterChatMessageRepository.SessionMessageCount count : messageRepository.countForSessions(ids)) {
            stats.put(count.getSessionId(), new MessageStats(count.getMessageCount(), null));
        }
        for (CharacterChatMessageEntity preview : messageRepository.findNewestNonblankForSessions(ids)) {
            MessageStats current = stats.getOrDefault(preview.getSession().getId(), MessageStats.empty());
            stats.put(preview.getSession().getId(), new MessageStats(current.messageCount(), preview));
        }
        return stats;
    }

    private SessionSummary toSummary(
            CharacterChatSessionEntity session,
            MessageStats stats,
            ClassroomContextResponse classroom) {
        CharacterChatMessageEntity preview = stats.preview();
        return new SessionSummary(
                session.getId(),
                characterIdentity(session),
                bookIdentity(session),
                preview == null ? "" : preview(preview.getContent()),
                preview == null ? null : preview.getRole(),
                Math.toIntExact(stats.messageCount()),
                toInstant(session.getCreatedAt()),
                toInstant(session.getLastMessageAt()),
                toInstant(session.getUpdatedAt()),
                context(session),
                resume(session, classroom)
        );
    }

    private CharacterIdentity characterIdentity(CharacterChatSessionEntity session) {
        String portraitUrl = session.isPortraitAvailableSnapshot()
                ? "/api/characters/" + session.getCharacter().getId() + "/portrait"
                : null;
        return new CharacterIdentity(
                session.getCharacter().getId(),
                session.getCharacterNameSnapshot(),
                portraitUrl
        );
    }

    private BookIdentity bookIdentity(CharacterChatSessionEntity session) {
        return new BookIdentity(
                session.getBook().getId(),
                session.getBookTitleSnapshot(),
                session.getBookAuthorSnapshot()
        );
    }

    private ChatContext context(CharacterChatSessionEntity session) {
        return new ChatContext(
                session.getContextChapter().getId(),
                session.getContextChapterIndex(),
                session.getContextChapterTitle(),
                session.getContextParagraphIndex()
        );
    }

    private Resume resume(CharacterChatSessionEntity session, ClassroomContextResponse classroom) {
        String reason = null;
        if (!chatEnabled) reason = "CHAT_DISABLED";
        else if (!characterEnabled || !Boolean.TRUE.equals(session.getBook().getCharacterEnabled())) reason = "BOOK_DISABLED";
        else if (session.getCharacter().getStatus() != CharacterStatus.COMPLETED) reason = "CHARACTER_UNAVAILABLE";
        else if (classroom != null && classroom.enrolled()
                && (!classroom.features().characterEnabled() || !classroom.features().chatEnabled())) {
            reason = "CLASSROOM_POLICY";
        }
        return new Resume(reason == null, "/my-chats?session=" + session.getId(), reason);
    }

    private String preview(String content) {
        String oneLine = WHITESPACE.matcher(content.trim()).replaceAll(" ");
        int count = oneLine.codePointCount(0, oneLine.length());
        if (count <= MAX_PREVIEW_CODE_POINTS) return oneLine;
        return oneLine.substring(0, oneLine.offsetByCodePoints(0, MAX_PREVIEW_CODE_POINTS));
    }

    private String encodeCursor(CharacterChatSessionEntity session, String fingerprint) {
        try {
            ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(payloadBytes)) {
                out.writeByte(CURSOR_VERSION);
                Instant lastMessageAt = toInstant(session.getLastMessageAt());
                out.writeLong(lastMessageAt.getEpochSecond());
                out.writeInt(lastMessageAt.getNano());
                out.writeUTF(session.getId());
                out.writeUTF(fingerprint);
            }
            byte[] payload = payloadBytes.toByteArray();
            byte[] signature = hmac(payload);
            ByteArrayOutputStream token = new ByteArrayOutputStream();
            token.write(payload);
            token.write(signature);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(token.toByteArray());
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to create chat history cursor.", ex);
        }
    }

    private CursorPosition decodeCursor(String cursor, String expectedFingerprint) {
        if (cursor == null) return null;
        try {
            byte[] token = Base64.getUrlDecoder().decode(cursor);
            if (token.length <= 32) throw new IllegalArgumentException();
            byte[] payload = java.util.Arrays.copyOf(token, token.length - 32);
            byte[] signature = java.util.Arrays.copyOfRange(token, token.length - 32, token.length);
            if (!MessageDigest.isEqual(signature, hmac(payload))) throw new IllegalArgumentException();
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
                if (in.readByte() != CURSOR_VERSION) throw new IllegalArgumentException();
                long epochSecond = in.readLong();
                int nano = in.readInt();
                LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSecond, nano), ZoneOffset.UTC);
                String sessionId = in.readUTF();
                String fingerprint = in.readUTF();
                if (in.available() != 0 || !expectedFingerprint.equals(fingerprint) || sessionId.isBlank()) {
                    throw new IllegalArgumentException();
                }
                return new CursorPosition(time, sessionId);
            }
        } catch (Exception ex) {
            throw new ChatHistoryValidationException(
                    "INVALID_CURSOR",
                    "The page cursor is invalid for this request."
            );
        }
    }

    private byte[] hmac(byte[] payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(cursorKey, "HmacSHA256"));
        return mac.doFinal(payload);
    }

    private String fingerprint(
            String ownerUserId,
            String q,
            String bookId,
            String characterId,
            LocalDateTime activeAfter,
            LocalDateTime activeBefore,
            String sort) {
        String canonical = String.join("\u001f",
                ownerUserId,
                nullToEmpty(q),
                nullToEmpty(bookId),
                nullToEmpty(characterId),
                activeAfter == null ? "" : activeAfter.toString(),
                activeBefore == null ? "" : activeBefore.toString(),
                sort);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to fingerprint chat history filters.", ex);
        }
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static byte[] cursorKey(String configuredSecret) {
        if (configuredSecret == null || configuredSecret.isBlank()) {
            byte[] randomKey = new byte[32];
            new SecureRandom().nextBytes(randomKey);
            return randomKey;
        }
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(configuredSecret.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to initialize chat history cursor signing.", ex);
        }
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static Instant toInstant(LocalDateTime value) {
        return value.toInstant(ZoneOffset.UTC);
    }

    private static ChatHistoryValidationException invalid(String message) {
        return new ChatHistoryValidationException("INVALID_PARAMETER", message);
    }

    public record ListRequest(
            String limit,
            String cursor,
            String q,
            String bookId,
            String characterId,
            String activeAfter,
            String activeBefore,
            String sort
    ) {
        public static ListRequest empty() {
            return new ListRequest(null, null, null, null, null, null, null, null);
        }
    }

    private record ValidatedListRequest(
            int limit,
            String queryPattern,
            String bookId,
            String characterId,
            LocalDateTime activeAfter,
            LocalDateTime activeBefore,
            String cursor,
            String fingerprint
    ) {
    }

    private record CursorPosition(LocalDateTime lastMessageAt, String sessionId) {
    }

    private record MessageStats(long messageCount, CharacterChatMessageEntity preview) {
        private static MessageStats empty() {
            return new MessageStats(0, null);
        }
    }
}
