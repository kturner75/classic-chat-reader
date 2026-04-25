package com.classicchatreader.entity;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "books")
public class BookEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String author;

    @Column(length = 2000)
    private String description;

    private String coverUrl;

    @Column(nullable = false)
    private String source; // "gutenberg", "standardebooks", "manual"

    private String sourceId; // ID from the source (e.g., Gutenberg book number)

    // TTS Voice Settings (persisted after LLM analysis)
    private String ttsVoice;
    private Double ttsSpeed;
    @Column(length = 1000)
    private String ttsInstructions;
    @Column(length = 500)
    private String ttsReasoning;
    private Boolean ttsEnabled = false;

    // Illustration Style Settings (persisted after LLM analysis)
    private String illustrationStyle;
    @Column(length = 1000)
    private String illustrationPromptPrefix;
    @Column(length = 1000)
    private String illustrationSetting; // Cultural/geographic setting (e.g., "19th century Russia, Russian Orthodox")
    @Column(length = 2000)
    private String illustrationStyleReasoning;
    private Boolean illustrationEnabled = false;

    // Character Prefetch Tracking
    private Boolean characterPrefetchCompleted = false;
    private Boolean characterEnabled = false;

    @OneToMany(mappedBy = "book", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("chapterIndex")
    private List<ChapterEntity> chapters = new ArrayList<>();

    public BookEntity() {}

    public BookEntity(String title, String author, String source) {
        this.title = title;
        this.author = author;
        this.source = source;
    }

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getCoverUrl() { return coverUrl; }
    public void setCoverUrl(String coverUrl) { this.coverUrl = coverUrl; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }

    public List<ChapterEntity> getChapters() { return chapters; }
    public void setChapters(List<ChapterEntity> chapters) { this.chapters = chapters; }

    public String getTtsVoice() { return ttsVoice; }
    public void setTtsVoice(String ttsVoice) { this.ttsVoice = ttsVoice; }

    public Double getTtsSpeed() { return ttsSpeed; }
    public void setTtsSpeed(Double ttsSpeed) { this.ttsSpeed = ttsSpeed; }

    public String getTtsInstructions() { return ttsInstructions; }
    public void setTtsInstructions(String ttsInstructions) { this.ttsInstructions = ttsInstructions; }

    public String getTtsReasoning() { return ttsReasoning; }
    public void setTtsReasoning(String ttsReasoning) { this.ttsReasoning = ttsReasoning; }

    public Boolean getTtsEnabled() { return ttsEnabled; }
    public void setTtsEnabled(Boolean ttsEnabled) { this.ttsEnabled = ttsEnabled; }

    public String getIllustrationStyle() { return illustrationStyle; }
    public void setIllustrationStyle(String illustrationStyle) { this.illustrationStyle = illustrationStyle; }

    public String getIllustrationPromptPrefix() { return illustrationPromptPrefix; }
    public void setIllustrationPromptPrefix(String illustrationPromptPrefix) { this.illustrationPromptPrefix = illustrationPromptPrefix; }

    public String getIllustrationSetting() { return illustrationSetting; }
    public void setIllustrationSetting(String illustrationSetting) { this.illustrationSetting = illustrationSetting; }

    public String getIllustrationStyleReasoning() { return illustrationStyleReasoning; }
    public void setIllustrationStyleReasoning(String illustrationStyleReasoning) { this.illustrationStyleReasoning = illustrationStyleReasoning; }

    public Boolean getIllustrationEnabled() { return illustrationEnabled; }
    public void setIllustrationEnabled(Boolean illustrationEnabled) { this.illustrationEnabled = illustrationEnabled; }

    public Boolean getCharacterPrefetchCompleted() { return characterPrefetchCompleted; }
    public void setCharacterPrefetchCompleted(Boolean characterPrefetchCompleted) { this.characterPrefetchCompleted = characterPrefetchCompleted; }

    public Boolean getCharacterEnabled() { return characterEnabled; }
    public void setCharacterEnabled(Boolean characterEnabled) { this.characterEnabled = characterEnabled; }

    public void addChapter(ChapterEntity chapter) {
        chapters.add(chapter);
        chapter.setBook(this);
    }
}
