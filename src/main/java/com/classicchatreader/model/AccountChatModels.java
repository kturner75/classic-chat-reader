package com.classicchatreader.model;

import java.time.Instant;
import java.util.List;

public final class AccountChatModels {
    private AccountChatModels() {
    }

    public record CharacterIdentity(String id, String name, String portraitUrl) {
    }

    public record BookIdentity(String id, String title, String author) {
    }

    public record ChatContext(
            String chapterId,
            int chapterIndex,
            String chapterTitle,
            int paragraphIndex
    ) {
    }

    public record Resume(boolean available, String url, String unavailableReason) {
    }

    public record SessionSummary(
            String sessionId,
            CharacterIdentity character,
            BookIdentity book,
            String previewText,
            String previewRole,
            int messageCount,
            Instant createdAt,
            Instant lastMessageAt,
            Instant updatedAt,
            ChatContext context,
            Resume resume
    ) {
    }

    public record PageInfo(int limit, String nextCursor, boolean hasMore) {
    }

    public record SessionListResponse(List<SessionSummary> items, PageInfo page) {
    }

    public record FilterOption(String id, String label) {
    }

    public record CharacterFilterOption(String id, String label, String bookId) {
    }

    public record FilterOptionsResponse(List<FilterOption> books, List<CharacterFilterOption> characters) {
    }

    public record SessionDetail(
            String sessionId,
            CharacterIdentity character,
            BookIdentity book,
            Instant createdAt,
            Instant lastMessageAt,
            ChatContext context,
            Resume resume
    ) {
    }

    public record Message(String messageId, String role, String content, Instant createdAt) {
    }

    public record SessionDetailResponse(SessionDetail session, List<Message> messages) {
    }

    public record CharacterConversationResponse(SessionDetail session, List<Message> messages) {
    }

    public record ContinueRequest(String content, ChatContext context) {
    }

    public record VoiceCallTurn(String turnId, String role, String content) {
    }

    public record VoiceCallTranscriptRequest(List<VoiceCallTurn> turns) {
    }

    public record CharacterVoiceCallTranscriptRequest(List<VoiceCallTurn> turns, ChatContext context) {
    }

    public record VoiceCallTranscriptResponse(List<Message> messages, Instant lastMessageAt) {
    }

    public record CharacterVoiceCallTranscriptResponse(
            String sessionId,
            List<Message> messages,
            Instant lastMessageAt
    ) {
    }

    public record ContinueResponse(
            Message userMessage,
            Message characterMessage,
            ChatContext context,
            Instant lastMessageAt
    ) {
    }

    public record CharacterExchangeResponse(
            String sessionId,
            Message userMessage,
            Message characterMessage,
            ChatContext context,
            Instant lastMessageAt
    ) {
    }

    public record ApiError(String code, String message) {
    }

    public record ErrorEnvelope(ApiError error) {
    }
}
