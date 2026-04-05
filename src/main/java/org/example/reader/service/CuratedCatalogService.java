package org.example.reader.service;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class CuratedCatalogService {

    /**
     * Shared curated catalog list used for:
     * - Landing-page discovery/search when catalog mode is "curated"
     * - Batch pre-generation source list
     *
     * Curated catalog used by the landing page and batch pre-generation.
     */
    private static final List<CuratedCatalogBook> CURATED_BOOKS = List.of(
            new CuratedCatalogBook(1342, "Pride and Prejudice", "Jane Austen", 100_000, List.of("Courtship fiction"), List.of("Romance")),
            new CuratedCatalogBook(2701, "Moby Dick", "Herman Melville", 99_000, List.of("Whaling -- Fiction"), List.of("Adventure")),
            new CuratedCatalogBook(84, "Frankenstein", "Mary Shelley", 98_000, List.of("Science fiction"), List.of("Horror")),
            new CuratedCatalogBook(345, "Dracula", "Bram Stoker", 97_000, List.of("Vampires -- Fiction"), List.of("Gothic")),
            new CuratedCatalogBook(11, "Alice's Adventures in Wonderland", "Lewis Carroll", 96_000, List.of("Fantasy fiction"), List.of("Children")),
            new CuratedCatalogBook(1260, "Jane Eyre", "Charlotte Bronte", 95_000, List.of("Governesses -- Fiction"), List.of("Classics")),
            new CuratedCatalogBook(768, "Wuthering Heights", "Emily Bronte", 94_000, List.of("Love stories"), List.of("Classics")),
            new CuratedCatalogBook(174, "The Picture of Dorian Gray", "Oscar Wilde", 93_000, List.of("Psychological fiction"), List.of("Classics")),
            new CuratedCatalogBook(98, "A Tale of Two Cities", "Charles Dickens", 92_000, List.of("Historical fiction"), List.of("Classics")),
            new CuratedCatalogBook(1184, "The Count of Monte Cristo", "Alexandre Dumas", 91_000, List.of("Adventure stories"), List.of("Classics")),
            new CuratedCatalogBook(2554, "Crime and Punishment", "Fyodor Dostoyevsky", 90_000, List.of("Psychological fiction"), List.of("Classics")),
            new CuratedCatalogBook(1399, "Anna Karenina", "Leo Tolstoy", 89_000, List.of("Domestic fiction"), List.of("Classics")),
            new CuratedCatalogBook(1661, "The Adventures of Sherlock Holmes", "Arthur Conan Doyle", 88_000, List.of("Detective and mystery stories"), List.of("Detective Fiction")),
            new CuratedCatalogBook(1727, "The Odyssey", "Homer", 87_000, List.of("Epic poetry"), List.of("Poetry")),
            new CuratedCatalogBook(996, "Don Quixote", "Miguel de Cervantes", 86_000, List.of("Satire"), List.of("Classics")),
            new CuratedCatalogBook(135, "Les Miserables", "Victor Hugo", 85_000, List.of("Historical fiction"), List.of("Classics")),
            new CuratedCatalogBook(2600, "War and Peace", "Leo Tolstoy", 84_000, List.of("Historical fiction"), List.of("Classics")),
            new CuratedCatalogBook(28054, "The Brothers Karamazov", "Fyodor Dostoyevsky", 83_000, List.of("Brothers -- Fiction"), List.of("Classics")),
            new CuratedCatalogBook(120, "Treasure Island", "Robert Louis Stevenson", 82_000, List.of("Treasure troves -- Fiction"), List.of("Adventure")),
            new CuratedCatalogBook(25, "The Scarlet Letter", "Nathaniel Hawthorne", 81_000, List.of("Adultery -- Fiction"), List.of("Classics")),
            new CuratedCatalogBook(1400, "Great Expectations", "Charles Dickens", 80_000, List.of("Orphans -- Fiction"), List.of("Classics")),
            new CuratedCatalogBook(76, "Adventures of Huckleberry Finn", "Mark Twain", 79_000, List.of("Boys -- Fiction"), List.of("Adventure")),
            new CuratedCatalogBook(46, "A Christmas Carol", "Charles Dickens", 78_000, List.of("Christmas stories"), List.of("Holiday")),
            new CuratedCatalogBook(43, "The Strange Case of Dr. Jekyll and Mr. Hyde", "Robert Louis Stevenson", 77_000, List.of("Personality disorders -- Fiction"), List.of("Horror")),
            new CuratedCatalogBook(35, "The Time Machine", "H. G. Wells", 76_000, List.of("Time travel -- Fiction"), List.of("Science Fiction")),
            new CuratedCatalogBook(36, "The War of the Worlds", "H. G. Wells", 75_000, List.of("Martians -- Fiction"), List.of("Science Fiction")),
            new CuratedCatalogBook(5230, "The Invisible Man", "H. G. Wells", 74_000, List.of("Science fiction"), List.of("Science Fiction")),
            new CuratedCatalogBook(2488, "Twenty Thousand Leagues Under the Seas", "Jules Verne", 73_000, List.of("Sea stories"), List.of("Adventure")),
            new CuratedCatalogBook(103, "Around the World in Eighty Days", "Jules Verne", 72_000, List.of("Voyages around the world -- Fiction"), List.of("Adventure")),
            new CuratedCatalogBook(18857, "Journey to the Centre of the Earth", "Jules Verne", 71_000, List.of("Science fiction"), List.of("Adventure")),
            new CuratedCatalogBook(37106, "Little Women", "Louisa May Alcott", 70_000, List.of("Sisters -- Fiction"), List.of("Children")),
            new CuratedCatalogBook(17396, "The Secret Garden", "Frances Hodgson Burnett", 69_000, List.of("Gardens -- Fiction"), List.of("Children")),
            new CuratedCatalogBook(16, "Peter Pan", "J. M. Barrie", 68_000, List.of("Fantasy fiction"), List.of("Children")),
            new CuratedCatalogBook(55, "The Wonderful Wizard of Oz", "L. Frank Baum", 67_000, List.of("Fantasy fiction"), List.of("Children")),
            new CuratedCatalogBook(52521, "Grimm's Fairy Tales", "Jacob Grimm", 66_000, List.of("Fairy tales"), List.of("Children")),
            new CuratedCatalogBook(215, "The Call of the Wild", "Jack London", 65_000, List.of("Dogs -- Fiction"), List.of("Adventure")),
            new CuratedCatalogBook(219, "Heart of Darkness", "Joseph Conrad", 64_000, List.of("Psychological fiction"), List.of("Classics")),
            new CuratedCatalogBook(940, "The Last of the Mohicans", "James Fenimore Cooper", 63_000, List.of("Historical fiction"), List.of("Adventure")),
            new CuratedCatalogBook(1257, "The Three Musketeers", "Alexandre Dumas", 62_000, List.of("Adventure stories"), List.of("Adventure")),
            new CuratedCatalogBook(161, "Sense and Sensibility", "Jane Austen", 61_000, List.of("Domestic fiction"), List.of("Romance")),
            new CuratedCatalogBook(105, "Persuasion", "Jane Austen", 60_000, List.of("Domestic fiction"), List.of("Romance")),
            new CuratedCatalogBook(141, "Mansfield Park", "Jane Austen", 59_000, List.of("Domestic fiction"), List.of("Romance")),
            new CuratedCatalogBook(121, "Northanger Abbey", "Jane Austen", 58_000, List.of("Young women -- Fiction"), List.of("Romance")),
            new CuratedCatalogBook(145, "Middlemarch", "George Eliot", 57_000, List.of("Domestic fiction"), List.of("Classics")),
            new CuratedCatalogBook(766, "David Copperfield", "Charles Dickens", 56_000, List.of("Bildungsromans"), List.of("Classics")),
            new CuratedCatalogBook(730, "Oliver Twist", "Charles Dickens", 55_000, List.of("Orphans -- Fiction"), List.of("Classics")),
            new CuratedCatalogBook(1837, "The Prince and the Pauper", "Mark Twain", 54_000, List.of("Princes -- Fiction"), List.of("Adventure")),
            new CuratedCatalogBook(86, "A Connecticut Yankee in King Arthur's Court", "Mark Twain", 53_000, List.of("Arthurian romances -- Adaptations"), List.of("Satire")),
            new CuratedCatalogBook(2852, "The Hound of the Baskervilles", "Arthur Conan Doyle", 52_000, List.of("Detective and mystery stories"), List.of("Detective Fiction")),
            new CuratedCatalogBook(175, "The Phantom of the Opera", "Gaston Leroux", 51_000, List.of("Horror tales"), List.of("Gothic")),
            new CuratedCatalogBook(844, "The Importance of Being Earnest", "Oscar Wilde", 50_000, List.of("Comedy plays"), List.of("Plays")),
            new CuratedCatalogBook(74, "The Adventures of Tom Sawyer", "Mark Twain", 49_000, List.of("Adventure stories"), List.of("Adventure")),
            new CuratedCatalogBook(45, "Anne of Green Gables", "L. M. Montgomery", 48_000, List.of("Girls -- Fiction"), List.of("Children")),
            new CuratedCatalogBook(23, "Narrative of the Life of Frederick Douglass, an American Slave", "Frederick Douglass", 47_000, List.of("Enslaved persons -- United States -- Biography"), List.of("Biography")),
            new CuratedCatalogBook(829, "Gulliver's Travels", "Jonathan Swift", 46_000, List.of("Satire"), List.of("Adventure")),
            new CuratedCatalogBook(69087, "The Murder of Roger Ackroyd", "Agatha Christie", 45_000, List.of("Detective and mystery stories"), List.of("Detective Fiction")),
            new CuratedCatalogBook(3268, "The Mysteries of Udolpho", "Ann Radcliffe", 44_000, List.of("Gothic fiction"), List.of("Gothic")),
            new CuratedCatalogBook(67098, "Winnie-the-Pooh", "A. A. Milne", 43_000, List.of("Animals -- Juvenile fiction"), List.of("Children")),
            new CuratedCatalogBook(12, "Through the Looking-Glass", "Lewis Carroll", 42_000, List.of("Fantasy fiction"), List.of("Children")),
            new CuratedCatalogBook(203, "Uncle Tom's Cabin", "Harriet Beecher Stowe", 41_000, List.of("Enslaved persons -- Fiction"), List.of("Classics")),
            new CuratedCatalogBook(583, "The Woman in White", "Wilkie Collins", 40_000, List.of("Gothic fiction"), List.of("Detective Fiction")),
            new CuratedCatalogBook(696, "The Castle of Otranto", "Horace Walpole", 39_000, List.of("Gothic fiction"), List.of("Gothic")),
            new CuratedCatalogBook(289, "The Wind in the Willows", "Kenneth Grahame", 38_000, List.of("Animals -- Fiction"), List.of("Children")),
            new CuratedCatalogBook(4276, "North and South", "Elizabeth Gaskell", 37_000, List.of("Bildungsromans"), List.of("Classics")),
            new CuratedCatalogBook(22120, "The Canterbury Tales", "Geoffrey Chaucer", 36_000, List.of("Christian pilgrims and pilgrimages -- Poetry"), List.of("Poetry")),
            new CuratedCatalogBook(932, "The Fall of the House of Usher", "Edgar Allan Poe", 35_000, List.of("Horror tales"), List.of("Horror")),
            new CuratedCatalogBook(6124, "Pamela, or Virtue Rewarded", "Samuel Richardson", 34_000, List.of("Epistolary fiction"), List.of("Classics")),
            new CuratedCatalogBook(77, "The House of the Seven Gables", "Nathaniel Hawthorne", 33_000, List.of("Haunted houses -- Fiction"), List.of("Gothic")),
            new CuratedCatalogBook(610, "Idylls of the King", "Alfred Tennyson", 32_000, List.of("Arthur, King -- Poetry"), List.of("Poetry")),
            new CuratedCatalogBook(6053, "Evelina", "Fanny Burney", 31_000, List.of("Bildungsromans"), List.of("Classics")),
            new CuratedCatalogBook(2787, "An Old-Fashioned Girl", "Louisa May Alcott", 30_000, List.of("Friendship -- Juvenile fiction"), List.of("Children")),
            new CuratedCatalogBook(2265, "Hamlet", "William Shakespeare", 29_000, List.of("Denmark -- Drama"), List.of("Plays")),
            new CuratedCatalogBook(56621, "Aurora Leigh", "Elizabeth Barrett Browning", 28_000, List.of("Novels in verse"), List.of("Poetry")),
            new CuratedCatalogBook(70841, "Robinson Crusoe", "Daniel Defoe", 27_000, List.of("Adventure stories"), List.of("Adventure")),
            new CuratedCatalogBook(66084, "Sir Gawain and the Green Knight", "Jessie L. Weston", 26_000, List.of("Arthurian romances"), List.of("Mythology")),
            new CuratedCatalogBook(64636, "Rip Van Winkle", "Washington Irving", 25_000, List.of("Fantasy fiction"), List.of("Short Stories")),
            new CuratedCatalogBook(778, "Five Children and It", "E. Nesbit", 24_000, List.of("Wishes -- Fiction"), List.of("Children"))
    );

    private static final Comparator<CuratedCatalogBook> POPULARITY_ORDER =
            Comparator.comparingInt(CuratedCatalogBook::downloadCount).reversed()
                    .thenComparing(CuratedCatalogBook::title, String.CASE_INSENSITIVE_ORDER);

    public List<CuratedCatalogBook> getPopularBooks() {
        return CURATED_BOOKS;
    }

    public boolean isCuratedGutenbergId(int gutenbergId) {
        return CURATED_BOOKS.stream().anyMatch(book -> book.gutenbergId() == gutenbergId);
    }

    public List<CuratedCatalogBook> search(String query) {
        String normalized = normalize(query);
        if (normalized.isEmpty()) {
            return CURATED_BOOKS;
        }
        return CURATED_BOOKS.stream()
                .filter(book -> matches(book, normalized))
                .sorted(POPULARITY_ORDER)
                .toList();
    }

    private boolean matches(CuratedCatalogBook book, String normalizedQuery) {
        String normalizedTitle = normalize(book.title());
        String normalizedAuthor = normalize(book.author());
        return normalizedTitle.contains(normalizedQuery)
                || normalizedAuthor.contains(normalizedQuery)
                || (normalizedTitle + " " + normalizedAuthor).contains(normalizedQuery);
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public record CuratedCatalogBook(
            int gutenbergId,
            String title,
            String author,
            int downloadCount,
            List<String> subjects,
            List<String> bookshelves
    ) {}
}
