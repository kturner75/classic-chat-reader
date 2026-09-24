package com.classicchatreader.service;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Service
public class CuratedCatalogService {

    /**
     * Curated landing catalog (BL-072): the {@code active} rows of {@code curated_books}, used for
     * landing-page discovery/search when catalog mode is "curated", batch pre-generation, and the
     * curated feature defaults on import. Membership only: marking a title inactive never deletes or
     * changes the book, its covers, characters or illustrations.
     *
     * <p>This is read once per book on library listings, so active rows are held in memory, reloaded
     * on the next read after any write here and at most {@link #SNAPSHOT_TTL_NANOS} after a change made elsewhere.
     */
    private static final long SNAPSHOT_TTL_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final int MAX_TEXT = 512;
    private static final int MAX_LIST_ITEMS = 25;
    private static final int MAX_LIST_ITEM_LENGTH = 200;

    /** {@code generation} is the write count the rows were loaded under; any later write makes it stale. */
    private record Snapshot(List<CuratedCatalogBook> books, Set<Integer> ids, long loadedAt, long generation) {}

    private final CuratedBookStore store;
    private final AtomicLong writes = new AtomicLong();
    private volatile Snapshot snapshot;

    public CuratedCatalogService(CuratedBookStore store) {
        this.store = store;
    }

    private static final Comparator<CuratedCatalogBook> POPULARITY_ORDER =
            Comparator.comparingInt(CuratedCatalogBook::downloadCount).reversed()
                    .thenComparing(CuratedCatalogBook::title, String.CASE_INSENSITIVE_ORDER);

    public List<CuratedCatalogBook> getPopularBooks() {
        return active().books();
    }

    public boolean isCuratedGutenbergId(int gutenbergId) {
        return active().ids().contains(gutenbergId);
    }

