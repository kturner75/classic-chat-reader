package com.classicchatreader.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "paragraphs")
public class ParagraphEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chapter_id", nullable = false)
    private ChapterEntity chapter;

    @Column(nullable = false)
    private int paragraphIndex;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    public ParagraphEntity() {}

    public ParagraphEntity(int paragraphIndex, String content) {
        this.paragraphIndex = paragraphIndex;
        this.content = content;
    }

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public ChapterEntity getChapter() { return chapter; }
    public void setChapter(ChapterEntity chapter) { this.chapter = chapter; }

    public int getParagraphIndex() { return paragraphIndex; }
    public void setParagraphIndex(int paragraphIndex) { this.paragraphIndex = paragraphIndex; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
