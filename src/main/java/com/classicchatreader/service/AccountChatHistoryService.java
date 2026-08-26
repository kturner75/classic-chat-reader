package com.classicchatreader.service;

import com.classicchatreader.entity.CharacterChatConversationEntity;
import com.classicchatreader.entity.CharacterChatMessageEntity;
import com.classicchatreader.entity.CharacterChatMessageRole;
import com.classicchatreader.entity.CharacterStatus;
import com.classicchatreader.entity.CharacterEntity;
import com.classicchatreader.entity.CharacterType;
import com.classicchatreader.model.AccountChatModels.BookIdentity;
import com.classicchatreader.model.AccountChatModels.CharacterFilterOption;
import com.classicchatreader.model.AccountChatModels.CharacterConversationResponse;
import com.classicchatreader.model.AccountChatModels.CharacterExchangeResponse;
import com.classicchatreader.model.AccountChatModels.CharacterVoiceCallTranscriptRequest;
import com.classicchatreader.model.AccountChatModels.CharacterVoiceCallTranscriptResponse;
import com.classicchatreader.model.AccountChatModels.ChatContext;
import com.classicchatreader.model.AccountChatModels.CharacterIdentity;
import com.classicchatreader.model.AccountChatModels.ContinueRequest;
import com.classicchatreader.model.AccountChatModels.ContinueResponse;
import com.classicchatreader.model.AccountChatModels.FilterOption;
import com.classicchatreader.model.AccountChatModels.FilterOptionsResponse;
import com.classicchatreader.model.AccountChatModels.Message;
import com.classicchatreader.model.AccountChatModels.PageInfo;
import com.classicchatreader.model.AccountChatModels.Resume;
import com.classicchatreader.model.AccountChatModels.SessionDetail;
import com.classicchatreader.model.AccountChatModels.SessionDetailResponse;
import com.classicchatreader.model.AccountChatModels.SessionListResponse;
import com.classicchatreader.model.AccountChatModels.SessionSummary;
import com.classicchatreader.model.AccountChatModels.VoiceCallTranscriptRequest;
import com.classicchatreader.model.AccountChatModels.VoiceCallTranscriptResponse;
import com.classicchatreader.model.AccountChatModels.VoiceCallTurn;
import com.classicchatreader.model.ClassroomContextResponse;
import com.classicchatreader.model.ChatMessage;
import com.classicchatreader.repository.CharacterChatConversationRepository;
import com.classicchatreader.repository.CharacterChatMessageRepository;
import com.classicchatreader.repository.CharacterRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.UserRepository;
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
    private static final int MAX_VOICE_CALL_TURNS = 20;
    private static final byte CURSOR_VERSION = 1;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final CharacterChatConversationRepository conversationRepository;
    private final CharacterChatMessageRepository messageRepository;
    private final CharacterRepository characterRepository;
    private final ChapterRepository chapterRepository;
    private final UserRepository userRepository;
    private final CharacterChatService characterChatService;
    private final ClassroomContextService classroomContextService;
    private final boolean chatEnabled;
    private final boolean characterEnabled;
    private final byte[] cursorKey;

    public AccountChatHistoryService(
            CharacterChatConversationRepository conversationRepository,
            CharacterChatMessageRepository messageRepository,
            CharacterRepository characterRepository,
            ChapterRepository chapterRepository,
            UserRepository userRepository,
            CharacterChatService characterChatService,
            ClassroomContextService classroomContextService,
            @Value("${ai.chat.enabled:false}") boolean chatEnabled,
            @Value("${character.enabled:true}") boolean characterEnabled,
            @Value("${account.chat.cursor-secret:}") String configuredCursorSecret) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.characterRepository = characterRepository;
        this.chapterRepository = chapterRepository;
        this.userRepository = userRepository;
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

        List<CharacterChatConversationEntity> fetched = conversationRepository.findVisiblePage(
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
        List<CharacterChatConversationEntity> sessions = hasMore
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
    public FilterOptionsResponse filterOptions(String ownerUserId) {
        Objects.requireNonNull(ownerUserId, "ownerUserId");
        List<FilterOption> books = conversationRepository.findVisibleFilterBooks(ownerUserId).stream()
                .map(row -> new FilterOption(row.getId(), row.getTitle()))
                .toList();
        List<CharacterFilterOption> characters = conversationRepository.findVisibleFilterCharacters(ownerUserId).stream()
                .map(row -> new CharacterFilterOption(row.getId(), row.getName(), row.getBookId()))
                .toList();
        return new FilterOptionsResponse(books, characters);
    }

    @Transactional(readOnly = true)
    public SessionDetailResponse get(String ownerUserId, String sessionId) {
        Objects.requireNonNull(ownerUserId, "ownerUserId");
        if (sessionId == null || sessionId.isBlank()) return null;
        CharacterChatConversationEntity session = conversationRepository
                .findByIdAndUserId(sessionId, ownerUserId)
                .orElse(null);
        if (session == null) return null;

        List<CharacterChatMessageEntity> entities = messageRepository
                .findByConversationIdAndUserIdOrderBySequenceNumberAsc(sessionId, ownerUserId);
        if (entities.stream().noneMatch(m -> m.getRole() == CharacterChatMessageRole.USER)) return null;
        CharacterEntity character = characterRepository.findByIdWithBookAndChapter(session.getCharacterId()).orElse(null);
        if (character == null) return null;
        ClassroomContextResponse classroom = classroomContextService.getContext(ownerUserId);
        SessionDetail detail = new SessionDetail(
                session.getId(),
                characterIdentity(character),
                bookIdentity(character),
                toInstant(session.getCreatedAt()),
                toInstant(session.getUpdatedAt()),
                context(session, character),
                resume(session, character, classroom)
        );
        List<Message> messages = entities.stream()
                .map(m -> new Message(m.getId(), m.getRole().name(), m.getContent(), toInstant(m.getCreatedAt())))
                .toList();
        return new SessionDetailResponse(detail, messages);
    }

    @Transactional(readOnly = true)
    public CharacterConversationResponse getLatestForCharacter(String ownerUserId, String characterId) {
        Objects.requireNonNull(ownerUserId, "ownerUserId");
        if (characterId == null || characterId.isBlank()) {
            return new CharacterConversationResponse(null, List.of());
        }
        List<CharacterChatConversationEntity> conversations = conversationRepository
                .findByUserIdAndCharacterIdOrderByUpdatedAtDescCreatedAtDesc(ownerUserId, characterId);
        for (CharacterChatConversationEntity conversation : conversations) {
            SessionDetailResponse detail = get(ownerUserId, conversation.getId());
            if (detail != null) {
                return new CharacterConversationResponse(detail.session(), detail.messages());
            }
        }
        return new CharacterConversationResponse(null, List.of());
    }

    @Transactional
    public CharacterExchangeResponse sendToCharacter(
            String ownerUserId,
            String characterId,
            ContinueRequest request,
            String idempotencyKey) {
        Objects.requireNonNull(ownerUserId, "ownerUserId");
        String content = validateMessageContent(request);
        String requestKey = validateRequiredIdempotencyKey(idempotencyKey);

        // Lock the account before selecting or creating its latest character thread. This closes
        // the no-row race where concurrent first messages could otherwise create two conversations.
        if (userRepository.findByIdForUpdate(ownerUserId).isEmpty()) return null;
        CharacterEntity character = characterRepository.findByIdWithBookAndChapter(characterId).orElse(null);
        if (character == null) return null;

        List<CharacterChatConversationEntity> existing = conversationRepository
                .findByUserIdAndCharacterIdOrderByUpdatedAtDescCreatedAtDesc(ownerUserId, characterId);
        CharacterChatConversationEntity session;
        if (existing.isEmpty()) {
            session = new CharacterChatConversationEntity();
            session.setUserId(ownerUserId);
            session.setCharacterId(characterId);
            session = conversationRepository.saveAndFlush(session);
        } else {
            session = conversationRepository
                    .findByIdAndUserIdForUpdate(existing.getFirst().getId(), ownerUserId)
                    .orElse(null);
            if (session == null) return null;
        }

        if (!resume(session, character, classroomContextService.getContext(ownerUserId)).available()) {
            throw new ChatHistoryValidationException("CHAT_UNAVAILABLE", "This conversation cannot be continued.");
        }

        List<CharacterChatMessageEntity> transcript = messageRepository
                .findByConversationIdAndUserIdOrderBySequenceNumberAsc(session.getId(), ownerUserId);
        ContinueResponse replay = replayExistingExchange(session, character, transcript, requestKey);
        if (replay != null) return exchangeResponse(session.getId(), replay);

        ChatContext chatContext = updateContextFromRequest(
                session, character, request == null ? null : request.context());
        List<ChatMessage> history = transcript.stream()
                .map(message -> new ChatMessage(
                        message.getRole() == CharacterChatMessageRole.USER ? "user" : "character",
                        message.getContent(),
                        toInstant(message.getCreatedAt()).toEpochMilli()))
                .toList();
        String reply = characterChatService.chat(
                characterId,
                content,
                history,
                chatContext.chapterIndex(),
                chatContext.paragraphIndex()
        );
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        long nextSequence = transcript.isEmpty() ? 0 : transcript.getLast().getSequenceNumber() + 1;
        CharacterChatMessageEntity userMessage = messageEntity(
                session, nextSequence, CharacterChatMessageRole.USER, content, requestKey, now);
        CharacterChatMessageEntity characterMessage = messageEntity(
                session, nextSequence + 1, CharacterChatMessageRole.CHARACTER, reply, null, now.plusNanos(1));
        messageRepository.saveAll(List.of(userMessage, characterMessage));
        session.setUpdatedAt(characterMessage.getCreatedAt());
        conversationRepository.save(session);
        return new CharacterExchangeResponse(
                session.getId(),
                toMessage(userMessage),
                toMessage(characterMessage),
                chatContext,
                toInstant(session.getUpdatedAt())
        );
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

        List<CharacterChatConversationEntity> existing = conversationRepository
                .findByUserIdAndCharacterIdOrderByUpdatedAtDescCreatedAtDesc(ownerUserId, characterId);
        CharacterChatConversationEntity session = existing.isEmpty()
                ? new CharacterChatConversationEntity()
                : existing.get(0);
        if (session.getId() == null) {
            session.setUserId(ownerUserId);
            session.setCharacterId(characterId);
        }
        updateContext(session, chapter, paragraphIndex);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        session.setUpdatedAt(now.plusNanos(1));
        conversationRepository.save(session);
        List<CharacterChatMessageEntity> transcript = messageRepository
                .findByConversationIdAndUserIdOrderBySequenceNumberAsc(session.getId(), ownerUserId);
        long nextSequence = transcript.isEmpty() ? 0 : transcript.get(transcript.size() - 1).getSequenceNumber() + 1;
        messageRepository.saveAll(List.of(
                messageEntity(session, nextSequence, CharacterChatMessageRole.USER, userContent, null, now),
                messageEntity(session, nextSequence + 1, CharacterChatMessageRole.CHARACTER,
                        characterContent, null, now.plusNanos(1))
        ));
        return session.getId();
    }

    @Transactional
    public ContinueResponse continueConversation(
            String ownerUserId,
            String sessionId,
            ContinueRequest request,
            String idempotencyKey) {
        String content = validateMessageContent(request);
        String requestKey = validateIdempotencyKey(idempotencyKey);
        // Serialize concurrent continues for the same session so a second in-flight
        // request with the same Idempotency-Key waits, then replays instead of double-calling the model.
        CharacterChatConversationEntity session = conversationRepository
                .findByIdAndUserIdForUpdate(sessionId, ownerUserId)
                .orElse(null);
        if (session == null) return null;
        CharacterEntity character = characterRepository.findByIdWithBookAndChapter(session.getCharacterId()).orElse(null);
        if (character == null) return null;
        if (!resume(session, character, classroomContextService.getContext(ownerUserId)).available()) {
            throw new ChatHistoryValidationException("CHAT_UNAVAILABLE", "This conversation cannot be continued.");
        }

        List<CharacterChatMessageEntity> transcript = messageRepository
                .findByConversationIdAndUserIdOrderBySequenceNumberAsc(sessionId, ownerUserId);
        if (requestKey != null) {
            ContinueResponse replay = replayExistingExchange(session, character, transcript, requestKey);
            if (replay != null) return replay;
        }
        List<ChatMessage> history = transcript.stream()
                .map(message -> new ChatMessage(
                        message.getRole() == CharacterChatMessageRole.USER ? "user" : "character",
                        message.getContent(),
                        toInstant(message.getCreatedAt()).toEpochMilli()))
                .toList();
        ChatContext chatContext = context(session, character);
        String reply = characterChatService.chat(
                session.getCharacterId(),
                content,
                history,
                chatContext.chapterIndex(),
                chatContext.paragraphIndex()
        );
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        long nextSequence = transcript.isEmpty() ? 0 : transcript.get(transcript.size() - 1).getSequenceNumber() + 1;
        CharacterChatMessageEntity userMessage = messageEntity(
                session, nextSequence, CharacterChatMessageRole.USER, content, requestKey, now);
        CharacterChatMessageEntity characterMessage = messageEntity(
                session, nextSequence + 1, CharacterChatMessageRole.CHARACTER, reply, null, now.plusNanos(1));
        messageRepository.saveAll(List.of(userMessage, characterMessage));
        session.setUpdatedAt(characterMessage.getCreatedAt());
        conversationRepository.save(session);
        return new ContinueResponse(
                toMessage(userMessage),
                toMessage(characterMessage),
                chatContext,
                toInstant(session.getUpdatedAt())
        );
    }

    @Transactional
    public VoiceCallTranscriptResponse appendVoiceCallTurns(
            String ownerUserId,
            String sessionId,
            VoiceCallTranscriptRequest request) {
        Objects.requireNonNull(ownerUserId, "ownerUserId");
        List<ValidatedVoiceCallTurn> turns = validateVoiceCallTurns(request);
        CharacterChatConversationEntity session = conversationRepository
                .findByIdAndUserIdForUpdate(sessionId, ownerUserId)
                .orElse(null);
        if (session == null) return null;
        CharacterEntity character = characterRepository.findByIdWithBookAndChapter(session.getCharacterId()).orElse(null);
        if (character == null) return null;
        if (!resume(session, character, classroomContextService.getContext(ownerUserId), true).available()) {
            throw new ChatHistoryValidationException("CHAT_UNAVAILABLE", "This conversation cannot be continued.");
        }
        return appendVoiceCallTurns(session, ownerUserId, turns, null, null);
    }

    @Transactional
    public CharacterVoiceCallTranscriptResponse appendVoiceCallTurnsToCharacter(
            String ownerUserId,
            String characterId,
            CharacterVoiceCallTranscriptRequest request) {
        Objects.requireNonNull(ownerUserId, "ownerUserId");
        List<ValidatedVoiceCallTurn> turns = validateVoiceCallTurns(
                request == null ? null : new VoiceCallTranscriptRequest(request.turns()));
        if (userRepository.findByIdForUpdate(ownerUserId).isEmpty()) return null;
        CharacterEntity character = characterRepository.findByIdWithBookAndChapter(characterId).orElse(null);
        if (character == null) return null;

        List<CharacterChatConversationEntity> existing = conversationRepository
                .findByUserIdAndCharacterIdOrderByUpdatedAtDescCreatedAtDesc(ownerUserId, characterId);
        CharacterChatConversationEntity session;
        if (existing.isEmpty()) {
            session = new CharacterChatConversationEntity();
            session.setUserId(ownerUserId);
            session.setCharacterId(characterId);
            session = conversationRepository.saveAndFlush(session);
        } else {
            session = conversationRepository
                    .findByIdAndUserIdForUpdate(existing.getFirst().getId(), ownerUserId)
                    .orElse(null);
            if (session == null) return null;
        }
        if (!resume(session, character, classroomContextService.getContext(ownerUserId), true).available()) {
            throw new ChatHistoryValidationException("CHAT_UNAVAILABLE", "This conversation cannot be continued.");
        }
        VoiceCallTranscriptResponse response = appendVoiceCallTurns(
                session,
                ownerUserId,
                turns,
                character,
                request == null ? null : request.context()
        );
        return new CharacterVoiceCallTranscriptResponse(
                session.getId(), response.messages(), response.lastMessageAt());
    }

    private VoiceCallTranscriptResponse appendVoiceCallTurns(
            CharacterChatConversationEntity session,
            String ownerUserId,
            List<ValidatedVoiceCallTurn> turns,
            CharacterEntity contextCharacter,
            ChatContext requestedContext) {
        List<CharacterChatMessageEntity> transcript = messageRepository
                .findByConversationIdAndUserIdOrderBySequenceNumberAsc(session.getId(), ownerUserId);
        Map<String, CharacterChatMessageEntity> byClientMessageId = new HashMap<>();
        for (CharacterChatMessageEntity message : transcript) {
            if (message.getClientMessageId() != null) {
                byClientMessageId.put(message.getClientMessageId(), message);
            }
        }

        long nextSequence = transcript.isEmpty() ? 0 : transcript.getLast().getSequenceNumber() + 1;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<CharacterChatMessageEntity> persisted = new ArrayList<>();
        List<CharacterChatMessageEntity> created = new ArrayList<>();
        for (ValidatedVoiceCallTurn turn : turns) {
            CharacterChatMessageEntity existing = byClientMessageId.get(turn.clientMessageId());
            if (existing != null) {
                if (existing.getRole() != turn.role() || !existing.getContent().equals(turn.content())) {
                    throw new ChatHistoryValidationException(
                            "IDEMPOTENCY_CONFLICT", "A voice call turn ID was reused with different content.");
                }
                persisted.add(existing);
                continue;
            }
            CharacterChatMessageEntity message = messageEntity(
                    session,
                    nextSequence++,
                    turn.role(),
                    turn.content(),
                    turn.clientMessageId(),
                    now.plusNanos(created.size())
            );
            created.add(message);
            persisted.add(message);
            byClientMessageId.put(turn.clientMessageId(), message);
        }
        if (!created.isEmpty()) {
            if (contextCharacter != null) {
                updateContextFromRequest(session, contextCharacter, requestedContext);
            }
            messageRepository.saveAll(created);
            CharacterChatMessageEntity newest = created.getLast();
            session.setUpdatedAt(newest.getCreatedAt());
            conversationRepository.save(session);
        }
        return new VoiceCallTranscriptResponse(
                persisted.stream().map(this::toMessage).toList(),
                toInstant(session.getUpdatedAt())
        );
    }

    private void updateContext(CharacterChatConversationEntity session, com.classicchatreader.entity.ChapterEntity chapter,
                               int paragraphIndex) {
        session.setContextChapterId(chapter.getId());
        session.setContextChapterIndex(chapter.getChapterIndex());
        session.setContextChapterTitle(chapter.getTitle());
        session.setContextParagraphIndex(Math.max(0, paragraphIndex));
    }

    private CharacterChatMessageEntity messageEntity(
            CharacterChatConversationEntity session,
            long sequenceNumber,
            CharacterChatMessageRole role,
            String content,
            String clientMessageId,
            LocalDateTime createdAt) {
        CharacterChatMessageEntity message = new CharacterChatMessageEntity();
        message.setConversationId(session.getId());
        message.setUserId(session.getUserId());
        message.setSequenceNumber(sequenceNumber);
        message.setRole(role);
        message.setContent(content);
        message.setClientMessageId(clientMessageId);
        message.setCreatedAt(createdAt);
        return message;
    }

    private Message toMessage(CharacterChatMessageEntity message) {
        return new Message(message.getId(), message.getRole().name(), message.getContent(), toInstant(message.getCreatedAt()));
    }

    private ContinueResponse replayExistingExchange(
            CharacterChatConversationEntity session,
            CharacterEntity character,
            List<CharacterChatMessageEntity> transcript,
            String requestKey) {
        CharacterChatMessageEntity userMessage = transcript.stream()
                .filter(message -> requestKey.equals(message.getClientMessageId()))
                .findFirst()
                .orElse(null);
        if (userMessage == null) return null;
        CharacterChatMessageEntity characterMessage = transcript.stream()
                .filter(message -> message.getSequenceNumber() == userMessage.getSequenceNumber() + 1)
                .filter(message -> message.getRole() == CharacterChatMessageRole.CHARACTER)
                .findFirst()
                .orElse(null);
        if (characterMessage == null) return null;
        return new ContinueResponse(
                toMessage(userMessage),
                toMessage(characterMessage),
                context(session, character),
                toInstant(session.getUpdatedAt())
        );
    }

    private CharacterExchangeResponse exchangeResponse(String sessionId, ContinueResponse response) {
        return new CharacterExchangeResponse(
                sessionId,
                response.userMessage(),
                response.characterMessage(),
                response.context(),
                response.lastMessageAt()
        );
    }

    private ChatContext updateContextFromRequest(
            CharacterChatConversationEntity session,
            CharacterEntity character,
            ChatContext supplied) {
        var chapter = character.getFirstChapter();
        int paragraphIndex = 0;
        if (supplied != null) {
            if (supplied.paragraphIndex() < 0) {
                throw new ChatHistoryValidationException("INVALID_CONTEXT", "paragraphIndex must not be negative.");
            }
            String suppliedChapterId = blankToNull(supplied.chapterId());
            if (suppliedChapterId != null) {
                chapter = chapterRepository.findByIdWithBook(suppliedChapterId).orElse(null);
                if (chapter == null || !chapter.getBook().getId().equals(character.getBook().getId())) {
                    throw new ChatHistoryValidationException(
                            "INVALID_CONTEXT", "chapterId must belong to the character's book.");
                }
                if (chapter.getChapterIndex() != supplied.chapterIndex()) {
                    throw new ChatHistoryValidationException("INVALID_CONTEXT", "chapterIndex must match chapterId.");
                }
            } else {
                chapter = chapterRepository.findByBookIdAndChapterIndex(
                        character.getBook().getId(), supplied.chapterIndex()).orElse(null);
                if (chapter == null) {
                    throw new ChatHistoryValidationException(
                            "INVALID_CONTEXT", "chapterIndex is not valid for the character's book.");
                }
            }
            paragraphIndex = supplied.paragraphIndex();
        } else if (session.getContextChapterId() != null) {
            return context(session, character);
        }
        updateContext(session, chapter, paragraphIndex);
        return context(session, character);
    }

    private String validateMessageContent(ContinueRequest request) {
        String content = request == null ? null : normalizeMessage(request.content());
        if (content == null) {
            throw new ChatHistoryValidationException("INVALID_MESSAGE", "Message content is required.");
        }
        if (content.codePointCount(0, content.length()) > 4000) {
            throw new ChatHistoryValidationException(
                    "INVALID_MESSAGE", "Message content must be at most 4000 characters.");
        }
        return content;
    }

    private List<ValidatedVoiceCallTurn> validateVoiceCallTurns(VoiceCallTranscriptRequest request) {
        List<VoiceCallTurn> supplied = request == null ? null : request.turns();
        if (supplied == null || supplied.isEmpty()) {
            throw new ChatHistoryValidationException(
                    "INVALID_VOICE_TURNS", "At least one voice call turn is required.");
        }
        if (supplied.size() > MAX_VOICE_CALL_TURNS) {
            throw new ChatHistoryValidationException(
                    "INVALID_VOICE_TURNS", "At most 20 voice call turns may be saved at once.");
        }
        List<ValidatedVoiceCallTurn> validated = new ArrayList<>();
        Map<String, Boolean> seenTurnIds = new HashMap<>();
        for (VoiceCallTurn turn : supplied) {
            String turnId = turn == null ? null : blankToNull(turn.turnId());
            if (turnId == null || turnId.length() > 249) {
                throw new ChatHistoryValidationException(
                        "INVALID_VOICE_TURN", "Each voice call turn requires a valid turnId.");
            }
            if (seenTurnIds.put(turnId, Boolean.TRUE) != null) {
                throw new ChatHistoryValidationException(
                        "INVALID_VOICE_TURN", "Voice call turn IDs must be unique within a request.");
            }
            String roleName = turn == null || turn.role() == null
                    ? ""
                    : turn.role().trim().toUpperCase(Locale.ROOT);
            if (!"USER".equals(roleName) && !"CHARACTER".equals(roleName)) {
                throw new ChatHistoryValidationException(
                        "INVALID_VOICE_TURN", "Voice call turn role must be USER or CHARACTER.");
            }
            CharacterChatMessageRole role = CharacterChatMessageRole.valueOf(roleName);
            String content = normalizeMessage(turn == null ? null : turn.content());
            if (content == null || content.codePointCount(0, content.length()) > 4000) {
                throw new ChatHistoryValidationException(
                        "INVALID_VOICE_TURN", "Voice call turn content must be between 1 and 4000 characters.");
            }
            validated.add(new ValidatedVoiceCallTurn("voice:" + turnId, role, content));
        }
        return validated;
    }

    private String validateRequiredIdempotencyKey(String value) {
        String normalized = validateIdempotencyKey(value);
        if (normalized == null) {
            throw new ChatHistoryValidationException(
                    "INVALID_IDEMPOTENCY_KEY", "Idempotency-Key is required.");
        }
        return normalized;
    }

    private String validateIdempotencyKey(String value) {
        String normalized = blankToNull(value);
        if (normalized != null && normalized.length() > 255) {
            throw new ChatHistoryValidationException("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key is too long.");
        }
        return normalized;
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

    private Map<String, MessageStats> loadMessageStats(List<CharacterChatConversationEntity> sessions) {
        if (sessions.isEmpty()) return Map.of();
        List<String> ids = sessions.stream().map(CharacterChatConversationEntity::getId).toList();
        Map<String, MessageStats> stats = new HashMap<>();
        for (CharacterChatMessageRepository.ConversationMessageCount count
                : messageRepository.countForConversations(ids)) {
            stats.put(count.getConversationId(), new MessageStats(count.getMessageCount(), null));
        }
        for (CharacterChatMessageEntity preview : messageRepository.findNewestNonblankForConversations(ids)) {
            MessageStats current = stats.getOrDefault(preview.getConversationId(), MessageStats.empty());
            stats.put(preview.getConversationId(), new MessageStats(current.messageCount(), preview));
        }
        return stats;
    }

    private SessionSummary toSummary(
            CharacterChatConversationEntity session,
            MessageStats stats,
            ClassroomContextResponse classroom) {
        CharacterEntity character = characterRepository.findByIdWithBookAndChapter(session.getCharacterId())
                .orElseThrow(() -> new IllegalStateException("Conversation character no longer exists."));
        CharacterChatMessageEntity preview = stats.preview();
        return new SessionSummary(
                session.getId(),
                characterIdentity(character),
                bookIdentity(character),
                preview == null ? "" : preview(preview.getContent()),
                preview == null ? null : preview.getRole().name(),
                Math.toIntExact(stats.messageCount()),
                toInstant(session.getCreatedAt()),
                toInstant(session.getUpdatedAt()),
                toInstant(session.getUpdatedAt()),
                context(session, character),
                resume(session, character, classroom)
        );
    }

    private CharacterIdentity characterIdentity(CharacterEntity character) {
        String portraitUrl = character.hasStoredPortraitImage()
                ? "/api/characters/" + character.getId() + "/portrait"
                : null;
        return new CharacterIdentity(
                character.getId(),
                character.getName(),
                portraitUrl
        );
    }

    private BookIdentity bookIdentity(CharacterEntity character) {
        var book = character.getBook();
        return new BookIdentity(
                book.getId(),
                book.getTitle(),
                book.getAuthor()
        );
    }

    private ChatContext context(CharacterChatConversationEntity session, CharacterEntity character) {
        var fallbackChapter = character.getFirstChapter();
        return new ChatContext(
                session.getContextChapterId() == null ? fallbackChapter.getId() : session.getContextChapterId(),
                session.getContextChapterIndex() == null
                        ? fallbackChapter.getChapterIndex()
                        : session.getContextChapterIndex(),
                session.getContextChapterTitle() == null
                        ? fallbackChapter.getTitle()
                        : session.getContextChapterTitle(),
                session.getContextParagraphIndex() == null ? 0 : session.getContextParagraphIndex()
        );
    }

    private Resume resume(
            CharacterChatConversationEntity session,
            CharacterEntity character,
            ClassroomContextResponse classroom) {
        return resume(session, character, classroom, false);
    }

    private Resume resume(
            CharacterChatConversationEntity session,
            CharacterEntity character,
            ClassroomContextResponse classroom,
            boolean requirePrimary) {
        String reason = null;
        if (!chatEnabled) reason = "CHAT_DISABLED";
        else if (!characterEnabled || !Boolean.TRUE.equals(character.getBook().getCharacterEnabled())) reason = "BOOK_DISABLED";
        else if (character.getStatus() != CharacterStatus.COMPLETED
                || !isChatEligibleCharacter(character)
                || (requirePrimary && character.getCharacterType() != CharacterType.PRIMARY)) {
            reason = "CHARACTER_UNAVAILABLE";
        }
        else if (classroom != null && classroom.enrolled()
                && (!classroom.features().characterEnabled() || !classroom.features().chatEnabled())) {
            reason = "CLASSROOM_POLICY";
        }
        boolean chatAvailable = reason == null;
        boolean voiceCallAvailable = chatAvailable && character.getCharacterType() == CharacterType.PRIMARY;
        return new Resume(chatAvailable, "/my-chats?session=" + session.getId(), reason, voiceCallAvailable);
    }

    /**
     * Matches {@link CharacterService#isChatEligible}: PRIMARY only.
     * Empty PRIMARY means nobody to call; SECONDARY is never a fallback.
     */
    private boolean isChatEligibleCharacter(CharacterEntity character) {
        return character.getCharacterType() == CharacterType.PRIMARY;
    }

    private String preview(String content) {
        String oneLine = WHITESPACE.matcher(content.trim()).replaceAll(" ");
        int count = oneLine.codePointCount(0, oneLine.length());
        if (count <= MAX_PREVIEW_CODE_POINTS) return oneLine;
        return oneLine.substring(0, oneLine.offsetByCodePoints(0, MAX_PREVIEW_CODE_POINTS));
    }

    private String encodeCursor(CharacterChatConversationEntity session, String fingerprint) {
        try {
            ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(payloadBytes)) {
                out.writeByte(CURSOR_VERSION);
                Instant lastMessageAt = toInstant(session.getUpdatedAt());
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

    private record ValidatedVoiceCallTurn(
            String clientMessageId,
            CharacterChatMessageRole role,
            String content) {
    }
}
