package com.classicchatreader.config;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.ParagraphEntity;
import com.classicchatreader.repository.BookRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Profile({"dev", "test", "smoke"})
@Order(1) // Run before SearchIndexInitializer
public class DataInitializer implements CommandLineRunner {

    private final BookRepository bookRepository;

    public DataInitializer(BookRepository bookRepository) {
        this.bookRepository = bookRepository;
    }

    @Override
    public void run(String... args) {
        // Only initialize if database is empty
        if (bookRepository.count() > 0) {
            System.out.println("Database already contains books, skipping initialization");
            return;
        }

        System.out.println("Initializing sample books...");

        // Pride and Prejudice
        BookEntity prideAndPrejudice = new BookEntity("Pride and Prejudice", "Jane Austen", "manual");
        prideAndPrejudice.setDescription("A romantic novel following the Bennet family.");

        ChapterEntity pnpCh1 = new ChapterEntity(0, "Chapter 1");
        pnpCh1.addParagraph(new ParagraphEntity(0,
            "It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife."));
        pnpCh1.addParagraph(new ParagraphEntity(1,
            "However little known the feelings or views of such a man may be on his first entering a neighbourhood, this truth is so well fixed in the minds of the surrounding families, that he is considered as the rightful property of some one or other of their daughters."));
        pnpCh1.addParagraph(new ParagraphEntity(2,
            "\"My dear Mr. Bennet,\" said his lady to him one day, \"have you heard that Netherfield Park is let at last?\""));
        pnpCh1.addParagraph(new ParagraphEntity(3,
            "Mr. Bennet replied that he had not."));
        prideAndPrejudice.addChapter(pnpCh1);

        ChapterEntity pnpCh2 = new ChapterEntity(1, "Chapter 2");
        pnpCh2.addParagraph(new ParagraphEntity(0,
            "Mr. Bennet was among the earliest of those who waited on Mr. Bingley. He had always intended to visit him, though to the last always assuring his wife that he should not go."));
        prideAndPrejudice.addChapter(pnpCh2);

        ChapterEntity pnpCh3 = new ChapterEntity(2, "Chapter 3");
        pnpCh3.addParagraph(new ParagraphEntity(0,
            "Not all that Mrs. Bennet, however, with the assistance of her five daughters, could ask on the subject, was sufficient to draw from her husband any satisfactory description of Mr. Bingley."));
        prideAndPrejudice.addChapter(pnpCh3);

        bookRepository.save(prideAndPrejudice);

        // Moby Dick
        BookEntity mobyDick = new BookEntity("Moby Dick", "Herman Melville", "manual");
        mobyDick.setDescription("The saga of Captain Ahab and his obsessive quest.");

        ChapterEntity mdCh1 = new ChapterEntity(0, "Loomings");
        mdCh1.addParagraph(new ParagraphEntity(0,
            "Call me Ishmael. Some years ago—never mind how long precisely—having little or no money in my purse, and nothing particular to interest me on shore, I thought I would sail about a little and see the watery part of the world."));
        mdCh1.addParagraph(new ParagraphEntity(1,
            "It is a way I have of driving off the spleen and regulating the circulation. Whenever I find myself growing grim about the mouth; whenever it is a damp, drizzly November in my soul; whenever I find myself involuntarily pausing before coffin warehouses, and bringing up the rear of every funeral I meet; and especially whenever my hypos get such an upper hand of me, that it requires a strong moral principle to prevent me from deliberately stepping into the street, and methodically knocking people's hats off—then, I account it high time to get to sea as soon as I can."));
        mdCh1.addParagraph(new ParagraphEntity(2,
            "This is my substitute for pistol and ball. With a philosophical flourish Cato throws himself upon his sword; I quietly take to the ship."));
        mobyDick.addChapter(mdCh1);

        ChapterEntity mdCh2 = new ChapterEntity(1, "The Carpet-Bag");
        mdCh2.addParagraph(new ParagraphEntity(0,
            "I stuffed a shirt or two into my old carpet-bag, tucked it under my arm, and started for Cape Horn and the Pacific."));
        mobyDick.addChapter(mdCh2);

        ChapterEntity mdCh3 = new ChapterEntity(2, "The Spouter-Inn");
        mdCh3.addParagraph(new ParagraphEntity(0,
            "Entering that gable-ended Spouter-Inn, you found yourself in a wide, low, straggling entry with old-fashioned wainscots, reminding one of the bulwarks of some condemned old craft."));
        mobyDick.addChapter(mdCh3);

        bookRepository.save(mobyDick);

        System.out.println("Sample books initialized: " + bookRepository.count() + " books");
    }
}
