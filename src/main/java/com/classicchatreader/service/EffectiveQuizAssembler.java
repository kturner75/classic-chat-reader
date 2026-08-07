package com.classicchatreader.service;

import com.classicchatreader.entity.QuizQuestionOverrideEntity;
import com.classicchatreader.model.ChapterQuizPayload;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Pure merge of generated chapter quiz payload with class-scoped overlay rows.
 * Merge order for ACTIVE rows: DISABLE → OVERRIDE → ADD (by sort_order).
 */
public final class EffectiveQuizAssembler {

    private EffectiveQuizAssembler() {
    }

    public record MergeResult(
            ChapterQuizPayload effective,
            List<String> staleOverrideIds
    ) {
    }

    public static MergeResult merge(
            ChapterQuizPayload generated,
            List<QuizQuestionOverrideEntity> overrides,
            ObjectMapper objectMapper) {
        ChapterQuizPayload base = generated == null || generated.questions() == null
                ? new ChapterQuizPayload(List.of())
                : generated;

        Map<String, ChapterQuizPayload.Question> byId = new LinkedHashMap<>();
        for (ChapterQuizPayload.Question question : base.questions()) {
            if (question == null) {
                continue;
            }
            String id = ensureId(question.id());
            byId.put(id, withId(question, id));
        }

        List<QuizQuestionOverrideEntity> active = overrides == null
                ? List.of()
                : overrides.stream()
                .filter(Objects::nonNull)
                .filter(row -> row.getDeletedAt() == null)
                .filter(row -> QuizQuestionOverrideEntity.STATUS_ACTIVE.equalsIgnoreCase(nullToEmpty(row.getStatus())))
                .sorted(Comparator
                        .comparingInt((QuizQuestionOverrideEntity row) -> operationRank(row.getOperation()))
                        .thenComparingInt(QuizQuestionOverrideEntity::getSortOrder)
                        .thenComparing(row -> nullToEmpty(row.getId())))
                .toList();

        List<String> staleOverrideIds = new ArrayList<>();
        List<ChapterQuizPayload.Question> additions = new ArrayList<>();

        for (QuizQuestionOverrideEntity row : active) {
            String operation = nullToEmpty(row.getOperation()).toUpperCase(Locale.ROOT);
            switch (operation) {
                case QuizQuestionOverrideEntity.OPERATION_DISABLE -> {
                    String sourceId = trimToNull(row.getSourceQuestionId());
                    if (sourceId == null || !byId.containsKey(sourceId)) {
                        staleOverrideIds.add(row.getId());
                        continue;
                    }
                    byId.remove(sourceId);
                }
                case QuizQuestionOverrideEntity.OPERATION_OVERRIDE -> {
                    String sourceId = trimToNull(row.getSourceQuestionId());
                    if (sourceId == null || !byId.containsKey(sourceId)) {
                        staleOverrideIds.add(row.getId());
                        continue;
                    }
                    ChapterQuizPayload.Question parsed = parseQuestion(row.getQuestionJson(), objectMapper);
                    if (parsed == null) {
                        staleOverrideIds.add(row.getId());
                        continue;
                    }
                    byId.put(sourceId, withId(parsed, sourceId));
                }
                case QuizQuestionOverrideEntity.OPERATION_ADD -> {
                    ChapterQuizPayload.Question parsed = parseQuestion(row.getQuestionJson(), objectMapper);
                    if (parsed == null) {
                        staleOverrideIds.add(row.getId());
                        continue;
                    }
                    String id = ensureId(parsed.id());
                    additions.add(withId(parsed, id));
                }
                default -> staleOverrideIds.add(row.getId());
            }
        }

        List<ChapterQuizPayload.Question> merged = new ArrayList<>(byId.values());
        merged.addAll(additions);
        return new MergeResult(new ChapterQuizPayload(merged), List.copyOf(staleOverrideIds));
    }

    public static Set<String> generatedQuestionIds(ChapterQuizPayload generated) {
        Set<String> ids = new HashSet<>();
        if (generated == null || generated.questions() == null) {
            return ids;
        }
        for (ChapterQuizPayload.Question question : generated.questions()) {
            if (question != null && question.id() != null && !question.id().isBlank()) {
                ids.add(question.id().trim());
            }
        }
        return ids;
    }

    private static int operationRank(String operation) {
        return switch (nullToEmpty(operation).toUpperCase(Locale.ROOT)) {
            case QuizQuestionOverrideEntity.OPERATION_DISABLE -> 0;
            case QuizQuestionOverrideEntity.OPERATION_OVERRIDE -> 1;
            case QuizQuestionOverrideEntity.OPERATION_ADD -> 2;
            default -> 99;
        };
    }

    private static ChapterQuizPayload.Question parseQuestion(String questionJson, ObjectMapper objectMapper) {
        if (questionJson == null || questionJson.isBlank() || objectMapper == null) {
            return null;
        }
        try {
            ChapterQuizPayload.Question parsed = objectMapper.readValue(questionJson, ChapterQuizPayload.Question.class);
            if (parsed == null || parsed.question() == null || parsed.question().isBlank()) {
                return null;
            }
            if (parsed.options() == null || parsed.options().size() < 2) {
                return null;
            }
            return parsed;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static ChapterQuizPayload.Question withId(ChapterQuizPayload.Question question, String id) {
        return new ChapterQuizPayload.Question(
                id,
                question.question(),
                question.options(),
                question.correctOptionIndex(),
                question.citationParagraphIndex(),
                question.citationSnippet()
        );
    }

    private static String ensureId(String id) {
        String trimmed = trimToNull(id);
        return trimmed != null ? trimmed : UUID.randomUUID().toString();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
