package com.xianyusmart.service.reply;

import com.xianyusmart.entity.bo.KeywordReplyRuleBO;
import com.xianyusmart.event.chatMessageEvent.ChatMessageData;
import com.xianyusmart.service.KeywordReplyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class KeywordReplyStrategy implements ReplyStrategy {

    private static final int REPLY_TYPE_KEYWORD = 1;

    @Autowired
    private KeywordReplyService keywordReplyService;

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
        if (matchedRules == null || matchedRules.isEmpty()) {
            return ReplyResult.fail();
        }

        KeywordReplyRuleBO winningRule = matchedRules.getFirst();
        List<KeywordReplyRuleBO.KeywordReplyContentBO> allContents = winningRule.getContents() == null
                ? List.of() : winningRule.getContents();

        if (allContents.isEmpty()) {
            return ReplyResult.fail();
        }

        // 规则与内容都按持久化顺序确定，确保同一问题的预演、重试和审计可以复现。
        KeywordReplyRuleBO.KeywordReplyContentBO selected = allContents.getFirst();
        List<ReplyResult.ReplyItem> items = new ArrayList<>();
        String text = selected.getReplyText();
        String image = selected.getReplyImageUrl();
        boolean hasText = text != null && !text.trim().isEmpty();
        boolean hasImage = image != null && !image.trim().isEmpty();
        if (hasText && hasImage) {
            items.add(ReplyResult.ReplyItem.textAndImage(text, image, REPLY_TYPE_KEYWORD));
        } else if (hasText) {
            items.add(ReplyResult.ReplyItem.text(text, REPLY_TYPE_KEYWORD));
        } else if (hasImage) {
            items.add(ReplyResult.ReplyItem.image(image, REPLY_TYPE_KEYWORD));
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
}
