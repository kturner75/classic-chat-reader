package com.classicchatreader.service;

import com.classicchatreader.config.ReadingBuddyProperties;
import com.classicchatreader.entity.ReadingBuddyPreferenceEntity;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ReadingBuddyPreferenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Global + per-book reading buddy preferences. Global rows use
 * {@link #GLOBAL_BOOK_ID} as {@code book_id} (no FK to books).
 */
@Service
public class ReadingBuddyPreferenceService {

    public static final String GLOBAL_BOOK_ID = "__global__";
    public static final String PERSONA_SOURCE_GLOBAL = "global";
    public static final String PERSONA_SOURCE_BOOK_OVERRIDE = "book_override";

    private static final Set<String> FREQUENCIES = Set.of("rare", "occasional", "chatty");

    private final ReadingBuddyPreferenceRepository preferenceRepository;
    private final ReadingBuddyPersonaCatalog personaCatalog;
    private final ReadingBuddyProperties properties;
    private final BookRepository bookRepository;

    public ReadingBuddyPreferenceService(
            ReadingBuddyPreferenceRepository preferenceRepository,
            ReadingBuddyPersonaCatalog personaCatalog,
            ReadingBuddyProperties properties,
            BookRepository bookRepository) {
        this.preferenceRepository = preferenceRepository;
        this.personaCatalog = personaCatalog;
        this.properties = properties;
        this.bookRepository = bookRepository;
    }

    @Transactional(readOnly = true)
    public EffectivePreferences getEffective(String ownerKey, String bookId) {
        ReadingBuddyPreferenceEntity global = preferenceRepository
                .findByOwnerKeyAndBookId(ownerKey, GLOBAL_BOOK_ID)
                .orElse(null);

        boolean enabled = global != null && global.isEnabled();
        String frequency = global != null && global.getFrequency() != null
                ? global.getFrequency()
                : "rare";
        String defaultPersonaId = resolveDefaultPersonaId(global);
        Long suppressUntilEpochMs = toEpochMillis(global != null ? global.getSuppressUntil() : null);

        String normalizedBookId = trimToNull(bookId);
        String personaId = defaultPersonaId;
        String personaSource = PERSONA_SOURCE_GLOBAL;
        String responseBookId = null;

        if (normalizedBookId != null && !GLOBAL_BOOK_ID.equals(normalizedBookId)) {
            responseBookId = normalizedBookId;
            Optional<ReadingBuddyPreferenceEntity> bookRow =
                    preferenceRepository.findByOwnerKeyAndBookId(ownerKey, normalizedBookId);
            if (bookRow.isPresent() && trimToNull(bookRow.get().getPersonaId()) != null) {
                personaId = bookRow.get().getPersonaId().trim();
                personaSource = PERSONA_SOURCE_BOOK_OVERRIDE;
            }
        }

        return new EffectivePreferences(
                enabled,
                frequency,
                defaultPersonaId,
                personaId,
                personaSource,
                suppressUntilEpochMs,
                responseBookId
        );
    }

    /**
     * Partial update. Global fields (enabled, frequency, defaultPersonaId, suppress) apply
     * to the {@link #GLOBAL_BOOK_ID} row. {@code bookId + personaId} upserts a book override;
     * {@code clearBookPersona} deletes the book override row's persona (or the row).
     */
    @Transactional
    public EffectivePreferences update(String ownerKey, PreferenceUpdate update) {
        if (update == null) {
            throw new IllegalArgumentException("Request body is required");
        }

        String bookId = trimToNull(update.bookId());
        boolean hasGlobalField = update.enabled() != null
                || update.frequency() != null
                || update.defaultPersonaId() != null
                || update.suppressUntilEpochMs() != null
                || update.quietMinutes() != null;

        if (bookId != null && GLOBAL_BOOK_ID.equals(bookId)) {
            throw new IllegalArgumentException("bookId must be a real book id, not the global sentinel");
        }

        if (Boolean.TRUE.equals(update.clearBookPersona())) {
            if (bookId == null) {
                throw new IllegalArgumentException("bookId is required when clearBookPersona is true");
            }
            clearBookPersona(ownerKey, bookId);
        } else if (bookId != null && update.personaId() != null) {
            upsertBookPersona(ownerKey, bookId, update.personaId());
        } else if (bookId != null && update.personaId() == null && !hasGlobalField) {
            // bookId alone with no persona change is a no-op for overrides
        }

        if (update.personaId() != null && bookId == null) {
            // persona without bookId updates global default
            ensureKnownPersona(update.personaId());
            ReadingBuddyPreferenceEntity global = getOrCreateGlobal(ownerKey);
            global.setDefaultPersonaId(update.personaId().trim());
            touch(global);
            preferenceRepository.save(global);
        }

        if (hasGlobalField) {
            ReadingBuddyPreferenceEntity global = getOrCreateGlobal(ownerKey);

            if (update.enabled() != null) {
                global.setEnabled(update.enabled());
            }
            if (update.frequency() != null) {
                global.setFrequency(normalizeFrequency(update.frequency()));
            }
            if (update.defaultPersonaId() != null) {
                ensureKnownPersona(update.defaultPersonaId());
                global.setDefaultPersonaId(update.defaultPersonaId().trim());
            }
            if (update.quietMinutes() != null) {
                int minutes = update.quietMinutes();
                if (minutes < 0) {
                    throw new IllegalArgumentException("quietMinutes must be non-negative");
                }
                if (minutes == 0) {
                    global.setSuppressUntil(null);
                } else {
                    global.setSuppressUntil(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(minutes));
                }
            } else if (update.suppressUntilEpochMs() != null) {
                long epochMs = update.suppressUntilEpochMs();
                if (epochMs <= 0) {
                    global.setSuppressUntil(null);
                } else {
                    global.setSuppressUntil(
                            LocalDateTime.ofEpochSecond(
                                    epochMs / 1000,
                                    (int) ((epochMs % 1000) * 1_000_000),
                                    ZoneOffset.UTC
                            )
                    );
                }
            }

            touch(global);
            preferenceRepository.save(global);
        }

        // When only defaultPersonaId was set via personaId path without other global fields,
        // ensure we still return effective prefs.
        return getEffective(ownerKey, bookId);
    }

    private void upsertBookPersona(String ownerKey, String bookId, String personaId) {
        ensureKnownPersona(personaId);
        if (!bookRepository.existsById(bookId)) {
            throw new BookNotFoundException(bookId);
        }

        ReadingBuddyPreferenceEntity row = preferenceRepository
                .findByOwnerKeyAndBookId(ownerKey, bookId)
                .orElseGet(() -> {
                    ReadingBuddyPreferenceEntity created = new ReadingBuddyPreferenceEntity();
                    created.setOwnerKey(ownerKey);
                    created.setBookId(bookId);
                    // Book rows only store persona override; global fields unused on this row.
                    created.setEnabled(false);
                    created.setFrequency("rare");
                    return created;
                });
        row.setPersonaId(personaId.trim());
        touch(row);
        preferenceRepository.save(row);
    }

    private void clearBookPersona(String ownerKey, String bookId) {
        preferenceRepository.findByOwnerKeyAndBookId(ownerKey, bookId).ifPresent(row -> {
            // Book override rows exist only for persona; delete whole row.
            preferenceRepository.delete(row);
        });
    }

    private ReadingBuddyPreferenceEntity getOrCreateGlobal(String ownerKey) {
        return preferenceRepository.findByOwnerKeyAndBookId(ownerKey, GLOBAL_BOOK_ID)
                .orElseGet(() -> {
                    ReadingBuddyPreferenceEntity created = new ReadingBuddyPreferenceEntity();
                    created.setOwnerKey(ownerKey);
                    created.setBookId(GLOBAL_BOOK_ID);
                    created.setEnabled(false);
                    created.setFrequency("rare");
                    created.setDefaultPersonaId(ReadingBuddyPersonaCatalog.CLOSE_READER);
                    return created;
                });
    }

    private String resolveDefaultPersonaId(ReadingBuddyPreferenceEntity global) {
        if (global != null && trimToNull(global.getDefaultPersonaId()) != null) {
            String id = global.getDefaultPersonaId().trim();
            if (personaCatalog.isKnown(id)) {
                return id;
            }
        }
        return ReadingBuddyPersonaCatalog.CLOSE_READER;
    }

    private void ensureKnownPersona(String personaId) {
        if (personaId == null || personaId.isBlank() || !personaCatalog.isKnown(personaId.trim())) {
            throw new IllegalArgumentException("Unknown personaId: " + personaId);
        }
    }

    private String normalizeFrequency(String frequency) {
        if (frequency == null || frequency.isBlank()) {
            throw new IllegalArgumentException("frequency is required");
        }
        String normalized = frequency.trim().toLowerCase(Locale.ROOT);
        if (!FREQUENCIES.contains(normalized)) {
            throw new IllegalArgumentException("frequency must be rare, occasional, or chatty");
        }
        return normalized;
    }

    private void touch(ReadingBuddyPreferenceEntity entity) {
        entity.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(entity.getUpdatedAt());
        }
    }

    private static Long toEpochMillis(LocalDateTime value) {
        if (value == null) {
            return null;
        }
        return value.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Quiet default used when client omits quietMinutes on a quiet action. */
    public int quietDefaultMinutes() {
        return properties.getQuietDefaultMinutes();
    }

    public record EffectivePreferences(
            boolean enabled,
            String frequency,
            String defaultPersonaId,
            String personaId,
            String personaSource,
            Long suppressUntilEpochMs,
            String bookId
    ) {
    }

    public record PreferenceUpdate(
            Boolean enabled,
            String frequency,
            String defaultPersonaId,
            String personaId,
            String bookId,
            Boolean clearBookPersona,
            Long suppressUntilEpochMs,
            Integer quietMinutes
    ) {
    }

    public static class BookNotFoundException extends RuntimeException {
        public BookNotFoundException(String bookId) {
            super("Book not found: " + bookId);
        }
    }
}
