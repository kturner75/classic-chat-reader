package com.classicchatreader.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MlaCitationFormatterTest {

    private final MlaCitationFormatter formatter = new MlaCitationFormatter();

    @Test
    void format_withFullMetadata_returnsDeterministicCitation() {
        MlaCitationFormatter.Metadata metadata = new MlaCitationFormatter.Metadata(
                "Jane Austen",
                "Pride and Prejudice",
                "T. Egerton",
                1813,
                null,
                null,
                "https://www.gutenberg.org/ebooks/1342",
                LocalDate.of(2026, 2, 26)
        );

        String citation = formatter.format(metadata);

        assertEquals(
                "Austen, Jane. Pride and Prejudice. T. Egerton, 1813. https://www.gutenberg.org/ebooks/1342. Accessed 26 February 2026.",
                citation
        );
    }

    @Test
    void format_withMissingOptionalMetadata_omitsUnavailableSegments() {
        MlaCitationFormatter.Metadata metadata = new MlaCitationFormatter.Metadata(
                "",
                "Frankenstein",
                null,
                null,
                null,
                null,
                null,
                null
        );

        String citation = formatter.format(metadata);

        assertEquals("Frankenstein.", citation);
    }

    @Test
    void format_withMissingTitle_usesUntitledFallback() {
        MlaCitationFormatter.Metadata metadata = new MlaCitationFormatter.Metadata(
                "Mary Shelley",
                "   ",
                null,
                null,
                null,
                null,
                null,
                null
        );

        String citation = formatter.format(metadata);

        assertEquals("Shelley, Mary. Untitled work.", citation);
    }

    @Test
    void format_withSuffixAuthor_formatsSuffixInMlaOrder() {
        MlaCitationFormatter.Metadata metadata = new MlaCitationFormatter.Metadata(
                "Martin Luther King Jr",
                "Why We Can't Wait",
                null,
                null,
                null,
                null,
                null,
                null
        );

        String citation = formatter.format(metadata);

        assertEquals("King, Martin Luther, Jr. Why We Can't Wait.", citation);
    }

    @Test
    void format_withPreformattedAuthor_preservesProvidedOrder() {
        MlaCitationFormatter.Metadata metadata = new MlaCitationFormatter.Metadata(
                "Austen, Jane",
                "Emma",
                null,
                null,
                null,
                null,
                null,
                null
        );

        String citation = formatter.format(metadata);

        assertEquals("Austen, Jane. Emma.", citation);
    }

    @Test
    void format_withContainerLocationAndAccessedDate_includesWebsiteCitationSegments() {
        MlaCitationFormatter.Metadata metadata = new MlaCitationFormatter.Metadata(
                "Jane Austen",
                "Pride and Prejudice",
                null,
                null,
                "Project Gutenberg",
                "ClassicChatReader",
                "https://example.com/?book=book-1&chapter=ch-1&paragraph=4",
                LocalDate.of(2026, 4, 5)
        );

        String citation = formatter.format(metadata);

        assertEquals(
                "Austen, Jane. Pride and Prejudice. Project Gutenberg. ClassicChatReader. https://example.com/?book=book-1&chapter=ch-1&paragraph=4. Accessed 5 April 2026.",
                citation
        );
    }
}
