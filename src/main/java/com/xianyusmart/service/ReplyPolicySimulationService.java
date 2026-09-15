package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.config.rag.DynamicAIChatClientManager;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.entity.XianyuGoodsConfig;
import com.xianyusmart.entity.XianyuOperationLog;
import com.xianyusmart.entity.bo.KeywordReplyRuleBO;
import com.xianyusmart.event.chatMessageEvent.ChatMessageData;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.mapper.XianyuGoodsConfigMapper;
import com.xianyusmart.service.reply.AutoReplyEscalationPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** V6-AI-01/03/04/05：只读决策演练。不会调用模型，也不会向闲鱼发送消息。 */
@Service
public class ReplyPolicySimulationService {
    private static final Set<String> FACT_INTENTS = Set.of("PRICE", "VALIDITY", "AFTER_SALES", "INSTALL", "QR");
    private final JdbcTemplate jdbcTemplate;
    private final AccountAccessService accountAccessService;
    private final BuyerProfileService buyerProfileService;
    private final AiHandoffService aiHandoffService;
    private final AutoReplyEscalationPolicy escalationPolicy;
    private final KeywordReplyService keywordReplyService;
    private final XianyuGoodsConfigMapper goodsConfigMapper;
    private final GoodsKnowledgeService goodsKnowledgeService;
    private final DynamicAIChatClientManager aiManager;
    private final OperationLogService operationLogService;
    private final ObjectMapper objectMapper;

    public ReplyPolicySimulationService(JdbcTemplate jdbcTemplate, AccountAccessService accountAccessService,
                                        BuyerProfileService buyerProfileService, AiHandoffService aiHandoffService,
                                        AutoReplyEscalationPolicy escalationPolicy, KeywordReplyService keywordReplyService,
                                        XianyuGoodsConfigMapper goodsConfigMapper, GoodsKnowledgeService goodsKnowledgeService,
                                        DynamicAIChatClientManager aiManager, OperationLogService operationLogService,
                                        ObjectMapper objectMapper) {
        this.jdbcTemplate=jdbcTemplate; this.accountAccessService=accountAccessService;
        this.buyerProfileService=buyerProfileService; this.aiHandoffService=aiHandoffService;
        this.escalationPolicy=escalationPolicy; this.keywordReplyService=keywordReplyService;
        this.goodsConfigMapper=goodsConfigMapper; this.goodsKnowledgeService=goodsKnowledgeService;
        this.aiManager=aiManager; this.operationLogService=operationLogService; this.objectMapper=objectMapper;
    }

    @Transactional
    public Map<String,Object> simulate(Command command) {
        if (command == null) throw new BusinessException(400,"测试参数不能为空");
        Long accountId = command.accountId();
        if (accountId == null || accountId <= 0) throw new BusinessException(400,"账号ID无效");
        accountAccessService.requireAccess(accountId);
        String goodsId=required(command.goodsId(),"商品ID",100);
        String message=required(command.message(),"买家问题",2000);
        String requestId=required(command.requestId(),"requestId",80);
        Long goodsCount=jdbcTemplate.queryForObject("SELECT COUNT(*) FROM xianyu_goods WHERE tenant_id=? AND xianyu_account_id=? AND xy_good_id=?",
                Long.class,tenant(),accountId,goodsId);
        if(goodsCount==null||goodsCount==0) throw new BusinessException(404,"商品不存在或无权访问");
        String payloadHash=hash(json(Map.of("accountId",accountId,"goodsId",goodsId,
                "buyerUserId",safe(command.buyerUserId()),"sessionId",safe(command.sessionId()),"message",message)));
        Map<String,Object> replay=replay(requestId,payloadHash);
        if(replay!=null)return replay;

        List<Map<String,Object>> trace=new ArrayList<>();
        Decision decision=evaluate(command,goodsId,message,trace);
        jdbcTemplate.update("""
                INSERT INTO xianyu_reply_policy_simulation
                    (tenant_id,xianyu_account_id,xy_goods_id,buyer_user_id,session_id,buyer_message,
                     request_id,request_payload_hash,selected_strategy,selected_rule_id,knowledge_version_id,
                     knowledge_version_no,safety_verdict,handoff_reason_code,answer_preview,decision_trace_json,
                     would_send,platform_write,created_by,created_username)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,?)
                """,tenant(),accountId,goodsId,trim(command.buyerUserId()),trim(command.sessionId()),message,
                requestId,payloadHash,decision.strategy(),decision.ruleId(),decision.knowledgeId(),decision.knowledgeNo(),
                decision.safety(),decision.handoffReason(),decision.answer(),json(trace),decision.productionWouldSend()?1:0,
                UserContext.getUserId(),UserContext.getUsername());
        XianyuOperationLog log=new XianyuOperationLog();
        log.setXianyuAccountId(accountId);log.setOperationType("REPLY_POLICY_SIMULATE");log.setOperationModule("自动回复");
        log.setOperationDesc("演练回复策略（未发送）");log.setOperationStatus(1);log.setTargetType("GOODS");log.setTargetId(goodsId);
        log.setRequestId(requestId);log.setIdempotencyKey(requestId);log.setOutcomeState("LOCAL_SUCCESS");log.setDataSource("LOCAL");
        log.setRequestParams(json(Map.of("message",message,"payloadHash",payloadHash)));
        log.setResponseResult(json(Map.of("selectedStrategy",decision.strategy(),"platformWrite",false,
                "productionWouldSend",decision.productionWouldSend())));
        operationLogService.logRequired(log);
        Map<String,Object> result=result(requestId);result.put("idempotentReplay",false);return result;
    }

