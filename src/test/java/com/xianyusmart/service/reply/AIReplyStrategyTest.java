package com.xianyusmart.service.reply;

import com.xianyusmart.entity.XianyuGoodsConfig;
import com.xianyusmart.entity.XianyuGoodsInfo;
import com.xianyusmart.event.chatMessageEvent.ChatMessageData;
import com.xianyusmart.mapper.XianyuChatMessageMapper;
import com.xianyusmart.mapper.XianyuGoodsConfigMapper;
import com.xianyusmart.mapper.XianyuGoodsInfoMapper;
import com.xianyusmart.service.AIService;
import com.xianyusmart.service.AccountService;
import com.xianyusmart.service.GoodsKnowledgeService;
import com.xianyusmart.service.bo.RAGReplyResult;
import com.xianyusmart.config.rag.DynamicAIChatClientManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private DynamicAIChatClientManager chatClientManager;
    private GoodsKnowledgeService goodsKnowledgeService;

    @BeforeEach
    void setUp() {
        aiService = mock(AIService.class);
        configMapper = mock(XianyuGoodsConfigMapper.class);
        goodsMapper = mock(XianyuGoodsInfoMapper.class);
        XianyuChatMessageMapper messageMapper = mock(XianyuChatMessageMapper.class);
        AccountService accountService = mock(AccountService.class);
        chatClientManager = mock(DynamicAIChatClientManager.class);
        goodsKnowledgeService = mock(GoodsKnowledgeService.class);
        DynamicAIChatClientManager.AIStatusInfo status = new DynamicAIChatClientManager.AIStatusInfo();
        status.setModel("qa-model");
        when(chatClientManager.getStatusInfo()).thenReturn(status);
        when(chatClientManager.isAvailable()).thenReturn(true);

        strategy = new AIReplyStrategy();
        ReflectionTestUtils.setField(strategy, "aiService", aiService);
        ReflectionTestUtils.setField(strategy, "goodsConfigMapper", configMapper);
        ReflectionTestUtils.setField(strategy, "goodsInfoMapper", goodsMapper);
        ReflectionTestUtils.setField(strategy, "preparationService",
                new AIReplyPreparationService(messageMapper, accountService));
        ReflectionTestUtils.setField(strategy, "safetyGuard", new AIReplySafetyGuard());
        ReflectionTestUtils.setField(strategy, "chatClientManager", chatClientManager);
        ReflectionTestUtils.setField(strategy, "goodsKnowledgeService", goodsKnowledgeService);
        ReflectionTestUtils.setField(strategy, "factSafetyPolicy", new ReplyFactSafetyPolicy());
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
        when(goodsKnowledgeService.effective(1L, "g1")).thenReturn(
                new GoodsKnowledgeService.ActiveKnowledge(91L, 3, "最低价由卖家确认", null, null));
        when(goodsMapper.selectOne(any())).thenReturn(goods);

        RAGReplyResult aiResult = new RAGReplyResult();
        aiResult.setReplyContent("目前按标价出售，您可以先说说期望价格。");
        when(aiService.chatByRAGWithFixedMaterial(eq("最低多少钱"), eq("g1"), eq(""),
                any(String.class), eq("测试商品"))).thenReturn(aiResult);

        ReplyStrategy.ReplyResult result = strategy.execute(List.of(message("最低多少钱")));

        assertTrue(result.isSuccess());
        assertEquals("PRICE", result.getAiIntent());
        assertEquals(1, result.getBargainRound());
        assertEquals(91L, result.getKnowledgeVersionId());
        assertEquals(3, result.getKnowledgeVersionNo());
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
        assertEquals("AI_NO_SAFE_ANSWER", result.getHandoffReasonCode());
        assertEquals("qa-model", result.getModelName());
    }

    @Test
    void substitutesOrderInformationGuidanceForModelLinks() {
        XianyuGoodsConfig config = new XianyuGoodsConfig();
        config.setXianyuAutoReplyContextOn(0);
        when(configMapper.selectByAccountAndGoodsId(1L, "g1")).thenReturn(config);
        RAGReplyResult aiResult = new RAGReplyResult();
        aiResult.setReplyContent("请查看https://example.com/help");
        when(aiService.chatByRAGWithFixedMaterial(any(), any(), any(), any(), any())).thenReturn(aiResult);

        ReplyStrategy.ReplyResult result = strategy.execute(List.of(message("你好")));

        assertTrue(result.isSuccess());
        assertEquals("使用说明请查看订单发货信息，如需帮助请通过闲鱼联系卖家。",
                result.getItems().getFirst().getTextContent());
        assertEquals(2, result.getItems().getFirst().getReplyType());
        assertNull(result.getItems().getFirst().getImageUrl());
    }

    @Test
    void preservesLinkFreeAfterSalesGuidanceFromActiveKnowledge() {
        String guidance = "请打开订单收到的发货/提货入口，按页面提示扫码进入售后群，群内有客服协助。";
        XianyuGoodsConfig config = new XianyuGoodsConfig();
        config.setXianyuAutoReplyContextOn(0);
        when(configMapper.selectByAccountAndGoodsId(1L, "g1")).thenReturn(config);
        when(goodsKnowledgeService.effective(1L, "g1")).thenReturn(
                new GoodsKnowledgeService.ActiveKnowledge(92L, 4, guidance, null, null));
        RAGReplyResult aiResult = new RAGReplyResult();
        aiResult.setReplyContent(guidance);
        when(aiService.chatByRAGWithFixedMaterial(any(), any(), any(), any(), any())).thenReturn(aiResult);

        ReplyStrategy.ReplyResult result = strategy.execute(List.of(message("暗标检查售后怎么联系")));

        assertTrue(result.isSuccess());
        assertEquals(guidance, result.getItems().getFirst().getTextContent());
        assertEquals("AFTER_SALES", result.getAiIntent());
        assertEquals(92L, result.getKnowledgeVersionId());
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
