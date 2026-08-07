package com.classicchatreader.service;

import com.classicchatreader.entity.QuizQuestionOverrideEntity;
import com.classicchatreader.model.ChapterQuizPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectiveQuizAssemblerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void merge_disableOverrideAdd_appliesInOrder() throws Exception {
        ChapterQuizPayload generated = new ChapterQuizPayload(List.of(
                question("q1", "Generated one?", List.of("A", "B"), 0),
                question("q2", "Generated two?", List.of("C", "D"), 1)
        ));

        QuizQuestionOverrideEntity disable = row(
                "o-disable",
                QuizQuestionOverrideEntity.OPERATION_DISABLE,
                "q1",
                null,
                0);
        QuizQuestionOverrideEntity override = row(
                "o-override",
                QuizQuestionOverrideEntity.OPERATION_OVERRIDE,
                "q2",
                objectMapper.writeValueAsString(question("q2", "Overridden two?", List.of("X", "Y"), 0)),
                0);
        QuizQuestionOverrideEntity add = row(
                "o-add",
                QuizQuestionOverrideEntity.OPERATION_ADD,
                null,
                objectMapper.writeValueAsString(question("q3", "Added three?", List.of("E", "F"), 1)),
                5);
        QuizQuestionOverrideEntity archived = row(
                "o-archived",
                QuizQuestionOverrideEntity.OPERATION_ADD,
                null,
                objectMapper.writeValueAsString(question("q4", "Should skip?", List.of("G", "H"), 0)),
                1);
        archived.setStatus(QuizQuestionOverrideEntity.STATUS_ARCHIVED);

        EffectiveQuizAssembler.MergeResult result = EffectiveQuizAssembler.merge(
                generated,
                List.of(add, override, disable, archived),
                objectMapper);

        assertEquals(2, result.effective().questions().size());
        assertEquals("q2", result.effective().questions().get(0).id());
        assertEquals("Overridden two?", result.effective().questions().get(0).question());
        assertEquals("q3", result.effective().questions().get(1).id());
        assertTrue(result.staleOverrideIds().isEmpty());
    }

    @Test
    void merge_staleSourceIds_areReportedAndSkipped() throws Exception {
        ChapterQuizPayload generated = new ChapterQuizPayload(List.of(
                question("q1", "Only one?", List.of("A", "B"), 0)
        ));
        QuizQuestionOverrideEntity staleDisable = row(
                "stale-1",
                QuizQuestionOverrideEntity.OPERATION_DISABLE,
                "missing",
                null,
                0);
        QuizQuestionOverrideEntity staleOverride = row(
                "stale-2",
                QuizQuestionOverrideEntity.OPERATION_OVERRIDE,
                "also-missing",
                objectMapper.writeValueAsString(question("x", "Nope?", List.of("A", "B"), 0)),
                0);

        EffectiveQuizAssembler.MergeResult result = EffectiveQuizAssembler.merge(
                generated,
                List.of(staleDisable, staleOverride),
                objectMapper);

        assertEquals(1, result.effective().questions().size());
        assertEquals(List.of("stale-1", "stale-2"), result.staleOverrideIds());
    }

    @Test
    void merge_suppressBase_stillAppliesOverrideRowsAndIgnoresUnreferencedBase() throws Exception {
        ChapterQuizPayload generated = new ChapterQuizPayload(List.of(
                question("q1", "Visible one?", List.of("A", "B"), 0),
                question("q2", "Hidden by max-questions later?", List.of("C", "D"), 1),
                question("q3", "Also hidden?", List.of("E", "F"), 0)
        ));
        QuizQuestionOverrideEntity suppress = row(
                "o-suppress",
                QuizQuestionOverrideEntity.OPERATION_SUPPRESS_GENERATED,
                null,
                null,
                -1);
        suppress.setOverlayKey(QuizQuestionOverrideEntity.SUPPRESS_OVERLAY_KEY);
        QuizQuestionOverrideEntity override = row(
                "o-override",
                QuizQuestionOverrideEntity.OPERATION_OVERRIDE,
                "q1",
                objectMapper.writeValueAsString(question("q1", "Kept edited?", List.of("X", "Y"), 1)),
                0);
        QuizQuestionOverrideEntity add = row(
                "o-add",
                QuizQuestionOverrideEntity.OPERATION_ADD,
                null,
                objectMapper.writeValueAsString(question("q-new", "Teacher add?", List.of("G", "H"), 0)),
                1);

        EffectiveQuizAssembler.MergeResult result = EffectiveQuizAssembler.merge(
                generated,
                List.of(suppress, override, add),
                objectMapper);

        assertEquals(2, result.effective().questions().size());
        assertEquals("q1", result.effective().questions().get(0).id());
        assertEquals("Kept edited?", result.effective().questions().get(0).question());
        assertEquals("q-new", result.effective().questions().get(1).id());
        assertTrue(result.staleOverrideIds().isEmpty());
    }

    private static ChapterQuizPayload.Question question(
            String id, String stem, List<String> options, int correctIndex) {
        return new ChapterQuizPayload.Question(id, stem, options, correctIndex, 0, "cite");
    }

    private static QuizQuestionOverrideEntity row(
            String id,
            String operation,
            String sourceQuestionId,
            String questionJson,
            int sortOrder) {
        QuizQuestionOverrideEntity entity = new QuizQuestionOverrideEntity();
        entity.setId(id);
        entity.setTermId("term-1");
        entity.setBookId("book-1");
        entity.setChapterId("chapter-1");
        entity.setOperation(operation);
        entity.setSourceQuestionId(sourceQuestionId);
        entity.setOverlayKey(sourceQuestionId != null ? sourceQuestionId : id);
        entity.setSortOrder(sortOrder);
        entity.setQuestionJson(questionJson);
        entity.setStatus(QuizQuestionOverrideEntity.STATUS_ACTIVE);
        return entity;
    }
}
