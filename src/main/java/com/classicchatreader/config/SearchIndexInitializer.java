package com.classicchatreader.config;

import com.classicchatreader.service.SearchIndexRebuilder;
import org.apache.lucene.store.AlreadyClosedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Rebuilds the in-memory search index after startup, on its own thread so startup (and anything
 * else waiting on the main thread) does not wait for it. Until it finishes, in-book search
 * returns no results.
 */
@Component
@Order(2) // Run after DataInitializer
public class SearchIndexInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexInitializer.class);

    private final SearchIndexRebuilder searchIndexRebuilder;

    public SearchIndexInitializer(SearchIndexRebuilder searchIndexRebuilder) {
        this.searchIndexRebuilder = searchIndexRebuilder;
    }

    @Override
    public void run(String... args) {
        Thread thread = new Thread(() -> {
            try {
                searchIndexRebuilder.rebuild();
            } catch (AlreadyClosedException e) {
                log.info("Search index rebuild stopped: the application is shutting down");
            } catch (Exception e) {
                log.error("Search index rebuild failed; in-book search will be empty until the next restart", e);
            }
        }, "search-index-rebuild");
        thread.setDaemon(true);
        thread.start();
    }
}
