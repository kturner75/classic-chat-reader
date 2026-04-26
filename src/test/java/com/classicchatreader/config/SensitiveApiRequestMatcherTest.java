package com.classicchatreader.config;

import org.junit.jupiter.api.Test;

import static com.classicchatreader.config.SensitiveApiRequestMatcher.EndpointType.CHAT;
import static com.classicchatreader.config.SensitiveApiRequestMatcher.EndpointType.GENERATION;
import static com.classicchatreader.config.SensitiveApiRequestMatcher.EndpointType.ADMIN;
import static com.classicchatreader.config.SensitiveApiRequestMatcher.EndpointType.NONE;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SensitiveApiRequestMatcherTest {

    @Test
    void classify_marksGenerationEndpoints() {
        assertEquals(GENERATION, SensitiveApiRequestMatcher.classify("POST", "/api/pregen/book/book-1"));
        assertEquals(GENERATION, SensitiveApiRequestMatcher.classify("POST", "/api/pregen/jobs/book/book-1"));
        assertEquals(GENERATION, SensitiveApiRequestMatcher.classify("POST", "/api/pregen/jobs/gutenberg/1234"));
        assertEquals(GENERATION, SensitiveApiRequestMatcher.classify("POST", "/api/pregen/jobs/job-1/cancel"));
        assertEquals(GENERATION, SensitiveApiRequestMatcher.classify("GET", "/api/pregen/jobs/job-1"));
        assertEquals(GENERATION, SensitiveApiRequestMatcher.classify("DELETE", "/api/pregen/jobs/job-1"));
        assertEquals(GENERATION, SensitiveApiRequestMatcher.classify("POST", "/api/illustrations/chapter/ch-1/request"));
        assertEquals(GENERATION, SensitiveApiRequestMatcher.classify("POST", "/api/quizzes/chapter/ch-1/generate"));
        assertEquals(GENERATION, SensitiveApiRequestMatcher.classify("POST", "/api/library/book-1/cover/retry"));
    }

    @Test
    void classify_marksChatEndpoints() {
        assertEquals(CHAT, SensitiveApiRequestMatcher.classify("POST", "/api/characters/char-1/chat"));
        assertEquals(CHAT, SensitiveApiRequestMatcher.classify("POST", "/api/recaps/book/book-1/chat"));
    }

    @Test
    void classify_marksAdminEndpoints() {
        assertEquals(ADMIN, SensitiveApiRequestMatcher.classify("PATCH", "/api/library/book-1/features"));
        assertEquals(ADMIN, SensitiveApiRequestMatcher.classify("PUT", "/api/library/book-1/cover"));
        assertEquals(ADMIN, SensitiveApiRequestMatcher.classify("DELETE", "/api/library/book-1"));
        assertEquals(ADMIN, SensitiveApiRequestMatcher.classify("DELETE", "/api/library"));
    }

    @Test
    void classify_ignoresNonSensitiveEndpoints() {
        assertEquals(NONE, SensitiveApiRequestMatcher.classify("GET", "/api/import/popular"));
        assertEquals(NONE, SensitiveApiRequestMatcher.classify("POST", "/api/recaps/analytics"));
        assertEquals(NONE, SensitiveApiRequestMatcher.classify("GET", "/api/library/book-1"));
        assertEquals(NONE, SensitiveApiRequestMatcher.classify("GET", "/api/tts/speak/book-1/chapter-2/3"));
        assertEquals(NONE, SensitiveApiRequestMatcher.classify(null, "/api/pregen/book/book-1"));
    }
}
