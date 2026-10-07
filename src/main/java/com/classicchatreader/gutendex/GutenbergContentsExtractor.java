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

    /** Footnote markers Gutenberg appends to titles, e.g. "THE GREAT CARBUNCLE[4]". Shared with the parser. */
    static final Pattern FOOTNOTE_MARKER = Pattern.compile("\\s*\\[\\d+\\]");
    private static final int MAX_ENTRIES = 500;
    private static final Pattern PAGE_LINK_TEXT = Pattern.compile("^\\d{1,4}$");
    private static final Pattern PAGE_LINK_HREF = Pattern.compile("#page_?\\d+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER_ONLY = Pattern.compile("^[IVXLCDMivxlcdm\\d.\\s]+$");

    public List<ContentsEntry> extract(String html) {
        Document doc = Jsoup.parse(html);
        Element heading = findContentsHeading(doc);
        if (heading == null) {
            return List.of();
        }

        List<ContentsEntry> entries = new ArrayList<>();
        for (Element sibling = startAfter(heading); sibling != null; sibling = sibling.nextElementSibling()) {
            if (isHeading(sibling)) {
                break;
            }
            // A wrapper div that itself holds a heading is the start of the book, not more contents.
            if (!sibling.select("h1, h2, h3, h4").isEmpty()) {
                break;
            }
            for (String title : entryTitles(sibling)) {
                entries.add(new ContentsEntry(title, title.endsWith(":")));
                if (entries.size() >= MAX_ENTRIES) {
                    return entries;
                }
            }
        }
        return entries;
    }

    /**
     * One title per table row or list item: a row that links both its number and its title
     * ("<a>I.</a> <a>Howe's Masquerade</a>") is one entry, "I. Howe's Masquerade". Elsewhere each link
     * is its own entry.
     */
    private List<String> entryTitles(Element container) {
        List<String> titles = new ArrayList<>();
        Elements rows = container.select("tr, li");
        List<Element> units = rows.isEmpty() ? List.of(container) : rows;
        for (Element unit : units) {
            List<Element> links = ownLinks(unit, !rows.isEmpty());
            if (!rows.isEmpty()) links = withoutPageNumbers(links);
            if (!rows.isEmpty() && links.isEmpty()) continue;
            if (rows.isEmpty()) {
                for (Element link : links) addTitle(titles, link.text());
            } else {
                StringBuilder joined = new StringBuilder();
                for (Element link : links) joined.append(' ').append(link.text());
                String linked = clean(joined.toString());
                // A row that links only its number ("<a>I.</a>" then an unlinked title cell) is that
                // number plus the rest of the row.
                addTitle(titles, NUMBER_ONLY.matcher(linked).matches() ? ownText(unit) : linked);
            }
        }
        return titles;
    }

    /**
     * A row that links its title and also its page number ("<a>CHAPTER I</a> <a>1</a>") is the title.
     * Page-number links (bare digits, or a #Page_N target) are dropped when something else is linked.
     */
    private List<Element> withoutPageNumbers(List<Element> links) {
        List<Element> kept = new ArrayList<>();
        for (Element link : links) {
            if (!PAGE_LINK_TEXT.matcher(link.text().trim()).matches() && !PAGE_LINK_HREF.matcher(link.attr("href")).find()) {
                kept.add(link);
            }
        }
        return kept.isEmpty() ? links : kept;
    }

    /** The links that belong to this row or item, not to a row or item nested inside it. */
    private List<Element> ownLinks(Element unit, boolean isRow) {
        List<Element> own = new ArrayList<>();
        for (Element link : unit.select("a[href*=#]")) {
            if (!isRow || link.closest("tr, li") == unit) own.add(link);
        }
        return own;
    }

    /** A row's text without the text of rows or items nested inside it. */
    private String ownText(Element unit) {
        Element copy = unit.clone();
        copy.select("tr, li").forEach(Element::remove);
        return copy.text();
    }

    private void addTitle(List<String> titles, String raw) {
        String title = clean(raw);
        if (!title.isEmpty() && title.length() <= 150) titles.add(title);
    }

    /**
     * Where the contents follow the heading. A heading wrapped alone in a div
     * ({@code <div class="chapter"><h2>CONTENTS</h2></div><table>...}) has no sibling of its own, so
     * the walk starts after its wrapper.
     */
    private Element startAfter(Element heading) {
        Element anchor = heading;
        while (anchor.nextElementSibling() == null && anchor.parent() instanceof Element parent
                && parent.childrenSize() == 1 && !"body".equals(parent.tagName())) {
            anchor = parent;
        }
        return anchor.nextElementSibling();
    }

    private Element findContentsHeading(Document doc) {
        for (Element h : doc.select("h1, h2, h3, h4")) {
            String text = h.text().trim().replaceAll("[\\p{Punct}\\s]+$", "").toUpperCase();
            if (text.equals("CONTENTS") || text.equals("TABLE OF CONTENTS")) {
                return h;
            }
        }
        return null;
    }

    private boolean isHeading(Element el) {
        return el.tagName().matches("h[1-4]");
    }

    private String clean(String text) {
        return FOOTNOTE_MARKER.matcher(text).replaceAll("").replaceAll("\\s+", " ").trim();
    }
}
