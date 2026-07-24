package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.CharacterChatConversationEntity;
import com.classicchatreader.entity.CharacterChatMessageEntity;
import com.classicchatreader.entity.CharacterChatMessageRole;
import com.classicchatreader.entity.CharacterEntity;
import com.classicchatreader.entity.CharacterStatus;
import com.classicchatreader.entity.CharacterType;
import com.classicchatreader.model.ClassroomContextResponse;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@DataJpaTest
@Import(AccountChatHistoryService.class)
@TestPropertySource(properties = {
        "ai.chat.enabled=true",
        "character.enabled=true"
})
class AccountChatHistoryServiceTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 7, 21, 12, 0);

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private AccountChatHistoryService service;

    @MockitoBean
    private ClassroomContextService classroomContextService;

    @MockitoBean
    private CharacterChatService characterChatService;

    private final Set<String> persistedUsers = new HashSet<>();

    @BeforeEach
    void defaultClassroomPolicyAllowsChat() {
        persistedUsers.clear();
        when(classroomContextService.getContext(anyString())).thenReturn(ClassroomContextResponse.notEnrolled());
    }

    @Test
    void list_isOwnerScopedAndOmitsSessionsWithoutUserMessages() {
        Fixture owned = createSession("owner-1", "A Tale", "Author", "Alice", BASE.plusMinutes(3));
        addMessage(owned.session(), "USER", "Hello", BASE.plusMinutes(1));
        addMessage(owned.session(), "CHARACTER", "Welcome", BASE.plusMinutes(3));

        Fixture other = createSession("owner-2", "Other Book", "Other", "Mallory", BASE.plusMinutes(4));
        addMessage(other.session(), "USER", "private message", BASE.plusMinutes(4));

        Fixture empty = createSession("owner-1", "Empty Book", "Author", "Nobody", BASE.plusMinutes(5));
        addMessage(empty.session(), "CHARACTER", "Synthetic greeting", BASE.plusMinutes(5));
        flushAndClear();

        var result = service.list("owner-1", request(null, null, null, null, null, null, null, null));

        assertThat(result.items()).extracting(item -> item.sessionId()).containsExactly(owned.session().getId());
        assertThat(result.items().getFirst().previewText()).isEqualTo("Welcome");
        assertThat(result.items().getFirst().messageCount()).isEqualTo(2);
    }

    @Test
    void filterOptions_returnsDistinctOwnedBooksAndCharactersIncludingBeyondFirstPage() {
        Fixture pride = sessionWithUserMessage("owner", "Pride and Prejudice", "Jane Austen", "Elizabeth", BASE.plusMinutes(5));
        Fixture moby = sessionWithUserMessage("owner", "Moby-Dick", "Herman Melville", "Ahab", BASE.plusMinutes(1));
        Fixture otherOwner = sessionWithUserMessage("other", "Secret Book", "Author", "Hidden", BASE.plusMinutes(4));
        Fixture empty = createSession("owner", "Empty Book", "Author", "Nobody", BASE.plusMinutes(3));
        addMessage(empty.session(), "CHARACTER", "greeting only", BASE.plusMinutes(3));
        flushAndClear();

        var filters = service.filterOptions("owner");

        assertThat(filters.books()).extracting(option -> option.label())
                .containsExactly("Moby-Dick", "Pride and Prejudice");
        assertThat(filters.characters()).extracting(option -> option.label())
                .containsExactly("Ahab", "Elizabeth");
        assertThat(filters.characters()).extracting(option -> option.bookId())
                .containsExactlyInAnyOrder(moby.book().getId(), pride.book().getId());
        assertThat(filters.books()).extracting(option -> option.id()).doesNotContain(otherOwner.book().getId());
        assertThat(filters.characters()).extracting(option -> option.id())
                .doesNotContain(otherOwner.character().getId(), empty.character().getId());
    }

    @Test
    void list_emptyOwnerReturnsEmptyPageWithRequestedLimit() {
        var result = service.list("owner-with-no-chats", request("50", null, null, null, null, null, null, null));

        assertThat(result.items()).isEmpty();
        assertThat(result.page().limit()).isEqualTo(50);
        assertThat(result.page().hasMore()).isFalse();
        assertThat(result.page().nextCursor()).isNull();
    }

    @Test
    void list_ordersDeterministicallyAndCursorPaginationHasNoDuplicates() {
        Fixture oldest = sessionWithUserMessage("owner", "Oldest", "A", "One", BASE);
        Fixture tiedA = sessionWithUserMessage("owner", "Tie A", "A", "Two", BASE.plusMinutes(2));
        Fixture tiedB = sessionWithUserMessage("owner", "Tie B", "A", "Three", BASE.plusMinutes(2));
        flushAndClear();

        List<String> tiedIds = new ArrayList<>(List.of(tiedA.session().getId(), tiedB.session().getId()));
        tiedIds.sort(Comparator.naturalOrder());

        var first = service.list("owner", request("2", null, null, null, null, null, null, null));
        assertThat(first.items()).extracting(item -> item.sessionId()).containsExactlyElementsOf(tiedIds);
        assertThat(first.page().hasMore()).isTrue();
        assertThat(first.page().nextCursor()).isNotBlank();

        var second = service.list("owner", request("2", first.page().nextCursor(), null, null, null, null, null, null));
        assertThat(second.items()).extracting(item -> item.sessionId()).containsExactly(oldest.session().getId());
        assertThat(second.page().hasMore()).isFalse();
        assertThat(Set.of(
                first.items().get(0).sessionId(),
                first.items().get(1).sessionId(),
                second.items().getFirst().sessionId()
        )).hasSize(3);
    }

    @Test
    void cursorCannotBeReusedWithChangedSearchOrOwner() {
        sessionWithUserMessage("owner", "Alpha", "A", "One", BASE.plusMinutes(2));
        sessionWithUserMessage("owner", "Beta", "A", "Two", BASE.plusMinutes(1));
        flushAndClear();
        var first = service.list("owner", request("1", null, null, null, null, null, null, null));

        assertThatThrownBy(() -> service.list(
                "owner",
                request("1", first.page().nextCursor(), "alpha", null, null, null, null, null)
        )).isInstanceOf(ChatHistoryValidationException.class)
                .extracting(ex -> ((ChatHistoryValidationException) ex).getCode())
                .isEqualTo("INVALID_CURSOR");

        assertThatThrownBy(() -> service.list(
                "different-owner",
                request("1", first.page().nextCursor(), null, null, null, null, null, null)
        )).isInstanceOf(ChatHistoryValidationException.class);
    }

    @Test
    void searchAndFiltersCoverSnapshotsMessagesAndTimeBoundaries() {
        Fixture pride = createSession("owner", "Pride and Prejudice", "Jane Austen", "Elizabeth Bennet", BASE.plusMinutes(2));
        addMessage(pride.session(), "USER", "What about Darcy?", BASE.plusMinutes(1));
        addMessage(pride.session(), "CHARACTER", "First impressions matter", BASE.plusMinutes(2));
        Fixture expectations = sessionWithUserMessage(
                "owner", "Great Expectations", "Charles Dickens", "Pip", BASE.plusMinutes(4));
        flushAndClear();

        assertThat(service.list("owner", request(null, null, "  jane   AUSTEN ", null, null, null, null, null)).items())
                .extracting(item -> item.sessionId()).containsExactly(pride.session().getId());
        assertThat(service.list("owner", request(null, null, "elizabeth bennet", null, null, null, null, null)).items())
                .extracting(item -> item.sessionId()).containsExactly(pride.session().getId());
        assertThat(service.list("owner", request(null, null, "great expectations", null, null, null, null, null)).items())
                .extracting(item -> item.sessionId()).containsExactly(expectations.session().getId());
        assertThat(service.list("owner", request(null, null, "darcy", null, null, null, null, null)).items())
                .extracting(item -> item.sessionId()).containsExactly(pride.session().getId());
        assertThat(service.list("owner", request(null, null, null, pride.book().getId(), null, null, null, null)).items())
                .extracting(item -> item.sessionId()).containsExactly(pride.session().getId());
        assertThat(service.list("owner", request(null, null, null, null, expectations.character().getId(), null, null, null)).items())
                .extracting(item -> item.sessionId()).containsExactly(expectations.session().getId());
        assertThat(service.list(
                "owner",
                request(null, null, null, null, null, "2026-07-21T12:02:00Z", "2026-07-21T12:04:00Z", null)
        ).items()).extracting(item -> item.sessionId()).containsExactly(pride.session().getId());
    }

    @Test
    void invalidInputsAreRejected() {
        Fixture fixture = sessionWithUserMessage("owner", "Book", "Author", "Character", BASE);
        flushAndClear();

        assertInvalid(request("0", null, null, null, null, null, null, null));
        assertInvalid(request("51", null, null, null, null, null, null, null));
        assertInvalid(request("abc", null, null, null, null, null, null, null));
        assertInvalid(request(null, null, null, null, null, "not-an-instant", null, null));
        assertInvalid(request(null, null, null, null, null, "2026-07-22T00:00:00Z", "2026-07-21T00:00:00Z", null));
        assertInvalid(request(null, null, null, null, null, null, null, "oldest"));
        assertInvalid(request(null, "malformed", null, null, null, null, null, null));
        assertInvalid(request(null, null, null, " ", null, null, null, null));

        Fixture differentBook = createSession("owner", "Other", "Author", "Other Character", BASE.plusMinutes(1));
        addMessage(differentBook.session(), "USER", "Hi", BASE.plusMinutes(1));
        flushAndClear();
        assertInvalid(request(
                null, null, null, fixture.book().getId(), differentBook.character().getId(), null, null, null
        ));
    }

    @Test
    void detailIsOwnerScopedOldestFirstAndUnavailableCharacterRemainsReadable() {
        Fixture fixture = createSession("owner", "Book", "Author", "Character", BASE.plusMinutes(2));
        fixture.character().setStatus(CharacterStatus.FAILED);
        entityManager.merge(fixture.character());
        addMessage(fixture.session(), "USER", "First", BASE.plusMinutes(1));
        addMessage(fixture.session(), "CHARACTER", "Second", BASE.plusMinutes(2));
        flushAndClear();

        assertThat(service.get("other-owner", fixture.session().getId())).isNull();
        var detail = service.get("owner", fixture.session().getId());
        assertThat(detail).isNotNull();
        assertThat(detail.messages()).extracting(message -> message.content()).containsExactly("First", "Second");
        assertThat(detail.session().character().name()).isEqualTo("Character");
        assertThat(detail.session().resume().available()).isFalse();
        assertThat(detail.session().resume().unavailableReason()).isEqualTo("CHARACTER_UNAVAILABLE");
    }

    @Test
    void secondaryCharactersRemainReadableButCannotContinue() {
        Fixture fixture = createSession("owner", "Book", "Author", "Minor Character", BASE.plusMinutes(2));
        fixture.character().setCharacterType(CharacterType.SECONDARY);
        entityManager.merge(fixture.character());
        addMessage(fixture.session(), "USER", "Earlier", BASE.plusMinutes(1));
        addMessage(fixture.session(), "CHARACTER", "Reply", BASE.plusMinutes(2));
        flushAndClear();

        var detail = service.get("owner", fixture.session().getId());
        assertThat(detail).isNotNull();
        assertThat(detail.messages()).hasSize(2);
        assertThat(detail.session().resume().available()).isFalse();
        assertThat(detail.session().resume().unavailableReason()).isEqualTo("CHARACTER_UNAVAILABLE");

        assertThatThrownBy(() -> service.continueConversation(
                "owner",
                fixture.session().getId(),
                new com.classicchatreader.model.AccountChatModels.ContinueRequest("Try again", null),
                "request-secondary"))
                .isInstanceOf(ChatHistoryValidationException.class)
                .extracting(ex -> ((ChatHistoryValidationException) ex).getCode())
                .isEqualTo("CHAT_UNAVAILABLE");
        assertThatThrownBy(() -> service.sendToCharacter(
                "owner",
                fixture.character().getId(),
                new com.classicchatreader.model.AccountChatModels.ContinueRequest("Try again", null),
                "request-secondary-by-character"))
                .isInstanceOf(ChatHistoryValidationException.class)
                .extracting(ex -> ((ChatHistoryValidationException) ex).getCode())
                .isEqualTo("CHAT_UNAVAILABLE");
        org.mockito.Mockito.verifyNoInteractions(characterChatService);
    }

    @Test
    void previewIsNormalizedAndTruncatedByUnicodeCodePoint() {
        Fixture fixture = createSession("owner", "Book", "Author", "Character", BASE);
        String content = "  hello\n\tworld  " + "😀".repeat(200);
        addMessage(fixture.session(), "USER", content, BASE);
        flushAndClear();

        var summary = service.list("owner", AccountChatHistoryService.ListRequest.empty()).items().getFirst();
        assertThat(summary.previewText()).doesNotContain("\n", "\t");
        assertThat(summary.previewText().codePointCount(0, summary.previewText().length())).isEqualTo(160);
        assertThat(summary.previewRole()).isEqualTo("USER");
    }

    @Test
    void signedInReaderExchangesReuseOneSessionAndAppearInHistory() {
        Fixture fixture = createSession("owner", "Book", "Author", "Character", BASE);
        flushAndClear();

        String firstSessionId = service.recordExchange(
                "owner", fixture.character().getId(), "Hello", "Greetings", 0, 3);
        String secondSessionId = service.recordExchange(
                "owner", fixture.character().getId(), "Again", "Welcome back", 0, 4);
        flushAndClear();

        var result = service.list("owner", request(null, null, null, null, null, null, null, null));
        assertThat(secondSessionId).isEqualTo(firstSessionId);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().sessionId()).isEqualTo(firstSessionId);
        assertThat(result.items().getFirst().messageCount()).isEqualTo(4);
        assertThat(result.items().getFirst().context().paragraphIndex()).isEqualTo(4);
    }

    @Test
    void latestCharacterConversationIsEmptyWhenMissingAndNeverLeaksAnotherOwnersTranscript() {
        Fixture other = createSession("other-owner", "Book", "Author", "Character", BASE);
        addMessage(other.session(), "USER", "private question", BASE);
        flushAndClear();

        var empty = service.getLatestForCharacter("owner", other.character().getId());
        var visible = service.getLatestForCharacter("other-owner", other.character().getId());

        assertThat(empty.session()).isNull();
        assertThat(empty.messages()).isEmpty();
        assertThat(visible.session()).isNotNull();
        assertThat(visible.messages()).extracting(message -> message.content())
                .containsExactly("private question");
    }

    @Test
    void sendToCharacterCreatesOrderedTranscriptAndReplaysRetryWithoutDuplicates() {
        Fixture fixture = createSession("owner", "Book", "Author", "Character", BASE);
        entityManager.remove(fixture.session());
        flushAndClear();
        when(characterChatService.chat(
                org.mockito.ArgumentMatchers.eq(fixture.character().getId()),
                org.mockito.ArgumentMatchers.eq("Hello"),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.eq(7)))
                .thenReturn("Welcome");
        var request = new com.classicchatreader.model.AccountChatModels.ContinueRequest(
                "Hello",
                new com.classicchatreader.model.AccountChatModels.ChatContext(
                        fixture.chapter().getId(), 0, "Client supplied title", 7));

        var created = service.sendToCharacter("owner", fixture.character().getId(), request, "stable-request-1");
        var replayed = service.sendToCharacter("owner", fixture.character().getId(), request, "stable-request-1");
        flushAndClear();
        var loaded = service.getLatestForCharacter("owner", fixture.character().getId());

        assertThat(created).isNotNull();
        assertThat(replayed.sessionId()).isEqualTo(created.sessionId());
        assertThat(replayed.userMessage().messageId()).isEqualTo(created.userMessage().messageId());
        assertThat(replayed.characterMessage().messageId()).isEqualTo(created.characterMessage().messageId());
        assertThat(loaded.messages()).extracting(message -> message.role())
                .containsExactly("USER", "CHARACTER");
        assertThat(loaded.messages()).extracting(message -> message.content())
                .containsExactly("Hello", "Welcome");
        assertThat(loaded.session().context().chapterTitle()).isEqualTo("Chapter One");
        assertThat(loaded.session().context().paragraphIndex()).isEqualTo(7);
        org.mockito.Mockito.verify(characterChatService, times(1)).chat(
                org.mockito.ArgumentMatchers.eq(fixture.character().getId()),
                org.mockito.ArgumentMatchers.eq("Hello"),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.eq(7));
    }

    @Test
    void continuingOwnedSessionAppendsToThatSessionAndRejectsOtherOwner() {
        Fixture fixture = createSession("owner", "Book", "Author", "Character", BASE);
        addMessage(fixture.session(), "USER", "Earlier question", BASE.minusMinutes(1));
        addMessage(fixture.session(), "CHARACTER", "Earlier answer", BASE);
        flushAndClear();
        when(characterChatService.chat(
                org.mockito.ArgumentMatchers.eq(fixture.character().getId()),
                org.mockito.ArgumentMatchers.eq("New question"),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.eq(3)))
                .thenReturn("New answer");

        var continued = service.continueConversation(
                "owner",
                fixture.session().getId(),
                new com.classicchatreader.model.AccountChatModels.ContinueRequest("New question", null),
                "request-1");
        var replayed = service.continueConversation(
                "owner",
                fixture.session().getId(),
                new com.classicchatreader.model.AccountChatModels.ContinueRequest("New question", null),
                "request-1");
        var denied = service.continueConversation(
                "other-owner",
                fixture.session().getId(),
                new com.classicchatreader.model.AccountChatModels.ContinueRequest("Steal", null),
                "request-2");
        flushAndClear();

        assertThat(continued.userMessage().content()).isEqualTo("New question");
        assertThat(continued.characterMessage().content()).isEqualTo("New answer");
        assertThat(replayed.userMessage().messageId()).isEqualTo(continued.userMessage().messageId());
        assertThat(replayed.characterMessage().messageId()).isEqualTo(continued.characterMessage().messageId());
        assertThat(denied).isNull();
        assertThat(service.get("owner", fixture.session().getId()).messages()).hasSize(4);
        org.mockito.Mockito.verify(characterChatService, org.mockito.Mockito.times(1)).chat(
                org.mockito.ArgumentMatchers.eq(fixture.character().getId()),
                org.mockito.ArgumentMatchers.eq("New question"),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.eq(3));
    }

    @Test
    void voiceCallTurnsAreOwnerScopedDurableIdempotentAndIncludedInTextContext() {
        Fixture fixture = createSession("owner", "Book", "Author", "Character", BASE);
        addMessage(fixture.session(), "USER", "Earlier question", BASE.minusMinutes(1));
        addMessage(fixture.session(), "CHARACTER", "Earlier answer", BASE);
        flushAndClear();
        var request = new com.classicchatreader.model.AccountChatModels.VoiceCallTranscriptRequest(List.of(
                new com.classicchatreader.model.AccountChatModels.VoiceCallTurn(
                        "voice-turn-1", "USER", "Spoken question"),
                new com.classicchatreader.model.AccountChatModels.VoiceCallTurn(
                        "voice-turn-2", "CHARACTER", "Spoken answer")
        ));

        var appended = service.appendVoiceCallTurns("owner", fixture.session().getId(), request);
        var replayed = service.appendVoiceCallTurns("owner", fixture.session().getId(), request);
        var denied = service.appendVoiceCallTurns("other-owner", fixture.session().getId(), request);
        flushAndClear();

        assertThat(appended.messages()).extracting(message -> message.content())
                .containsExactly("Spoken question", "Spoken answer");
        assertThat(replayed.messages()).extracting(message -> message.messageId())
                .containsExactlyElementsOf(appended.messages().stream().map(message -> message.messageId()).toList());
        assertThat(denied).isNull();
        assertThat(service.get("owner", fixture.session().getId()).messages())
                .extracting(message -> message.content())
                .containsExactly("Earlier question", "Earlier answer", "Spoken question", "Spoken answer");

        when(characterChatService.chat(
                org.mockito.ArgumentMatchers.eq(fixture.character().getId()),
                org.mockito.ArgumentMatchers.eq("Text follow-up"),
                org.mockito.ArgumentMatchers.argThat(history -> history.size() == 4
                        && history.get(2).content().equals("Spoken question")
                        && history.get(3).content().equals("Spoken answer")),
                org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.eq(3)))
                .thenReturn("Follow-up answer");

        service.continueConversation(
                "owner",
                fixture.session().getId(),
                new com.classicchatreader.model.AccountChatModels.ContinueRequest("Text follow-up", null),
                "text-follow-up-1");

        org.mockito.Mockito.verify(characterChatService).chat(
                org.mockito.ArgumentMatchers.eq(fixture.character().getId()),
                org.mockito.ArgumentMatchers.eq("Text follow-up"),
                org.mockito.ArgumentMatchers.argThat(history -> history.size() == 4
                        && history.get(2).content().equals("Spoken question")
                        && history.get(3).content().equals("Spoken answer")),
                org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.eq(3));
    }

    @Test
    void voiceCallTurnsRejectInvalidPayloadsAndUnavailableChats() {
        Fixture fixture = createSession("owner", "Book", "Author", "Character", BASE);
        addMessage(fixture.session(), "USER", "Earlier", BASE);
        flushAndClear();

        assertThatThrownBy(() -> service.appendVoiceCallTurns(
                "owner",
                fixture.session().getId(),
                new com.classicchatreader.model.AccountChatModels.VoiceCallTranscriptRequest(List.of())
        )).isInstanceOf(ChatHistoryValidationException.class)
                .extracting(ex -> ((ChatHistoryValidationException) ex).getCode())
                .isEqualTo("INVALID_VOICE_TURNS");

        var invalidRole = new com.classicchatreader.model.AccountChatModels.VoiceCallTranscriptRequest(List.of(
                new com.classicchatreader.model.AccountChatModels.VoiceCallTurn(
                        "voice-turn-1", "SYSTEM", "Not allowed")
        ));
        assertThatThrownBy(() -> service.appendVoiceCallTurns(
                "owner", fixture.session().getId(), invalidRole
        )).isInstanceOf(ChatHistoryValidationException.class)
                .extracting(ex -> ((ChatHistoryValidationException) ex).getCode())
                .isEqualTo("INVALID_VOICE_TURN");

        CharacterEntity character = entityManager.find(CharacterEntity.class, fixture.character().getId());
        character.setCharacterType(CharacterType.SECONDARY);
        entityManager.flush();
        var valid = new com.classicchatreader.model.AccountChatModels.VoiceCallTranscriptRequest(List.of(
                new com.classicchatreader.model.AccountChatModels.VoiceCallTurn(
                        "voice-turn-2", "USER", "Still not allowed")
        ));
        assertThatThrownBy(() -> service.appendVoiceCallTurns(
                "owner", fixture.session().getId(), valid
        )).isInstanceOf(ChatHistoryValidationException.class)
                .extracting(ex -> ((ChatHistoryValidationException) ex).getCode())
                .isEqualTo("CHAT_UNAVAILABLE");
    }

    private void assertInvalid(AccountChatHistoryService.ListRequest request) {
        assertThatThrownBy(() -> service.list("owner", request))
                .isInstanceOf(ChatHistoryValidationException.class);
    }

    private Fixture sessionWithUserMessage(
            String owner,
            String title,
            String author,
            String characterName,
            LocalDateTime lastMessageAt) {
        Fixture fixture = createSession(owner, title, author, characterName, lastMessageAt);
        addMessage(fixture.session(), "USER", "hello " + title, lastMessageAt);
        return fixture;
    }

    private Fixture createSession(
            String owner,
            String title,
            String author,
            String characterName,
            LocalDateTime lastMessageAt) {
        ensureUser(owner);
        BookEntity book = new BookEntity(title, author, "manual");
        book.setCharacterEnabled(true);
        entityManager.persist(book);

        ChapterEntity chapter = new ChapterEntity(0, "Chapter One");
        chapter.setBook(book);
        entityManager.persist(chapter);

        CharacterEntity character = new CharacterEntity(book, characterName, "Description", chapter, 0);
        character.setStatus(CharacterStatus.COMPLETED);
        character.setCharacterType(CharacterType.PRIMARY);
        character.setPortraitFilename(characterName + ".png");
        entityManager.persist(character);

        CharacterChatConversationEntity session = new CharacterChatConversationEntity();
        session.setUserId(owner);
        session.setCharacterId(character.getId());
        session.setContextChapterId(chapter.getId());
        session.setContextChapterIndex(0);
        session.setContextChapterTitle("Chapter One");
        session.setContextParagraphIndex(3);
        session.setCreatedAt(lastMessageAt.minusMinutes(1));
        session.setUpdatedAt(lastMessageAt);
        entityManager.persist(session);
        return new Fixture(book, chapter, character, session);
    }

    private void ensureUser(String userId) {
        if (!persistedUsers.add(userId)) return;
        entityManager.createNativeQuery("""
                        INSERT INTO users (id, email, created_at, updated_at)
                        VALUES (:id, :email, :createdAt, :updatedAt)
                        """)
                .setParameter("id", userId)
                .setParameter("email", userId + "@example.test")
                .setParameter("createdAt", BASE)
                .setParameter("updatedAt", BASE)
                .executeUpdate();
    }

    private void addMessage(
            CharacterChatConversationEntity session,
            String role,
            String content,
            LocalDateTime createdAt) {
        Long sequenceNumber = entityManager.createQuery("""
                        SELECT COUNT(message) FROM CharacterChatMessageEntity message
                        WHERE message.conversationId = :conversationId
                        """, Long.class)
                .setParameter("conversationId", session.getId())
                .getSingleResult();
        CharacterChatMessageEntity message = new CharacterChatMessageEntity();
        message.setConversationId(session.getId());
        message.setUserId(session.getUserId());
        message.setSequenceNumber(sequenceNumber);
        message.setRole(CharacterChatMessageRole.valueOf(role));
        message.setContent(content);
        message.setCreatedAt(createdAt);
        entityManager.persist(message);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private AccountChatHistoryService.ListRequest request(
            String limit,
            String cursor,
            String q,
            String bookId,
            String characterId,
            String activeAfter,
            String activeBefore,
            String sort) {
        return new AccountChatHistoryService.ListRequest(
                limit, cursor, q, bookId, characterId, activeAfter, activeBefore, sort
        );
    }

    private record Fixture(
            BookEntity book,
            ChapterEntity chapter,
            CharacterEntity character,
            CharacterChatConversationEntity session
    ) {
    }
}