    private Decision evaluate(Command command,String goodsId,String message,List<Map<String,Object>> trace){
        String blocked=trim(command.buyerUserId())==null?null:buyerProfileService.automationBlockReason(command.accountId(),command.buyerUserId());
        boolean pending=trim(command.sessionId())!=null&&aiHandoffService.hasPending(command.accountId(),command.sessionId());
        if(blocked!=null||pending){
            trace.add(step("BLACKLIST_OR_HANDOFF","BLOCKED",blocked!=null?blocked:"会话已有待处理的人工任务"));
            return handoff("BLACKLIST_OR_HANDOFF","已有人工/黑名单门禁");
        }
        trace.add(step("BLACKLIST_OR_HANDOFF","PASSED","未发现买家自动化拦截或待处理人工任务"));
        ChatMessageData input=new ChatMessageData();input.setXianyuAccountId(command.accountId());input.setXyGoodsId(goodsId);
        input.setSId(command.sessionId());input.setSenderUserId(command.buyerUserId());input.setMsgContent(message);
        AutoReplyEscalationPolicy.Decision escalation=escalationPolicy.evaluate(List.of(input));
        if(escalation!=null){trace.add(step("SENSITIVE_OR_HUMAN","BLOCKED",escalation.reasonDetail()));
            return handoff(escalation.reasonCode(),escalation.reasonDetail());}
        trace.add(step("SENSITIVE_OR_HUMAN","PASSED","未命中敏感问题或人工请求门禁"));

        XianyuGoodsConfig config=goodsConfigMapper.selectByAccountAndGoodsId(command.accountId(),goodsId);
        boolean keywordOn=config!=null&&Integer.valueOf(1).equals(config.getXianyuKeywordReplyOn());
        List<KeywordReplyRuleBO> matches=keywordOn?keywordReplyService.matchKeyword(command.accountId(),goodsId,message):List.of();
        if(!matches.isEmpty()){
            KeywordReplyRuleBO winner=matches.getFirst();
            String answer=winner.getContents()==null||winner.getContents().isEmpty()?null:contentPreview(winner.getContents().getFirst());
            if(answer!=null){
                trace.add(step("KEYWORD","SELECTED","命中最高优先级规则 #"+winner.getId()+"；其余 "+Math.max(0,matches.size()-1)+" 条被压制"));
                return new Decision("KEYWORD",number(winner.getId()),null,null,"SAFE_RULE",null,answer,true);
            }
        }
        trace.add(step("KEYWORD",keywordOn?"NO_MATCH":"DISABLED",keywordOn?"没有有效关键词规则命中":"商品未开启关键词回复"));

        GoodsKnowledgeService.ActiveKnowledge knowledge=goodsKnowledgeService.effective(command.accountId(),goodsId);
        String intent=intent(message);
        if(knowledge!=null){
            trace.add(step("ACTIVE_KNOWLEDGE","AVAILABLE","使用有效知识版本 V"+knowledge.versionNo()));
        }else{
            trace.add(step("ACTIVE_KNOWLEDGE","UNAVAILABLE","没有处于有效期内的 ACTIVE 知识版本"));
        }
        boolean aiOn=config!=null&&Integer.valueOf(1).equals(config.getXianyuAutoReplyOn());
        boolean aiAvailable=aiOn&&aiManager.isAvailable();
        if(FACT_INTENTS.contains(intent)&&knowledge==null){
            trace.add(step("AI","SUPPRESSED","事实型问题缺少有效知识，禁止模型猜测"));
            return handoff("AI_NO_SAFE_ANSWER","事实型问题没有有效知识依据");
        }
        if(aiAvailable){
            String answer=knowledge==null?"需要由模型生成；本测试台默认不调用模型。":knowledgeAnswer(knowledge.content(),intent);
            trace.add(step("AI","WOULD_ATTEMPT","生产链路将调用模型并再次经过发送前安全门禁；本次未调用"));
            return new Decision(knowledge==null?"AI":"AI_WITH_ACTIVE_KNOWLEDGE",null,
                    knowledge==null?null:knowledge.id(),knowledge==null?null:knowledge.versionNo(),
                    knowledge==null?"MODEL_DRY_RUN_REQUIRED":"SAFE_ACTIVE_KNOWLEDGE",null,answer,true);
        }
        trace.add(step("AI",aiOn?"UNAVAILABLE":"DISABLED",aiOn?"AI 配置当前不可用":"商品未开启 AI 回复"));
        return handoff(aiOn?"AI_UNAVAILABLE":"NO_REPLY_STRATEGY",aiOn?"AI 服务不可用":"没有启用可用回复策略");
    }

