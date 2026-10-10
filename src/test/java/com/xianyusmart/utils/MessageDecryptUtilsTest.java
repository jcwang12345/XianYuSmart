package com.xianyusmart.utils;

import org.junit.jupiter.api.Test;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessageDecryptUtilsTest {

    @Test
    void decodesBase64JsonBeforeTryingMessagePack() {
        String encoded = Base64.getEncoder().encodeToString(
                "{\"1\":{\"2\":\"cid@goofish\"}}".getBytes(StandardCharsets.UTF_8));

        assertEquals("{\"1\":{\"2\":\"cid@goofish\"}}", MessageDecryptUtils.decrypt(encoded));
    }

    @Test
    void keepsLegacyMessagePackCompatibility() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (MessagePacker packer = MessagePack.newDefaultPacker(output)) {
            packer.packMapHeader(1);
            packer.packString("2");
            packer.packInt(2);
        }

        assertEquals("{\"2\":2}", MessageDecryptUtils.decrypt(
                Base64.getEncoder().encodeToString(output.toByteArray())));
    }

    @Test
    void decodesNumericKeyMessagePackChatRecord() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (MessagePacker packer = MessagePack.newDefaultPacker(output)) {
            packer.packMapHeader(1);
            packer.packInt(1);
            packer.packMapHeader(2);
            packer.packInt(2).packString("buyer@goofish");
            packer.packInt(10);
            packer.packMapHeader(2);
            packer.packString("senderUserId").packString("buyer");
            packer.packString("reminderContent").packString("测试B04");
        }

        String decoded = MessageDecryptUtils.decrypt(
                Base64.getEncoder().encodeToString(output.toByteArray()));
        org.junit.jupiter.api.Assertions.assertNotNull(decoded);
        org.junit.jupiter.api.Assertions.assertEquals("测试B04",
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(decoded)
                        .path("1").path("10").path("reminderContent").asText());
    }

    @Test
    void decodesSyntheticProtocolMessagePackFixture() throws Exception {
        String encoded;
        try (InputStream input = getClass().getResourceAsStream("/protocol/chat-text-msgpack.b64")) {
            org.junit.jupiter.api.Assertions.assertNotNull(input);
            encoded = new String(input.readAllBytes(), StandardCharsets.US_ASCII).trim();
        }

        String decoded = MessageDecryptUtils.decrypt(encoded);
        org.junit.jupiter.api.Assertions.assertNotNull(decoded);
        var record = new com.fasterxml.jackson.databind.ObjectMapper().readTree(decoded);
        org.junit.jupiter.api.Assertions.assertTrue(record.path("1").path("5").isIntegralNumber());
        org.junit.jupiter.api.Assertions.assertEquals(9000000000000000000L,
                record.path("1").path("5").longValue());
        org.junit.jupiter.api.Assertions.assertTrue(record.path("1").path("10").has("reminderContent"));
        org.junit.jupiter.api.Assertions.assertTrue(record.path("1").path("10").has("senderUserId"));
        var parsed = new com.xianyusmart.websocket.handler.LiveSyncMessageParser(
                new com.fasterxml.jackson.databind.ObjectMapper()).parse(1L, "/s/para", decoded);
        org.junit.jupiter.api.Assertions.assertTrue(parsed.parsed(), parsed.ignoredReason());
        org.junit.jupiter.api.Assertions.assertEquals(26, parsed.message().getContentType());
        org.junit.jupiter.api.Assertions.assertNotNull(parsed.message().getXyGoodsId());
        org.junit.jupiter.api.Assertions.assertEquals("QA_GOODS", parsed.message().getXyGoodsId());
        org.junit.jupiter.api.Assertions.assertEquals("QA_BUYER", parsed.message().getSenderUserId());
        org.junit.jupiter.api.Assertions.assertTrue(parsed.message().getMsgContent().contains("QA_SYNTHETIC"));
    }
}
