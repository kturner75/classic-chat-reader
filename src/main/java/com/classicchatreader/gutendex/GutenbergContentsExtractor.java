package com.classicchatreader.gutendex;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads the table of contents a Gutenberg HTML edition prints near its start: the linked
 * entries under a "Contents" heading. Studio compares this list with the chapters
 * {@link GutenbergContentParser} produced, so a parse that dropped stories is caught before
 * anything is built on it. Returns an empty list when the book has no recognisable contents.
 */
@Service
public class GutenbergContentsExtractor {

    public record ContentsEntry(String title, boolean group) {}

    private static final Pattern FOOTNOTE_MARKER = Pattern.compile("\\s*\\[\\d+\\]");
    private static final int MAX_ENTRIES = 500;

    public List<ContentsEntry> extract(String html) {
        Document doc = Jsoup.parse(html);
        Element heading = findContentsHeading(doc);
        if (heading == null) {
            return List.of();
        }

        List<ContentsEntry> entries = new ArrayList<>();
        for (Element sibling = heading.nextElementSibling(); sibling != null; sibling = sibling.nextElementSibling()) {
            if (isHeading(sibling)) {
                break;
            }
            // A wrapper div that itself holds a heading is the start of the book, not more contents.
            if (!sibling.select("h1, h2, h3").isEmpty()) {
                break;
            }
            Elements links = sibling.select("a[href*=#]");
            for (Element link : links) {
                String title = clean(link.text());
                if (title.isEmpty() || title.length() > 150) {
                    continue;
                }
                entries.add(new ContentsEntry(title, title.endsWith(":")));
                if (entries.size() >= MAX_ENTRIES) {
                    return entries;
                }
            }
        }
        return entries;
    }

    private Element findContentsHeading(Document doc) {
        for (Element h : doc.select("h1, h2, h3, h4")) {
            String text = h.text().trim().toUpperCase();
            if (text.equals("CONTENTS") || text.equals("TABLE OF CONTENTS") || text.equals("CONTENTS.")) {
                return h;
            }
        }
        return null;
    }

    private boolean isHeading(Element el) {
        return el.tagName().matches("h[1-3]");
    }

    private String clean(String text) {
        return FOOTNOTE_MARKER.matcher(text).replaceAll("").replaceAll("\\s+", " ").trim();
    }
}
