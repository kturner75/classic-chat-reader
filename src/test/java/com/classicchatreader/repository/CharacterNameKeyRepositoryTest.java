package com.classicchatreader.repository;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.CharacterEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest
class CharacterNameKeyRepositoryTest {

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private ChapterRepository chapterRepository;

    @Autowired
    private CharacterRepository characterRepository;

    @Test
    void rejectsSecondRowWithTheSameNormalizedNameKey() {
        ChapterEntity chapter = persistChapter();
        characterRepository.saveAndFlush(new CharacterEntity(
                chapter.getBook(), "Sally", "Catherine's sister", chapter, 1));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> characterRepository.saveAndFlush(new CharacterEntity(
                        chapter.getBook(), "  sally.  ", "The same sister", chapter, 4)));
    }

    private ChapterEntity persistChapter() {
        BookEntity book = bookRepository.saveAndFlush(new BookEntity("Northanger Abbey", "Jane Austen", "gutenberg"));
        ChapterEntity chapter = new ChapterEntity(0, "Chapter 1");
        chapter.setBook(book);
        return chapterRepository.saveAndFlush(chapter);
    }
}
