package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.CharacterEntity;
import com.classicchatreader.entity.CharacterStatus;
import com.classicchatreader.entity.CharacterType;
import com.classicchatreader.repository.CharacterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IllustrationPortraitReferencesTest {

    @Mock private CharacterRepository characterRepository;
    @Mock private ComfyUIService comfyUIService;

    private IllustrationPortraitReferences refs;
    private BookEntity book;
    private ChapterEntity chapter0;
    private ChapterEntity chapter1;

    @BeforeEach
    void setUp() {
        refs = new IllustrationPortraitReferences(characterRepository, comfyUIService);
        book = new BookEntity("An Old-Fashioned Girl", "Louisa May Alcott", "gutenberg");
        book.setId("book-1");
        chapter0 = new ChapterEntity(0, "Preface");
        chapter0.setId("ch-0");
        chapter0.setBook(book);
        chapter1 = new ChapterEntity(1, "CHAPTER I. POLLY ARRIVES");
        chapter1.setId("ch-1");
        chapter1.setBook(book);
    }

    @Test
    void matchesFullNameGivenNameAndPossessive() {
        assertTrue(IllustrationPortraitReferences.mentionedIn("Polly Milton", "polly sat by the window"));
        assertTrue(IllustrationPortraitReferences.mentionedIn("Polly Milton", "polly's trunk"));
        assertTrue(IllustrationPortraitReferences.mentionedIn("Tom Shaw", "tom shaw laughed"));
        assertFalse(IllustrationPortraitReferences.mentionedIn("Ann", "annual picnic"));
        assertFalse(IllustrationPortraitReferences.mentionedIn("Polly Milton", "the parlor was quiet"));
    }

    @Test
    void castComesFromChapterTitleAndTextNotTheWholeRoster() {
        CharacterEntity polly = character("Polly Milton", chapter1, CharacterType.PRIMARY, "polly.png");
        CharacterEntity tom = character("Tom Shaw", chapter1, CharacterType.PRIMARY, "tom.png");
        when(characterRepository.findByBookIdWithFirstChapterOrderByCreatedAt("book-1"))
                .thenReturn(List.of(polly, tom));

        var cast = refs.castForChapter(
                "book-1",
                chapter1,
                "Polly sat by the window and looked out at the city.");

        assertEquals(List.of("Polly Milton"), IllustrationPortraitReferences.namesOf(cast));
    }

    @Test
    void ensureCastNamedPutsMissingNamesOnThePrompt() {
        String filled = IllustrationPortraitReferences.ensureCastNamed(
                "a Boston parlor in afternoon light",
                List.of("Polly Milton"));
        assertTrue(filled.startsWith("Polly Milton in this scene."));
        assertEquals(
                "Polly at the window",
                IllustrationPortraitReferences.ensureCastNamed("Polly at the window", List.of("Polly Milton")));
    }

    @Test
    void usesImaginePromptWhenChapterTextOmitsTheName() {
        CharacterEntity polly = character("Polly Milton", chapter1, CharacterType.PRIMARY, "polly.png");
        when(characterRepository.findByBookIdWithFirstChapterOrderByCreatedAt("book-1"))
                .thenReturn(List.of(polly));
        when(comfyUIService.getPortraitImage("polly.png")).thenReturn(new byte[] {1, 2, 3});

        List<IllustrationPortraitReferences.PortraitRef> picked = refs.select(
                "book-1",
                chapter1,
                "vintage plate of Polly Milton at the window, visible face");

        assertEquals(1, picked.size());
        assertEquals("Polly Milton", picked.getFirst().name());
    }

    @Test
    void ignoresNamesThatAreOnlyInTheChapterNotThePrompt() {
        CharacterEntity tom = character("Tom Shaw", chapter1, CharacterType.PRIMARY, "tom.png");
        when(characterRepository.findByBookIdWithFirstChapterOrderByCreatedAt("book-1"))
                .thenReturn(List.of(tom));

        List<IllustrationPortraitReferences.PortraitRef> picked = refs.select(
                "book-1",
                chapter1,
                "vintage plate of a Boston street in snow");

        assertTrue(picked.isEmpty());
    }

    @Test
    void labelsAttachedPortraitsForImagine() {
        var attached = List.of(
                new IllustrationPortraitReferences.PortraitRef("Polly Milton", new byte[] {1}),
                new IllustrationPortraitReferences.PortraitRef("Tom Shaw", new byte[] {2}));
        String labeled = IllustrationPortraitReferences.appendLikeness(
                "a Boston parlor afternoon", attached);
        assertTrue(labeled.contains("Polly Milton"));
        assertTrue(labeled.contains("Tom Shaw"));
        assertTrue(labeled.contains("not a portrait"));
        assertTrue(labeled.contains("three-quarter"));
    }

    private CharacterEntity character(
            String name, ChapterEntity first, CharacterType type, String filename) {
        CharacterEntity c = new CharacterEntity(book, name, "desc", first, 0, type);
        c.setId("char-" + name.replace(' ', '-').toLowerCase());
        c.setStatus(CharacterStatus.COMPLETED);
        c.setPortraitFilename(filename);
        return c;
    }
}
