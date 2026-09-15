package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.config.rag.DynamicAIChatClientManager;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.entity.XianyuGoodsConfig;
import com.xianyusmart.entity.bo.KeywordReplyRuleBO;
import com.xianyusmart.mapper.XianyuGoodsConfigMapper;
import com.xianyusmart.service.reply.AutoReplyEscalationPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReplyPolicySimulationServiceTest {
    private JdbcTemplate jdbc;
    private BuyerProfileService buyers;
    private AiHandoffService handoffs;
    private KeywordReplyService keywords;
    private XianyuGoodsConfigMapper configs;
    private GoodsKnowledgeService knowledge;
    private DynamicAIChatClientManager ai;
    private ReplyPolicySimulationService service;

    @BeforeEach
    void setUp(){
        jdbc=mock(JdbcTemplate.class);buyers=mock(BuyerProfileService.class);handoffs=mock(AiHandoffService.class);
        keywords=mock(KeywordReplyService.class);configs=mock(XianyuGoodsConfigMapper.class);
        knowledge=mock(GoodsKnowledgeService.class);ai=mock(DynamicAIChatClientManager.class);
        service=new ReplyPolicySimulationService(jdbc,mock(AccountAccessService.class),buyers,handoffs,
                new AutoReplyEscalationPolicy(),keywords,configs,knowledge,ai,mock(OperationLogService.class),
                new ObjectMapper());
        TenantContext.set(6L);UserContext.set(7L,"policy-tester",6L);
        when(jdbc.queryForObject(contains("FROM xianyu_goods WHERE"),eq(Long.class),any(Object[].class))).thenReturn(1L);
        when(jdbc.queryForList(contains("xianyu_reply_policy_simulation"),any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForMap(contains("xianyu_reply_policy_simulation"),any(Object[].class))).thenReturn(resultRow());
    }

    @AfterEach void clear(){TenantContext.clear();UserContext.clear();}

    @Test
    void blacklistStopsBeforeKeywordKnowledgeAndAi(){
        when(buyers.automationBlockReason(9L,"buyer-1")).thenReturn("黑名单买家");
        service.simulate(command("普通问题","policy-blocked"));
        verify(keywords,never()).matchKeyword(any(),anyString(),anyString());
        verify(knowledge,never()).effective(any(),anyString());
        verify(ai,never()).isAvailable();
    }

    @Test
    void sensitiveQuestionStopsBeforeConfiguredReplyStrategies(){
        service.simulate(command("我要退款并投诉","policy-sensitive"));
        verify(keywords,never()).matchKeyword(any(),anyString(),anyString());
        verify(knowledge,never()).effective(any(),anyString());
        verify(ai,never()).isAvailable();
    }

    @Test
    void keywordWinnerSuppressesKnowledgeAndAi(){
        XianyuGoodsConfig config=new XianyuGoodsConfig();config.setXianyuKeywordReplyOn(1);config.setXianyuAutoReplyOn(1);
        when(configs.selectByAccountAndGoodsId(9L,"goods-1")).thenReturn(config);
        KeywordReplyRuleBO rule=new KeywordReplyRuleBO();rule.setId(88L);rule.setKeyword("安装");rule.setPriority(500);
        KeywordReplyRuleBO.KeywordReplyContentBO content=new KeywordReplyRuleBO.KeywordReplyContentBO();content.setReplyText("请按视频教程安装");
        rule.setContents(List.of(content));when(keywords.matchKeyword(9L,"goods-1","安装方法")).thenReturn(List.of(rule));
        service.simulate(command("安装方法","policy-keyword"));
        verify(knowledge,never()).effective(any(),anyString());
        verify(ai,never()).isAvailable();
    }

    @Test
    void factualQuestionWithoutActiveKnowledgeDoesNotReachBuyer(){
        XianyuGoodsConfig config=new XianyuGoodsConfig();config.setXianyuAutoReplyOn(1);
        when(configs.selectByAccountAndGoodsId(9L,"goods-1")).thenReturn(config);
        when(ai.isAvailable()).thenReturn(true);
        service.simulate(command("有效期是多少天","policy-no-knowledge"));
        verify(knowledge).effective(9L,"goods-1");
        verify(jdbc).update(contains("INSERT INTO xianyu_reply_policy_simulation"),any(Object[].class));
    }

    @Test
    void testConsoleAlwaysReportsNoPlatformWrite(){
        Map<String,Object> result=service.simulate(command("普通问题","policy-safe"));
        assertEquals(false,result.get("testConsoleWouldSend"));
        assertEquals(false,result.get("platformWrite"));
        assertEquals(false,result.get("aiNetworkCalls"));
    }

    private ReplyPolicySimulationService.Command command(String message,String requestId){
        return new ReplyPolicySimulationService.Command(9L,"goods-1","buyer-1","session-1",message,requestId);
    }
    private Map<String,Object> resultRow(){
        Map<String,Object> row=new LinkedHashMap<>();row.put("simulationId",1L);row.put("accountId",9L);
        row.put("goodsId","goods-1");row.put("message","问题");row.put("requestId","request");
        row.put("selectedStrategy","HANDOFF");row.put("safetyVerdict","HANDOFF_REQUIRED");
        row.put("decisionTraceJson","[]");row.put("productionWouldSend",0);row.put("platformWrite",false);
        return row;
    }
}
