package com.classicchatreader.config;

import org.junit.jupiter.api.Test;

import static com.classicchatreader.config.SensitiveApiRequestMatcher.EndpointType.BUDDY_CHECK;
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
    void classify_marksClassroomSuggestRoutesAsGeneration() {
        assertEquals(GENERATION, SensitiveApiRequestMatcher.classify(
                "POST", "/api/classroom/assignments/asg-1/suggest-questions"));
        assertEquals(GENERATION, SensitiveApiRequestMatcher.classify(
                "POST", "/api/classroom/assignments/asg-1/suggest-distractors"));
        assertEquals(GENERATION, SensitiveApiRequestMatcher.classify(
                "POST", "/api/classroom/terms/term-1/chapters/ch-1/suggest-questions"));
        assertEquals(GENERATION, SensitiveApiRequestMatcher.classify(
                "POST", "/api/classroom/terms/term-1/chapters/ch-1/suggest-distractors"));
        assertEquals(NONE, SensitiveApiRequestMatcher.classify(
                "GET", "/api/classroom/assignments/asg-1/suggest-questions"));
        assertEquals(NONE, SensitiveApiRequestMatcher.classify(
                "GET", "/api/classroom/terms/term-1/chapters/ch-1/suggest-distractors"));
    }

    @Test
    void classify_marksChatEndpoints() {
        assertEquals(CHAT, SensitiveApiRequestMatcher.classify("POST", "/api/characters/char-1/chat"));
        assertEquals(CHAT, SensitiveApiRequestMatcher.classify("POST", "/api/characters/char-1/call-session"));
        assertEquals(CHAT, SensitiveApiRequestMatcher.classify("POST", "/api/recaps/book/book-1/chat"));
        assertEquals(CHAT, SensitiveApiRequestMatcher.classify("POST", "/api/reading-buddy/chat"));
    }

    @Test
    void classify_marksBuddyCheckSeparatelyFromChat() {
        assertEquals(BUDDY_CHECK, SensitiveApiRequestMatcher.classify("POST", "/api/reading-buddy/check-comment"));
        // Chat remains on the shared CHAT bucket
        assertEquals(CHAT, SensitiveApiRequestMatcher.classify("POST", "/api/reading-buddy/chat"));
        assertEquals(NONE, SensitiveApiRequestMatcher.classify("GET", "/api/reading-buddy/check-comment"));
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
        assertEquals(NONE, SensitiveApiRequestMatcher.classify("GET", "/api/reading-buddy/history"));
        assertEquals(NONE, SensitiveApiRequestMatcher.classify("GET", "/api/reading-buddy/chat"));
        assertEquals(NONE, SensitiveApiRequestMatcher.classify(
                "GET", "/api/classroom/assignments/asg-1/suggest-questions"));
        assertEquals(NONE, SensitiveApiRequestMatcher.classify(
                "POST", "/api/classroom/assignments/asg-1/effective-quiz"));
    }

    @Test
    void classify_marksReadingBuddyMutationsAsChatSensitive() {
        assertEquals(CHAT, SensitiveApiRequestMatcher.classify("PUT", "/api/reading-buddy/preferences"));
        assertEquals(CHAT, SensitiveApiRequestMatcher.classify("DELETE", "/api/reading-buddy/history"));
        assertEquals(NONE, SensitiveApiRequestMatcher.classify("GET", "/api/reading-buddy/preferences"));
    }
}
