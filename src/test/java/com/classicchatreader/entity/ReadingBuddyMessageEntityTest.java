package com.classicchatreader.entity;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ReadingBuddyMessageEntityTest {

    @Test
    void computeContentHash_isSha256HexOfRoleKindContentUtf8() throws Exception {
        String role = "buddy";
        String kind = "proactive";
        String content = "Darcy really said that.";

        String expectedPayload = role + "\n" + kind + "\n" + content;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String expected = HexFormat.of().formatHex(
                digest.digest(expectedPayload.getBytes(StandardCharsets.UTF_8))
        );

        assertEquals(expected, ReadingBuddyMessageEntity.computeContentHash(role, kind, content));
        assertEquals(64, expected.length());
        assertEquals(expected, expected.toLowerCase());
    }

    @Test
    void computeContentHash_isDeterministicAndSensitiveToFields() {
        String a = ReadingBuddyMessageEntity.computeContentHash("user", "chat", "hello");
        String b = ReadingBuddyMessageEntity.computeContentHash("user", "chat", "hello");
        String c = ReadingBuddyMessageEntity.computeContentHash("buddy", "chat", "hello");
        String d = ReadingBuddyMessageEntity.computeContentHash("user", "proactive", "hello");
        String e = ReadingBuddyMessageEntity.computeContentHash("user", "chat", "hello!");

        assertEquals(a, b);
        assertNotEquals(a, c);
        assertNotEquals(a, d);
        assertNotEquals(a, e);
    }

    @Test
    void computeContentHash_handlesNullPartsAsEmpty() {
        String hash = ReadingBuddyMessageEntity.computeContentHash(null, null, null);
        assertEquals(ReadingBuddyMessageEntity.computeContentHash("", "", ""), hash);
    }

    @Test
    void proactivePositionKey_formatsChapterAndParagraph() {
        assertEquals("3:12", ReadingBuddyMessageEntity.proactivePositionKey(3, 12));
    }
}
