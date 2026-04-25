package com.classicchatreader.entity;

public enum CharacterType {
    PRIMARY,    // Pre-fetched main characters (from book-level LLM query)
    SECONDARY   // Chapter-extracted minor characters
}
