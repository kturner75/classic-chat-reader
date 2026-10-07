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

    @Test
    void aRowLinkingBothItsNumberAndItsTitleIsOneEntry() {
        String html = """
            <html><body>
            <h2>CONTENTS:</h2>
            <table>
              <tr><td><a href="#c1">I.</a></td><td><a href="#c1">Howe's Masquerade</a></td></tr>
              <tr><td><a href="#c2">II.</a></td><td><a href="#c2">Edward Randolph's Portrait</a></td></tr>
            </table>
            <h2>I. Howe's Masquerade</h2>
            </body></html>
            """;

        assertEquals(List.of("I. Howe's Masquerade", "II. Edward Randolph's Portrait"),
            extractor.extract(html).stream().map(ContentsEntry::title).toList());
    }

    @Test
    void anH4ContentsHeadingStopsAtTheNextH4() {
        String html = """
            <html><body>
            <h4>Contents</h4>
            <p><a href="#c1">One</a></p>
            <h4>Preface</h4>
            <p><a href="#c9">Not contents</a></p>
            </body></html>
            """;

        assertEquals(List.of("One"), extractor.extract(html).stream().map(ContentsEntry::title).toList());
    }

    @Test
    void aHeadingWrappedAloneInADivStillFindsTheTableAfterIt() {
        String html = """
            <html><body>
            <div class="chapter"><h2>CONTENTS</h2></div>
            <table><tr><td><a href="#c1">THE GRAY CHAMPION</a></td></tr></table>
            <div class="chapter"><h2>THE GRAY CHAMPION</h2></div>
            </body></html>
            """;

        assertEquals(List.of("THE GRAY CHAMPION"), extractor.extract(html).stream().map(ContentsEntry::title).toList());
    }

    @Test
    void aRowLinkingOnlyItsNumberTakesTheUnlinkedTitleCell() {
        String html = """
            <html><body>
            <h2>CONTENTS</h2>
            <table>
              <tr><td><a href="#c1">I.</a></td><td>Howe's Masquerade</td></tr>
              <tr><td><a href="#c2">II.</a></td><td>Edward Randolph's Portrait</td></tr>
            </table>
            <h2>I. Howe's Masquerade</h2>
            </body></html>
            """;

        assertEquals(List.of("I. Howe's Masquerade", "II. Edward Randolph's Portrait"),
            extractor.extract(html).stream().map(ContentsEntry::title).toList());
    }

    @Test
    void aNestedListKeepsTheGroupAndItsChildrenAsSeparateEntries() {
        String html = """
            <html><body>
            <h2>CONTENTS</h2>
            <ul>
              <li><a href="#p1">PART I:</a>
                <ul>
                  <li><a href="#c1">Chapter 1</a></li>
                  <li><a href="#c2">Chapter 2</a></li>
                </ul>
              </li>
              <li><a href="#c3">Epilogue</a></li>
            </ul>
            <h2>PART I</h2>
            </body></html>
            """;

        assertEquals(List.of(
            new ContentsEntry("PART I:", true),
            new ContentsEntry("Chapter 1", false),
            new ContentsEntry("Chapter 2", false),
            new ContentsEntry("Epilogue", false)), extractor.extract(html));
    }

    @Test
    void linkedPageNumbersAreNotPartOfTheTitle() {
        String html = """
            <html><body>
            <h2>CONTENTS</h2>
            <table>
              <tr><td><a href="#CHAPTER_I">CHAPTER I</a></td><td><a href="#Page_1">1</a></td></tr>
              <tr><td><a href="#CHAPTER_II">CHAPTER II. The Garden</a></td><td><a href="#Page_17">17</a></td></tr>
              <tr><td><a href="#c3">I.</a></td><td><a href="#c3">Howe's Masquerade</a></td></tr>
            </table>
            <h2>CHAPTER I</h2>
            </body></html>
            """;

        assertEquals(List.of("CHAPTER I", "CHAPTER II. The Garden", "I. Howe's Masquerade"),
            extractor.extract(html).stream().map(ContentsEntry::title).toList());
    }

    @Test
    void paragraphStyleContentsDropLinkedPageNumbersToo() {
        String html = """
            <html><body>
            <h2>CONTENTS</h2>
            <p><a href="#CHAPTER_I">CHAPTER I</a> <a href="#Page_1">1</a></p>
            <p><a href="#CHAPTER_II">CHAPTER II</a> <a href="#Page_17">17</a></p>
            <p><a href="#Page_30">30</a></p>
            <h2>CHAPTER I</h2>
            </body></html>
            """;

        assertEquals(List.of("CHAPTER I", "CHAPTER II"),
            extractor.extract(html).stream().map(ContentsEntry::title).toList());
    }

    @Test
    void chaptersThatAreLiterallyNumbersAreKept() {
        String html = """
            <html><body>
            <h2>CONTENTS</h2>
            <p><a href="#c1">1</a></p>
            <p><a href="#c2">2</a></p>
            <h2>1</h2>
            </body></html>
            """;

        assertEquals(List.of("1", "2"), extractor.extract(html).stream().map(ContentsEntry::title).toList());
    }

    @Test
    void aTitleThatLinksToAPageAnchorIsKept() {
        String html = """
            <html><body>
            <h2>CONTENTS</h2>
            <p><a href="#Page_1">CHAPTER I</a></p>
            <p><a href="#Page_9">CHAPTER II</a> <a href="#Page_9">9</a></p>
            <p><a href="#Page_xiv">PREFACE</a> <a href="#Page_xiv">xiv</a></p>
            <h2>CHAPTER I</h2>
            </body></html>
            """;

        assertEquals(List.of("CHAPTER I", "CHAPTER II", "PREFACE"),
            extractor.extract(html).stream().map(ContentsEntry::title).toList());
    }

    @Test
    void aParagraphWithALinkedNumberAndUnlinkedTitleIsOneEntry() {
        String html = """
            <html><body>
            <h2>CONTENTS</h2>
            <p><a href="#c1">I.</a> Howe's Masquerade</p>
            <p><a href="#c2">II.</a> Edward Randolph's Portrait</p>
            <h2>I. Howe's Masquerade</h2>
            </body></html>
            """;

        assertEquals(List.of("I. Howe's Masquerade", "II. Edward Randolph's Portrait"),
            extractor.extract(html).stream().map(ContentsEntry::title).toList());
    }

    @Test
    void paragraphsInsideAWrapperAreSeparateEntries() {
        String html = """
            <html><body>
            <h2>CONTENTS</h2>
            <div>
              <p><a href="#c1">I.</a> First Story</p>
              <p><a href="#c2">II.</a> Second Story</p>
            </div>
            <h2>I. First Story</h2>
            </body></html>
            """;

        assertEquals(List.of("I. First Story", "II. Second Story"),
            extractor.extract(html).stream().map(ContentsEntry::title).toList());
    }
}
