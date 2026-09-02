package com.classicchatreader.service;

import com.classicchatreader.entity.CharacterEntity;
import com.classicchatreader.entity.CharacterStatus;
import com.classicchatreader.entity.CharacterType;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.repository.CharacterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Picks accepted roster portraits for a chapter plate: names go on the Imagine
 * prompt. Portrait PNG bytes are not sent to {@code /images/edits} — that
 * treats the headshot as the canvas and reprints it as the chapter illustration.
 */
@Service
public class IllustrationPortraitReferences {

    static final int MAX_REFERENCES = 5;

    private static final Logger log = LoggerFactory.getLogger(IllustrationPortraitReferences.class);

    private static final Set<String> SKIP_GIVEN = Set.of(
            "mr", "mrs", "miss", "ms", "dr", "sir", "lady", "lord", "madame", "monsieur"
    );

    private final CharacterRepository characterRepository;
    private final ComfyUIService comfyUIService;

    public IllustrationPortraitReferences(
            CharacterRepository characterRepository,
            ComfyUIService comfyUIService) {
        this.characterRepository = characterRepository;
        this.comfyUIService = comfyUIService;
    }

    public record PortraitRef(String name, byte[] png) {}

    /**
     * Step 1: who belongs on this plate. Accepted portraits already introduced
     * and named in the chapter title or text. PRIMARY first, max {@value #MAX_REFERENCES}.
     */
    public List<CharacterEntity> castForChapter(String bookId, ChapterEntity chapter, String chapterText) {
        if (bookId == null || chapter == null) {
            return List.of();
        }
        String haystack = ((chapter.getTitle() == null ? "" : chapter.getTitle())
                + "\n"
                + (chapterText == null ? "" : chapterText)).toLowerCase(Locale.ROOT);
        if (haystack.isBlank()) {
            return List.of();
        }
        return available(bookId, chapter).stream()
                .filter(c -> mentionedIn(c.getName(), haystack))
                .sorted(Comparator
                        .comparing((CharacterEntity c) -> c.getCharacterType() != CharacterType.PRIMARY)
                        .thenComparingInt(c -> c.getFirstChapter() == null ? Integer.MAX_VALUE
                                : c.getFirstChapter().getChapterIndex()))
                .limit(MAX_REFERENCES)
                .toList();
    }

    /** Load portrait PNGs for a chosen cast (step 3). */
    public List<PortraitRef> load(List<CharacterEntity> cast) {
        if (cast == null || cast.isEmpty()) {
            return List.of();
        }
        List<PortraitRef> refs = new ArrayList<>();
        for (CharacterEntity character : cast) {
            if (!character.hasStoredPortraitImage()) {
                continue;
            }
            byte[] png = comfyUIService.getPortraitImage(character.getPortraitFilename());
            if (png == null || png.length == 0) {
                continue;
            }
            refs.add(new PortraitRef(character.getName(), png));
        }
        if (!refs.isEmpty()) {
            log.info("Illustration portrait refs: {}", refs.stream().map(PortraitRef::name).toList());
        }
        return refs;
    }

    /** Operator / stored prompt: attach portraits named in that prompt. */
    public List<PortraitRef> select(String bookId, ChapterEntity chapter, String imagePrompt) {
        if (bookId == null || chapter == null) {
            return List.of();
        }
        String haystack = imagePrompt == null ? "" : imagePrompt.toLowerCase(Locale.ROOT);
        if (haystack.isBlank()) {
            return List.of();
        }

        return load(available(bookId, chapter).stream()
                .filter(c -> mentionedIn(c.getName(), haystack))
                .sorted(Comparator
                        .comparing((CharacterEntity c) -> c.getCharacterType() != CharacterType.PRIMARY)
                        .thenComparingInt(c -> c.getFirstChapter() == null ? Integer.MAX_VALUE
                                : c.getFirstChapter().getChapterIndex()))
                .limit(MAX_REFERENCES)
                .toList());
    }

    public static List<String> namesOf(List<CharacterEntity> cast) {
        if (cast == null || cast.isEmpty()) {
            return List.of();
        }
        return cast.stream().map(CharacterEntity::getName).toList();
    }

    /** If the LLM dropped a cast name, put it on the prompt so Imagine still sees it. */
    public static String ensureCastNamed(String prompt, List<String> names) {
        if (prompt == null) {
            prompt = "";
        }
        if (names == null || names.isEmpty()) {
            return prompt;
        }
        StringBuilder missing = new StringBuilder();
        for (String name : names) {
            if (!mentionedIn(name, prompt)) {
                if (!missing.isEmpty()) {
                    missing.append(" and ");
                }
                missing.append(name);
            }
        }
        if (missing.isEmpty()) {
            return prompt;
        }
        return missing + " in this scene. " + prompt.trim();
    }

    public static String appendLikeness(String prompt, List<PortraitRef> refs) {
        if (prompt == null || prompt.isBlank() || refs == null || refs.isEmpty()) {
            return prompt;
        }
        StringBuilder sb = new StringBuilder(prompt.trim());
        sb.append(" Named people in this plate: ");
        for (int i = 0; i < refs.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(refs.get(i).name());
        }
        sb.append(". Include them in the scene at three-quarter or full figure, matching their usual appearance.");
        sb.append(" This is a narrative book plate, not a portrait or head-and-shoulders crop.");
        return sb.toString();
    }

    static boolean mentionedIn(String name, String haystack) {
        if (name == null || name.isBlank() || haystack == null || haystack.isBlank()) {
            return false;
        }
        LinkedHashMap<String, Boolean> needles = new LinkedHashMap<>();
        needles.put(name.trim(), Boolean.TRUE);
        String[] parts = name.trim().split("\\s+");
        if (parts.length > 0) {
            String given = parts[0].replaceAll("[^\\p{L}]", "");
            if (given.length() >= 4 && !SKIP_GIVEN.contains(given.toLowerCase(Locale.ROOT))) {
                needles.put(given, Boolean.TRUE);
            } else if (parts.length > 1) {
                String next = parts[1].replaceAll("[^\\p{L}]", "");
                if (next.length() >= 4) {
                    needles.put(next, Boolean.TRUE);
                }
            }
        }
        for (String needle : needles.keySet()) {
            Pattern word = Pattern.compile("(?i)(?<!\\p{L})" + Pattern.quote(needle) + "(?:'s)?(?!\\p{L})");
            if (word.matcher(haystack).find()) {
                return true;
            }
        }
        return false;
    }

    private List<CharacterEntity> available(String bookId, ChapterEntity chapter) {
        return characterRepository.findByBookIdWithFirstChapterOrderByCreatedAt(bookId).stream()
                .filter(c -> c.getStatus() == CharacterStatus.COMPLETED)
                .filter(CharacterEntity::hasStoredPortraitImage)
                .filter(c -> introducedBy(c, chapter))
                .toList();
    }

    private static boolean introducedBy(CharacterEntity character, ChapterEntity chapter) {
        if (character.getFirstChapter() == null) {
            return true;
        }
        return character.getFirstChapter().getChapterIndex() <= chapter.getChapterIndex();
    }
}
