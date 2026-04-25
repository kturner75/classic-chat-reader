package com.classicchatreader.gutendex;

import com.classicchatreader.gutendex.GutenbergContentParser.ParsedBook;
import com.classicchatreader.gutendex.GutenbergContentParser.ParsedChapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GutenbergContentParserTest {

    private GutenbergContentParser parser;

    @BeforeEach
    void setUp() {
        parser = new GutenbergContentParser();
    }

    @Test
    void parseExtractsChaptersFromH2Headers() {
        String html = """
            <html>
            <body>
                <h2>Chapter I</h2>
                <p>This is the first paragraph of chapter one with enough text to pass the minimum length requirement.</p>
                <p>This is the second paragraph of chapter one with sufficient content to be included.</p>
                <h2>Chapter II</h2>
                <p>This is the first paragraph of chapter two with adequate length for parsing.</p>
            </body>
            </html>
            """;

        ParsedBook book = parser.parse(html);

        assertEquals(2, book.chapters().size());
        assertEquals("Chapter I", book.chapters().get(0).title());
        assertEquals(2, book.chapters().get(0).paragraphs().size());
        assertEquals("Chapter II", book.chapters().get(1).title());
        assertEquals(1, book.chapters().get(1).paragraphs().size());
    }

    @Test
    void parseExtractsChaptersFromH3Headers() {
        String html = """
            <html>
            <body>
                <h3>Part One</h3>
                <p>Content of part one with enough characters to meet the minimum threshold.</p>
                <h3>Part Two</h3>
                <p>Content of part two with sufficient length to be parsed correctly.</p>
            </body>
            </html>
            """;

        ParsedBook book = parser.parse(html);

        assertEquals(2, book.chapters().size());
        assertEquals("Part One", book.chapters().get(0).title());
        assertEquals("Part Two", book.chapters().get(1).title());
    }

    @Test
    void parseHandlesChapterDivs() {
        String html = """
            <html>
            <body>
                <div class="chapter">
                    <h4>The Beginning</h4>
                    <p>First chapter content with adequate length for the parser to include it.</p>
                </div>
                <div class="chapter">
                    <h4>The Middle</h4>
                    <p>Second chapter content with sufficient text to pass validation.</p>
                </div>
            </body>
            </html>
            """;

        ParsedBook book = parser.parse(html);

        assertEquals(2, book.chapters().size());
        assertEquals("The Beginning", book.chapters().get(0).title());
        assertEquals("The Middle", book.chapters().get(1).title());
    }

    @Test
    void parseRemovesGutenbergBoilerplate() {
        String html = """
            <html>
            <body>
                <p>Start of this Project Gutenberg eBook</p>
                <p>Produced by Someone at Distributed Proofreading</p>
                <h2>Chapter 1</h2>
                <p>Actual book content that should be preserved by the parser.</p>
                <p>End of this Project Gutenberg eBook</p>
            </body>
            </html>
            """;

        ParsedBook book = parser.parse(html);

        assertEquals(1, book.chapters().size());
        assertEquals(1, book.chapters().get(0).paragraphs().size());
        assertTrue(book.chapters().get(0).paragraphs().get(0).contains("Actual book content"));
    }

    @Test
    void parseCreatesOneChapterWhenNoChaptersFound() {
        String html = """
            <html>
            <body>
                <p>Just a simple paragraph with enough content to be included by the parser.</p>
                <p>Another paragraph without any chapter markers but with sufficient length.</p>
            </body>
            </html>
            """;

        ParsedBook book = parser.parse(html);

        assertEquals(1, book.chapters().size());
        // Default title when no chapter markers are found
        assertEquals("Chapter 1", book.chapters().get(0).title());
        assertEquals(2, book.chapters().get(0).paragraphs().size());
    }

    @Test
    void parseRecognizesChapterPatternsInText() {
        String html = """
            <html>
            <body>
                <p>CHAPTER I</p>
                <p>First chapter content with sufficient length to pass the minimum threshold.</p>
                <p>Chapter II</p>
                <p>Second chapter content that is long enough to be included in results.</p>
            </body>
            </html>
            """;

        ParsedBook book = parser.parse(html);

        assertEquals(2, book.chapters().size());
    }

    @Test
    void parseRecognizesRomanNumerals() {
        String html = """
            <html>
            <body>
                <p>I.</p>
                <p>First section content with adequate length for the parser validation.</p>
                <p>II.</p>
                <p>Second section content that meets the minimum character requirement.</p>
            </body>
            </html>
            """;

        ParsedBook book = parser.parse(html);

        assertEquals(2, book.chapters().size());
    }

    @Test
    void parseFiltersShortParagraphs() {
        String html = """
            <html>
            <body>
                <h2>Chapter 1</h2>
                <p>Short</p>
                <p>This is a longer paragraph that should be included in the parsed output.</p>
                <p>Too short</p>
            </body>
            </html>
            """;

        ParsedBook book = parser.parse(html);

        assertEquals(1, book.chapters().size());
        assertEquals(1, book.chapters().get(0).paragraphs().size());
    }

    @Test
    void parseNormalizesWhitespace() {
        String html = """
            <html>
            <body>
                <h2>Chapter 1</h2>
                <p>This   has    multiple     spaces   that   should   be   normalized   properly.</p>
            </body>
            </html>
            """;

        ParsedBook book = parser.parse(html);

        String paragraph = book.chapters().get(0).paragraphs().get(0);
        assertFalse(paragraph.contains("  "));
    }

    @Test
    void parseHandlesEmptyHtml() {
        String html = "<html><body></body></html>";

        ParsedBook book = parser.parse(html);

        assertEquals(1, book.chapters().size());
        assertEquals("Full Text", book.chapters().get(0).title());
        assertTrue(book.chapters().get(0).paragraphs().isEmpty());
    }

    @Test
    void parseRemovesPreTags() {
        String html = """
            <html>
            <body>
                <pre>License text that should be removed from output</pre>
                <h2>Chapter 1</h2>
                <p>Regular content that should be preserved by the parser correctly.</p>
            </body>
            </html>
            """;

        ParsedBook book = parser.parse(html);

        assertEquals(1, book.chapters().size());
        for (String p : book.chapters().get(0).paragraphs()) {
            assertFalse(p.contains("License"));
        }
    }

    @Test
    void parseExtractsDropCapFromImageAltText() {
        // Gutenberg often uses images for decorative first letters (drop caps)
        // The letter is stored in the img alt attribute
        String html = """
            <html>
            <body>
                <h2>Chapter V</h2>
                <p><img src="images/dropcap_w.png" alt="W">ITHIN a short walk of the house there lived a family.</p>
                <p>This is the second paragraph without a drop cap image.</p>
            </body>
            </html>
            """;

        ParsedBook book = parser.parse(html);

        assertEquals(1, book.chapters().size());
        assertEquals(2, book.chapters().get(0).paragraphs().size());
        // The drop cap "W" should be extracted from the alt attribute
        assertTrue(book.chapters().get(0).paragraphs().get(0).startsWith("WITHIN"),
            "Expected paragraph to start with 'WITHIN' but got: " + book.chapters().get(0).paragraphs().get(0));
    }

    @Test
    void parseIgnoresNonDropCapImages() {
        // Images with multi-character alt text or non-letter alt text should not be treated as drop caps
        String html = """
            <html>
            <body>
                <h2>Chapter 1</h2>
                <p><img src="illustration.jpg" alt="A beautiful landscape">The story begins here with enough text to pass.</p>
            </body>
            </html>
            """;

        ParsedBook book = parser.parse(html);

        assertEquals(1, book.chapters().size());
        String para = book.chapters().get(0).paragraphs().get(0);
        assertTrue(para.startsWith("The story"),
            "Expected paragraph to start with 'The story' but got: " + para);
    }

    @Test
    void parsePreservesChapterTitleAfterNumber() {
        // Chapter titles like "Chapter 1. Loomings" should preserve the title name
        String html = """
            <html>
            <body>
                <h2>Chapter 1. Loomings</h2>
                <p>Call me Ishmael. Some years ago—never mind how long precisely.</p>
                <h2>Chapter 2. The Carpet-Bag</h2>
                <p>I stuffed a shirt or two into my old carpet-bag and started off.</p>
            </body>
            </html>
            """;

        ParsedBook book = parser.parse(html);

        assertEquals(2, book.chapters().size());
        assertEquals("Chapter 1. Loomings", book.chapters().get(0).title());
        assertEquals("Chapter 2. The Carpet-Bag", book.chapters().get(1).title());
    }

    @Test
    void parseExtractsChapterTitleFromMixedHeader() {
        // When header has extraneous content before chapter marker, extract chapter portion
        String html = """
            <html>
            <body>
                <h2>Some caption text. Chapter III. The Inn</h2>
                <p>The inn was a weathered building at the edge of town with many rooms.</p>
                <h2>Chapter IV. The Journey</h2>
                <p>They set off at dawn on horseback through the misty countryside.</p>
            </body>
            </html>
            """;

        ParsedBook book = parser.parse(html);

        assertEquals(2, book.chapters().size());
        assertEquals("Chapter III. The Inn", book.chapters().get(0).title());
        assertEquals("Chapter IV. The Journey", book.chapters().get(1).title());
    }

    @Test
    void parseRecognizesRomanNumeralWithTitle() {
        // Beowulf-style headers: "I. The Life and Death of Scyld"
        String html = """
            <html>
            <body>
                <h2>PREFACE</h2>
                <p>The translator's preface explaining the methodology and approach used.</p>
                <h2>I. The Life and Death of Scyld</h2>
                <p>Lo, we have heard of the glory of the Spear-Danes in days of old.</p>
                <h2>II. Scyld's Successors</h2>
                <p>Then Scyld departed at the destined hour, still in the prime of life.</p>
                <h2>III. Grendel the Murderer</h2>
                <p>Then a fierce spirit who dwelt in darkness wrathfully endured the torment.</p>
            </body>
            </html>
            """;

        ParsedBook book = parser.parse(html);

        assertEquals(4, book.chapters().size());
        assertEquals("PREFACE", book.chapters().get(0).title());
        assertEquals("I. The Life and Death of Scyld", book.chapters().get(1).title());
        assertEquals("II. Scyld's Successors", book.chapters().get(2).title());
        assertEquals("III. Grendel the Murderer", book.chapters().get(3).title());
    }

    @Test
    void parseExtractsVerseContentFromDivElements() {
        // Poetry like Beowulf uses <div class="l"> for verse lines instead of <p>
        String html = """
            <html>
            <body>
                <h2>I.</h2>
                <div class="l">Lo! the Spear-Danes' glory through splendid achievements</div>
                <div class="l">The folk-kings' former fame we have heard of,</div>
                <div class="l">How princes displayed then their prowess-in-battle.</div>
                <h2>II.</h2>
                <div class="l">Oft Scyld the Scefing from scathers in numbers</div>
                <div class="l">From many a people their mead-benches tore.</div>
            </body>
            </html>
            """;

        ParsedBook book = parser.parse(html);

        assertEquals(2, book.chapters().size());
        assertEquals("I.", book.chapters().get(0).title());
        assertEquals(3, book.chapters().get(0).paragraphs().size());
        assertTrue(book.chapters().get(0).paragraphs().get(0).contains("Spear-Danes"));
        assertEquals("II.", book.chapters().get(1).title());
        assertEquals(2, book.chapters().get(1).paragraphs().size());
    }
}
