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

    public SearchService() {
        this.index = new ByteBuffersDirectory();
        this.analyzer = new StandardAnalyzer();
    }

    public void indexBook(String bookId, String title, String author) throws IOException {
        try (IndexWriter writer = new IndexWriter(index, new IndexWriterConfig(analyzer))) {
            Document doc = new Document();
            doc.add(new StringField("type", "book", Field.Store.YES));
            doc.add(new StringField("bookId", bookId, Field.Store.YES));
            doc.add(new TextField("title", title, Field.Store.YES));
            doc.add(new TextField("author", author, Field.Store.YES));
            doc.add(new TextField("content", title + " " + author, Field.Store.NO));
            writer.addDocument(doc);
        }
    }

    public void indexParagraph(String bookId, String chapterId, int paragraphIndex, String content) throws IOException {
        try (IndexWriter writer = new IndexWriter(index, new IndexWriterConfig(analyzer))) {
            Document doc = new Document();
            doc.add(new StringField("type", "paragraph", Field.Store.YES));
            doc.add(new StringField("bookId", bookId, Field.Store.YES));
            doc.add(new StringField("chapterId", chapterId, Field.Store.YES));
            doc.add(new StoredField("paragraphIndex", paragraphIndex));
            doc.add(new TextField("content", content, Field.Store.YES));
            writer.addDocument(doc);
        }
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
        try (IndexWriter writer = new IndexWriter(index, new IndexWriterConfig(analyzer))) {
            writer.deleteDocuments(new Term("bookId", bookId));
        }
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
