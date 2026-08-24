package com.classicchatreader.service;

/**
 * Shared discovery-prompt contract for prefetch (PRIMARY) and chapter extraction (SECONDARY).
 */
final class CharacterDiscoveryPromptRules {

    static final String NAMED_PEOPLE_ONLY =
            "Roster entries must be named people only: distinct persons with a proper personal name "
                    + "or a well-known person epithet used as their name "
                    + "(\"The Creature\", \"The Monster\", \"The Turk\").";

    static final String REJECT_NON_PERSONS =
            "Reject animals, objects, places, celestial bodies (\"The Moon\", \"The Mule\"), "
                    + "collective or mass nouns (\"bees\", \"the crowd\"), generic roles "
                    + "(\"the maid\", \"a stranger\"), and LLM leftovers.";

    static final String NO_GLITCH_NAMES =
            "Do not emit duplicate or glitch names such as \"Elizabeth Lavenza (again)\" "
                    + "or parenthetical leftovers.";

    static final String FIRST_APPEARANCE_BLURB =
            "Description must be first-appearance only: who they are when the reader first meets them. "
                    + "Do not include later-plot facts, deaths, marriages, revelations, or outcomes "
                    + "from later chapters.";

    private CharacterDiscoveryPromptRules() {
    }
}