    /**
     * True when this stored book row is a curated Gutenberg title.
     * Used to expose reader features for books imported before they were
     * added to the curated catalog (flags are only written on first import).
     */
    public boolean isCuratedGutenbergSource(String source, String sourceId) {
        if (source == null || sourceId == null || sourceId.isBlank()) {
            return false;
        }
        if (!"gutenberg".equalsIgnoreCase(source.trim())) {
            return false;
        }
        try {
            return isCuratedGutenbergId(Integer.parseInt(sourceId.trim()));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public List<CuratedCatalogBook> search(String query) {
        String normalized = normalize(query);
        List<CuratedCatalogBook> books = getPopularBooks();
        if (normalized.isEmpty()) {
            return books;
        }
        return books.stream()
                .filter(book -> matches(book, normalized))
                .sorted(POPULARITY_ORDER)
                .toList();
    }

    /** Every curated row, active and inactive, most popular first. */
    public List<CuratedBookStore.Entry> listMembership() {
        return store.findAll().stream()
                .sorted(Comparator.comparing(CuratedBookStore.Entry::book, POPULARITY_ORDER))
                .toList();
    }

    public record AddResult(CuratedBookStore.Entry entry, boolean created) {}

    /**
     * Lists a title: inserts it, or reactivates an existing row. Fields left null on an existing row
     * keep their stored values; a new row needs a title and author.
     */
    public AddResult add(int gutenbergId, String title, String author, Integer popularity,
                         List<String> subjects, List<String> bookshelves, List<String> aliases) {
        requireGutenbergId(gutenbergId);
        Optional<CuratedBookStore.Entry> existing = store.find(gutenbergId);
        CuratedCatalogBook current = existing.map(CuratedBookStore.Entry::book).orElse(null);
        CuratedCatalogBook book = new CuratedCatalogBook(
                gutenbergId,
                text("title", title, current == null ? null : current.title()),
                text("author", author, current == null ? null : current.author()),
                popularity != null ? requireNonNegative(popularity) : current == null ? 0 : current.downloadCount(),
                list("subjects", subjects, current == null ? List.of() : current.subjects()),
                list("bookshelves", bookshelves, current == null ? List.of() : current.bookshelves()),
                list("aliases", aliases, current == null ? List.of() : current.aliases()));
        CuratedBookStore.Entry saved = store.save(book, CuratedBookStore.STATUS_ACTIVE);
        writes.incrementAndGet();
        return new AddResult(saved, existing.isEmpty());
    }

    /** Lists or unlists an existing title. Never touches the book row or any generated artifact. */
    public Optional<CuratedBookStore.Entry> setStatus(int gutenbergId, String status) {
        requireGutenbergId(gutenbergId);
        if (!CuratedBookStore.STATUS_ACTIVE.equals(status) && !CuratedBookStore.STATUS_INACTIVE.equals(status)) {
            throw new IllegalArgumentException("status must be \"active\" or \"inactive\"");
        }
        Optional<CuratedBookStore.Entry> updated = store.updateStatus(gutenbergId, status);
        writes.incrementAndGet();
        return updated;
    }

    private Snapshot active() {
        Snapshot current = snapshot;
        long generation = writes.get();
        if (current == null
                || current.generation() != generation
                || System.nanoTime() - current.loadedAt() > SNAPSHOT_TTL_NANOS) {
            // Read the generation before loading: a write that lands mid-load leaves this snapshot
            // stale, so the next call reloads instead of serving the pre-write rows until the TTL.
            List<CuratedCatalogBook> books = store.findActive().stream().sorted(POPULARITY_ORDER).toList();
            Set<Integer> ids = books.stream().map(CuratedCatalogBook::gutenbergId).collect(Collectors.toUnmodifiableSet());
            current = new Snapshot(books, ids, System.nanoTime(), generation);
            snapshot = current;
        }
        return current;
    }

    private static void requireGutenbergId(int gutenbergId) {
        if (gutenbergId <= 0) {
            throw new IllegalArgumentException("sourceId must be a positive Gutenberg number");
        }
    }

    private static int requireNonNegative(int popularity) {
        if (popularity < 0) {
            throw new IllegalArgumentException("popularity must not be negative");
        }
        return popularity;
    }

    private static String text(String field, String value, String fallback) {
        String resolved = value == null ? fallback : value.trim();
        if (resolved == null || resolved.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (resolved.length() > MAX_TEXT) {
            throw new IllegalArgumentException(field + " must be at most " + MAX_TEXT + " characters");
        }
        return resolved;
    }

    private static List<String> list(String field, List<String> values, List<String> fallback) {
        if (values == null) {
            return fallback;
        }
        List<String> cleaned = values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .toList();
        if (cleaned.size() > MAX_LIST_ITEMS || cleaned.stream().anyMatch(v -> v.length() > MAX_LIST_ITEM_LENGTH)) {
            throw new IllegalArgumentException(field + " allows at most " + MAX_LIST_ITEMS
                    + " entries of " + MAX_LIST_ITEM_LENGTH + " characters");
        }
        return cleaned;
    }

    private boolean matches(CuratedCatalogBook book, String normalizedQuery) {
        String normalizedTitle = normalize(book.title());
        String normalizedAuthor = normalize(book.author());
        if (normalizedTitle.contains(normalizedQuery)
                || normalizedAuthor.contains(normalizedQuery)
                || (normalizedTitle + " " + normalizedAuthor).contains(normalizedQuery)) {
            return true;
        }
        return book.aliases().stream()
                .map(this::normalize)
                .anyMatch(alias -> alias.contains(normalizedQuery));
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * @param subjects LCSH-like topical descriptors (not classroom search aliases)
     * @param bookshelves shelf / genre labels for discovery UI
     * @param aliases optional search aliases for contained short works or common titles
     *                that differ from the Gutenberg volume title
     */
    public record CuratedCatalogBook(
            int gutenbergId,
            String title,
            String author,
            int downloadCount,
            List<String> subjects,
            List<String> bookshelves,
            List<String> aliases
    ) {
        public CuratedCatalogBook {
            subjects = subjects == null ? List.of() : List.copyOf(subjects);
            bookshelves = bookshelves == null ? List.of() : List.copyOf(bookshelves);
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }

        public CuratedCatalogBook(
                int gutenbergId,
                String title,
                String author,
                int downloadCount,
                List<String> subjects,
                List<String> bookshelves
        ) {
            this(gutenbergId, title, author, downloadCount, subjects, bookshelves, List.of());
        }
    }
}
