package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.event.chatMessageEvent.ChatMessageData;
import com.xianyusmart.event.chatMessageEvent.ChatMessageReceivedEvent;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.service.*;
import com.xianyusmart.service.reply.HumanTakeoverManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@Profile("qa")
@RestController
@RequestMapping("/api/qa/r01")
public class QaR01Controller {
    private final R01QaPlatformService platform;
    private final JdbcTemplate jdbc;
    private final AccountAccessService access;
    private final ApplicationEventPublisher events;
    private final HumanTakeoverManager takeover;
    private final ConversationAssignmentService conversations;
    private final AutoDeliveryService delivery;
    private final DeliveryTaskScheduler discovery;
    private final com.xianyusmart.websocket.handler.LiveSyncMessageParser parser;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    private final PlatformWritePolicy writes;
    private final BackgroundPlatformActivityPolicy activity;
    public QaR01Controller(R01QaPlatformService platform, JdbcTemplate jdbc, AccountAccessService access,
        ApplicationEventPublisher events, HumanTakeoverManager takeover, ConversationAssignmentService conversations,
        AutoDeliveryService delivery, DeliveryTaskScheduler discovery, com.xianyusmart.websocket.handler.LiveSyncMessageParser parser, com.fasterxml.jackson.databind.ObjectMapper json, PlatformWritePolicy writes, BackgroundPlatformActivityPolicy activity) {
        this.platform=platform; this.jdbc=jdbc; this.access=access; this.events=events;
        this.takeover=takeover; this.conversations=conversations; this.delivery=delivery; this.discovery=discovery; this.parser=parser; this.json=json; this.writes=writes; this.activity=activity;
    }
    private static long stableId(String kind,long tenant,Long account,String key) {
        return java.util.UUID.nameUUIDFromBytes((kind+"|"+tenant+"|"+account+"|"+key)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8)).getMostSignificantBits() & Long.MAX_VALUE;
    }
    private void guard(Long account,String key) { platform.require(account,key); access.requireAccess(account); }
    @PostMapping("/fixtures") @Transactional
    public ResultObject<Map<String,Object>> fixtures(@RequestBody Fixture request) {
        guard(request.accountId(),request.key());
        String goods=request.key()+"-goods",buyer=request.key()+"-buyer";
        if(goods.length()>100 || buyer.length()>100) throw new BusinessException(400,"QA key过长");
        platform.register(request.accountId(),request.key(),request.status()==null?"PAID":request.status(),
            request.scenario()==null?"SUCCESS":request.scenario(),goods,buyer);
        long tenant=TenantContext.get();
        jdbc.update("UPDATE xianyu_account SET status=1 WHERE tenant_id=? AND id=?",tenant,request.accountId());
        List<Long> existing=jdbc.queryForList("SELECT id FROM xianyu_goods WHERE tenant_id=? AND xianyu_account_id=? AND xy_good_id=?",Long.class,tenant,request.accountId(),goods);
        if(existing.isEmpty()) {
            Long id=stableId("goods",tenant,request.accountId(),goods);
            jdbc.update("INSERT INTO xianyu_goods(id,tenant_id,xianyu_account_id,xy_good_id,title,sku_count,coverage_status) VALUES(?,?,?,?,'QA R01',0,'PARTIAL') ON DUPLICATE KEY UPDATE id=id",id,tenant,request.accountId(),goods);
        }
        jdbc.update("""
            INSERT INTO xianyu_goods_config(tenant_id,xianyu_account_id,xy_goods_id,xianyu_auto_delivery_on,
                xianyu_keyword_reply_on,human_intervention_on,human_intervention_minutes,xianyu_auto_rate_content)
            VALUES(?,?,?,1,1,1,10,'QA R01') ON DUPLICATE KEY UPDATE id=id
            """,tenant,request.accountId(),goods);
        jdbc.update("""
            INSERT INTO xianyu_fixed_delivery_template(tenant_id,xianyu_account_id,template_name,delivery_content,message_template)
            VALUES(?,?,?,'QA R01 fixed content','{deliveryContent}') ON DUPLICATE KEY UPDATE id=id
            """,tenant,request.accountId(),request.key());
        Long template=jdbc.queryForObject("SELECT id FROM xianyu_fixed_delivery_template WHERE tenant_id=? AND xianyu_account_id=? AND template_name=?",Long.class,tenant,request.accountId(),request.key());
        jdbc.update("INSERT IGNORE INTO xianyu_fixed_delivery_template_account(template_id,tenant_id,xianyu_account_id) VALUES(?,?,?)",template,tenant,request.accountId());
        jdbc.update("""
            INSERT INTO xianyu_goods_auto_delivery_config(tenant_id,xianyu_account_id,xy_goods_id,delivery_mode,
                fixed_template_id,voucher_delivery_enabled,chat_delivery_enabled,rag_delay_seconds,auto_confirm_shipment)
            VALUES(?,?,?,1,?,1,1,1,1) ON DUPLICATE KEY UPDATE id=id
            """,tenant,request.accountId(),goods,template);
        if(Boolean.TRUE.equals(request.cardDelivery())) {
            Long pool=stableId("pool",tenant,request.accountId(),request.key());
            jdbc.update("INSERT INTO xianyu_kami_config(id,tenant_id,xianyu_account_id,alias_name,total_count) VALUES(?,?,?,?,2) ON DUPLICATE KEY UPDATE id=id",pool,tenant,request.accountId(),request.key());
            jdbc.update("INSERT IGNORE INTO xianyu_kami_config_account(kami_config_id,tenant_id,xianyu_account_id) VALUES(?,?,?)",pool,tenant,request.accountId());
            for(int i=1;i<=2;i++) jdbc.update("INSERT IGNORE INTO xianyu_kami_item(tenant_id,kami_config_id,kami_content,sort_order) VALUES(?,?,?,?)",tenant,pool,request.key()+"-CARD-"+i,i);
            // Inventory inputs only; reservation/consumption/outcomes remain real service operations.
            jdbc.update("UPDATE xianyu_goods SET coverage_status='FULL' WHERE tenant_id=? AND xianyu_account_id=? AND xy_good_id=?",tenant,request.accountId(),goods);
            jdbc.update("UPDATE xianyu_goods_auto_delivery_config SET delivery_mode=2,kami_config_ids=? WHERE tenant_id=? AND xianyu_account_id=? AND xy_goods_id=? AND NOT EXISTS(SELECT 1 FROM xianyu_goods_order o WHERE o.tenant_id=? AND o.xianyu_account_id=? AND o.order_id=?)",String.valueOf(pool),tenant,request.accountId(),goods,tenant,request.accountId(),request.key());
        }
        jdbc.update("""
            INSERT INTO xianyu_keyword_reply_rule(tenant_id,xianyu_account_id,xy_goods_id,keyword,is_fallback)
            VALUES(?,?,?,'QA-R01',1) ON DUPLICATE KEY UPDATE id=id
            """,tenant,request.accountId(),goods);
        Long rule=jdbc.queryForObject("SELECT id FROM xianyu_keyword_reply_rule WHERE tenant_id=? AND xianyu_account_id=? AND xy_goods_id=? AND is_fallback=1",Long.class,tenant,request.accountId(),goods);
        jdbc.update("INSERT IGNORE INTO xianyu_keyword_reply_rule_account(rule_id,tenant_id,xianyu_account_id) VALUES(?,?,?)",rule,tenant,request.accountId());
        if(jdbc.queryForObject("SELECT COUNT(*) FROM xianyu_keyword_reply_content WHERE rule_id=?",Long.class,rule)==0)
            jdbc.update("INSERT INTO xianyu_keyword_reply_content(tenant_id,rule_id,reply_text) VALUES(?,?,'QA R01 safe reply')",tenant,rule);
        return ResultObject.success(Map.of("key",request.key(),"accountId",request.accountId(),"goodsId",goods,"buyerId",buyer));
    }
    @PostMapping("/message")
    public ResultObject<Map<String,Object>> message(@RequestBody Message request) {
        guard(request.accountId(),request.key());
        platform.fact(request.accountId(),request.key());
        String pnm=request.messageId(); platform.require(request.accountId(),pnm);
        String session=request.key()+"-buyer@goofish";
        ChatMessageData data=new ChatMessageData(); data.setXianyuAccountId(request.accountId());
        data.setPnmId(pnm); data.setSId(session); data.setXyGoodsId(request.key()+"-goods");
        data.setContentType(1); data.setMsgContent(request.content()==null?"QA-R01 正常咨询":request.content());
        data.setSenderUserId(Boolean.TRUE.equals(request.seller()) ?
            jdbc.queryForObject("SELECT unb FROM xianyu_account WHERE tenant_id=? AND id=?",String.class,TenantContext.get(),request.accountId()) : request.key()+"-buyer");
        data.setSenderUserName(Boolean.TRUE.equals(request.seller()) ? "QA seller" : "QA buyer");
        data.setMessageTime(System.currentTimeMillis()); data.setCompleteMsg("{}");
        try {
            Map<String,Object> extension=Map.of("senderUserId",data.getSenderUserId(),"senderNick",data.getSenderUserName(),
                "reminderContent",data.getMsgContent(),"bizTag",json.writeValueAsString(Map.of("messageId",pnm)),
                "extJson",json.writeValueAsString(Map.of("messageId",pnm,"itemId",data.getXyGoodsId())));
            Map<String,Object> inner=new LinkedHashMap<>(); inner.put("2",session); inner.put("5",data.getMessageTime()); inner.put("10",extension);
            if("OLD".equals(request.protocol())) inner.put("3",pnm);
            String payload="EMPTY".equals(request.protocol()) ? "{}" : json.writeValueAsString(Map.of("1",inner));
            var parsed=parser.parse(request.accountId(),"/s/sync",payload);
            if(!parsed.parsed()) return ResultObject.success(Map.of("ignoredReason",parsed.ignoredReason(),"isolatedPlatformAdapter",true));
            org.springframework.beans.BeanUtils.copyProperties(parsed.message(),data);
        } catch(java.io.IOException e) { throw new BusinessException(400,"QA 协议构造失败"); }
        events.publishEvent(new ChatMessageReceivedEvent(this,data));
        conversations.refresh();
        return ResultObject.success(Map.of("messageId",pnm,"sessionId",session,"isolatedPlatformAdapter",true));
    }
    @PostMapping("/configure")
    public ResultObject<Map<String,Object>> configure(@RequestBody Configuration request) {
        guard(request.accountId(),request.key()); platform.fact(request.accountId(),request.key());
        if(request.keywordEnabled()!=null) jdbc.update("UPDATE xianyu_goods_config SET xianyu_keyword_reply_on=? WHERE tenant_id=? AND xianyu_account_id=? AND xy_goods_id=?",request.keywordEnabled()?1:0,TenantContext.get(),request.accountId(),request.key()+"-goods");
        if(request.replyContentEnabled()!=null) jdbc.update("UPDATE xianyu_keyword_reply_content c JOIN xianyu_keyword_reply_rule r ON r.id=c.rule_id SET c.status=? WHERE r.tenant_id=? AND r.xianyu_account_id=? AND r.xy_goods_id=?",request.replyContentEnabled()?"ACTIVE":"DISABLED",TenantContext.get(),request.accountId(),request.key()+"-goods");
        if(request.deliveryEnabled()!=null) jdbc.update("UPDATE xianyu_goods_config SET xianyu_auto_delivery_on=? WHERE tenant_id=? AND xianyu_account_id=? AND xy_goods_id=?",request.deliveryEnabled()?1:0,TenantContext.get(),request.accountId(),request.key()+"-goods");
        if(request.accountEnabled()!=null) jdbc.update("UPDATE xianyu_account SET status=? WHERE tenant_id=? AND id=?",request.accountEnabled()?1:0,TenantContext.get(),request.accountId());
        if(request.connected()!=null) platform.connection(request.accountId(),request.connected());
        return evidence(request.accountId(),request.key());
    }
    @PostMapping("/expire-takeover")
    public ResultObject<Map<String,Object>> expire(@RequestBody Fixture request) {
        guard(request.accountId(),request.key());
        takeover.expireForQa(request.accountId(),request.key()+"-goods",request.key()+"-buyer@goofish");
        conversations.refresh(); return evidence(request.accountId(),request.key());
    }
    @PostMapping("/discover")
    public ResultObject<Map<String,Object>> discover(@RequestBody Fixture request) {
        guard(request.accountId(),request.key()); discovery.discoverQaOrders();
        // discover clears its per-account TenantContext in finally.
        return withTenant(request.accountId(),request.key());
    }
    @PostMapping("/deliver")
    public ResultObject<Map<String,Object>> deliver(@RequestBody Fixture request) {
        guard(request.accountId(),request.key());
        Long id=jdbc.queryForObject("SELECT id FROM xianyu_goods_order WHERE tenant_id=? AND xianyu_account_id=? AND order_id=?",Long.class,TenantContext.get(),request.accountId(),request.key());
        delivery.executeDelivery(id,request.accountId(),request.key()+"-goods",request.key()+"-buyer@goofish",request.key(),"QA buyer",false);
        return withTenant(request.accountId(),request.key());
    }
    private ResultObject<Map<String,Object>> withTenant(Long account,String key) {
        // The account was already authorised; restore only its persisted tenant after real worker finally blocks.
        Long tenant=jdbc.queryForObject("SELECT tenant_id FROM xianyu_account WHERE id=?",Long.class,account);
        TenantContext.set(tenant); return evidence(account,key);
    }
    @GetMapping("/evidence")
    public ResultObject<Map<String,Object>> evidence(@RequestParam Long accountId,@RequestParam String key) {
        guard(accountId,key); long tenant=TenantContext.get();
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("mockCalls",platform.counts(key)); out.put("messageCalls",platform.counts(key+"-buyer"));
        out.put("discoveryCalls",platform.counts("account-"+accountId)); out.put("isolatedPlatformAdapter",platform.enabled());
        out.put("realPlatformWriteGateEnabled",writes.enabled());
        out.put("backgroundPlatformActivityEnabled",activity.enabled());
        out.put("credentialRows",jdbc.queryForObject("SELECT COUNT(*) FROM xianyu_cookie WHERE tenant_id=?",Long.class,tenant));
        out.put("enabledNotificationChannels",jdbc.queryForObject("SELECT COUNT(*) FROM xianyu_notification_channel WHERE tenant_id=? AND enabled=1",Long.class,tenant));
        out.put("replyConfiguration",jdbc.queryForMap("SELECT xianyu_auto_reply_on,xianyu_keyword_reply_on,human_intervention_on FROM xianyu_goods_config WHERE tenant_id=? AND xianyu_account_id=? AND xy_goods_id=?",tenant,accountId,key+"-goods"));
        out.put("messages",jdbc.queryForList("SELECT id,pnm_id,s_id,sender_user_id,message_time FROM xianyu_chat_message WHERE tenant_id=? AND xianyu_account_id=? AND xy_goods_id=?",tenant,accountId,key+"-goods"));
        out.put("reply",jdbc.queryForList("SELECT id,pnm_id,state,decision_state,handoff_reason_code,last_error_code,external_attempt_started FROM xianyu_goods_auto_reply_record WHERE tenant_id=? AND xianyu_account_id=? AND xy_goods_id=?",tenant,accountId,key+"-goods"));
        out.put("takeover",jdbc.queryForList("SELECT id,s_id,end_time FROM xianyu_human_intervention_record WHERE tenant_id=? AND xianyu_account_id=? AND xy_goods_id=?",tenant,accountId,key+"-goods"));
        out.put("handoffs",jdbc.queryForList("SELECT id,status,reason_code,blocks_auto_reply FROM xianyu_ai_handoff_task WHERE tenant_id=? AND xianyu_account_id=? AND session_id=?",tenant,accountId,key+"-buyer@goofish"));
        out.put("conversation",jdbc.queryForList("SELECT session_id,manual_takeover_state,manual_takeover_until,handoff_status,auto_reply_state FROM conversation_assignment WHERE tenant_id=? AND xianyu_account_id=? AND session_id=?",tenant,accountId,key+"-buyer@goofish"));
        out.put("orders",jdbc.queryForList("SELECT id,order_id,state,delivery_status,expected_quantity,delivered_quantity,confirm_state,attempt_count,external_attempt_started,next_retry_time,last_error_code,fail_reason FROM xianyu_goods_order WHERE tenant_id=? AND xianyu_account_id=? AND order_id=?",tenant,accountId,key));
        out.put("inventory",jdbc.queryForList("SELECT i.id,i.status,i.order_id,i.reserved_account_id FROM xianyu_kami_item i JOIN xianyu_kami_config c ON c.id=i.kami_config_id WHERE c.tenant_id=? AND c.xianyu_account_id=? AND c.alias_name=?",tenant,accountId,key));
        out.put("inventoryEvents",jdbc.queryForList("SELECT event_type,outcome_state,quantity,order_id FROM xianyu_kami_inventory_event WHERE tenant_id=? AND xianyu_account_id=? AND order_id=?",tenant,accountId,key));
        return ResultObject.success(out);
    }
    public record Fixture(Long accountId,String key,String status,String scenario,Boolean cardDelivery) {}
    public record Configuration(Long accountId,String key,Boolean keywordEnabled,Boolean deliveryEnabled,Boolean accountEnabled,Boolean connected,Boolean replyContentEnabled) {}
    public record Message(Long accountId,String key,String messageId,String content,Boolean seller,String protocol) {}
}
