package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.CharacterEntity;
import com.classicchatreader.entity.CharacterType;
import com.classicchatreader.entity.ParagraphEntity;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ChapterAnalysisRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.CharacterRepository;
import com.classicchatreader.repository.ParagraphRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceUnitUtil;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@DataJpaTest
class CharacterServiceRefineFirstAppearanceTest {

    @Autowired
    private EntityManager entityManager;
    @Autowired
    private BookRepository bookRepository;
    @Autowired
    private ChapterRepository chapterRepository;
    @Autowired
    private CharacterRepository characterRepository;
    @Autowired
    private ParagraphRepository paragraphRepository;

    private CharacterService service;
    private PersistenceUnitUtil persistenceUtil;
    private String bookId;
    private ChapterEntity letterI;
    private String characterId;

    @BeforeEach
    void setUp() {
        service = new CharacterService(
                characterRepository,
                mock(ChapterAnalysisRepository.class),
                chapterRepository,
                bookRepository,
                paragraphRepository,
                mock(CharacterExtractionService.class),
                mock(CharacterPortraitService.class),
                mock(IllustrationService.class),
                mock(ComfyUIService.class),
                mock(CharacterPortraitImageGeneratorService.class),
                new AssetKeyService()
        );
        persistenceUtil = entityManager.getEntityManagerFactory().getPersistenceUnitUtil();

        BookEntity book = bookRepository.saveAndFlush(
                new BookEntity("Frankenstein", "Mary Wollstonecraft Shelley", "gutenberg"));
        bookId = book.getId();

        letterI = new ChapterEntity(0, "Letter I");
        letterI.setBook(book);
        letterI = chapterRepository.saveAndFlush(letterI);

        ChapterEntity chapterV = new ChapterEntity(4, "Chapter V");
        chapterV.setBook(book);
        chapterV = chapterRepository.saveAndFlush(chapterV);

        ParagraphEntity paragraph = new ParagraphEntity(2, "Victor Frankenstein was born in Naples.");
        paragraph.setChapter(letterI);
        paragraphRepository.saveAndFlush(paragraph);

        CharacterEntity victor = new CharacterEntity(
                book, "Victor Frankenstein", "A Genevese student", chapterV, 0, CharacterType.PRIMARY);
        characterId = characterRepository.saveAndFlush(victor).getId();
        entityManager.flush();
    }

    @Test
    void refineTrustedFirstAppearancesWorksWithoutOpenSessionWhenFirstChapterIsJoinFetched() {
        List<CharacterEntity> trusted =
                characterRepository.findByBookIdWithFirstChapterOrderByCreatedAt(bookId);
        assertThat(trusted).hasSize(1);
        assertThat(persistenceUtil.isLoaded(trusted.getFirst().getFirstChapter())).isTrue();

        ChapterEntity current = chapterRepository.findById(letterI.getId()).orElseThrow();
        entityManager.clear();

        assertThat(entityManager.contains(trusted.getFirst())).isFalse();
        assertThat(entityManager.contains(trusted.getFirst().getFirstChapter())).isFalse();
        assertThat(persistenceUtil.isLoaded(trusted.getFirst().getFirstChapter())).isTrue();

        int updated = ReflectionTestUtils.invokeMethod(
                service, "refineTrustedFirstAppearances", current, trusted);

        assertThat(updated).isEqualTo(1);
        entityManager.flush();
        entityManager.clear();

        CharacterEntity reloaded = characterRepository.findByIdWithBookAndChapter(characterId).orElseThrow();
        assertThat(reloaded.getFirstChapter().getId()).isEqualTo(letterI.getId());
        assertThat(reloaded.getFirstParagraphIndex()).isEqualTo(2);
    }

    @Test
    void refineTrustedFirstAppearancesDoesNotInitializeLazyFirstChapterProxy() {
        List<CharacterEntity> lazyRoster =
                characterRepository.findByBookIdOrderByCreatedAt(bookId);
        ChapterEntity current = chapterRepository.findById(letterI.getId()).orElseThrow();
        entityManager.clear();

        assertThat(persistenceUtil.isLoaded(lazyRoster.getFirst(), "firstChapter")).isFalse();

        assertThatThrownBy(() -> lazyRoster.getFirst().getFirstChapter().getChapterIndex())
                .isInstanceOf(LazyInitializationException.class);

        List<CharacterEntity> trusted =
                characterRepository.findByBookIdWithFirstChapterOrderByCreatedAt(bookId);
        entityManager.clear();

        int updated = ReflectionTestUtils.invokeMethod(
                service, "refineTrustedFirstAppearances", current, trusted);

        assertThat(updated).isEqualTo(1);
        assertThat(persistenceUtil.isLoaded(trusted.getFirst().getFirstChapter())).isTrue();
    }
}
