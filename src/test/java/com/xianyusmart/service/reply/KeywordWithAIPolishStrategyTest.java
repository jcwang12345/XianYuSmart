package com.xianyusmart.service.reply;

import com.xianyusmart.config.rag.DynamicAIChatClientManager;
import com.xianyusmart.entity.bo.KeywordReplyRuleBO;
import com.xianyusmart.event.chatMessageEvent.ChatMessageData;
import com.xianyusmart.service.AIService;
import com.xianyusmart.service.KeywordReplyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KeywordWithAIPolishStrategyTest {
    private static final String SAFE_GUIDANCE = "请打开订单收到的发货/提货入口，按页面提示扫码进入售后群，群内有客服协助。";
    private static final String LINK_FREE_FALLBACK = "使用说明请查看订单发货信息，如需帮助请通过闲鱼联系卖家。";
    private static final String CONFIGURED_IMAGE = "https://example.com/qr.png";
    private final KeywordReplyService keywords = mock(KeywordReplyService.class);
    private final AIService ai = mock(AIService.class);
    private final DynamicAIChatClientManager manager = mock(DynamicAIChatClientManager.class);
    private final KeywordWithAIPolishStrategy strategy = new KeywordWithAIPolishStrategy();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(strategy, "keywordReplyService", keywords);
        ReflectionTestUtils.setField(strategy, "aiService", ai);
        ReflectionTestUtils.setField(strategy, "dynamicAIChatClientManager", manager);
        ReflectionTestUtils.setField(strategy, "safetyGuard", new AIReplySafetyGuard());
        when(manager.isAvailable()).thenReturn(true);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "【AI服务错误】连接超时"})
    void guardsOriginalTextWhenPolishReturnsNoSafeAnswer(String modelReply) {
        match("请查看https://example.com/help", CONFIGURED_IMAGE);
        when(ai.simpleChat(anyString())).thenReturn(modelReply);

        assertTextOnly(strategy.execute(List.of(message())), LINK_FREE_FALLBACK);
    }

    @Test
    void guardsOriginalTextWhenModelThrows() {
        match("请查看example.com/help", CONFIGURED_IMAGE);
        when(ai.simpleChat(anyString())).thenThrow(new IllegalStateException("synthetic model failure"));

        assertTextOnly(strategy.execute(List.of(message())), LINK_FREE_FALLBACK);
    }

    @Test
    void guardsOriginalTextWhenModelIsUnavailable() {
        match("请查看127.0.0.1/help", CONFIGURED_IMAGE);
        when(manager.isAvailable()).thenReturn(false);

        assertTextOnly(strategy.execute(List.of(message())), LINK_FREE_FALLBACK);
        verifyNoInteractions(ai);
    }

    @Test
    void replacesLinksIntroducedByPolishAndIncludesGenerationConstraint() {
        match("使用说明请查看订单发货信息。", null);
        when(ai.simpleChat(anyString())).thenReturn("请打开https://example.com/new-help");

        assertTextOnly(strategy.execute(List.of(message())), LINK_FREE_FALLBACK);
        verify(ai).simpleChat(argThat(prompt -> prompt.contains("不得输出网址、链接")
                && prompt.contains("不编造售后群、群内客服或订单已发货等状态")));
    }

    @Test
    void preservesPlainSupportGuidanceAndDoesNotAttachConfiguredImage() {
        match(SAFE_GUIDANCE, CONFIGURED_IMAGE);
        when(ai.simpleChat(anyString())).thenReturn(SAFE_GUIDANCE);

        ReplyStrategy.ReplyResult result = strategy.execute(List.of(message()));

        assertTextOnly(result, SAFE_GUIDANCE);
        assertEquals(11L, result.getSelectedRuleId());
        assertEquals(12L, result.getSelectedContentId());
        assertEquals("售后", result.getMatchedKeyword());
    }

    @Test
    void doesNotSendImageOnlyAiMixedReply() {
        match(null, CONFIGURED_IMAGE);

        ReplyStrategy.ReplyResult result = strategy.execute(List.of(message()));

        assertFalse(result.isSuccess());
        assertTrue(result.getItems().isEmpty());
        verifyNoInteractions(ai);
    }

    @Test
    void doesNotSendImageWhenOriginalAndModelAreInternalErrors() {
        match("AI回复生成失败：synthetic", CONFIGURED_IMAGE);
        when(ai.simpleChat(anyString())).thenReturn("【AI服务错误】synthetic");

        ReplyStrategy.ReplyResult result = strategy.execute(List.of(message()));

        assertFalse(result.isSuccess());
        assertTrue(result.getItems().isEmpty());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"请查看https://example.com/help"})
    void leavesPureKeywordTextAndImageBehaviorUnchanged(String originalText) {
        match(originalText, CONFIGURED_IMAGE);
        KeywordReplyStrategy pureKeyword = new KeywordReplyStrategy();
        ReflectionTestUtils.setField(pureKeyword, "keywordReplyService", keywords);

        ReplyStrategy.ReplyResult result = pureKeyword.execute(List.of(message()));

        assertTrue(result.isSuccess());
        assertEquals(1, result.getItems().size());
        assertEquals(1, result.getItems().getFirst().getReplyType());
        assertEquals(CONFIGURED_IMAGE, result.getItems().getFirst().getImageUrl());
        if (originalText != null && !originalText.isBlank()) {
            assertEquals(originalText, result.getItems().getFirst().getTextContent());
        } else {
            assertNull(result.getItems().getFirst().getTextContent());
        }
        verifyNoInteractions(ai);
    }

    private void match(String text, String image) {
        KeywordReplyRuleBO.KeywordReplyContentBO content = new KeywordReplyRuleBO.KeywordReplyContentBO();
        content.setId(12L);
        content.setReplyText(text);
        content.setReplyImageUrl(image);
        KeywordReplyRuleBO rule = new KeywordReplyRuleBO();
        rule.setId(11L);
        rule.setKeyword("售后");
        rule.setContents(List.of(content));
        when(keywords.matchKeyword(1L, "goods-1", "售后怎么处理")).thenReturn(List.of(rule));
    }

    private static void assertTextOnly(ReplyStrategy.ReplyResult result, String expected) {
        assertTrue(result.isSuccess());
        assertEquals(1, result.getItems().size());
        assertEquals(3, result.getItems().getFirst().getReplyType());
        assertEquals(expected, result.getItems().getFirst().getTextContent());
        assertNull(result.getItems().getFirst().getImageUrl());
    }

    private static ChatMessageData message() {
        ChatMessageData message = new ChatMessageData();
        message.setXianyuAccountId(1L);
        message.setXyGoodsId("goods-1");
        message.setMsgContent("售后怎么处理");
        return message;
    }
}
