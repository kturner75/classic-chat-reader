package com.classicchatreader.entity;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "chapters")
public class ChapterEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "book_id", nullable = false)
    private BookEntity book;

    @Column(nullable = false)
    private int chapterIndex;

    @Column(nullable = false)
    private String title;

    @OneToMany(mappedBy = "chapter", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("paragraphIndex")
    private List<ParagraphEntity> paragraphs = new ArrayList<>();

    public ChapterEntity() {}

    public ChapterEntity(int chapterIndex, String title) {
        this.chapterIndex = chapterIndex;
        this.title = title;
    }

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public BookEntity getBook() { return book; }
    public void setBook(BookEntity book) { this.book = book; }

    public int getChapterIndex() { return chapterIndex; }
    public void setChapterIndex(int chapterIndex) { this.chapterIndex = chapterIndex; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public List<ParagraphEntity> getParagraphs() { return paragraphs; }
    public void setParagraphs(List<ParagraphEntity> paragraphs) { this.paragraphs = paragraphs; }

    public void addParagraph(ParagraphEntity paragraph) {
        paragraphs.add(paragraph);
        paragraph.setChapter(this);
    }
}
