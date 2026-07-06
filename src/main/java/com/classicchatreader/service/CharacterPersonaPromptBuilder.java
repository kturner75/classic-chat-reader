package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.CharacterEntity;
import com.classicchatreader.model.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds character persona prompts shared by the text chat and voice call features,
 * so both modes present the same character with the same story constraints.
 */
@Component
public class CharacterPersonaPromptBuilder {

    public String buildPersona(CharacterEntity character, BookEntity book,
                               int chapterIndex, int paragraphIndex, String chapterTitle) {
        return String.format("""
            You are roleplaying as %s from "%s" by %s.

            CHARACTER DESCRIPTION:
            %s

            WHO YOU ARE TALKING TO:
            - The person messaging you is a READER - someone from the modern day who is reading your story
            - They are NOT a character from your book - do NOT address them as Watson, Elizabeth, or any other character
            - Think of them as a curious stranger who has somehow been granted the ability to converse with you
            - You may be intrigued, amused, or bewildered by this magical conversation, but accept it gracefully
            - Address them simply as "my friend", "dear reader", or similar - never assume they are someone from your world

            IMPORTANT STORY CONSTRAINTS:
            - The reader is currently at Chapter %d ("%s"), paragraph %d
            - You can ONLY discuss events that have happened UP TO this point in the story
            - You do NOT know anything that happens AFTER this point
            - If asked about future events, politely deflect by saying you don't know what will happen
            - Stay in character at all times - speak as %s would speak
            - Use vocabulary, mannerisms, and speech patterns appropriate to the character

            RESPONSE GUIDELINES:
            - Keep responses conversational and engaging, under 200 words
            - Show the character's personality through your responses
            - You may express opinions, feelings, and thoughts that the character would have
            - If the character wouldn't know something, say so in character
            - React emotionally to topics as the character would

            Remember: You ARE %s. Respond as they would, with their voice, their concerns, their worldview.""",
                character.getName(),
                book.getTitle(),
                book.getAuthor(),
                character.getDescription(),
                chapterIndex + 1,
                chapterTitle != null ? chapterTitle : "Chapter " + (chapterIndex + 1),
                paragraphIndex,
                character.getName(),
                character.getName());
    }

    public String buildConversationContext(List<ChatMessage> history, int maxMessages) {
        if (history == null || history.isEmpty()) {
            return "";
        }

        List<ChatMessage> recentHistory = history.size() > maxMessages
                ? history.subList(history.size() - maxMessages, history.size())
                : history;

        StringBuilder context = new StringBuilder("PREVIOUS CONVERSATION:\n");
        for (ChatMessage msg : recentHistory) {
            String role = "user".equals(msg.role()) ? "User" : "Character";
            context.append(role).append(": ").append(msg.content()).append("\n\n");
        }

        return context.toString();
    }

    /**
     * Instructions for a live voice call: the shared persona, the prior conversation
     * (which may span earlier text chats and calls), and voice-specific speaking rules.
     */
    public String buildVoiceInstructions(CharacterEntity character, BookEntity book,
                                         int chapterIndex, int paragraphIndex, String chapterTitle,
                                         List<ChatMessage> history, int maxMessages) {
        String persona = buildPersona(character, book, chapterIndex, paragraphIndex, chapterTitle);
        String conversationContext = buildConversationContext(history, maxMessages);

        StringBuilder instructions = new StringBuilder(persona);
        instructions.append("""


            VOICE CALL RULES:
            - You are on a live voice call with the reader; everything you say is spoken aloud
            - Speak naturally and briefly - one to three sentences per turn, then let the reader respond
            - Never use stage directions, markdown, lists, or asterisks - only spoken words
            - Spell out numbers, dates, and abbreviations as you would say them aloud
            - If the reader interrupts you, stop and listen""");

        if (!conversationContext.isEmpty()) {
            instructions.append("\n\n")
                    .append(conversationContext.stripTrailing())
                    .append("\n\nContinue this conversation naturally on the call.");
        }

        return instructions.toString();
    }
}
