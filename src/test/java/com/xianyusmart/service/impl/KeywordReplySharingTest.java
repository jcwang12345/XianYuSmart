package com.xianyusmart.service.impl;

import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.entity.XianyuKeywordReplyRule;
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
        when(accountMapper.selectById(anyLong())).thenAnswer(invocation -> {
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
}
