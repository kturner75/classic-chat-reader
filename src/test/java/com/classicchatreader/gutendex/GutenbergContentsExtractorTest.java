package com.classicchatreader.gutendex;

import com.classicchatreader.gutendex.GutenbergContentsExtractor.ContentsEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GutenbergContentsExtractorTest {

    private final GutenbergContentsExtractor extractor = new GutenbergContentsExtractor();

    @Test
    void readsLinkedEntriesFromAContentsTable() {
        String html = """
            <html><body>
            <h1>TWICE-TOLD TALES</h1>
            <h2>CONTENTS</h2>
            <table><tbody>
              <tr><td><a href="https://example.org/x.html#chap01">THE GRAY CHAMPION</a></td></tr>
              <tr><td><a href="#chap02">THE GREAT CARBUNCLE[4]</a></td></tr>
              <tr><td><a href="#chap03">LEGENDS OF THE PROVINCE HOUSE:</a></td></tr>
            </tbody></table>
            <hr>
            <div class="chapter"><h2>Twice-Told Tales</h2></div>
            <p><a href="#chap99">A link in the book body</a></p>
            </body></html>
            """;

        List<ContentsEntry> entries = extractor.extract(html);

        assertEquals(List.of(
            new ContentsEntry("THE GRAY CHAMPION", false),
            new ContentsEntry("THE GREAT CARBUNCLE", false),
            new ContentsEntry("LEGENDS OF THE PROVINCE HOUSE:", true)), entries);
    }

    @Test
    void readsLinkedParagraphsAndLists() {
        String html = """
            <html><body>
            <h2>Contents</h2>
            <p><a href="#c1">Chapter I</a></p>
            <ul><li><a href="#c2">Chapter II</a></li></ul>
            <h2>Chapter I</h2><p><a href="#c1">not contents</a></p>
            </body></html>
            """;

        assertEquals(List.of("Chapter I", "Chapter II"),
            extractor.extract(html).stream().map(ContentsEntry::title).toList());
    }

    @Test
    void returnsNothingWhenThereIsNoContentsHeading() {
        assertTrue(extractor.extract("<html><body><h2>Chapter I</h2><p>text</p></body></html>").isEmpty());
    }
}
