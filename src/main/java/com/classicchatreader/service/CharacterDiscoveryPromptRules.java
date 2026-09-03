package com.classicchatreader.service;

/**
 * Shared discovery-prompt contract for prefetch (PRIMARY) and chapter extraction (SECONDARY).
 */
final class CharacterDiscoveryPromptRules {

    static final String NAMED_PEOPLE_ONLY =
            "Roster entries must be named people or named speaking figures with a proper name "
                    + "or a well-known person epithet used as their name "
                    + "(\"The Creature\", \"The Monster\", \"The Turk\", \"White Rabbit\", "
                    + "\"Cheshire Cat\", \"The Hatter\"). Non-human speakers with proper names "
                    + "are allowed; generic descriptors are not.";

    static final String REJECT_NON_PERSONS =
            "Reject generic animals, objects, places, celestial bodies (\"The Moon\", \"The Mule\"), "
                    + "collective or mass nouns (\"bees\", \"the crowd\"), generic roles "
                    + "(\"the maid\", \"a stranger\"), and LLM leftovers. Do not reject a named "
                    + "speaking figure solely because they are not human.";

    static final String NO_GLITCH_NAMES =
            "Do not emit duplicate or glitch names such as \"Elizabeth Lavenza (again)\" "
                    + "or parenthetical leftovers.";

    static final String FIRST_APPEARANCE_BLURB =
            "Description must be first-appearance only: who they are when the reader first meets them. "
                    + "Do not include later-plot facts, deaths, marriages, revelations, or outcomes "
                    + "from later chapters.";

    static final String FIRST_CHAPTER_PLACEMENT =
            "firstChapterIndex is the 0-based chapterIndex from the CHAPTER MAP below — the chapter where "
                    + "the character is first present as a person in the story, including under another "
                    + "name or shorter form. Do not use the first exact full-name string match. Do not "
                    + "use a later journal, diary, or recap that restates their name. A name merely "
                    + "mentioned in Preface, Introduction, or other front matter is not first appearance "
                    + "as a person. Return null if unsure; do not guess 0 or 1.";

    static final String CHARACTER_TYPE_RULE =
            "characterType is PRIMARY for central recurring/chat-worthy leads, or SECONDARY for supporting "
                    + "named characters worth a roster entry and portrait but not primary chat.";

    private CharacterDiscoveryPromptRules() {
    }
}
