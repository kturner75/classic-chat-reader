package com.classicchatreader.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.Instant;

public record ChatMessage(
    String role,
    String content,
    @JsonDeserialize(using = TimestampDeserializer.class) long timestamp
) {
    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, System.currentTimeMillis());
    }

    public static ChatMessage character(String content) {
        return new ChatMessage("character", content, System.currentTimeMillis());
    }

    public static final class TimestampDeserializer extends JsonDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken().isNumeric()) {
                return parser.getLongValue();
            }
            if (parser.currentToken().isScalarValue()) {
                String value = parser.getValueAsString();
                if (value != null && !value.isBlank()) {
                    try {
                        return Long.parseLong(value);
                    } catch (NumberFormatException ignored) {
                        try {
                            return Instant.parse(value).toEpochMilli();
                        } catch (DateTimeException ignoredDate) {
                            // Fall through to Jackson's standard invalid-value response.
                        }
                    }
                }
            }
            return (Long) context.handleUnexpectedToken(Long.class, parser);
        }
    }
}
