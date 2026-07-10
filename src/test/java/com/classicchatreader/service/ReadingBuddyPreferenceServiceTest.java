package com.classicchatreader.service;

import com.classicchatreader.config.ReadingBuddyProperties;
import com.classicchatreader.entity.ReadingBuddyPreferenceEntity;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ReadingBuddyPreferenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReadingBuddyPreferenceServiceTest {

    @Mock
    private ReadingBuddyPreferenceRepository preferenceRepository;

    @Mock
    private BookRepository bookRepository;

    private final Map<String, ReadingBuddyPreferenceEntity> store = new LinkedHashMap<>();

    private ReadingBuddyPreferenceService preferenceService;

    @BeforeEach
    void setUp() {
        store.clear();
        ReadingBuddyProperties properties = new ReadingBuddyProperties();
        properties.setQuietDefaultMinutes(45);
        ReadingBuddyPersonaCatalog catalog = new ReadingBuddyPersonaCatalog(properties);
        preferenceService = new ReadingBuddyPreferenceService(
                preferenceRepository,
                catalog,
                properties,
                bookRepository
        );

        org.mockito.Mockito.lenient().when(preferenceRepository.findByOwnerKeyAndBookId(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String key = storageKey(invocation.getArgument(0), invocation.getArgument(1));
                    return Optional.ofNullable(store.get(key));
                });
        org.mockito.Mockito.lenient().when(preferenceRepository.save(any(ReadingBuddyPreferenceEntity.class)))
                .thenAnswer(invocation -> {
                    ReadingBuddyPreferenceEntity entity = invocation.getArgument(0);
                    store.put(storageKey(entity.getOwnerKey(), entity.getBookId()), entity);
                    return entity;
                });
        org.mockito.Mockito.lenient().doAnswer(invocation -> {
            ReadingBuddyPreferenceEntity entity = invocation.getArgument(0);
            store.remove(storageKey(entity.getOwnerKey(), entity.getBookId()));
            return null;
        }).when(preferenceRepository).delete(any(ReadingBuddyPreferenceEntity.class));
    }

    private static String storageKey(String ownerKey, String bookId) {
        return ownerKey + "|" + bookId;
    }

    @Test
    void getEffective_withoutRows_returnsDefaults() {
        ReadingBuddyPreferenceService.EffectivePreferences prefs =
                preferenceService.getEffective("reader-1", null);

        assertFalse(prefs.enabled());
        assertEquals("rare", prefs.frequency());
        assertEquals(ReadingBuddyPersonaCatalog.CLOSE_READER, prefs.defaultPersonaId());
        assertEquals(ReadingBuddyPersonaCatalog.CLOSE_READER, prefs.personaId());
        assertEquals(ReadingBuddyPreferenceService.PERSONA_SOURCE_GLOBAL, prefs.personaSource());
        assertNull(prefs.suppressUntilEpochMs());
        assertNull(prefs.bookId());
    }

    @Test
    void getEffective_withBookOverride_returnsBookPersonaSource() {
        ReadingBuddyPreferenceEntity global = new ReadingBuddyPreferenceEntity();
        global.setOwnerKey("reader-1");
        global.setBookId(ReadingBuddyPreferenceService.GLOBAL_BOOK_ID);
        global.setEnabled(true);
        global.setFrequency("occasional");
        global.setDefaultPersonaId(ReadingBuddyPersonaCatalog.CLOSE_READER);
        global.setSuppressUntil(LocalDateTime.of(2026, 7, 8, 12, 0));
        store.put(storageKey("reader-1", ReadingBuddyPreferenceService.GLOBAL_BOOK_ID), global);

        ReadingBuddyPreferenceEntity book = new ReadingBuddyPreferenceEntity();
        book.setOwnerKey("reader-1");
        book.setBookId("book-1");
        book.setPersonaId(ReadingBuddyPersonaCatalog.HUMORIST);
        store.put(storageKey("reader-1", "book-1"), book);

        ReadingBuddyPreferenceService.EffectivePreferences prefs =
                preferenceService.getEffective("reader-1", "book-1");

        assertTrue(prefs.enabled());
        assertEquals("occasional", prefs.frequency());
        assertEquals(ReadingBuddyPersonaCatalog.CLOSE_READER, prefs.defaultPersonaId());
        assertEquals(ReadingBuddyPersonaCatalog.HUMORIST, prefs.personaId());
        assertEquals(ReadingBuddyPreferenceService.PERSONA_SOURCE_BOOK_OVERRIDE, prefs.personaSource());
        assertEquals(
                LocalDateTime.of(2026, 7, 8, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
                prefs.suppressUntilEpochMs()
        );
        assertEquals("book-1", prefs.bookId());
    }

    @Test
    void update_enablesGlobalAndSetsFrequency() {
        ReadingBuddyPreferenceService.EffectivePreferences prefs = preferenceService.update(
                "reader-1",
                new ReadingBuddyPreferenceService.PreferenceUpdate(
                        true,
                        "chatty",
                        ReadingBuddyPersonaCatalog.HISTORIAN,
                        null,
                        null,
                        null,
                        null,
                        null
                )
        );

        ReadingBuddyPreferenceEntity saved =
                store.get(storageKey("reader-1", ReadingBuddyPreferenceService.GLOBAL_BOOK_ID));
        assertEquals("reader-1", saved.getOwnerKey());
        assertEquals(ReadingBuddyPreferenceService.GLOBAL_BOOK_ID, saved.getBookId());
        assertTrue(saved.isEnabled());
        assertEquals("chatty", saved.getFrequency());
        assertEquals(ReadingBuddyPersonaCatalog.HISTORIAN, saved.getDefaultPersonaId());

        assertTrue(prefs.enabled());
        assertEquals("chatty", prefs.frequency());
        assertEquals(ReadingBuddyPersonaCatalog.HISTORIAN, prefs.defaultPersonaId());
        assertEquals(ReadingBuddyPreferenceService.PERSONA_SOURCE_GLOBAL, prefs.personaSource());
    }

    @Test
    void update_bookPersonaOverride_requiresKnownBookAndPersona() {
        when(bookRepository.existsById("book-1")).thenReturn(true);

        ReadingBuddyPreferenceService.EffectivePreferences prefs = preferenceService.update(
                "reader-1",
                new ReadingBuddyPreferenceService.PreferenceUpdate(
                        null,
                        null,
                        null,
                        ReadingBuddyPersonaCatalog.HUMORIST,
                        "book-1",
                        null,
                        null,
                        null
                )
        );

        ReadingBuddyPreferenceEntity saved = store.get(storageKey("reader-1", "book-1"));
        assertEquals("book-1", saved.getBookId());
        assertEquals(ReadingBuddyPersonaCatalog.HUMORIST, saved.getPersonaId());

        assertEquals(ReadingBuddyPersonaCatalog.HUMORIST, prefs.personaId());
        assertEquals(ReadingBuddyPreferenceService.PERSONA_SOURCE_BOOK_OVERRIDE, prefs.personaSource());
        assertEquals("book-1", prefs.bookId());
    }

    @Test
    void update_unknownPersona_throws() {
        assertThrows(IllegalArgumentException.class, () -> preferenceService.update(
                "reader-1",
                new ReadingBuddyPreferenceService.PreferenceUpdate(
                        null, null, "not_a_persona", null, null, null, null, null
                )
        ));
        verify(preferenceRepository, never()).save(any());
    }

    @Test
    void update_unknownBook_throwsBookNotFound() {
        when(bookRepository.existsById("missing")).thenReturn(false);

        assertThrows(ReadingBuddyPreferenceService.BookNotFoundException.class, () -> preferenceService.update(
                "reader-1",
                new ReadingBuddyPreferenceService.PreferenceUpdate(
                        null, null, null, ReadingBuddyPersonaCatalog.HUMORIST, "missing", null, null, null
                )
        ));
    }

    @Test
    void update_quietMinutes_setsSuppressUntil() {
        preferenceService.update(
                "reader-1",
                new ReadingBuddyPreferenceService.PreferenceUpdate(
                        null, null, null, null, null, null, null, 45
                )
        );

        ReadingBuddyPreferenceEntity saved =
                store.get(storageKey("reader-1", ReadingBuddyPreferenceService.GLOBAL_BOOK_ID));
        assertTrue(saved.getSuppressUntil().isAfter(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(40)));
        assertTrue(saved.getSuppressUntil().isBefore(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(50)));
    }

    @Test
    void update_clearBookPersona_deletesOverrideRow() {
        ReadingBuddyPreferenceEntity book = new ReadingBuddyPreferenceEntity();
        book.setOwnerKey("reader-1");
        book.setBookId("book-1");
        book.setPersonaId(ReadingBuddyPersonaCatalog.HUMORIST);
        store.put(storageKey("reader-1", "book-1"), book);

        preferenceService.update(
                "reader-1",
                new ReadingBuddyPreferenceService.PreferenceUpdate(
                        null, null, null, null, "book-1", true, null, null
                )
        );

        assertFalse(store.containsKey(storageKey("reader-1", "book-1")));
    }
}
