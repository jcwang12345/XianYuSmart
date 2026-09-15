package com.xianyusmart.service.reply;

import com.xianyusmart.config.rag.DynamicAIChatClientManager;
import com.xianyusmart.entity.XianyuGoodsConfig;
import com.xianyusmart.entity.XianyuGoodsInfo;
import com.xianyusmart.entity.bo.KeywordReplyRuleBO;
import com.xianyusmart.event.chatMessageEvent.ChatMessageData;
import com.xianyusmart.mapper.XianyuGoodsConfigMapper;
import com.xianyusmart.mapper.XianyuGoodsInfoMapper;
import com.xianyusmart.service.AIService;
import com.xianyusmart.service.KeywordReplyService;
import com.xianyusmart.service.GoodsKnowledgeService;
import com.xianyusmart.service.bo.RAGReplyResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class KeywordWithAIPolishStrategy implements ReplyStrategy {

    private static final int REPLY_TYPE_KEYWORD_AI = 3;
    private static final int REPLY_TYPE_AI = 2;

    @Autowired
    private KeywordReplyService keywordReplyService;

    @Autowired
    private AIService aiService;

    @Autowired
    private DynamicAIChatClientManager dynamicAIChatClientManager;

    @Autowired
    private XianyuGoodsConfigMapper goodsConfigMapper;

    @Autowired
    private XianyuGoodsInfoMapper goodsInfoMapper;

    @Autowired
    private AIReplyPreparationService preparationService;

    @Autowired
    private AIReplySafetyGuard safetyGuard;

    @Autowired
    private GoodsKnowledgeService goodsKnowledgeService;

    @Autowired
    private ReplyFactSafetyPolicy factSafetyPolicy;

    @Override
    public ReplyResult execute(List<ChatMessageData> messageList) {
        ChatMessageData lastMessage = messageList.get(messageList.size() - 1);
        Long accountId = lastMessage.getXianyuAccountId();
        String xyGoodsId = lastMessage.getXyGoodsId();

        String buyerMessage = messageList.stream()
                .map(ChatMessageData::getMsgContent)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");

        List<KeywordReplyRuleBO> matchedRules = keywordReplyService.matchKeyword(accountId, xyGoodsId, buyerMessage);

        if (matchedRules != null && !matchedRules.isEmpty()) {
            return executeKeywordWithPolish(accountId, matchedRules);
        }

        return executeAIReply(accountId, xyGoodsId, messageList);
    }

    private ReplyResult executeKeywordWithPolish(Long accountId, List<KeywordReplyRuleBO> matchedRules) {
        KeywordReplyRuleBO winningRule = matchedRules.getFirst();
        List<KeywordReplyRuleBO.KeywordReplyContentBO> allContents = winningRule.getContents() == null
                ? List.of() : winningRule.getContents();

        if (allContents.isEmpty()) {
            return ReplyResult.fail();
        }

        // 使用稳定候选，避免网络重试前后挑中不同话术。
        KeywordReplyRuleBO.KeywordReplyContentBO selected = allContents.getFirst();
        List<ReplyResult.ReplyItem> items = new ArrayList<>();
        String originalText = selected.getReplyText();
        String image = selected.getReplyImageUrl();
        boolean hasText = originalText != null && !originalText.trim().isEmpty();
        boolean hasImage = image != null && !image.trim().isEmpty();

        String finalText = originalText;
        if (hasText && dynamicAIChatClientManager.isAvailable() && aiService != null) {
            try {
                String polishPrompt = String.format(
                        "你是一个闲鱼卖家，请用自然亲切的语气简单润色以下回复内容，保持原意不变，不要添加额外信息，直接输出润色后的内容：\n\n%s",
                        originalText
                );
                String polishedText = aiService.simpleChat(polishPrompt);
                String safePolishedText = safetyGuard.safeOrNull(polishedText);
                if (safePolishedText != null) {
                    finalText = safePolishedText;
                }
            } catch (Exception e) {
                log.warn("【账号{}】AI润化失败，使用原文回复: {}", accountId, e.getMessage());
            }
        }

        boolean finalHasText = finalText != null && !finalText.trim().isEmpty();
        if (finalHasText && hasImage) {
            items.add(ReplyResult.ReplyItem.textAndImage(finalText, image, REPLY_TYPE_KEYWORD_AI));
        } else if (finalHasText) {
            items.add(ReplyResult.ReplyItem.text(finalText, REPLY_TYPE_KEYWORD_AI));
        } else if (hasImage) {
            items.add(ReplyResult.ReplyItem.image(image, REPLY_TYPE_KEYWORD_AI));
        }

        if (items.isEmpty()) {
            return ReplyResult.fail();
        }

        ReplyResult result = ReplyResult.of(items);
        if (winningRule.getId() != null) result.setSelectedRuleId(Long.valueOf(winningRule.getId().toString()));
        if (selected.getId() != null) result.setSelectedContentId(Long.valueOf(selected.getId().toString()));
        String keywords = Integer.valueOf(1).equals(winningRule.getIsFallback()) ? "" : winningRule.getKeyword();
        result.setMatchedKeyword(keywords);
        result.setMatchedRules(matchedRules);
        if (!matchedRules.isEmpty()) {
            result.setMatchedRule(winningRule);
        }
        return result;
    }

    private ReplyResult executeAIReply(Long accountId, String xyGoodsId,
                                       List<ChatMessageData> messageList) {
        try {
            XianyuGoodsConfig goodsConfig = goodsConfigMapper.selectByAccountAndGoodsId(accountId, xyGoodsId);
            GoodsKnowledgeService.ActiveKnowledge knowledge = goodsKnowledgeService.effective(accountId, xyGoodsId);
            String fixedMaterial = knowledge == null ? null : knowledge.content();
            ReplyFactSafetyPolicy.Decision factDecision = factSafetyPolicy.evaluate(messageList, knowledge != null);
            if (!factDecision.allowed()) {
                return ReplyResult.handoff(factDecision.reasonCode(), factDecision.reasonDetail());
            }

            XianyuGoodsInfo goodsInfo = goodsInfoMapper.selectOne(
                    new LambdaQueryWrapper<XianyuGoodsInfo>()
                            .eq(XianyuGoodsInfo::getXyGoodId, xyGoodsId)
                            .eq(XianyuGoodsInfo::getXianyuAccountId, accountId)
            );
            String goodsDetail = goodsInfo != null ? goodsInfo.getDetailInfo() : null;
            AIReplyPreparationService.PreparedReply prepared = preparationService.prepare(messageList, goodsConfig, goodsInfo);
            ReplyResult result = executePreparedAIReply(prepared, xyGoodsId, fixedMaterial, goodsDetail);
            if (knowledge != null) {
                result.setKnowledgeVersionId(knowledge.id());
                result.setKnowledgeVersionNo(knowledge.versionNo());
            }
            return result;

        } catch (Exception e) {
            log.error("【账号{}】AI回复失败: xyGoodsId={}", accountId, xyGoodsId, e);
            return ReplyResult.fail();
        }
    }

    private ReplyResult executePreparedAIReply(AIReplyPreparationService.PreparedReply prepared,
                                               String xyGoodsId, String fixedMaterial, String goodsDetail) {
        long startedAt = System.nanoTime();
        RAGReplyResult ragResult = aiService.chatByRAGWithFixedMaterial(
                prepared.buyerMessage(), xyGoodsId, prepared.contextMessages(),
                AIReplyStrategy.appendPolicy(fixedMaterial, prepared.policy()), goodsDetail);

        String safeReply = ragResult == null ? null : safetyGuard.safeOrNull(ragResult.getReplyContent());
        Double confidence = ragResult == null || ragResult.getHitDetails() == null ? null
                : ragResult.getHitDetails().stream().map(RAGReplyResult.RAGHitDetail::getScore)
                .filter(Objects::nonNull).max(Double::compareTo).orElse(null);
        if (safeReply == null) return ReplyResult.handoff("AI_NO_SAFE_ANSWER","AI 返回为空或被安全门禁拦截");
        if (confidence != null && confidence < 0.55d) {
            ReplyResult handoff=ReplyResult.handoff("LOW_CONFIDENCE","知识命中置信度低于安全阈值");
            handoff.setConfidenceScore(confidence);
            return handoff;
        }
        ReplyResult result = ReplyResult.of(Collections.singletonList(
                ReplyResult.ReplyItem.text(safeReply, REPLY_TYPE_AI)));
        result.setAiIntent(prepared.intent().name());
        result.setBargainRound(prepared.bargainRound());
        result.setContextMessages(prepared.contextMessages());
        result.setRagHitDetails(ragResult.getHitDetails());
        result.setConfidenceScore(confidence);
        try { result.setModelName(dynamicAIChatClientManager.getStatusInfo().getModel()); } catch(Exception ignored) { }
        result.setProcessingDurationMs((System.nanoTime()-startedAt)/1_000_000L);
        return result;
    }

}
