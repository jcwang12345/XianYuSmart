package com.xianyusmart.service.reply;

import com.xianyusmart.entity.XianyuGoodsConfig;
import com.xianyusmart.entity.XianyuGoodsInfo;
import com.xianyusmart.event.chatMessageEvent.ChatMessageData;
import com.xianyusmart.mapper.XianyuChatMessageMapper;
import com.xianyusmart.mapper.XianyuGoodsConfigMapper;
import com.xianyusmart.mapper.XianyuGoodsInfoMapper;
import com.xianyusmart.service.AIService;
import com.xianyusmart.service.AccountService;
import com.xianyusmart.service.bo.RAGReplyResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AIReplyStrategyTest {

    private AIService aiService;
    private XianyuGoodsConfigMapper configMapper;
    private XianyuGoodsInfoMapper goodsMapper;
    private AIReplyStrategy strategy;

    @BeforeEach
    void setUp() {
        aiService = mock(AIService.class);
        configMapper = mock(XianyuGoodsConfigMapper.class);
        goodsMapper = mock(XianyuGoodsInfoMapper.class);
        XianyuChatMessageMapper messageMapper = mock(XianyuChatMessageMapper.class);
        AccountService accountService = mock(AccountService.class);

        strategy = new AIReplyStrategy();
        ReflectionTestUtils.setField(strategy, "aiService", aiService);
        ReflectionTestUtils.setField(strategy, "goodsConfigMapper", configMapper);
        ReflectionTestUtils.setField(strategy, "goodsInfoMapper", goodsMapper);
        ReflectionTestUtils.setField(strategy, "preparationService",
                new AIReplyPreparationService(messageMapper, accountService));
        ReflectionTestUtils.setField(strategy, "safetyGuard", new AIReplySafetyGuard());
    }

    @Test
    void sendsPreparedIntentPolicyThroughContextAwareAiMethod() {
        XianyuGoodsConfig config = new XianyuGoodsConfig();
        config.setXianyuAutoReplyContextOn(0);
        config.setFixedMaterial("最低价由卖家确认");
        XianyuGoodsInfo goods = new XianyuGoodsInfo();
        goods.setSoldPrice("100");
        goods.setDetailInfo("测试商品");
        when(configMapper.selectByAccountAndGoodsId(1L, "g1")).thenReturn(config);
        when(goodsMapper.selectOne(any())).thenReturn(goods);

        RAGReplyResult aiResult = new RAGReplyResult();
        aiResult.setReplyContent("目前按标价出售，您可以先说说期望价格。");
        when(aiService.chatByRAGWithFixedMaterial(eq("最低多少钱"), eq("g1"), eq(""),
                any(String.class), eq("测试商品"))).thenReturn(aiResult);

        ReplyStrategy.ReplyResult result = strategy.execute(List.of(message("最低多少钱")));

        assertTrue(result.isSuccess());
        assertEquals("PRICE", result.getAiIntent());
        assertEquals(1, result.getBargainRound());
        assertEquals("目前按标价出售，您可以先说说期望价格。",
                result.getItems().getFirst().getTextContent());
        verify(aiService).chatByRAGWithFixedMaterial(eq("最低多少钱"), eq("g1"), eq(""),
                argThat(material -> material.contains("最低价由卖家确认")
                        && material.contains("不得自行降价")), eq("测试商品"));
    }

    @Test
    void doesNotSendInternalAiErrorsToBuyer() {
        XianyuGoodsConfig config = new XianyuGoodsConfig();
        config.setXianyuAutoReplyContextOn(0);
        when(configMapper.selectByAccountAndGoodsId(1L, "g1")).thenReturn(config);
        when(goodsMapper.selectOne(any())).thenReturn(new XianyuGoodsInfo());
        RAGReplyResult aiResult = new RAGReplyResult();
        aiResult.setReplyContent("【AI服务错误】连接超时");
        when(aiService.chatByRAGWithFixedMaterial(any(), any(), any(), any(), any())).thenReturn(aiResult);

        ReplyStrategy.ReplyResult result = strategy.execute(List.of(message("你好")));

        assertFalse(result.isSuccess());
    }

    private static ChatMessageData message(String content) {
        ChatMessageData message = new ChatMessageData();
        message.setXianyuAccountId(1L);
        message.setXyGoodsId("g1");
        message.setSId("buyer@goofish");
        message.setPnmId("p1");
        message.setMsgContent(content);
        return message;
    }
}
