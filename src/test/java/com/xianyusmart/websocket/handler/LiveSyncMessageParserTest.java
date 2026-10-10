package com.xianyusmart.websocket.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class LiveSyncMessageParserTest {

    private final LiveSyncMessageParser parser = new LiveSyncMessageParser(new ObjectMapper());

    @Test
    void parsesCurrentTextMessageWithoutLegacyPnmField() throws Exception {
        String json = new ObjectMapper().writeValueAsString(java.util.Map.of("1", java.util.Map.of(
                "2", "cid-001@goofish", "5", 1_790_770_000,
                "10", java.util.Map.of("senderNick", "买家小张", "senderUserId", "buyer-1",
                        "reminderContent", "在吗？", "bizTag", "{\"messageId\":\"m-001\"}",
                        "extJson", "{\"messageId\":\"m-001\",\"itemId\":\"item-001\"}"),
                "6", java.util.Map.of("3", java.util.Map.of("5",
                        "{\"contentType\":1,\"text\":{\"text\":\"在吗？\"}}")))));

        var result = parser.parse(1L, "/s/para", json);

        assertTrue(result.parsed(), result.ignoredReason());
        assertEquals("m-001", result.message().getPnmId());
        assertEquals("cid-001@goofish", result.message().getSId());
        assertEquals("item-001", result.message().getXyGoodsId());
        assertEquals(1, result.message().getContentType());
        assertEquals("在吗？", result.message().getMsgContent());
        assertEquals(1_790_770_000_000L, result.message().getMessageTime());
    }

    @Test
    void decodesBase64ContentAtNewContentField() throws Exception {
        String content = Base64.getEncoder().encodeToString(
                "{\"contentType\":2,\"image\":{\"pics\":[{\"url\":\"//img.example/a.jpg\"}]}}"
                        .getBytes(StandardCharsets.UTF_8));
        String json = new ObjectMapper().writeValueAsString(java.util.Map.of("1", java.util.Map.of(
                "2", "cid-image@goofish",
                "10", java.util.Map.of("senderUserId", "buyer-image", "reminderContent", "[图片]",
                        "bizTag", "{\"messageId\":\"m-image\"}"),
                "6", java.util.Map.of("3", java.util.Map.of("1", content)))));

        var result = parser.parse(1L, "/s/para", json);

        assertTrue(result.parsed(), result.ignoredReason());
        assertEquals(2, result.message().getContentType());
        assertEquals("https://img.example/a.jpg", result.message().getMsgContent());
    }

    @Test
    void parsesCardUpdatePaymentShape() throws Exception {
        String json = new ObjectMapper().writeValueAsString(java.util.Map.of(
                "1", "encoded-card-id", "2", "cid-pay@goofish", "5", 1_790_771_000_000L,
                "4", java.util.Map.of("senderUserId", "buyer-pay", "senderNick", "买家",
                        "reminderContent", "[我已付款，等待你发货]",
                        "bizTag", "{\"messageId\":\"m-pay\"}",
                        "extJson", "{\"messageId\":\"m-pay\",\"itemId\":\"item-pay\",\"orderId\":\"order-pay\"}")));

        var result = parser.parse(1L, "/s/para", json);

        assertTrue(result.parsed(), result.ignoredReason());
        assertEquals("m-pay", result.message().getPnmId());
        assertEquals("cid-pay@goofish", result.message().getSId());
        assertEquals("item-pay", result.message().getXyGoodsId());
        assertEquals(26, result.message().getContentType());
    }

    @Test
    void parsesSystemPaymentCardAndExtractsItemFromJumpUrl() throws Exception {
        String json = new ObjectMapper().writeValueAsString(java.util.Map.of("1", java.util.Map.of(
                "2", "cid-system@goofish", "6", java.util.Map.of("3", java.util.Map.of(
                        "2", "[已付款，待发货]",
                        "5", "{\"dxCard\":{\"item\":{\"main\":{\"exContent\":{\"button\":{\"targetUrl\":\"https://h5.example/order?orderId=order-1&itemId=item-1\"}}}}}}}")))));

        var result = parser.parse(1L, "/s/para", json);

        assertTrue(result.parsed(), result.ignoredReason());
        assertEquals(26, result.message().getContentType());
        assertEquals("item-1", result.message().getXyGoodsId());
        assertTrue(result.message().getPnmId().startsWith("fallback-"));
    }

    @Test
    void fallbackMessageIdIsStableAcrossReplay() {
        String json = """
                {"1":{"2":"cid-stable@goofish","10":{"senderUserId":"buyer","reminderContent":"hello"}}}
                """;

        var first = parser.parse(1L, "/s/para", json);
        var second = parser.parse(1L, "/s/para", json);

        assertTrue(first.parsed(), first.ignoredReason());
        assertEquals(first.message().getPnmId(), second.message().getPnmId());
    }

    @Test
    void keepsLegacyPnmIdAndIgnoresReadReceipt() {
        String legacy = """
                {"1":{"2":"cid-old@goofish","3":"legacy.PNM","10":{"senderUserId":"buyer","reminderContent":"old"}}}
                """;
        assertEquals("legacy.PNM", parser.parse(1L, "/s/para", legacy).message().getPnmId());

        var receipt = parser.parse(1L, "/s/para", "{\"2\":2}");
        assertFalse(receipt.parsed());
        assertEquals("READ_RECEIPT", receipt.ignoredReason());
    }
}
