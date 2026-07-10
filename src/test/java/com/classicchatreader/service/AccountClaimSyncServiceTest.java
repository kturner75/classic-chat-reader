package com.classicchatreader.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.ParagraphAnnotationEntity;
import com.classicchatreader.entity.QuizAttemptEntity;
import com.classicchatreader.entity.QuizTrophyEntity;
import com.classicchatreader.entity.ReadingBuddyMemoryEntity;
import com.classicchatreader.entity.ReadingBuddyMessageEntity;
import com.classicchatreader.entity.ReadingBuddyPreferenceEntity;
import com.classicchatreader.entity.UserReaderStateEntity;
import com.classicchatreader.model.AccountStateSnapshot;
import com.classicchatreader.repository.ParagraphAnnotationRepository;
import com.classicchatreader.repository.QuizAttemptRepository;
import com.classicchatreader.repository.QuizTrophyRepository;
import com.classicchatreader.repository.ReadingBuddyMemoryRepository;
import com.classicchatreader.repository.ReadingBuddyMessageRepository;
import com.classicchatreader.repository.ReadingBuddyPreferenceRepository;
import com.classicchatreader.repository.UserReaderClaimRepository;
import com.classicchatreader.repository.UserReaderStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountClaimSyncServiceTest {

    @Mock
    private ParagraphAnnotationRepository paragraphAnnotationRepository;

    @Mock
    private QuizAttemptRepository quizAttemptRepository;

    @Mock
    private QuizTrophyRepository quizTrophyRepository;

    @Mock
    private ReadingBuddyPreferenceRepository readingBuddyPreferenceRepository;

    @Mock
    private ReadingBuddyMessageRepository readingBuddyMessageRepository;

    @Mock
    private ReadingBuddyMemoryRepository readingBuddyMemoryRepository;

    @Mock
    private UserReaderStateRepository userReaderStateRepository;

    @Mock
    private UserReaderClaimRepository userReaderClaimRepository;

    private AccountClaimSyncService accountClaimSyncService;

    @BeforeEach
    void setUp() {
        accountClaimSyncService = new AccountClaimSyncService(
                paragraphAnnotationRepository,
                quizAttemptRepository,
                quizTrophyRepository,
                readingBuddyPreferenceRepository,
                readingBuddyMessageRepository,
                readingBuddyMemoryRepository,
                userReaderStateRepository,
                userReaderClaimRepository,
                new ObjectMapper()
        );
    }

    @Test
    void claimAndSync_claimsAnonymousDataAndMergesIncomingState() {
        String userId = "user-1";
        String readerId = "reader-cookie-1";

        BookEntity book = new BookEntity("Book", "Author", "gutenberg");
        book.setId("book-1");
        ChapterEntity chapter = new ChapterEntity(1, "Chapter 1");
        chapter.setId("chapter-1");
        chapter.setBook(book);

        ParagraphAnnotationEntity sourceAnnotation = new ParagraphAnnotationEntity();
        sourceAnnotation.setReaderId(readerId);
        sourceAnnotation.setBook(book);
        sourceAnnotation.setChapter(chapter);
        sourceAnnotation.setParagraphIndex(0);
        sourceAnnotation.setHighlighted(true);
        sourceAnnotation.setBookmarked(false);
        sourceAnnotation.setNoteText("new note");
        sourceAnnotation.setUpdatedAt(LocalDateTime.of(2026, 2, 18, 10, 0));

        ParagraphAnnotationEntity targetAnnotation = new ParagraphAnnotationEntity();
        targetAnnotation.setUserId(userId);
        targetAnnotation.setBook(book);
        targetAnnotation.setChapter(chapter);
        targetAnnotation.setParagraphIndex(0);
        targetAnnotation.setHighlighted(false);
        targetAnnotation.setBookmarked(false);
        targetAnnotation.setNoteText("old note");
        targetAnnotation.setUpdatedAt(LocalDateTime.of(2026, 2, 18, 9, 0));

        QuizAttemptEntity attempt = new QuizAttemptEntity();
        attempt.setReaderId(readerId);

        QuizTrophyEntity trophy = new QuizTrophyEntity();
        trophy.setReaderId(readerId);
        trophy.setBook(book);
        trophy.setCode("quiz_first_attempt");
        trophy.setTitle("First Checkpoint");
        trophy.setDescription("Complete your first chapter quiz.");

        when(userReaderClaimRepository.existsByUserIdAndReaderId(userId, readerId)).thenReturn(false);
        when(paragraphAnnotationRepository.findByReaderIdAndUserIdIsNull(readerId)).thenReturn(List.of(sourceAnnotation));
        when(paragraphAnnotationRepository.findByUserIdAndBook_IdAndChapter_IdAndParagraphIndex(
                userId,
                "book-1",
                "chapter-1",
                0
        )).thenReturn(Optional.of(targetAnnotation));
        when(quizAttemptRepository.findByReaderIdAndUserIdIsNull(readerId)).thenReturn(List.of(attempt));
        when(quizTrophyRepository.findByReaderIdAndUserIdIsNull(readerId)).thenReturn(List.of(trophy));
        when(quizTrophyRepository.findByBookIdAndUserIdAndCode("book-1", userId, "quiz_first_attempt"))
                .thenReturn(Optional.empty());
        when(readingBuddyPreferenceRepository.findByOwnerKey(readerId)).thenReturn(List.of());
        when(readingBuddyMessageRepository.findByOwnerKey(readerId)).thenReturn(List.of());
        when(readingBuddyMemoryRepository.findByOwnerKey(readerId)).thenReturn(List.of());
        when(userReaderStateRepository.findById(userId)).thenReturn(Optional.empty());
        when(userReaderStateRepository.save(any(UserReaderStateEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AccountStateSnapshot incoming = new AccountStateSnapshot(
                List.of("book-1"),
                Map.of(),
                new AccountStateSnapshot.ReaderPreferences(1.2, 1.7, 4.0, "warm", true, true, true, "2026-02-18T10:00:00Z"),
                Map.of("book-1", true)
        );

        AccountClaimSyncService.ClaimSyncResult result =
                accountClaimSyncService.claimAndSync(userId, readerId, incoming);

        assertTrue(result.claimApplied());
        assertEquals(List.of("book-1"), result.state().favoriteBookIds());
        assertEquals(true, result.state().recapOptOut().get("book-1"));

        verify(paragraphAnnotationRepository).save(targetAnnotation);
        verify(paragraphAnnotationRepository).delete(sourceAnnotation);

        ArgumentCaptor<QuizAttemptEntity> attemptCaptor = ArgumentCaptor.forClass(QuizAttemptEntity.class);
        verify(quizAttemptRepository).save(attemptCaptor.capture());
        assertEquals(userId, attemptCaptor.getValue().getUserId());

        ArgumentCaptor<UserReaderStateEntity> stateCaptor = ArgumentCaptor.forClass(UserReaderStateEntity.class);
        verify(userReaderStateRepository).save(stateCaptor.capture());
        assertEquals(userId, stateCaptor.getValue().getUserId());
        assertTrue(stateCaptor.getValue().getStateJson().contains("book-1"));
    }

    @Test
    void claimAndSync_whenClaimAlreadyRecorded_skipsAnonymousClaimPass() {
        String userId = "user-1";
        String readerId = "reader-cookie-1";

        UserReaderStateEntity existing = new UserReaderStateEntity();
        existing.setUserId(userId);
        existing.setStateJson("{" +
                "\"favoriteBookIds\":[\"book-existing\"]," +
                "\"bookActivity\":{}," +
                "\"readerPreferences\":null," +
                "\"recapOptOut\":{}" +
                "}");

        when(userReaderClaimRepository.existsByUserIdAndReaderId(userId, readerId)).thenReturn(true);
        when(userReaderStateRepository.findById(userId)).thenReturn(Optional.of(existing));
        when(userReaderStateRepository.save(any(UserReaderStateEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AccountClaimSyncService.ClaimSyncResult result =
                accountClaimSyncService.claimAndSync(userId, readerId, AccountStateSnapshot.empty());

        assertEquals(List.of("book-existing"), result.state().favoriteBookIds());
        verify(paragraphAnnotationRepository, never()).findByReaderIdAndUserIdIsNull(eq(readerId));
        verify(quizAttemptRepository, never()).findByReaderIdAndUserIdIsNull(eq(readerId));
        verify(quizTrophyRepository, never()).findByReaderIdAndUserIdIsNull(eq(readerId));
        verify(readingBuddyPreferenceRepository, never()).findByOwnerKey(eq(readerId));
        verify(readingBuddyMessageRepository, never()).findByOwnerKey(eq(readerId));
        verify(readingBuddyMemoryRepository, never()).findByOwnerKey(eq(readerId));
    }

    @Test
    void claimAndSync_rewritesBuddyPrefsWhenAccountHasNone() {
        String userId = "user-1";
        String readerId = "anon-reader";
        String userKey = "user:" + userId;

        ReadingBuddyPreferenceEntity anonGlobal = new ReadingBuddyPreferenceEntity();
        anonGlobal.setOwnerKey(readerId);
        anonGlobal.setBookId(ReadingBuddyPreferenceService.GLOBAL_BOOK_ID);
        anonGlobal.setEnabled(true);
        anonGlobal.setFrequency("chatty");
        anonGlobal.setDefaultPersonaId(ReadingBuddyPersonaCatalog.HUMORIST);
        anonGlobal.setUpdatedAt(LocalDateTime.of(2026, 7, 1, 10, 0));

        when(userReaderClaimRepository.existsByUserIdAndReaderId(userId, readerId)).thenReturn(false);
        when(paragraphAnnotationRepository.findByReaderIdAndUserIdIsNull(readerId)).thenReturn(List.of());
        when(quizAttemptRepository.findByReaderIdAndUserIdIsNull(readerId)).thenReturn(List.of());
        when(quizTrophyRepository.findByReaderIdAndUserIdIsNull(readerId)).thenReturn(List.of());
        when(readingBuddyPreferenceRepository.findByOwnerKey(readerId)).thenReturn(List.of(anonGlobal));
        when(readingBuddyPreferenceRepository.findByOwnerKey(userKey)).thenReturn(List.of());
        when(readingBuddyMessageRepository.findByOwnerKey(readerId)).thenReturn(List.of());
        when(readingBuddyMemoryRepository.findByOwnerKey(readerId)).thenReturn(List.of());
        when(userReaderStateRepository.findById(userId)).thenReturn(Optional.empty());
        when(userReaderStateRepository.save(any(UserReaderStateEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(readingBuddyPreferenceRepository.save(any(ReadingBuddyPreferenceEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        accountClaimSyncService.claimAndSync(userId, readerId, AccountStateSnapshot.empty());

        ArgumentCaptor<ReadingBuddyPreferenceEntity> captor =
                ArgumentCaptor.forClass(ReadingBuddyPreferenceEntity.class);
        verify(readingBuddyPreferenceRepository).save(captor.capture());
        assertEquals(userKey, captor.getValue().getOwnerKey());
        assertTrue(captor.getValue().isEnabled());
        assertEquals("chatty", captor.getValue().getFrequency());
    }

    @Test
    void claimAndSync_prefsLastWriteWins_accountWinsOnTie() {
        String userId = "user-1";
        String readerId = "anon-reader";
        String userKey = "user:" + userId;
        LocalDateTime sameTime = LocalDateTime.of(2026, 7, 1, 12, 0);

        ReadingBuddyPreferenceEntity anonGlobal = new ReadingBuddyPreferenceEntity();
        anonGlobal.setOwnerKey(readerId);
        anonGlobal.setBookId(ReadingBuddyPreferenceService.GLOBAL_BOOK_ID);
        anonGlobal.setEnabled(true);
        anonGlobal.setFrequency("chatty");
        anonGlobal.setDefaultPersonaId(ReadingBuddyPersonaCatalog.HUMORIST);
        anonGlobal.setUpdatedAt(sameTime);

        ReadingBuddyPreferenceEntity userGlobal = new ReadingBuddyPreferenceEntity();
        userGlobal.setOwnerKey(userKey);
        userGlobal.setBookId(ReadingBuddyPreferenceService.GLOBAL_BOOK_ID);
        userGlobal.setEnabled(false);
        userGlobal.setFrequency("rare");
        userGlobal.setDefaultPersonaId(ReadingBuddyPersonaCatalog.CLOSE_READER);
        userGlobal.setUpdatedAt(sameTime);

        when(userReaderClaimRepository.existsByUserIdAndReaderId(userId, readerId)).thenReturn(false);
        when(paragraphAnnotationRepository.findByReaderIdAndUserIdIsNull(readerId)).thenReturn(List.of());
        when(quizAttemptRepository.findByReaderIdAndUserIdIsNull(readerId)).thenReturn(List.of());
        when(quizTrophyRepository.findByReaderIdAndUserIdIsNull(readerId)).thenReturn(List.of());
        when(readingBuddyPreferenceRepository.findByOwnerKey(readerId)).thenReturn(List.of(anonGlobal));
        when(readingBuddyPreferenceRepository.findByOwnerKey(userKey)).thenReturn(List.of(userGlobal));
        when(readingBuddyMessageRepository.findByOwnerKey(readerId)).thenReturn(List.of());
        when(readingBuddyMemoryRepository.findByOwnerKey(readerId)).thenReturn(List.of());
        when(userReaderStateRepository.findById(userId)).thenReturn(Optional.empty());
        when(userReaderStateRepository.save(any(UserReaderStateEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        accountClaimSyncService.claimAndSync(userId, readerId, AccountStateSnapshot.empty());

        // Tie → account keeps its values; anon deleted; user row not overwritten.
        verify(readingBuddyPreferenceRepository).delete(anonGlobal);
        verify(readingBuddyPreferenceRepository, never()).save(userGlobal);
        assertFalseEnabledUnchanged(userGlobal);
    }

    @Test
    void claimAndSync_prefsLastWriteWins_anonNewerCopiesOntoUser() {
        String userId = "user-1";
        String readerId = "anon-reader";
        String userKey = "user:" + userId;
        LocalDateTime suppressUntil = LocalDateTime.of(2026, 7, 3, 15, 0);

        ReadingBuddyPreferenceEntity anonGlobal = new ReadingBuddyPreferenceEntity();
        anonGlobal.setOwnerKey(readerId);
        anonGlobal.setBookId(ReadingBuddyPreferenceService.GLOBAL_BOOK_ID);
        anonGlobal.setEnabled(true);
        anonGlobal.setFrequency("chatty");
        anonGlobal.setDefaultPersonaId(ReadingBuddyPersonaCatalog.HUMORIST);
        anonGlobal.setSuppressUntil(suppressUntil);
        anonGlobal.setUpdatedAt(LocalDateTime.of(2026, 7, 2, 12, 0));

        ReadingBuddyPreferenceEntity userGlobal = new ReadingBuddyPreferenceEntity();
        userGlobal.setOwnerKey(userKey);
        userGlobal.setBookId(ReadingBuddyPreferenceService.GLOBAL_BOOK_ID);
        userGlobal.setEnabled(false);
        userGlobal.setFrequency("rare");
        userGlobal.setDefaultPersonaId(ReadingBuddyPersonaCatalog.CLOSE_READER);
        userGlobal.setUpdatedAt(LocalDateTime.of(2026, 7, 1, 12, 0));

        when(userReaderClaimRepository.existsByUserIdAndReaderId(userId, readerId)).thenReturn(false);
        when(paragraphAnnotationRepository.findByReaderIdAndUserIdIsNull(readerId)).thenReturn(List.of());
        when(quizAttemptRepository.findByReaderIdAndUserIdIsNull(readerId)).thenReturn(List.of());
        when(quizTrophyRepository.findByReaderIdAndUserIdIsNull(readerId)).thenReturn(List.of());
        when(readingBuddyPreferenceRepository.findByOwnerKey(readerId)).thenReturn(List.of(anonGlobal));
        when(readingBuddyPreferenceRepository.findByOwnerKey(userKey)).thenReturn(List.of(userGlobal));
        when(readingBuddyMessageRepository.findByOwnerKey(readerId)).thenReturn(List.of());
        when(readingBuddyMemoryRepository.findByOwnerKey(readerId)).thenReturn(List.of());
        when(userReaderStateRepository.findById(userId)).thenReturn(Optional.empty());
        when(userReaderStateRepository.save(any(UserReaderStateEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(readingBuddyPreferenceRepository.save(any(ReadingBuddyPreferenceEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        accountClaimSyncService.claimAndSync(userId, readerId, AccountStateSnapshot.empty());

        ArgumentCaptor<ReadingBuddyPreferenceEntity> captor =
                ArgumentCaptor.forClass(ReadingBuddyPreferenceEntity.class);
        verify(readingBuddyPreferenceRepository).save(captor.capture());
        ReadingBuddyPreferenceEntity saved = captor.getValue();
        assertEquals(userKey, saved.getOwnerKey());
        assertTrue(saved.isEnabled());
        assertEquals("chatty", saved.getFrequency());
        assertEquals(ReadingBuddyPersonaCatalog.HUMORIST, saved.getDefaultPersonaId());
        assertEquals(suppressUntil, saved.getSuppressUntil());
        assertEquals(LocalDateTime.of(2026, 7, 2, 12, 0), saved.getUpdatedAt());
        verify(readingBuddyPreferenceRepository).delete(anonGlobal);
    }

    private static void assertFalseEnabledUnchanged(ReadingBuddyPreferenceEntity userGlobal) {
        assertEquals(false, userGlobal.isEnabled());
        assertEquals("rare", userGlobal.getFrequency());
        assertEquals(ReadingBuddyPersonaCatalog.CLOSE_READER, userGlobal.getDefaultPersonaId());
    }

    @Test
    void claimAndSync_messagesBulkRewriteWhenUserHasNoHistory() {
        String userId = "user-1";
        String readerId = "anon-reader";
        String userKey = "user:" + userId;

        ReadingBuddyMessageEntity anonMsg = new ReadingBuddyMessageEntity();
        anonMsg.setOwnerKey(readerId);
        anonMsg.setBookId("book-1");
        anonMsg.setPersonaId(ReadingBuddyPersonaCatalog.HUMORIST);
        anonMsg.setRole("buddy");
        anonMsg.setKind("proactive");
        anonMsg.setContent("A witty aside.");
        anonMsg.setChapterIndex(1);
        anonMsg.setParagraphIndex(2);
        anonMsg.setProactivePositionKey("1:2");
        anonMsg.setContentHash(ReadingBuddyMessageEntity.computeContentHash("buddy", "proactive", "A witty aside."));
        anonMsg.setCreatedAt(LocalDateTime.of(2026, 7, 1, 9, 0));

        when(userReaderClaimRepository.existsByUserIdAndReaderId(userId, readerId)).thenReturn(false);
        when(paragraphAnnotationRepository.findByReaderIdAndUserIdIsNull(readerId)).thenReturn(List.of());
        when(quizAttemptRepository.findByReaderIdAndUserIdIsNull(readerId)).thenReturn(List.of());
        when(quizTrophyRepository.findByReaderIdAndUserIdIsNull(readerId)).thenReturn(List.of());
        when(readingBuddyPreferenceRepository.findByOwnerKey(readerId)).thenReturn(List.of());
        when(readingBuddyMessageRepository.findByOwnerKey(readerId)).thenReturn(List.of(anonMsg));
        when(readingBuddyMessageRepository.countByOwnerKeyAndBookIdAndPersonaId(
                userKey, "book-1", ReadingBuddyPersonaCatalog.HUMORIST)).thenReturn(0L);
        when(readingBuddyMemoryRepository.findByOwnerKey(readerId)).thenReturn(List.of());
        when(userReaderStateRepository.findById(userId)).thenReturn(Optional.empty());
        when(userReaderStateRepository.save(any(UserReaderStateEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(readingBuddyMessageRepository.save(any(ReadingBuddyMessageEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        accountClaimSyncService.claimAndSync(userId, readerId, AccountStateSnapshot.empty());

        ArgumentCaptor<ReadingBuddyMessageEntity> captor =
                ArgumentCaptor.forClass(ReadingBuddyMessageEntity.class);
        verify(readingBuddyMessageRepository).save(captor.capture());
        assertEquals(userKey, captor.getValue().getOwnerKey());
        assertEquals(anonMsg.getContentHash(), captor.getValue().getContentHash());
    }

    @Test
    void claimAndSync_messagesAppendDedupeByContentHash() {
        String userId = "user-1";
        String readerId = "anon-reader";
        String userKey = "user:" + userId;

        String duplicateHash = ReadingBuddyMessageEntity.computeContentHash("user", "chat", "same text");
        String uniqueHash = ReadingBuddyMessageEntity.computeContentHash("user", "chat", "only anon");

        ReadingBuddyMessageEntity anonDup = new ReadingBuddyMessageEntity();
        anonDup.setOwnerKey(readerId);
        anonDup.setBookId("book-1");
        anonDup.setPersonaId(ReadingBuddyPersonaCatalog.HUMORIST);
        anonDup.setRole("user");
        anonDup.setKind("chat");
        anonDup.setContent("same text");
        anonDup.setChapterIndex(1);
        anonDup.setParagraphIndex(0);
        anonDup.setContentHash(duplicateHash);
        anonDup.setCreatedAt(LocalDateTime.of(2026, 7, 1, 8, 0));

        ReadingBuddyMessageEntity anonUnique = new ReadingBuddyMessageEntity();
        anonUnique.setOwnerKey(readerId);
        anonUnique.setBookId("book-1");
        anonUnique.setPersonaId(ReadingBuddyPersonaCatalog.HUMORIST);
        anonUnique.setRole("user");
        anonUnique.setKind("chat");
        anonUnique.setContent("only anon");
        anonUnique.setChapterIndex(1);
        anonUnique.setParagraphIndex(1);
        anonUnique.setContentHash(uniqueHash);
        anonUnique.setCreatedAt(LocalDateTime.of(2026, 7, 1, 9, 0));

        ReadingBuddyMessageEntity userExisting = new ReadingBuddyMessageEntity();
        userExisting.setOwnerKey(userKey);
        userExisting.setBookId("book-1");
        userExisting.setPersonaId(ReadingBuddyPersonaCatalog.HUMORIST);
        userExisting.setRole("user");
        userExisting.setKind("chat");
        userExisting.setContent("same text");
        userExisting.setChapterIndex(1);
        userExisting.setParagraphIndex(0);
        userExisting.setContentHash(duplicateHash);
        userExisting.setCreatedAt(LocalDateTime.of(2026, 7, 1, 7, 0));

        when(userReaderClaimRepository.existsByUserIdAndReaderId(userId, readerId)).thenReturn(false);
        when(paragraphAnnotationRepository.findByReaderIdAndUserIdIsNull(readerId)).thenReturn(List.of());
        when(quizAttemptRepository.findByReaderIdAndUserIdIsNull(readerId)).thenReturn(List.of());
        when(quizTrophyRepository.findByReaderIdAndUserIdIsNull(readerId)).thenReturn(List.of());
        when(readingBuddyPreferenceRepository.findByOwnerKey(readerId)).thenReturn(List.of());
        when(readingBuddyMessageRepository.findByOwnerKey(readerId)).thenReturn(List.of(anonDup, anonUnique));
        when(readingBuddyMessageRepository.countByOwnerKeyAndBookIdAndPersonaId(
                userKey, "book-1", ReadingBuddyPersonaCatalog.HUMORIST)).thenReturn(1L);
        when(readingBuddyMessageRepository.findByOwnerKeyAndBookIdAndPersonaIdOrderByCreatedAtAsc(
                userKey, "book-1", ReadingBuddyPersonaCatalog.HUMORIST)).thenReturn(List.of(userExisting));
        when(readingBuddyMemoryRepository.findByOwnerKey(readerId)).thenReturn(List.of());
        when(userReaderStateRepository.findById(userId)).thenReturn(Optional.empty());
        when(userReaderStateRepository.save(any(UserReaderStateEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(readingBuddyMessageRepository.save(any(ReadingBuddyMessageEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        accountClaimSyncService.claimAndSync(userId, readerId, AccountStateSnapshot.empty());

        verify(readingBuddyMessageRepository).delete(anonDup);
        ArgumentCaptor<ReadingBuddyMessageEntity> captor =
                ArgumentCaptor.forClass(ReadingBuddyMessageEntity.class);
        verify(readingBuddyMessageRepository).save(captor.capture());
        assertEquals(userKey, captor.getValue().getOwnerKey());
        assertEquals(uniqueHash, captor.getValue().getContentHash());
    }

    @Test
    void claimAndSync_memoriesKeepNewerSummary() {
        String userId = "user-1";
        String readerId = "anon-reader";
        String userKey = "user:" + userId;

        ReadingBuddyMemoryEntity anonMem = new ReadingBuddyMemoryEntity();
        anonMem.setOwnerKey(readerId);
        anonMem.setBookId("book-1");
        anonMem.setPersonaId(ReadingBuddyPersonaCatalog.HISTORIAN);
        anonMem.setSummaryText("newer summary");
        anonMem.setSummaryVersion(2);
        anonMem.setSummaryMaxChapterIndex(5);
        anonMem.setSummaryMaxParagraphIndex(3);
        anonMem.setUpdatedAt(LocalDateTime.of(2026, 7, 2, 10, 0));

        ReadingBuddyMemoryEntity userMem = new ReadingBuddyMemoryEntity();
        userMem.setOwnerKey(userKey);
        userMem.setBookId("book-1");
        userMem.setPersonaId(ReadingBuddyPersonaCatalog.HISTORIAN);
        userMem.setSummaryText("older summary");
        userMem.setSummaryVersion(1);
        userMem.setUpdatedAt(LocalDateTime.of(2026, 7, 1, 10, 0));

        when(userReaderClaimRepository.existsByUserIdAndReaderId(userId, readerId)).thenReturn(false);
        when(paragraphAnnotationRepository.findByReaderIdAndUserIdIsNull(readerId)).thenReturn(List.of());
        when(quizAttemptRepository.findByReaderIdAndUserIdIsNull(readerId)).thenReturn(List.of());
        when(quizTrophyRepository.findByReaderIdAndUserIdIsNull(readerId)).thenReturn(List.of());
        when(readingBuddyPreferenceRepository.findByOwnerKey(readerId)).thenReturn(List.of());
        when(readingBuddyMessageRepository.findByOwnerKey(readerId)).thenReturn(List.of());
        when(readingBuddyMemoryRepository.findByOwnerKey(readerId)).thenReturn(List.of(anonMem));
        when(readingBuddyMemoryRepository.findByOwnerKeyAndBookIdAndPersonaId(
                userKey, "book-1", ReadingBuddyPersonaCatalog.HISTORIAN)).thenReturn(Optional.of(userMem));
        when(userReaderStateRepository.findById(userId)).thenReturn(Optional.empty());
        when(userReaderStateRepository.save(any(UserReaderStateEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(readingBuddyMemoryRepository.save(any(ReadingBuddyMemoryEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        accountClaimSyncService.claimAndSync(userId, readerId, AccountStateSnapshot.empty());

        ArgumentCaptor<ReadingBuddyMemoryEntity> captor =
                ArgumentCaptor.forClass(ReadingBuddyMemoryEntity.class);
        verify(readingBuddyMemoryRepository).save(captor.capture());
        assertEquals("newer summary", captor.getValue().getSummaryText());
        assertEquals(2, captor.getValue().getSummaryVersion());
        assertEquals(Integer.valueOf(5), captor.getValue().getSummaryMaxChapterIndex());
        verify(readingBuddyMemoryRepository).delete(anonMem);
    }

    @Test
    void claimAndSync_proactivePosition_anonEarlier_copiesOntoUserAndDeletesAnon() {
        String userId = "user-1";
        String readerId = "anon-reader";
        String userKey = "user:" + userId;

        ReadingBuddyMessageEntity anon = new ReadingBuddyMessageEntity();
        anon.setId("anon-msg-A");
        anon.setOwnerKey(readerId);
        anon.setBookId("book-1");
        anon.setPersonaId(ReadingBuddyPersonaCatalog.HUMORIST);
        anon.setRole("buddy");
        anon.setKind("proactive");
        anon.setContent("earlier anon comment");
        anon.setChapterIndex(2);
        anon.setParagraphIndex(4);
        anon.setProactivePositionKey("2:4");
        anon.setContentHash(ReadingBuddyMessageEntity.computeContentHash(
                "buddy", "proactive", "earlier anon comment"));
        anon.setCreatedAt(LocalDateTime.of(2026, 7, 1, 8, 0));

        ReadingBuddyMessageEntity user = new ReadingBuddyMessageEntity();
        user.setId("user-msg-U");
        user.setOwnerKey(userKey);
        user.setBookId("book-1");
        user.setPersonaId(ReadingBuddyPersonaCatalog.HUMORIST);
        user.setRole("buddy");
        user.setKind("proactive");
        user.setContent("later user comment");
        user.setChapterIndex(2);
        user.setParagraphIndex(4);
        user.setProactivePositionKey("2:4");
        user.setContentHash(ReadingBuddyMessageEntity.computeContentHash(
                "buddy", "proactive", "later user comment"));
        user.setCreatedAt(LocalDateTime.of(2026, 7, 1, 10, 0));

        when(userReaderClaimRepository.existsByUserIdAndReaderId(userId, readerId)).thenReturn(false);
        when(paragraphAnnotationRepository.findByReaderIdAndUserIdIsNull(readerId)).thenReturn(List.of());
        when(quizAttemptRepository.findByReaderIdAndUserIdIsNull(readerId)).thenReturn(List.of());
        when(quizTrophyRepository.findByReaderIdAndUserIdIsNull(readerId)).thenReturn(List.of());
        when(readingBuddyPreferenceRepository.findByOwnerKey(readerId)).thenReturn(List.of());
        when(readingBuddyMessageRepository.findByOwnerKey(readerId)).thenReturn(List.of(anon));
        when(readingBuddyMessageRepository.countByOwnerKeyAndBookIdAndPersonaId(
                userKey, "book-1", ReadingBuddyPersonaCatalog.HUMORIST)).thenReturn(1L);
        when(readingBuddyMessageRepository.findByOwnerKeyAndBookIdAndPersonaIdOrderByCreatedAtAsc(
                userKey, "book-1", ReadingBuddyPersonaCatalog.HUMORIST)).thenReturn(List.of(user));
        when(readingBuddyMemoryRepository.findByOwnerKey(readerId)).thenReturn(List.of());
        when(userReaderStateRepository.findById(userId)).thenReturn(Optional.empty());
        when(userReaderStateRepository.save(any(UserReaderStateEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(readingBuddyMessageRepository.save(any(ReadingBuddyMessageEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        accountClaimSyncService.claimAndSync(userId, readerId, AccountStateSnapshot.empty());

        ArgumentCaptor<ReadingBuddyMessageEntity> captor =
                ArgumentCaptor.forClass(ReadingBuddyMessageEntity.class);
        verify(readingBuddyMessageRepository).save(captor.capture());
        ReadingBuddyMessageEntity saved = captor.getValue();
        assertEquals("user-msg-U", saved.getId());
        assertEquals("earlier anon comment", saved.getContent());
        assertEquals(anon.getContentHash(), saved.getContentHash());
        assertEquals(LocalDateTime.of(2026, 7, 1, 8, 0), saved.getCreatedAt());
        verify(readingBuddyMessageRepository).delete(anon);
    }

    @Test
    void claimAndSync_proactivePosition_userEarlier_deletesAnonLeavesUserUnchanged() {
        String userId = "user-1";
        String readerId = "anon-reader";
        String userKey = "user:" + userId;

        ReadingBuddyMessageEntity anon = new ReadingBuddyMessageEntity();
        anon.setId("anon-msg-A");
        anon.setOwnerKey(readerId);
        anon.setBookId("book-1");
        anon.setPersonaId(ReadingBuddyPersonaCatalog.HUMORIST);
        anon.setRole("buddy");
        anon.setKind("proactive");
        anon.setContent("later anon comment");
        anon.setChapterIndex(2);
        anon.setParagraphIndex(4);
        anon.setProactivePositionKey("2:4");
        anon.setContentHash(ReadingBuddyMessageEntity.computeContentHash(
                "buddy", "proactive", "later anon comment"));
        anon.setCreatedAt(LocalDateTime.of(2026, 7, 1, 11, 0));

        ReadingBuddyMessageEntity user = new ReadingBuddyMessageEntity();
        user.setId("user-msg-U");
        user.setOwnerKey(userKey);
        user.setBookId("book-1");
        user.setPersonaId(ReadingBuddyPersonaCatalog.HUMORIST);
        user.setRole("buddy");
        user.setKind("proactive");
        user.setContent("earlier user comment");
        user.setChapterIndex(2);
        user.setParagraphIndex(4);
        user.setProactivePositionKey("2:4");
        String userHash = ReadingBuddyMessageEntity.computeContentHash(
                "buddy", "proactive", "earlier user comment");
        user.setContentHash(userHash);
        user.setCreatedAt(LocalDateTime.of(2026, 7, 1, 9, 0));

        when(userReaderClaimRepository.existsByUserIdAndReaderId(userId, readerId)).thenReturn(false);
        when(paragraphAnnotationRepository.findByReaderIdAndUserIdIsNull(readerId)).thenReturn(List.of());
        when(quizAttemptRepository.findByReaderIdAndUserIdIsNull(readerId)).thenReturn(List.of());
        when(quizTrophyRepository.findByReaderIdAndUserIdIsNull(readerId)).thenReturn(List.of());
        when(readingBuddyPreferenceRepository.findByOwnerKey(readerId)).thenReturn(List.of());
        when(readingBuddyMessageRepository.findByOwnerKey(readerId)).thenReturn(List.of(anon));
        when(readingBuddyMessageRepository.countByOwnerKeyAndBookIdAndPersonaId(
                userKey, "book-1", ReadingBuddyPersonaCatalog.HUMORIST)).thenReturn(1L);
        when(readingBuddyMessageRepository.findByOwnerKeyAndBookIdAndPersonaIdOrderByCreatedAtAsc(
                userKey, "book-1", ReadingBuddyPersonaCatalog.HUMORIST)).thenReturn(List.of(user));
        when(readingBuddyMemoryRepository.findByOwnerKey(readerId)).thenReturn(List.of());
        when(userReaderStateRepository.findById(userId)).thenReturn(Optional.empty());
        when(userReaderStateRepository.save(any(UserReaderStateEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        accountClaimSyncService.claimAndSync(userId, readerId, AccountStateSnapshot.empty());

        verify(readingBuddyMessageRepository).delete(anon);
        verify(readingBuddyMessageRepository, never()).save(any(ReadingBuddyMessageEntity.class));
        assertEquals("earlier user comment", user.getContent());
        assertEquals(userHash, user.getContentHash());
        assertEquals(LocalDateTime.of(2026, 7, 1, 9, 0), user.getCreatedAt());
    }

    @Test
    void claimAndSync_memoryLastMessageId_remapsDeletedAnonToSurvivingUserId() {
        String userId = "user-1";
        String readerId = "anon-reader";
        String userKey = "user:" + userId;

        ReadingBuddyMessageEntity anon = new ReadingBuddyMessageEntity();
        anon.setId("anon-msg-A");
        anon.setOwnerKey(readerId);
        anon.setBookId("book-1");
        anon.setPersonaId(ReadingBuddyPersonaCatalog.HISTORIAN);
        anon.setRole("buddy");
        anon.setKind("proactive");
        anon.setContent("anon at position");
        anon.setChapterIndex(3);
        anon.setParagraphIndex(1);
        anon.setProactivePositionKey("3:1");
        anon.setContentHash(ReadingBuddyMessageEntity.computeContentHash(
                "buddy", "proactive", "anon at position"));
        anon.setCreatedAt(LocalDateTime.of(2026, 7, 1, 10, 0));

        ReadingBuddyMessageEntity user = new ReadingBuddyMessageEntity();
        user.setId("user-msg-U");
        user.setOwnerKey(userKey);
        user.setBookId("book-1");
        user.setPersonaId(ReadingBuddyPersonaCatalog.HISTORIAN);
        user.setRole("buddy");
        user.setKind("proactive");
        user.setContent("user earlier at same position");
        user.setChapterIndex(3);
        user.setParagraphIndex(1);
        user.setProactivePositionKey("3:1");
        user.setContentHash(ReadingBuddyMessageEntity.computeContentHash(
                "buddy", "proactive", "user earlier at same position"));
        user.setCreatedAt(LocalDateTime.of(2026, 7, 1, 8, 0));

        ReadingBuddyMemoryEntity anonMem = new ReadingBuddyMemoryEntity();
        anonMem.setOwnerKey(readerId);
        anonMem.setBookId("book-1");
        anonMem.setPersonaId(ReadingBuddyPersonaCatalog.HISTORIAN);
        anonMem.setSummaryText("summary pointing at deleted anon message");
        anonMem.setSummaryVersion(3);
        anonMem.setLastMessageId("anon-msg-A");
        anonMem.setUpdatedAt(LocalDateTime.of(2026, 7, 2, 12, 0));

        ReadingBuddyMemoryEntity userMem = new ReadingBuddyMemoryEntity();
        userMem.setOwnerKey(userKey);
        userMem.setBookId("book-1");
        userMem.setPersonaId(ReadingBuddyPersonaCatalog.HISTORIAN);
        userMem.setSummaryText("older");
        userMem.setSummaryVersion(1);
        userMem.setLastMessageId("user-msg-U");
        userMem.setUpdatedAt(LocalDateTime.of(2026, 7, 1, 12, 0));

        when(userReaderClaimRepository.existsByUserIdAndReaderId(userId, readerId)).thenReturn(false);
        when(paragraphAnnotationRepository.findByReaderIdAndUserIdIsNull(readerId)).thenReturn(List.of());
        when(quizAttemptRepository.findByReaderIdAndUserIdIsNull(readerId)).thenReturn(List.of());
        when(quizTrophyRepository.findByReaderIdAndUserIdIsNull(readerId)).thenReturn(List.of());
        when(readingBuddyPreferenceRepository.findByOwnerKey(readerId)).thenReturn(List.of());
        when(readingBuddyMessageRepository.findByOwnerKey(readerId)).thenReturn(List.of(anon));
        when(readingBuddyMessageRepository.countByOwnerKeyAndBookIdAndPersonaId(
                userKey, "book-1", ReadingBuddyPersonaCatalog.HISTORIAN)).thenReturn(1L);
        when(readingBuddyMessageRepository.findByOwnerKeyAndBookIdAndPersonaIdOrderByCreatedAtAsc(
                userKey, "book-1", ReadingBuddyPersonaCatalog.HISTORIAN)).thenReturn(List.of(user));
        when(readingBuddyMemoryRepository.findByOwnerKey(readerId)).thenReturn(List.of(anonMem));
        when(readingBuddyMemoryRepository.findByOwnerKeyAndBookIdAndPersonaId(
                userKey, "book-1", ReadingBuddyPersonaCatalog.HISTORIAN)).thenReturn(Optional.of(userMem));
        when(userReaderStateRepository.findById(userId)).thenReturn(Optional.empty());
        when(userReaderStateRepository.save(any(UserReaderStateEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(readingBuddyMemoryRepository.save(any(ReadingBuddyMemoryEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        accountClaimSyncService.claimAndSync(userId, readerId, AccountStateSnapshot.empty());

        verify(readingBuddyMessageRepository).delete(anon);
        ArgumentCaptor<ReadingBuddyMemoryEntity> captor =
                ArgumentCaptor.forClass(ReadingBuddyMemoryEntity.class);
        verify(readingBuddyMemoryRepository).save(captor.capture());
        // Anon memory was newer; last_message_id remapped A → U (not left dangling as A).
        assertEquals("user-msg-U", captor.getValue().getLastMessageId());
        assertEquals("summary pointing at deleted anon message", captor.getValue().getSummaryText());
    }
}
