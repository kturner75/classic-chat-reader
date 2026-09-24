package com.classicchatreader.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/** One title's curated landing catalog membership (BL-072). The list columns hold JSON arrays. */
@Entity
@Table(name = "curated_books",
        uniqueConstraints = @UniqueConstraint(name = "uk_curated_books_source", columnNames = {"source", "source_id"}))
public class CuratedBookEntity {

    @Id
    @Column(length = 255)
    private String id;

    @Column(nullable = false, length = 32)
    private String source;

    @Column(name = "source_id", nullable = false, length = 64)
    private String sourceId;

    @Column(nullable = false, length = 512)
    private String title;

    @Column(nullable = false, length = 512)
    private String author;

    @Column(nullable = false)
    private int popularity;

    @Column(name = "subjects_json", nullable = false, columnDefinition = "TEXT")
    private String subjectsJson;

    @Column(name = "bookshelves_json", nullable = false, columnDefinition = "TEXT")
    private String bookshelvesJson;

    @Column(name = "aliases_json", nullable = false, columnDefinition = "TEXT")
    private String aliasesJson;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }
    public int getPopularity() { return popularity; }
    public void setPopularity(int popularity) { this.popularity = popularity; }
    public String getSubjectsJson() { return subjectsJson; }
    public void setSubjectsJson(String subjectsJson) { this.subjectsJson = subjectsJson; }
    public String getBookshelvesJson() { return bookshelvesJson; }
    public void setBookshelvesJson(String bookshelvesJson) { this.bookshelvesJson = bookshelvesJson; }
    public String getAliasesJson() { return aliasesJson; }
    public void setAliasesJson(String aliasesJson) { this.aliasesJson = aliasesJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
