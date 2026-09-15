package com.xianyusmart.service.impl;

import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.entity.XianyuKeywordReplyRule;
import com.xianyusmart.entity.XianyuKeywordReplyContent;
import com.xianyusmart.mapper.SharedAccountLinkMapper;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.mapper.XianyuGoodsConfigMapper;
import com.xianyusmart.mapper.XianyuKeywordReplyContentMapper;
import com.xianyusmart.mapper.XianyuKeywordReplyRuleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KeywordReplySharingTest {

    @Mock XianyuKeywordReplyRuleMapper ruleMapper;
    @Mock XianyuKeywordReplyContentMapper contentMapper;
    @Mock XianyuGoodsConfigMapper goodsConfigMapper;
    @Mock SharedAccountLinkMapper sharedAccountLinkMapper;
    @Mock XianyuAccountMapper accountMapper;
    @InjectMocks KeywordReplyServiceImpl service;

    @BeforeEach
    void accounts() {
        lenient().when(accountMapper.selectById(anyLong())).thenAnswer(invocation -> {
            XianyuAccount account = new XianyuAccount();
            account.setId(invocation.getArgument(0));
            account.setTenantId(5L);
            return account;
        });
    }

    @Test
    void changingRuleToMultipleAccountsMakesItAccountScoped() {
        XianyuKeywordReplyRule rule = new XianyuKeywordReplyRule();
        rule.setId(6L);
        rule.setTenantId(5L);
        rule.setXianyuAccountId(1L);
        rule.setXyGoodsId("goods-a");
        rule.setSharingScope("GOODS");
        when(ruleMapper.selectById(6L)).thenReturn(rule);

        service.updateAccounts(6L, List.of(1L, 2L));

        assertEquals("ACCOUNT", rule.getSharingScope());
        verify(ruleMapper).updateById(rule);
        verify(sharedAccountLinkMapper).deleteKeywordRuleAccounts(6L);
        verify(sharedAccountLinkMapper).insertKeywordRuleAccounts(6L, 5L, List.of(1L, 2L));
    }

    @Test
    void matchingOrdersByPriorityThenSpecificityAndSupportsRegex() {
        XianyuKeywordReplyRule contains = rule(1L, "安装", "CONTAINS", 100);
        XianyuKeywordReplyRule exact = rule(2L, "怎么安装", "EXACT", 100);
        XianyuKeywordReplyRule regex = rule(3L, "安装.*教程", "REGEX", 200);
        when(ruleMapper.selectEffective(1L, "goods-a")).thenReturn(List.of(contains, exact, regex));
        when(contentMapper.selectEffectiveByRuleIds(List.of(3L, 1L))).thenReturn(List.of(
                content(3L, "正则回复"), content(1L, "包含回复")));
        when(sharedAccountLinkMapper.selectKeywordRuleAccounts(anyLong())).thenReturn(List.of(1L));

        var matches = service.matchKeyword(1L, "goods-a", "怎么安装教程");

        assertEquals(List.of(3L, 1L), matches.stream().map(v -> Long.valueOf(v.getId().toString())).toList());
        assertEquals("REGEX", matches.getFirst().getMatchType());
    }

    @Test
    void invalidRegexIsIgnoredInsteadOfBreakingReplyPipeline() {
        XianyuKeywordReplyRule broken = rule(4L, "[未闭合", "REGEX", 999);
        when(ruleMapper.selectEffective(1L, "goods-a")).thenReturn(List.of(broken));

        var matches = service.matchKeyword(1L, "goods-a", "任意消息");

        assertEquals(0, matches.size());
        verify(contentMapper, never()).selectEffectiveByRuleIds(any());
    }

    private XianyuKeywordReplyRule rule(Long id, String keyword, String type, int priority) {
        XianyuKeywordReplyRule rule = new XianyuKeywordReplyRule();
        rule.setId(id); rule.setTenantId(5L); rule.setXianyuAccountId(1L); rule.setXyGoodsId("goods-a");
        rule.setSharingScope("GOODS"); rule.setKeyword(keyword); rule.setMatchType(type);
        rule.setMatchMode("EXACT".equals(type) ? 2 : "REGEX".equals(type) ? 3 : 1);
        rule.setPriority(priority); rule.setEnabled(1); rule.setVersionNo(1); rule.setIsFallback(0);
        return rule;
    }

    private XianyuKeywordReplyContent content(Long ruleId, String text) {
        XianyuKeywordReplyContent content = new XianyuKeywordReplyContent();
        content.setId(ruleId * 10); content.setRuleId(ruleId); content.setReplyText(text);
        content.setStatus("ACTIVE"); content.setVersionNo(1);
        return content;
    }
}
