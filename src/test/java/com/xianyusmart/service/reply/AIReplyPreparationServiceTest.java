package com.xianyusmart.service.reply;

import com.xianyusmart.entity.XianyuChatMessage;
import com.xianyusmart.entity.XianyuGoodsConfig;
import com.xianyusmart.entity.XianyuGoodsInfo;
import com.xianyusmart.event.chatMessageEvent.ChatMessageData;
import com.xianyusmart.mapper.XianyuChatMessageMapper;
import com.xianyusmart.service.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AIReplyPreparationServiceTest {

    private XianyuChatMessageMapper messageMapper;
    private AccountService accountService;
    private AIReplyPreparationService service;

    @BeforeEach
    void setUp() {
        messageMapper = mock(XianyuChatMessageMapper.class);
        accountService = mock(AccountService.class);
        service = new AIReplyPreparationService(messageMapper, accountService);
    }

    @ParameterizedTest
    @CsvSource({
            "最低多少钱,PRICE",
            "这个型号支持什么接口,TECHNICAL",
            "付款后多久发货,DELIVERY",
            "不能用了可以退款吗,AFTER_SALES",
            "你好还在吗,GENERAL"
    })
    void routesCommonBuyerIntents(String message, AIReplyPreparationService.Intent expected) {
        assertEquals(expected, service.classify(message));
    }

    @Test
    void includesHistoryAndCountsBargainRoundsWithoutDuplicatingTrigger() {
        ChatMessageData trigger = trigger("current", "还能再便宜一点吗");
        XianyuGoodsConfig config = new XianyuGoodsConfig();
        config.setXianyuAutoReplyContextOn(1);
        XianyuGoodsInfo goods = new XianyuGoodsInfo();
        goods.setSoldPrice("99.00");

        when(accountService.getXianyuUserId(7L)).thenReturn("seller");
        when(messageMapper.findRecentBySId(eq(7L), eq("chat@goofish"), anyInt(), eq(0)))
                .thenReturn(List.of(
                        stored("current", "buyer", "还能再便宜一点吗", 1),
                        stored("seller-reply", "seller", "目前按标价出售", 888),
                        stored("first-price", "buyer", "90元可以吗", 1)
                ));

        AIReplyPreparationService.PreparedReply prepared = service.prepare(List.of(trigger), config, goods);

        assertEquals(AIReplyPreparationService.Intent.PRICE, prepared.intent());
        assertEquals(2, prepared.bargainRound());
        assertEquals("user: 90元可以吗\nassistant: 目前按标价出售", prepared.contextMessages());
        assertFalse(prepared.contextMessages().contains("还能再便宜一点吗"));
        assertTrue(prepared.policy().contains("商品当前标价为99.00元"));
        assertTrue(prepared.policy().contains("不得自行降价"));
    }

    @Test
    void honorsDisabledContextSwitch() {
        ChatMessageData trigger = trigger("current", "你好");
        XianyuGoodsConfig config = new XianyuGoodsConfig();
        config.setXianyuAutoReplyContextOn(0);

        AIReplyPreparationService.PreparedReply prepared = service.prepare(List.of(trigger), config, null);

        assertEquals("", prepared.contextMessages());
        verify(messageMapper, never()).findRecentBySId(eq(7L), eq("chat@goofish"), anyInt(), eq(0));
    }

    private static ChatMessageData trigger(String pnmId, String content) {
        ChatMessageData message = new ChatMessageData();
        message.setXianyuAccountId(7L);
        message.setSId("chat@goofish");
        message.setXyGoodsId("goods-1");
        message.setPnmId(pnmId);
        message.setMsgContent(content);
        return message;
    }

    private static XianyuChatMessage stored(String pnmId, String sender, String content, int contentType) {
        XianyuChatMessage message = new XianyuChatMessage();
        message.setPnmId(pnmId);
        message.setSenderUserId(sender);
        message.setMsgContent(content);
        message.setContentType(contentType);
        return message;
    }
}