    private Map<String,Object> replay(String requestId,String payloadHash){
        List<Map<String,Object>> rows=jdbcTemplate.queryForList("SELECT request_payload_hash requestPayloadHash FROM xianyu_reply_policy_simulation WHERE tenant_id=? AND request_id=?",tenant(),requestId);
        if(rows.isEmpty())return null;
        if(!payloadHash.equals(String.valueOf(rows.getFirst().get("requestPayloadHash"))))throw new BusinessException(409,"相同 requestId 已绑定不同的测试问题");
        Map<String,Object> result=result(requestId);result.put("idempotentReplay",true);return result;
    }
    private Map<String,Object> result(String requestId){
        Map<String,Object> result=new LinkedHashMap<>(jdbcTemplate.queryForMap("""
                SELECT id simulationId,xianyu_account_id accountId,xy_goods_id goodsId,buyer_user_id buyerUserId,
                       session_id sessionId,buyer_message message,request_id requestId,selected_strategy selectedStrategy,
                       selected_rule_id selectedRuleId,knowledge_version_id knowledgeVersionId,
                       knowledge_version_no knowledgeVersionNo,safety_verdict safetyVerdict,
                       handoff_reason_code handoffReasonCode,answer_preview answerPreview,
                       decision_trace_json decisionTraceJson,would_send productionWouldSend,
                       platform_write platformWrite,created_username createdUsername,created_time createdTime
                  FROM xianyu_reply_policy_simulation WHERE tenant_id=? AND request_id=?
                """,tenant(),requestId));
        result.put("decisionTrace",readJson((String)result.remove("decisionTraceJson")));
        result.put("testConsoleWouldSend",false);
        result.put("aiNetworkCalls",false);
        result.put("dataNotice","测试台只演练决策并保存证据，不调用 AI、不向买家发送消息。productionWouldSend 仅表示正式链路会继续尝试。" );
        return result;
    }
    private Decision handoff(String reason,String detail){return new Decision("HANDOFF",null,null,null,"HANDOFF_REQUIRED",reason,detail,false);}
    private Map<String,Object> step(String name,String outcome,String reason){return Map.of("step",name,"outcome",outcome,"reason",reason);}
    private String contentPreview(KeywordReplyRuleBO.KeywordReplyContentBO content){String text=trim(content.getReplyText());if(text!=null)return limit(text,600);String image=trim(content.getReplyImageUrl());return image==null?null:"[图片回复] "+image;}
    private String knowledgeAnswer(String content,String intent){
        String[] lines=content.split("[\\r\\n]+");String[] markers=switch(intent){case"PRICE"->new String[]{"价格","售价","优惠","底价"};case"VALIDITY"->new String[]{"有效期","激活"};case"AFTER_SALES"->new String[]{"售后","退款","客服"};case"INSTALL"->new String[]{"安装","教程","office","wps"};case"QR"->new String[]{"二维码","群","扫码"};default->new String[]{};};
        String joined=java.util.Arrays.stream(lines).filter(line->markers.length==0||java.util.Arrays.stream(markers).anyMatch(m->line.toLowerCase(Locale.ROOT).contains(m))).limit(3).collect(java.util.stream.Collectors.joining("\n"));
        return limit(joined.isBlank()?content:joined,600);
    }
    private String intent(String message){String text=message.toLowerCase(Locale.ROOT);if(text.matches("(?s).*(有效期|几天|多久到期|激活).*"))return"VALIDITY";if(text.matches("(?s).*(二维码|扫码|加群|售后群).*"))return"QR";if(text.matches("(?s).*(安装|教程|office|wps|插件).*"))return"INSTALL";if(text.matches("(?s).*(退款|退货|售后|不能用).*"))return"AFTER_SALES";if(text.matches("(?s).*(价格|多少钱|优惠|便宜|砍价).*"))return"PRICE";return"GENERAL";}
    private Object readJson(String value){try{return objectMapper.readValue(value,Object.class);}catch(Exception e){return List.of();}}
    private String json(Object value){try{return objectMapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("无法序列化决策证据",e);}}
    private String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("无法生成测试请求指纹",e);}}
    private Long tenant(){Long value=TenantContext.get();if(value==null)throw new BusinessException(401,"缺少经营主体上下文");return value;}
    private String required(String value,String label,int max){String result=trim(value);if(result==null)throw new BusinessException(400,label+"不能为空");if(result.length()>max)throw new BusinessException(400,label+"不能超过"+max+"个字符");return result;}
    private String trim(String value){return value==null||value.trim().isEmpty()?null:value.trim();}
    private String safe(String value){String result=trim(value);return result==null?"":result;}
    private String limit(String value,int max){return value==null?null:value.substring(0,Math.min(max,value.length()));}
    private Long number(Object value){return value instanceof Number n?n.longValue():Long.valueOf(String.valueOf(value));}

    public record Command(Long accountId,String goodsId,String buyerUserId,String sessionId,String message,String requestId){}
    private record Decision(String strategy,Long ruleId,Long knowledgeId,Integer knowledgeNo,String safety,String handoffReason,String answer,boolean productionWouldSend){}
}
