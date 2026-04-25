package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.repository.BookRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GenerationQueueRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(GenerationQueueRecoveryService.class);

    private final BookRepository bookRepository;
    private final IllustrationService illustrationService;
    private final CharacterService characterService;
    private final ChapterRecapService chapterRecapService;
    private final boolean recoveryEnabled;

    public GenerationQueueRecoveryService(
            BookRepository bookRepository,
            IllustrationService illustrationService,
            CharacterService characterService,
            ChapterRecapService chapterRecapService,
            @Value("${generation.queue.recovery.enabled:true}") boolean recoveryEnabled) {
        this.bookRepository = bookRepository;
        this.illustrationService = illustrationService;
        this.characterService = characterService;
        this.chapterRecapService = chapterRecapService;
        this.recoveryEnabled = recoveryEnabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        recoverPendingGenerationWork();
    }

    RecoverySummary recoverPendingGenerationWork() {
        if (!recoveryEnabled) {
            log.info("Generation queue recovery is disabled");
            return new RecoverySummary(0, 0, 0, 0, 0);
        }

        List<BookEntity> books = bookRepository.findAll();
        if (books.isEmpty()) {
            log.info("No books found for generation queue recovery");
            return new RecoverySummary(0, 0, 0, 0, 0);
        }

        int illustrationsRequeued = 0;
        int portraitsRequeued = 0;
        int analysesRequeued = 0;
        int recapsRequeued = 0;

        for (BookEntity book : books) {
            String bookId = book.getId();
            if (bookId == null || bookId.isBlank()) {
                continue;
            }
            illustrationsRequeued += illustrationService.resetAndRequeueStuckForBook(bookId);
            portraitsRequeued += characterService.resetAndRequeueStuckPortraitsForBook(bookId);
            analysesRequeued += characterService.resetAndRequeueStuckAnalysesForBook(bookId);
            recapsRequeued += chapterRecapService.resetAndRequeueStuckForBook(bookId);
        }

        RecoverySummary summary = new RecoverySummary(
                books.size(),
                illustrationsRequeued,
                portraitsRequeued,
                analysesRequeued,
                recapsRequeued
        );

        log.info(
                "Recovered generation queues across {} books: illustrations={}, portraits={}, analyses={}, recaps={}",
                summary.booksScanned(),
                summary.illustrationsRequeued(),
                summary.portraitsRequeued(),
                summary.analysesRequeued(),
                summary.recapsRequeued()
        );
        return summary;
    }

    record RecoverySummary(
            int booksScanned,
            int illustrationsRequeued,
            int portraitsRequeued,
            int analysesRequeued,
            int recapsRequeued) {
    }
}
