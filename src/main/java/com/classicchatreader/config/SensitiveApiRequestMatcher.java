package com.classicchatreader.config;

import java.util.regex.Pattern;

/**
 * Matches API routes that should receive public-mode auth and rate limiting.
 */
public final class SensitiveApiRequestMatcher {

    private static final Pattern TTS_ANALYZE_PATH = Pattern.compile("^/api/tts/analyze/[^/]+$");

    private static final Pattern ILLUSTRATION_ANALYZE_PATH = Pattern.compile("^/api/illustrations/analyze/[^/]+$");
    private static final Pattern ILLUSTRATION_CHAPTER_ACTION_PATH =
            Pattern.compile("^/api/illustrations/chapter/[^/]+/(request|prefetch-next|regenerate)$");

    private static final Pattern CHARACTER_CHAPTER_ACTION_PATH =
            Pattern.compile("^/api/characters/chapter/[^/]+/(analyze|prefetch-next)$");
    private static final Pattern CHARACTER_BOOK_PREFETCH_PATH = Pattern.compile("^/api/characters/book/[^/]+/prefetch$");
    private static final Pattern CHARACTER_CHAT_PATH = Pattern.compile("^/api/characters/[^/]+/chat$");

    private static final Pattern PREGEN_BOOK_PATH = Pattern.compile("^/api/pregen/book/[^/]+$");
    private static final Pattern PREGEN_GUTENBERG_PATH = Pattern.compile("^/api/pregen/gutenberg/\\d+$");
    private static final Pattern PREGEN_BOOK_COVERS_PATH = Pattern.compile("^/api/pregen/book/[^/]+/covers$");
    private static final Pattern PREGEN_GUTENBERG_COVERS_PATH = Pattern.compile("^/api/pregen/gutenberg/\\d+/covers$");
    private static final Pattern PREGEN_JOB_BOOK_PATH = Pattern.compile("^/api/pregen/jobs/book/[^/]+$");
    private static final Pattern PREGEN_JOB_GUTENBERG_PATH = Pattern.compile("^/api/pregen/jobs/gutenberg/\\d+$");
    private static final Pattern PREGEN_JOB_STATUS_PATH = Pattern.compile("^/api/pregen/jobs/[^/]+$");
    private static final Pattern PREGEN_JOB_CANCEL_PATH = Pattern.compile("^/api/pregen/jobs/[^/]+/cancel$");

    private static final Pattern RECAP_GENERATE_PATH = Pattern.compile("^/api/recaps/chapter/[^/]+/generate$");
    private static final Pattern RECAP_REQUEUE_PATH = Pattern.compile("^/api/recaps/book/[^/]+/requeue-stuck$");
    private static final Pattern RECAP_CHAT_PATH = Pattern.compile("^/api/recaps/book/[^/]+/chat$");

    private static final Pattern QUIZ_GENERATE_PATH = Pattern.compile("^/api/quizzes/chapter/[^/]+/generate$");
    private static final Pattern LIBRARY_FEATURES_PATH = Pattern.compile("^/api/library/[^/]+/features$");
    private static final Pattern LIBRARY_COVER_REQUEST_PATH = Pattern.compile("^/api/library/[^/]+/cover/request$");
    private static final Pattern LIBRARY_COVER_RETRY_PATH = Pattern.compile("^/api/library/[^/]+/cover/retry$");
    private static final Pattern LIBRARY_COVER_PATH = Pattern.compile("^/api/library/[^/]+/cover$");
    private static final Pattern LIBRARY_DELETE_BOOK_PATH = Pattern.compile("^/api/library/[^/]+$");

    private SensitiveApiRequestMatcher() {
    }

    public enum EndpointType {
        NONE,
        GENERATION,
        CHAT,
        ADMIN
    }

    public static EndpointType classify(String method, String path) {
        if (method == null || path == null) {
            return EndpointType.NONE;
        }

        if ("POST".equals(method)) {
            if ("/api/tts/speak".equals(path)
                    || TTS_ANALYZE_PATH.matcher(path).matches()
                    || ILLUSTRATION_ANALYZE_PATH.matcher(path).matches()
                    || ILLUSTRATION_CHAPTER_ACTION_PATH.matcher(path).matches()
                    || "/api/illustrations/retry-stuck".equals(path)
                    || CHARACTER_CHAPTER_ACTION_PATH.matcher(path).matches()
                    || CHARACTER_BOOK_PREFETCH_PATH.matcher(path).matches()
                    || PREGEN_BOOK_PATH.matcher(path).matches()
                    || PREGEN_GUTENBERG_PATH.matcher(path).matches()
                    || PREGEN_BOOK_COVERS_PATH.matcher(path).matches()
                    || PREGEN_GUTENBERG_COVERS_PATH.matcher(path).matches()
                    || PREGEN_JOB_BOOK_PATH.matcher(path).matches()
                    || PREGEN_JOB_GUTENBERG_PATH.matcher(path).matches()
                    || PREGEN_JOB_CANCEL_PATH.matcher(path).matches()
                    || RECAP_GENERATE_PATH.matcher(path).matches()
                    || RECAP_REQUEUE_PATH.matcher(path).matches()
                    || QUIZ_GENERATE_PATH.matcher(path).matches()
                    || LIBRARY_COVER_REQUEST_PATH.matcher(path).matches()
                    || LIBRARY_COVER_RETRY_PATH.matcher(path).matches()) {
                return EndpointType.GENERATION;
            }

            if (CHARACTER_CHAT_PATH.matcher(path).matches()
                    || RECAP_CHAT_PATH.matcher(path).matches()) {
                return EndpointType.CHAT;
            }
        }

        if ("GET".equals(method)) {
            if (PREGEN_JOB_STATUS_PATH.matcher(path).matches()) {
                return EndpointType.GENERATION;
            }
        }

        if ("PATCH".equals(method) && LIBRARY_FEATURES_PATH.matcher(path).matches()) {
            return EndpointType.ADMIN;
        }

        if ("PUT".equals(method) && LIBRARY_COVER_PATH.matcher(path).matches()) {
            return EndpointType.ADMIN;
        }

        if ("DELETE".equals(method) && PREGEN_JOB_STATUS_PATH.matcher(path).matches()) {
            return EndpointType.GENERATION;
        }

        if ("DELETE".equals(method)
                && ("/api/library".equals(path) || LIBRARY_DELETE_BOOK_PATH.matcher(path).matches())) {
            return EndpointType.ADMIN;
        }

        return EndpointType.NONE;
    }
}
