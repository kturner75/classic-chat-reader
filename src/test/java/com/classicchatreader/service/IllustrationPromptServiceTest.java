package com.classicchatreader.service;

import com.classicchatreader.model.IllustrationSettings;
import com.classicchatreader.service.llm.LlmOptions;
import com.classicchatreader.service.llm.LlmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IllustrationPromptServiceTest {

    @Mock
    private LlmProvider reasoningProvider;

    private IllustrationPromptService service;

    @BeforeEach
    void setUp() {
        when(reasoningProvider.getProviderName()).thenReturn("test");
        service = new IllustrationPromptService(reasoningProvider);
        ReflectionTestUtils.setField(service, "cacheOnly", false);
    }

    @Test
    void chapterPromptWriterDoesNotInjectClassroomSafetyRules() {
        when(reasoningProvider.generate(anyString(), any(LlmOptions.class)))
                .thenReturn("watercolor plate, Polly sewing in the Shaw parlor");

        service.generatePromptForChapter(
                "An Old-Fashioned Girl",
                "Louisa May Alcott",
                "Chapter 1",
                "Polly arrived in Boston and sat with Fanny in the parlor.",
                IllustrationSettings.defaults());

        ArgumentCaptor<String> writerPrompt = ArgumentCaptor.forClass(String.class);
        verify(reasoningProvider).generate(writerPrompt.capture(), any(LlmOptions.class));
        String sent = writerPrompt.getValue();
        assertThat(sent).doesNotContain(ImagePromptSafety.LLM_RULES);
        assertThat(sent).doesNotContain("CLASSROOM IMAGE SAFETY");
        assertThat(sent).doesNotContain("Prefer setting, objects, atmosphere");
        assertThat(sent).contains("writes a scene from this chapter");
    }

    @Test
    void chapterPromptIsNotRewrittenWithClassroomSuffixOrEmptyLandscape() {
        String llmPrompt = "Victorian parlor, an adolescent girl and her romantic friend sewing by the window";
        assertThat(ImagePromptSafety.isBlocked(llmPrompt)).isTrue();
        when(reasoningProvider.generate(anyString(), any(LlmOptions.class))).thenReturn(llmPrompt);

        String generated = service.generatePromptForChapter(
                "An Old-Fashioned Girl",
                "Louisa May Alcott",
                "Chapter 1",
                "Polly sat with Fanny.",
                IllustrationSettings.defaults());

        assertThat(generated).isEqualTo(llmPrompt);
        assertThat(generated).doesNotContain("School-appropriate book illustration");
        assertThat(generated).doesNotContain(ImagePromptSafety.SUFFIX.trim());
        assertThat(generated).doesNotContain("distant architecture and landscape only");
        assertThat(generated).doesNotContain("no figures");
    }

    @Test
    void fallbackPromptIsNotPassedThroughImagePromptSafety() {
        ReflectionTestUtils.setField(service, "cacheOnly", true);

        String fallback = service.generatePromptForChapter(
                "An Old-Fashioned Girl",
                "Louisa May Alcott",
                "Chapter 1",
                "Polly sat with Fanny.",
                IllustrationSettings.defaults());

        assertThat(fallback).contains("a scene from An Old-Fashioned Girl");
        assertThat(fallback).doesNotContain("School-appropriate book illustration");
        assertThat(fallback).doesNotContain(ImagePromptSafety.SUFFIX.trim());
        assertThat(fallback).doesNotContain("distant architecture and landscape only");
    }
}
