package com.classicchatreader.service;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class SearchService {

    private static final int SNIPPET_MAX_LENGTH = 140;
    private final Directory index;
    private final StandardAnalyzer analyzer;
    /**
     * One writer for the life of the index. Opening a writer per paragraph made the startup
     * rebuild take ~15 minutes in prod; IndexWriter is thread-safe, and searches see whatever was
     * last committed.
     */
    private final IndexWriter writer;

    public SearchService() {
        this.index = new ByteBuffersDirectory();
        this.analyzer = new StandardAnalyzer();
        try {
            this.writer = new IndexWriter(index, new IndexWriterConfig(analyzer));
            this.writer.commit();
        } catch (IOException e) {
            throw new IllegalStateException("Could not open the in-memory search index", e);
        }
    }

    @PreDestroy
    void close() throws IOException {
        writer.close();
    }

    /** A paragraph to index: its chapter, position and text. */
    public record ParagraphText(String chapterId, int paragraphIndex, String content) {}

    public void indexBook(String bookId, String title, String author) throws IOException {
        writer.addDocument(bookDocument(bookId, title, author));
        writer.commit();
    }

    public void indexParagraph(String bookId, String chapterId, int paragraphIndex, String content) throws IOException {
        writer.addDocument(paragraphDocument(bookId, new ParagraphText(chapterId, paragraphIndex, content)));
        writer.commit();
    }

    /** Replaces everything indexed for this book with its title, author and paragraphs, in one commit. */
    public void indexBookContent(String bookId, String title, String author, List<ParagraphText> paragraphs)
            throws IOException {
        addBookContent(bookId, title, author, paragraphs);
        writer.commit();
    }

    /** Receives each book's content during {@link #rebuild}. */
    public interface RebuildSink {
        void addBook(String bookId, String title, String author, List<ParagraphText> paragraphs) throws IOException;
    }

    /** Callback that feeds every book to the rebuild. */
    public interface RebuildSource {
        void feed(RebuildSink sink) throws IOException;
    }

    /**
     * Rebuilds the whole index in one pass and one commit. Searches keep seeing the previous
     * commit until it finishes (unless a book is saved meanwhile, whose commit would also publish
     * the rebuild so far; the rebuild still completes).
     */
    public void rebuild(RebuildSource source) throws IOException {
        writer.deleteAll();
        source.feed(this::addBookContent);
        writer.commit();
    }

    private void addBookContent(String bookId, String title, String author, List<ParagraphText> paragraphs)
            throws IOException {
        writer.deleteDocuments(new Term("bookId", bookId));
        List<Document> docs = new ArrayList<>(paragraphs.size() + 1);
        docs.add(bookDocument(bookId, title, author));
        for (ParagraphText paragraph : paragraphs) {
            docs.add(paragraphDocument(bookId, paragraph));
        }
        writer.addDocuments(docs);
    }

    private static Document bookDocument(String bookId, String title, String author) {
        Document doc = new Document();
        doc.add(new StringField("type", "book", Field.Store.YES));
        doc.add(new StringField("bookId", bookId, Field.Store.YES));
        doc.add(new TextField("title", title, Field.Store.YES));
        doc.add(new TextField("author", author, Field.Store.YES));
        doc.add(new TextField("content", title + " " + author, Field.Store.NO));
        return doc;
    }

    private static Document paragraphDocument(String bookId, ParagraphText paragraph) {
        Document doc = new Document();
        doc.add(new StringField("type", "paragraph", Field.Store.YES));
        doc.add(new StringField("bookId", bookId, Field.Store.YES));
        doc.add(new StringField("chapterId", paragraph.chapterId(), Field.Store.YES));
        doc.add(new StoredField("paragraphIndex", paragraph.paragraphIndex()));
        doc.add(new TextField("content", paragraph.content(), Field.Store.YES));
        return doc;
    }

    public List<SearchResult> search(String queryStr, int maxResults) throws IOException, ParseException {
        return search(queryStr, null, maxResults);
    }

    public List<SearchResult> search(String queryStr, String bookId, int maxResults) throws IOException, ParseException {
        return search(queryStr, bookId, null, maxResults);
    }

    public List<SearchResult> search(String queryStr, String bookId, String chapterId, int maxResults) throws IOException, ParseException {
        List<SearchResult> results = new ArrayList<>();

        try (DirectoryReader reader = DirectoryReader.open(index)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            QueryParser parser = new QueryParser("content", analyzer);
            Query contentQuery = parser.parse(queryStr);

            BooleanQuery.Builder builder = new BooleanQuery.Builder();
            builder.add(contentQuery, BooleanClause.Occur.MUST);
            boolean paragraphOnly = false;
            if (bookId != null && !bookId.isBlank()) {
                builder.add(new TermQuery(new Term("bookId", bookId)), BooleanClause.Occur.FILTER);
                paragraphOnly = true;
            }
            if (chapterId != null && !chapterId.isBlank()) {
                builder.add(new TermQuery(new Term("chapterId", chapterId)), BooleanClause.Occur.FILTER);
                paragraphOnly = true;
            }
            if (paragraphOnly) {
                builder.add(new TermQuery(new Term("type", "paragraph")), BooleanClause.Occur.FILTER);
            }
            Query finalQuery = builder.build();

            ScoreDoc[] hits = searcher.search(finalQuery, Math.max(1, maxResults)).scoreDocs;

            for (ScoreDoc hit : hits) {
                Document doc = searcher.storedFields().document(hit.doc);
                String type = doc.get("type");
                Integer paragraphIndex = null;
                String snippet = null;

                if ("paragraph".equals(type)) {
                    paragraphIndex = doc.getField("paragraphIndex").numericValue().intValue();
                    String content = doc.get("content");
                    snippet = buildSnippet(content, queryStr);
                }

                results.add(new SearchResult(
                    type,
                    doc.get("bookId"),
                    doc.get("chapterId"),
                    paragraphIndex,
                    doc.get("title"),
                    snippet,
                    hit.score
                ));
            }
        }

        return results;
    }

    private String buildSnippet(String content, String queryStr) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= SNIPPET_MAX_LENGTH) {
            return normalized;
        }

        List<String> terms = extractTerms(queryStr);
        String lowerContent = normalized.toLowerCase(Locale.ROOT);
        int matchIndex = -1;
        for (String term : terms) {
            int index = lowerContent.indexOf(term);
            if (index >= 0 && (matchIndex < 0 || index < matchIndex)) {
                matchIndex = index;
            }
        }

        int start = 0;
        if (matchIndex >= 0) {
            start = Math.max(0, matchIndex - (SNIPPET_MAX_LENGTH / 2));
            if (start > 0) {
                int previousSpace = normalized.lastIndexOf(' ', start);
                if (previousSpace >= 0) {
                    start = previousSpace + 1;
                }
            }
        }

        int end = Math.min(normalized.length(), start + SNIPPET_MAX_LENGTH);
        if (end < normalized.length()) {
            int nextSpace = normalized.indexOf(' ', end);
            if (nextSpace >= 0) {
                end = nextSpace;
            }
        }

        String snippet = normalized.substring(start, end).trim();
        if (start > 0) {
            snippet = "..." + snippet;
        }
        if (end < normalized.length()) {
            snippet = snippet + "...";
        }
        return snippet;
    }

    private List<String> extractTerms(String queryStr) {
        if (queryStr == null || queryStr.isBlank()) {
            return List.of();
        }
        String normalized = queryStr.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{Nd}]+", " ");
        Set<String> terms = new LinkedHashSet<>();
        Arrays.stream(normalized.split("\\s+"))
                .map(String::trim)
                .filter(term -> term.length() >= 2)
                .forEach(terms::add);
        return List.copyOf(terms);
    }

    public void deleteByBookId(String bookId) throws IOException {
        writer.deleteDocuments(new Term("bookId", bookId));
        writer.commit();
    }

    public record SearchResult(
        String type,
        String bookId,
        String chapterId,
        Integer paragraphIndex,
        String title,
        String snippet,
        float score
    ) {}
}
