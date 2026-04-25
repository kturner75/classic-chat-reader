package com.classicchatreader.gutendex;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GutenbergContentParser {

    private static final Pattern CHAPTER_PATTERN = Pattern.compile(
        "^(chapter|book|part|section|act|scene|canto|volume|stave)\\s+[IVXLCDM\\d]+",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern ROMAN_NUMERAL_PATTERN = Pattern.compile(
        "^[IVXLCDM]+\\.?$"
    );

    // Pattern for numbered sections like "I. Title" or "XIV. Some Title Here"
    private static final Pattern NUMBERED_SECTION_PATTERN = Pattern.compile(
        "^[IVXLCDM]+\\.\\s+.+",
        Pattern.CASE_INSENSITIVE
    );

    // Patterns for Gutenberg markers
    private static final Pattern START_MARKER = Pattern.compile(
        "\\*\\*\\*\\s*START OF (THE|THIS) PROJECT GUTENBERG",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern END_MARKER = Pattern.compile(
        "\\*\\*\\*\\s*END OF (THE|THIS) PROJECT GUTENBERG",
        Pattern.CASE_INSENSITIVE
    );

    // Boilerplate text patterns to filter out
    private static final List<String> BOILERPLATE_PHRASES = List.of(
        "project gutenberg",
        "distributed proofreading",
        "please read the license",
        "free use and distribution",
        "gutenberg ebook",
        "gutenberg license",
        "electronic version",
        "terms of use",
        "full license",
        "donation",
        "public domain",
        "copyright",
        "transcriber",
        "produced by",
        "proofreading team",
        "online at"
    );

    public record ParsedBook(List<ParsedChapter> chapters) {}

    public record ParsedChapter(String title, List<String> paragraphs) {}

    public ParsedBook parse(String html) {
        // First, try to extract just the book content between markers
        String cleanedHtml = extractContentBetweenMarkers(html);

        Document doc = Jsoup.parse(cleanedHtml);

        // Remove Gutenberg boilerplate elements
        removeBoilerplate(doc);

        // Try to find chapters
        List<ParsedChapter> chapters = extractChapters(doc);

        if (chapters.isEmpty()) {
            // No chapters found, treat whole content as one chapter
            chapters = List.of(extractAsOneChapter(doc));
        }

        // Filter out any remaining boilerplate from chapters
        chapters = filterBoilerplateFromChapters(chapters);

        // Ensure we always return at least one chapter (even if empty)
        if (chapters.isEmpty()) {
            chapters = List.of(new ParsedChapter("Full Text", List.of()));
        }

        return new ParsedBook(chapters);
    }

    private String extractContentBetweenMarkers(String html) {
        // Try to find START and END markers in the raw HTML
        Matcher startMatcher = START_MARKER.matcher(html);
        Matcher endMatcher = END_MARKER.matcher(html);

        int startPos = 0;
        int endPos = html.length();

        if (startMatcher.find()) {
            // Find the end of the line containing the start marker
            int lineEnd = html.indexOf('\n', startMatcher.end());
            if (lineEnd != -1) {
                startPos = lineEnd + 1;
            } else {
                startPos = startMatcher.end();
            }
        }

        if (endMatcher.find()) {
            endPos = endMatcher.start();
        }

        if (startPos < endPos && startPos > 0) {
            return html.substring(startPos, endPos);
        }

        return html;
    }

    private void removeBoilerplate(Document doc) {
        // Remove common Gutenberg boilerplate elements
        doc.select("pre").remove();
        doc.select(".pg-boilerplate").remove();
        doc.select("[class*=boilerplate]").remove();
        doc.select("[id*=boilerplate]").remove();

        // Remove small/centered text often used for notices
        for (Element small : doc.select("small")) {
            if (isBoilerplateText(small.text())) {
                small.remove();
            }
        }

        // Remove specific paragraph/div elements containing boilerplate
        // Only check leaf-level elements to avoid removing entire content trees
        List<Element> toRemove = new ArrayList<>();
        for (Element el : doc.select("p, div, span, td, li")) {
            // Use ownText to check only this element's direct text, not children
            String ownText = el.ownText().toLowerCase();
            String fullText = el.text().toLowerCase();

            // For paragraphs with only boilerplate content
            if (el.tagName().equals("p") && isBoilerplateText(fullText)) {
                toRemove.add(el);
            }
            // For other elements, check if their own text has boilerplate markers
            else if (ownText.contains("***") && (ownText.contains("start of") || ownText.contains("end of"))) {
                toRemove.add(el);
            }
        }

        for (Element el : toRemove) {
            el.remove();
        }
    }

    private boolean isBoilerplateText(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase();

        int matchCount = 0;
        for (String phrase : BOILERPLATE_PHRASES) {
            if (lower.contains(phrase)) {
                matchCount++;
                if (matchCount >= 2) return true;
            }
        }

        // Also check for specific patterns
        if (lower.contains("***") && lower.contains("gutenberg")) return true;
        if (lower.startsWith("this ebook") || lower.startsWith("this e-book")) return true;

        return false;
    }

    private List<ParsedChapter> filterBoilerplateFromChapters(List<ParsedChapter> chapters) {
        List<ParsedChapter> filtered = new ArrayList<>();

        for (ParsedChapter chapter : chapters) {
            // Filter out paragraphs that look like boilerplate
            List<String> cleanParagraphs = new ArrayList<>();
            for (String para : chapter.paragraphs()) {
                if (!isBoilerplateParagraph(para)) {
                    cleanParagraphs.add(para);
                }
            }

            // Only keep chapters with actual content
            if (!cleanParagraphs.isEmpty()) {
                // Also skip chapters with boilerplate titles
                String title = chapter.title();
                if (!isBoilerplateText(title.toLowerCase())) {
                    filtered.add(new ParsedChapter(title, cleanParagraphs));
                }
            }
        }

        return filtered;
    }

    private boolean isBoilerplateParagraph(String text) {
        if (text == null || text.length() < 20) return true;

        String lower = text.toLowerCase();

        // Check for boilerplate phrases
        for (String phrase : BOILERPLATE_PHRASES) {
            if (lower.contains(phrase)) {
                return true;
            }
        }

        return false;
    }

    private List<ParsedChapter> extractChapters(Document doc) {
        List<ParsedChapter> chapters = new ArrayList<>();

        // Combine h2 and h3 headers and find chapter-like ones
        Elements allHeaders = doc.select("h2, h3");
        if (allHeaders.size() > 1) {
            chapters = extractChaptersFromHeaders(doc, allHeaders);
            if (!chapters.isEmpty()) return chapters;
        }

        // Try divs with chapter class
        Elements chapterDivs = doc.select("div.chapter, div[class*=chapter]");
        if (!chapterDivs.isEmpty()) {
            for (Element div : chapterDivs) {
                String title = extractChapterTitle(div);
                List<String> paragraphs = extractParagraphs(div);
                if (!paragraphs.isEmpty()) {
                    chapters.add(new ParsedChapter(title, splitLongParagraphs(paragraphs)));
                }
            }
            if (!chapters.isEmpty()) return chapters;
        }

        // Try to find chapter markers in text (including verse lines)
        Elements allParagraphs = doc.select("p, div.l");
        chapters = extractChaptersFromParagraphs(allParagraphs);

        return chapters;
    }

    private List<ParsedChapter> extractChaptersFromHeaders(Document doc, Elements headers) {
        List<ParsedChapter> chapters = new ArrayList<>();

        // Filter to only include headers that look like actual chapters
        List<Element> chapterHeaders = new ArrayList<>();
        for (Element header : headers) {
            String title = header.text().trim();
            if (looksLikeChapterHeader(title)) {
                chapterHeaders.add(header);
            }
        }

        if (chapterHeaders.isEmpty()) {
            return chapters;
        }

        // Get all paragraphs and headers, then merge into ordered list based on source position
        Elements allParagraphs = doc.select("p");

        // Build a combined list of elements with their source positions
        record ElementWithPos(Element element, int sourcePos, boolean isHeader) {}
        List<ElementWithPos> allElements = new ArrayList<>();

        // Headers - use their index in the filtered chapterHeaders list
        java.util.Set<Element> headerSet = new java.util.HashSet<>(chapterHeaders);

        // Add all paragraphs, verse lines, and chapter headers to a list, tracking source position
        // by iterating the body in document order
        int position = 0;
        for (Element el : doc.body().getAllElements()) {
            if (headerSet.contains(el)) {
                allElements.add(new ElementWithPos(el, position++, true));
            } else if (el.tagName().equals("p")) {
                allElements.add(new ElementWithPos(el, position++, false));
            } else if (el.tagName().equals("div") && el.hasClass("l")) {
                // Verse lines in poetry (e.g., Beowulf uses <div class="l"> for each line)
                allElements.add(new ElementWithPos(el, position++, false));
            }
        }

        // Now process sequentially
        String currentTitle = null;
        List<String> currentParagraphs = new ArrayList<>();

        for (ElementWithPos ewp : allElements) {
            if (ewp.isHeader()) {
                // Save previous chapter if it has content
                if (currentTitle != null && !currentParagraphs.isEmpty()) {
                    chapters.add(new ParsedChapter(currentTitle, splitLongParagraphs(new ArrayList<>(currentParagraphs))));
                }
                // Extract clean chapter title (handles headers with mixed content like captions)
                currentTitle = extractChapterTitleFromHeader(ewp.element().text().trim());
                currentParagraphs.clear();
            } else {
                // Only add paragraphs if we're in a chapter
                if (currentTitle != null) {
                    String text = extractTextWithDropCap(ewp.element());
                    if (!text.isEmpty() && text.length() > 20) {
                        currentParagraphs.add(text);
                    }
                }
            }
        }

        // Don't forget the last chapter
        if (currentTitle != null && !currentParagraphs.isEmpty()) {
            chapters.add(new ParsedChapter(currentTitle, splitLongParagraphs(currentParagraphs)));
        }

        return chapters;
    }

    // Pattern to find chapter markers anywhere in text (for headers with mixed content)
    private static final Pattern CHAPTER_MARKER_PATTERN = Pattern.compile(
        "(CHAPTER|Chapter|STAVE|Stave)\\s+[IVXLCDM\\d]+\\.?",
        Pattern.CASE_INSENSITIVE
    );

    private boolean looksLikeChapterHeader(String title) {
        if (title == null || title.isEmpty() || title.length() > 150) {
            return false;
        }

        String upper = title.toUpperCase().trim();

        // Skip table of contents and similar
        if (upper.contains("CONTENTS")) return false;
        if (upper.contains("TABLE OF")) return false;

        // Match "CHAPTER X" or "STAVE X" patterns - either at start or anywhere in text
        if (upper.startsWith("CHAPTER ")) return true;
        if (upper.startsWith("STAVE ")) return true;
        if (CHAPTER_MARKER_PATTERN.matcher(title).find()) return true;

        // Match "BOOK X" patterns
        if (upper.startsWith("BOOK ") && upper.length() < 50) return true;

        // Match "PART X" patterns
        if (upper.startsWith("PART ") && upper.length() < 50) return true;

        // Match other common section types that should be included
        if (upper.equals("ETYMOLOGY") || upper.startsWith("ETYMOLOGY.")) return true;
        if (upper.equals("EXTRACTS") || upper.startsWith("EXTRACTS ")) return true;
        if (upper.equals("EPILOGUE") || upper.startsWith("EPILOGUE.")) return true;
        if (upper.equals("PROLOGUE") || upper.startsWith("PROLOGUE.")) return true;
        if (upper.equals("INTRODUCTION") || upper.startsWith("INTRODUCTION.")) return true;
        if (upper.equals("PREFACE") || upper.startsWith("PREFACE.")) return true;

        // Roman numerals alone (e.g., "I." or "XIV")
        if (ROMAN_NUMERAL_PATTERN.matcher(title.trim()).matches()) return true;

        // Roman numeral with title (e.g., "I. The Life and Death of Scyld")
        if (NUMBERED_SECTION_PATTERN.matcher(title.trim()).matches()) return true;

        return false;
    }

    /**
     * Extracts a clean chapter title from header text that may contain image captions.
     * E.g., "I hope Mr. Bingley will like it. CHAPTER II." -> "Chapter II."
     * But preserves chapter names: "Chapter 1. Loomings" -> "Chapter 1. Loomings"
     */
    private String extractChapterTitleFromHeader(String headerText) {
        if (headerText == null) return "Chapter";

        String trimmed = headerText.trim();

        // If the header starts with a chapter pattern, it's a clean header - keep the whole thing
        // This preserves chapter titles like "Chapter 1. Loomings"
        if (CHAPTER_PATTERN.matcher(trimmed).find()) {
            return trimmed;
        }

        // Header has extraneous content before chapter marker (e.g., image captions)
        // Extract just the chapter marker and everything after it
        Matcher matcher = CHAPTER_MARKER_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            // Return from the chapter marker to the end of the string
            return trimmed.substring(matcher.start()).trim();
        }

        // For other patterns (like roman numerals alone), return the trimmed text
        return trimmed;
    }

    private List<ParsedChapter> extractChaptersFromParagraphs(Elements paragraphs) {
        List<ParsedChapter> chapters = new ArrayList<>();
        List<String> currentParagraphs = new ArrayList<>();
        String currentTitle = "Chapter 1";
        int chapterNum = 1;

        for (Element p : paragraphs) {
            String text = extractTextWithDropCap(p);

            if (text.isEmpty()) continue;

            // Check if this looks like a chapter heading
            if (isChapterHeading(text)) {
                // Save previous chapter if it has content
                if (!currentParagraphs.isEmpty()) {
                    chapters.add(new ParsedChapter(currentTitle, splitLongParagraphs(new ArrayList<>(currentParagraphs))));
                    currentParagraphs.clear();
                }
                currentTitle = text;
                chapterNum++;
            } else if (text.length() > 20) {
                // Regular paragraph
                currentParagraphs.add(text);
            }
        }

        // Don't forget the last chapter
        if (!currentParagraphs.isEmpty()) {
            chapters.add(new ParsedChapter(currentTitle, splitLongParagraphs(currentParagraphs)));
        }

        return chapters;
    }

    private ParsedChapter extractAsOneChapter(Document doc) {
        List<String> paragraphs = extractParagraphs(doc);
        return new ParsedChapter("Full Text", splitLongParagraphs(paragraphs));
    }

    private List<String> extractParagraphs(Element container) {
        List<String> paragraphs = new ArrayList<>();

        // Select both regular paragraphs and verse lines (div.l used in poetry)
        for (Element p : container.select("p, div.l")) {
            String text = extractTextWithDropCap(p);
            if (!text.isEmpty() && text.length() > 20) {
                paragraphs.add(text);
            }
        }

        return paragraphs;
    }

    private String extractChapterTitle(Element container) {
        // Try to find a heading
        Element heading = container.selectFirst("h1, h2, h3, h4");
        if (heading != null) {
            return heading.text().trim();
        }

        // Try first strong or bold text
        Element strong = container.selectFirst("strong, b");
        if (strong != null) {
            String text = strong.text().trim();
            if (text.length() < 50) {
                return text;
            }
        }

        return "Chapter";
    }

    private boolean isChapterHeading(String text) {
        if (text.length() > 50) return false;

        String upper = text.toUpperCase();

        // Check for common chapter patterns
        if (CHAPTER_PATTERN.matcher(text).find()) return true;
        if (upper.startsWith("CHAPTER ")) return true;
        if (upper.startsWith("STAVE ")) return true;
        if (upper.startsWith("BOOK ")) return true;
        if (upper.startsWith("PART ")) return true;

        // Roman numerals alone
        if (ROMAN_NUMERAL_PATTERN.matcher(text.trim()).matches()) return true;

        // Roman numeral with title (e.g., "I. The Life and Death of Scyld")
        if (NUMBERED_SECTION_PATTERN.matcher(text.trim()).matches()) return true;

        return false;
    }

    private String cleanText(String text) {
        if (text == null) return "";

        return text
            .replaceAll("\\s+", " ")  // Normalize whitespace
            .replaceAll("[\\u00a0\\u2007\\u202F]", " ")  // Non-breaking spaces
            .trim();
    }

    /**
     * Extracts text from an element, including alt text from drop cap images.
     * Gutenberg books often use images for decorative first letters (drop caps),
     * and the letter is stored in the alt attribute.
     */
    private String extractTextWithDropCap(Element element) {
        StringBuilder text = new StringBuilder();

        // Check for drop cap image at the start
        Element firstImg = element.selectFirst("img");
        if (firstImg != null) {
            String alt = firstImg.attr("alt");
            // Drop caps are typically single letters
            if (alt != null && alt.length() == 1 && Character.isLetter(alt.charAt(0))) {
                text.append(alt);
            }
        }

        text.append(element.text());
        return cleanText(text.toString());
    }

    // Split long paragraphs to ensure they fit in viewport
    private static final int MAX_PARAGRAPH_LENGTH = 800;

    private List<String> splitLongParagraphs(List<String> paragraphs) {
        List<String> result = new ArrayList<>();

        for (String para : paragraphs) {
            if (para.length() <= MAX_PARAGRAPH_LENGTH) {
                result.add(para);
            } else {
                // Split at sentence boundaries where possible
                result.addAll(splitParagraph(para));
            }
        }

        return result;
    }

    private List<String> splitParagraph(String para) {
        List<String> chunks = new ArrayList<>();

        // Try to split at sentence boundaries
        String remaining = para;
        while (remaining.length() > MAX_PARAGRAPH_LENGTH) {
            int splitPoint = findSplitPoint(remaining, MAX_PARAGRAPH_LENGTH);
            chunks.add(remaining.substring(0, splitPoint).trim());
            remaining = remaining.substring(splitPoint).trim();
        }

        if (!remaining.isEmpty()) {
            chunks.add(remaining);
        }

        return chunks;
    }

    private int findSplitPoint(String text, int maxLength) {
        // Look for sentence ending (.!?) followed by space, searching backwards from maxLength
        for (int i = maxLength; i > maxLength / 2; i--) {
            if (i < text.length()) {
                char c = text.charAt(i - 1);
                char next = text.charAt(i);
                if ((c == '.' || c == '!' || c == '?') && Character.isWhitespace(next)) {
                    return i;
                }
            }
        }

        // Fall back to splitting at word boundary
        for (int i = maxLength; i > maxLength / 2; i--) {
            if (i < text.length() && Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }

        // Last resort: split at maxLength
        return Math.min(maxLength, text.length());
    }
}
