package com.xianyusmart.websocket.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.event.chatMessageEvent.ChatMessageReceivedEvent;
import com.xianyusmart.utils.AccountDisplayNameUtils;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SyncMessageHandlerTest {

    @Test
    void extractsOnlyOrderIdFromNestedPaymentCardJson() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        String card = objectMapper.writeValueAsString(Map.of(
                "contentType", 26,
                "dxCard", Map.of("item", Map.of("main", Map.of("exContent", Map.of(
                        "button", Map.of("targetUrl", "https://h5.example/order?orderId=4502258607179022847"),
                        "title", "我已付款，等待你发货"))))));
        String record = objectMapper.writeValueAsString(Map.of("1", Map.of(
                "2", "buyer@goofish", "3", "payment-pnm",
                "6", Map.of("3", Map.of("5", card)),
                "10", Map.of("senderUserId", "buyer", "reminderContent", "我已付款，等待你发货"))));
        String encoded = Base64.getEncoder().encodeToString(record.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> envelope = Map.of("lwp", "/s/sync",
                "body", Map.of("syncPushPackage", Map.of("data", List.of(Map.of("data", encoded)))));

        AtomicReference<Object> published = new AtomicReference<>();
        SyncMessageHandler handler = handler(objectMapper, published);
        handler.handle("1", envelope);

        assertInstanceOf(ChatMessageReceivedEvent.class, published.get());
        assertEquals("4502258607179022847",
                ((ChatMessageReceivedEvent) published.get()).getMessageData().getOrderId());
    }

    @Test
    void publishesCurrentCardUpdateWithOrderAndGoodsIds() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        String record = objectMapper.writeValueAsString(Map.of(
                "1", "encoded-card-id",
                "2", "cid-pay@goofish",
                "4", Map.of("senderUserId", "buyer-pay",
                        "reminderContent", "[我已付款，等待你发货]",
                        "bizTag", "{\"messageId\":\"m-pay-handler\"}",
                        "extJson", "{\"itemId\":\"item-pay\",\"orderId\":\"order-pay\"}")));
        String encoded = Base64.getEncoder().encodeToString(record.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> envelope = Map.of(
                "lwp", "/s/para",
                "body", Map.of("syncPushPackage", Map.of("data", List.of(Map.of("data", encoded)))));

        AtomicReference<Object> published = new AtomicReference<>();
        SyncMessageHandler handler = handler(objectMapper, published);

        handler.handle("1", envelope);

        assertInstanceOf(ChatMessageReceivedEvent.class, published.get());
        var message = ((ChatMessageReceivedEvent) published.get()).getMessageData();
        assertEquals("m-pay-handler", message.getPnmId());
        assertEquals("item-pay", message.getXyGoodsId());
        assertEquals("order-pay", message.getOrderId());
        assertEquals(26, message.getContentType());
    }

    private SyncMessageHandler handler(ObjectMapper objectMapper, AtomicReference<Object> published) {
        ApplicationEventPublisher publisher = published::set;
        SyncMessageHandler handler = new SyncMessageHandler();
        ReflectionTestUtils.setField(handler, "eventPublisher", publisher);
        ReflectionTestUtils.setField(handler, "liveSyncMessageParser", new LiveSyncMessageParser(objectMapper));
        AccountDisplayNameUtils names = mock(AccountDisplayNameUtils.class);
        when(names.getDisplayName("1")).thenReturn("账号1");
        ReflectionTestUtils.setField(handler, "displayNameUtils", names);
        return handler;
    }
}
