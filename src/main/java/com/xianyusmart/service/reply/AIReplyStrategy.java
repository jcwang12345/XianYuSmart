package com.xianyusmart.service.reply;

import com.xianyusmart.entity.XianyuGoodsConfig;
import com.xianyusmart.entity.XianyuGoodsInfo;
import com.xianyusmart.entity.bo.KeywordReplyRuleBO;
import com.xianyusmart.event.chatMessageEvent.ChatMessageData;
import com.xianyusmart.mapper.XianyuGoodsConfigMapper;
import com.xianyusmart.mapper.XianyuGoodsInfoMapper;
import com.xianyusmart.service.AIService;
import com.xianyusmart.service.bo.RAGReplyResult;
import com.xianyusmart.config.rag.DynamicAIChatClientManager;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Slf4j
@Component
public class AIReplyStrategy implements ReplyStrategy {

    private static final int REPLY_TYPE_AI = 2;

    @Autowired
    private AIService aiService;

    @Autowired
    private XianyuGoodsConfigMapper goodsConfigMapper;

    @Autowired
    private XianyuGoodsInfoMapper goodsInfoMapper;

    @Autowired
    private AIReplyPreparationService preparationService;

    @Autowired
    private AIReplySafetyGuard safetyGuard;

    @Autowired
    private DynamicAIChatClientManager chatClientManager;

    @Override
    public ReplyResult execute(List<ChatMessageData> messageList) {
        long startedAt = System.nanoTime();
        ChatMessageData lastMessage = messageList.get(messageList.size() - 1);
        Long accountId = lastMessage.getXianyuAccountId();
        String xyGoodsId = lastMessage.getXyGoodsId();

        try {
            XianyuGoodsConfig goodsConfig = goodsConfigMapper.selectByAccountAndGoodsId(accountId, xyGoodsId);
            String fixedMaterial = goodsConfig != null ? goodsConfig.getFixedMaterial() : null;

            XianyuGoodsInfo goodsInfo = goodsInfoMapper.selectOne(
                    new LambdaQueryWrapper<XianyuGoodsInfo>()
                            .eq(XianyuGoodsInfo::getXyGoodId, xyGoodsId)
                            .eq(XianyuGoodsInfo::getXianyuAccountId, accountId)
            );
            String goodsDetail = goodsInfo != null ? goodsInfo.getDetailInfo() : null;

            AIReplyPreparationService.PreparedReply prepared = preparationService.prepare(
                    messageList, goodsConfig, goodsInfo);
            String guardedMaterial = appendPolicy(fixedMaterial, prepared.policy());
            RAGReplyResult result = aiService.chatByRAGWithFixedMaterial(
                    prepared.buyerMessage(), xyGoodsId, prepared.contextMessages(), guardedMaterial, goodsDetail);

            String safeReply = result == null ? null : safetyGuard.safeOrNull(result.getReplyContent());
            Double confidence = result == null || result.getHitDetails() == null ? null
                    : result.getHitDetails().stream().map(RAGReplyResult.RAGHitDetail::getScore)
                    .filter(java.util.Objects::nonNull).max(Double::compareTo).orElse(null);
            String model = null;
            try { model = chatClientManager.getStatusInfo().getModel(); } catch (Exception ignored) { }
            if (safeReply != null) {
                ReplyResult replyResult = ReplyResult.of(Collections.singletonList(
                        ReplyResult.ReplyItem.text(safeReply, REPLY_TYPE_AI)));
                replyResult.setAiIntent(prepared.intent().name());
                replyResult.setBargainRound(prepared.bargainRound());
                replyResult.setContextMessages(prepared.contextMessages());
                replyResult.setRagHitDetails(result.getHitDetails());
                replyResult.setConfidenceScore(confidence);
                replyResult.setModelName(model);
                replyResult.setProcessingDurationMs((System.nanoTime() - startedAt) / 1_000_000L);
                return replyResult;
            }
            ReplyResult handoff = ReplyResult.handoff(
                    chatClientManager.isAvailable() ? "AI_NO_SAFE_ANSWER" : "AI_UNAVAILABLE",
                    chatClientManager.isAvailable() ? "AI 返回为空或被安全门禁拦截" : "AI 服务未启用或配置不可用");
            handoff.setConfidenceScore(confidence);
            handoff.setModelName(model);
            handoff.setProcessingDurationMs((System.nanoTime() - startedAt) / 1_000_000L);
            return handoff;
        } catch (Exception e) {
            log.error("【账号{}】AI回复策略执行失败: xyGoodsId={}", accountId, xyGoodsId, e);
            ReplyResult handoff = ReplyResult.handoff("AI_UNAVAILABLE", "AI 调用失败或超时");
            handoff.setProcessingDurationMs((System.nanoTime() - startedAt) / 1_000_000L);
            return handoff;
        }
    }

    static String appendPolicy(String fixedMaterial, String policy) {
        String material = fixedMaterial == null ? "" : fixedMaterial.trim();
        if (material.isEmpty()) return "【系统回复策略】\n" + policy;
        return material + "\n\n【系统回复策略】\n" + policy;
    }
}
