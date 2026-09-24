package com.classicchatreader.service;

import com.classicchatreader.entity.CuratedBookEntity;
import com.classicchatreader.repository.CuratedBookRepository;
import com.classicchatreader.service.CuratedCatalogService.CuratedCatalogBook;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** Maps {@code curated_books} rows (BL-072) to catalog records. Only Gutenberg rows are stored today. */
@Component
public class CuratedBookStore {

    public static final String SOURCE_GUTENBERG = "gutenberg";
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_INACTIVE = "inactive";

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    /** A catalog row with its membership status. */
    public record Entry(CuratedCatalogBook book, String status) {}

    private final CuratedBookRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CuratedBookStore(CuratedBookRepository repository) {
        this.repository = repository;
    }

    public List<CuratedCatalogBook> findActive() {
        return repository.findBySourceAndStatus(SOURCE_GUTENBERG, STATUS_ACTIVE).stream()
                .map(this::toBook)
                .toList();
    }

    public List<Entry> findAll() {
        return repository.findBySource(SOURCE_GUTENBERG).stream().map(this::toEntry).toList();
    }

    public Optional<Entry> find(int gutenbergId) {
        return row(gutenbergId).map(this::toEntry);
    }

    /** Inserts the title or overwrites its stored metadata, with the given status. */
    public Entry save(CuratedCatalogBook book, String status) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        CuratedBookEntity row = row(book.gutenbergId()).orElseGet(() -> {
            CuratedBookEntity created = new CuratedBookEntity();
            created.setId(SOURCE_GUTENBERG + ":" + book.gutenbergId());
            created.setSource(SOURCE_GUTENBERG);
            created.setSourceId(String.valueOf(book.gutenbergId()));
            created.setCreatedAt(now);
            return created;
        });
        row.setTitle(book.title());
        row.setAuthor(book.author());
        row.setPopularity(book.downloadCount());
        row.setSubjectsJson(writeList(book.subjects()));
        row.setBookshelvesJson(writeList(book.bookshelves()));
        row.setAliasesJson(writeList(book.aliases()));
        row.setStatus(status);
        row.setUpdatedAt(now);
        return toEntry(repository.saveAndFlush(row));
    }

    public Optional<Entry> updateStatus(int gutenbergId, String status) {
        return row(gutenbergId).map(row -> {
            row.setStatus(status);
            row.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            return toEntry(repository.saveAndFlush(row));
        });
    }

    private Optional<CuratedBookEntity> row(int gutenbergId) {
        return repository.findBySourceAndSourceId(SOURCE_GUTENBERG, String.valueOf(gutenbergId));
    }

    private Entry toEntry(CuratedBookEntity row) {
        return new Entry(toBook(row), row.getStatus());
    }

    private CuratedCatalogBook toBook(CuratedBookEntity row) {
        return new CuratedCatalogBook(
                Integer.parseInt(row.getSourceId()),
                row.getTitle(),
                row.getAuthor(),
                row.getPopularity(),
                readList(row.getSubjectsJson()),
                readList(row.getBookshelvesJson()),
                readList(row.getAliasesJson()));
    }

    private List<String> readList(String json) {
        try {
            return json == null || json.isBlank() ? List.of() : objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("curated_books holds malformed JSON: " + json, e);
        }
    }

    private String writeList(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
