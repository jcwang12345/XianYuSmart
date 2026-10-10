package com.xianyusmart.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.entity.XianyuOperationLog;
import com.xianyusmart.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Transactional local commands. Platform credentials and status never enter these writes. */
@Service
public class AccountLifecycleService {
    private final JdbcTemplate jdbc;
    private final AccountAccessService access;
    private final OperationLogService audits;
    private final ObjectMapper json;
    private final AccountAutomationPolicy automation;
    private final PlatformPermissionService permissions;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<WebSocketService> websocket;
    public AccountLifecycleService(JdbcTemplate jdbc, AccountAccessService access, OperationLogService audits, ObjectMapper json, AccountAutomationPolicy automation, PlatformPermissionService permissions) {
        this.jdbc=jdbc; this.access=access; this.audits=audits; this.json=json; this.automation=automation; this.permissions=permissions;
    }

    public Map<String,Object> read(Long accountId) {
        owned(accountId);
        var rows=jdbc.queryForList("""
                SELECT account.id accountId,account.account_note accountNote,account.status platformStatus,
                       COALESCE(local.row_version,0) rowVersion,COALESCE(local.lifecycle_state,'ACTIVE') lifecycleState,
                       local.operating_tags_json operatingTagsJson,COALESCE(local.auto_reply_allowed,1) autoReplyAllowed,
                       COALESCE(local.auto_delivery_allowed,1) autoDeliveryAllowed,local.archived_from_state archivedFromState
                  FROM xianyu_account account LEFT JOIN xianyu_account_local_profile local
                    ON local.tenant_id=account.tenant_id AND local.xianyu_account_id=account.id
                 WHERE account.tenant_id=? AND account.id=?
                """,tenant(),accountId);
        var result=new LinkedHashMap<>(rows.getFirst());
        result.put("groupIds",jdbc.queryForList("SELECT group_id FROM xianyu_account_group_member WHERE tenant_id=? AND xianyu_account_id=? ORDER BY group_id",Long.class,tenant(),accountId));
        Object tags=result.remove("operatingTagsJson");result.put("operatingTags",tags==null?List.of():parse(String.valueOf(tags),new TypeReference<List<String>>(){}));
        result.put("autoReplyAllowed",enabled(result.get("autoReplyAllowed")));result.put("autoDeliveryAllowed",enabled(result.get("autoDeliveryAllowed")));
        Long userId=com.xianyusmart.context.UserContext.getUserId();
        result.put("canEdit",userId!=null && permissions.hasPermission(userId,PermissionCatalog.ACTION_ACCOUNT_WRITE));
        result.put("canManageLifecycle",result.get("canEdit"));
        result.put("canDelete",isDeleteOwner() && permissions.hasPermission(userId,PermissionCatalog.ACTION_ACCOUNT_DELETE));
        WebSocketService socket=websocket==null?null:websocket.getIfAvailable();
        result.put("runtimeConnection",socket==null?"UNKNOWN":socket.isConnected(accountId)?"CONNECTED":"DISCONNECTED");
        int platformStatus=result.get("platformStatus") instanceof Number number?number.intValue():0;
        result.put("verificationState",platformStatus==-1 || platformStatus==-2?"VERIFICATION_REQUIRED":"UNKNOWN");
        return result;
    }

    @Transactional
    public Map<String,Object> command(JsonNode input, boolean lifecycle) {
        if(input==null||!input.isObject())throw bad("账号命令必须为对象");
        Set<String> allowed=lifecycle?Set.of("accountId","requestId","expectedVersion","action"):
                Set.of("accountId","requestId","expectedVersion","accountNote","groupIds","operatingTags","autoReplyAllowed","autoDeliveryAllowed");
        input.fieldNames().forEachRemaining(key->{if(!allowed.contains(key))throw bad("账号命令含不支持字段");});
        Long id=number(input,"accountId",1);long expected=number(input,"expectedVersion",0);
        String requestId=text(input,"requestId",80,false);owned(id);
        Map<String,Object> payload=new TreeMap<>();payload.put("accountId",id);payload.put("requestId",requestId);payload.put("expectedVersion",expected);
        String type=lifecycle?"LIFECYCLE":"UPDATE";
        if(lifecycle || (input.has("autoReplyAllowed")&&!input.get("autoReplyAllowed").isNull()) || (input.has("autoDeliveryAllowed")&&!input.get("autoDeliveryAllowed").isNull())) automation.lockMutationUntilCompletion(id);
        if(lifecycle){String action=text(input,"action",16,false);if(!Set.of("DISABLE","ENABLE","ARCHIVE","RESTORE").contains(action))throw bad("生命周期动作不支持");payload.put("action",action);}
        else{
            if(input.has("accountNote")&&!input.get("accountNote").isNull())payload.put("accountNote",text(input,"accountNote",100,true));
            for(String field:List.of("autoReplyAllowed","autoDeliveryAllowed"))if(input.has(field)&&!input.get(field).isNull()){
                if(!input.get(field).isBoolean())throw bad("策略开关必须是布尔值");payload.put(field,input.get(field).booleanValue());
            }
            if(input.has("groupIds"))payload.put("groupIds",groups(input.get("groupIds")));
            if(input.has("operatingTags"))payload.put("operatingTags",tags(input.get("operatingTags")));
        }
        String hash=hash(type+write(payload));
        jdbc.update("INSERT INTO xianyu_account_local_command(tenant_id,xianyu_account_id,request_id,command_type,payload_hash,result_json) VALUES(?,?,?,?,?,'') ON DUPLICATE KEY UPDATE id=id",tenant(),id,requestId,type,hash);
        var stored=jdbc.queryForMap("SELECT id,xianyu_account_id accountId,command_type commandType,payload_hash payloadHash,result_json resultJson FROM xianyu_account_local_command WHERE tenant_id=? AND request_id=? FOR UPDATE",tenant(),requestId);
        if(!String.valueOf(stored.get("resultJson")).isBlank()){
            if(!hash.equals(stored.get("payloadHash"))||!type.equals(stored.get("commandType"))||((Number)stored.get("accountId")).longValue()!=id)throw new BusinessException(409,"requestId已用于其他命令或载荷");
            var replay=parse((String)stored.get("resultJson"),new TypeReference<Map<String,Object>>(){});replay.put("replayed",true);return replay;
        }
        jdbc.update("INSERT IGNORE INTO xianyu_account_local_profile(tenant_id,xianyu_account_id) VALUES(?,?)",tenant(),id);
        var locked=jdbc.queryForMap("SELECT row_version rowVersion,lifecycle_state lifecycleState,archived_from_state archivedFromState FROM xianyu_account_local_profile WHERE tenant_id=? AND xianyu_account_id=? FOR UPDATE",tenant(),id);
        long current=((Number)locked.get("rowVersion")).longValue();if(current!=expected)throw new BusinessException(409,"账号版本已变化，请重新加载后保存（当前版本 "+current+"）");
        var before=read(id);var changes=new LinkedHashMap<String,Object>();
        if(lifecycle){
            String state=(String)locked.get("lifecycleState"),action=(String)payload.get("action");
            String next=switch(action){case "DISABLE"->state.equals("ARCHIVED")?state:"DISABLED";case "ENABLE"->state.equals("ARCHIVED")?state:"ACTIVE";case "ARCHIVE"->"ARCHIVED";default->state.equals("ARCHIVED")?(String)locked.get("archivedFromState"):state;};
            if(next==null||!Set.of("ACTIVE","DISABLED","ARCHIVED").contains(next))throw new BusinessException(409,"归档来源状态异常，不能恢复");
            if(state.equals("ARCHIVED")&&!action.equals("RESTORE")&&!action.equals("ARCHIVE"))throw new BusinessException(409,"请先恢复归档账号");
            if(!state.equals(next)){
                if(!next.equals("ACTIVE"))requireNotBusy(id);
                changes.put("lifecycleState",diff(state,next));
                jdbc.update("UPDATE xianyu_account_local_profile SET lifecycle_state=?,archived_from_state=?,archived_at=? WHERE tenant_id=? AND xianyu_account_id=?",next,next.equals("ARCHIVED")?state:null,next.equals("ARCHIVED")?java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()):null,tenant(),id);
                if(!next.equals("ACTIVE"))jdbc.update("UPDATE xianyu_goods_auto_reply_record SET state=-2,lease_owner=NULL,lease_expire_time=NULL WHERE tenant_id=? AND xianyu_account_id=? AND state=0 AND external_attempt_started=0",tenant(),id);
            }
        }else{
            for(var entry:payload.entrySet())if(!Set.of("accountId","requestId","expectedVersion").contains(entry.getKey())&&!Objects.equals(before.get(entry.getKey()),entry.getValue()))changes.put(entry.getKey(),diff(before.get(entry.getKey()),entry.getValue()));
            if(changes.containsKey("accountNote"))jdbc.update("UPDATE xianyu_account SET account_note=? WHERE tenant_id=? AND id=?",payload.get("accountNote"),tenant(),id);
            if(changes.containsKey("groupIds")){
                jdbc.update("DELETE FROM xianyu_account_group_member WHERE tenant_id=? AND xianyu_account_id=?",tenant(),id);
                for(Long group:(List<Long>)payload.get("groupIds"))jdbc.update("INSERT INTO xianyu_account_group_member(tenant_id,group_id,xianyu_account_id) VALUES(?,?,?)",tenant(),group,id);
            }
            for(String field:List.of("operatingTags","autoReplyAllowed","autoDeliveryAllowed"))if(changes.containsKey(field)){
                String column=switch(field){case "operatingTags"->"operating_tags_json";case "autoReplyAllowed"->"auto_reply_allowed";default->"auto_delivery_allowed";};
                Object value=field.equals("operatingTags")?write(payload.get(field)):Boolean.TRUE.equals(payload.get(field))?1:0;
                jdbc.update("UPDATE xianyu_account_local_profile SET "+column+"=? WHERE tenant_id=? AND xianyu_account_id=?",value,tenant(),id);
            }
        }
        if(!changes.isEmpty())jdbc.update("UPDATE xianyu_account_local_profile SET row_version=row_version+1 WHERE tenant_id=? AND xianyu_account_id=?",tenant(),id);
        var result=new LinkedHashMap<String,Object>();result.put("commandId",stored.get("id"));result.put("requestId",requestId);result.put("replayed",false);result.put("rowVersion",current+(changes.isEmpty()?0:1));result.put("changedFields",new ArrayList<>(changes.keySet()));result.put("localResult",changes.isEmpty()?"NO_CHANGE":"APPLIED");result.put("profile",read(id));
        if(lifecycle)result.put("connectionOutcome",changes.isEmpty()?"NO_CHANGE":((Map<?,?>)result.get("profile")).get("lifecycleState").equals("ACTIVE")?"CONNECTION_CHECK_PENDING":"STOP_PENDING");
        if(!changes.isEmpty()){
            XianyuOperationLog audit=new XianyuOperationLog();audit.setXianyuAccountId(id);audit.setOperationType("ACCOUNT_LOCAL_"+type);audit.setOperationModule("ACCOUNT");audit.setOperationStatus(1);audit.setTargetType("ACCOUNT");audit.setTargetId(String.valueOf(id));audit.setRequestId(requestId);audit.setOutcomeState("APPLIED");audit.setDataSource("LOCAL_COMMAND");audit.setFieldDiffJson(write(changes));audits.logRequired(audit);
        }
        jdbc.update("UPDATE xianyu_account_local_command SET result_json=? WHERE tenant_id=? AND request_id=?",write(result),tenant(),requestId);return result;
    }
    public Map<String,Object> deletePreflight(Long accountId) {
        requireDeleteOwner();owned(accountId);
        var dependencies=new ArrayList<Map<String,Object>>();
        Set<String> runtime=Set.of("xianyu_cookie","xianyu_device_profile","xianyu_account_local_profile",
                "xianyu_account_local_command","xianyu_account_capability","xianyu_account_group_member","sys_user_account_scope");
        // Server schema metadata, never request-provided SQL or identifiers. Unknown business
        // tables are conservative blockers rather than silently omitted from deletion checks.
        var tables=jdbc.queryForList("SELECT TABLE_NAME tableName FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND COLUMN_NAME='xianyu_account_id' ORDER BY TABLE_NAME");
        for(var table:tables) {
            String name=(String)table.get("tableName");
            if(runtime.contains(name))continue;
            if(!name.matches("[a-z][a-z0-9_]*"))throw new BusinessException(503,"账号依赖目录暂不可用");
            long count=jdbc.queryForObject("SELECT COUNT(*) FROM `"+name+"` WHERE xianyu_account_id=?",Long.class,accountId);
            if(count>0){
                String category=name.contains("order")?"订单或履约历史":name.contains("chat")||name.contains("reply")?"会话或回复历史":name.contains("goods")?"商品或配置":name.contains("operation_log")?"业务审计":name.contains("notify")||name.contains("notification")?"通知记录":"关联业务记录";
                String path=name.contains("order")?"/orders":name.contains("chat")||name.contains("reply")?"/messages":name.contains("goods")?"/goods":"/operation-log";
                dependencies.add(Map.of("dependency",name,"label",category,"count",count,"path",path+"?accountId="+accountId));
            }
        }
        var account=jdbc.queryForMap("SELECT account_note,unb FROM xianyu_account WHERE tenant_id=? AND id=?",tenant(),accountId);
        String note=account.get("account_note")==null?"":String.valueOf(account.get("account_note")).trim();
        var profile=read(accountId);
        return Map.of("accountId",accountId,"canDelete",dependencies.isEmpty(),"dependencies",dependencies,
                "rowVersion",profile.get("rowVersion"),"confirmationLabel",note.isEmpty()?String.valueOf(account.get("unb")):note,
                "recommendation",dependencies.isEmpty()?"CONFIRM_EMPTY_ACCOUNT":"ARCHIVE_AND_PRESERVE_HISTORY","archivePath","/accounts?accountId="+accountId);
    }

    @Transactional
    public Map<String,Object> delete(JsonNode input) {
        requireDeleteOwner();
        if(input==null||!input.isObject())throw bad("删除命令必须为对象");
        Set<String> allowed=Set.of("accountId","requestId","expectedVersion","confirmationText","confirmationNote");
        input.fieldNames().forEachRemaining(key->{if(!allowed.contains(key))throw bad("删除命令含不支持字段");});
        Long id=number(input,"accountId",1);long expected=number(input,"expectedVersion",0);
        String requestId=text(input,"requestId",80,false),confirmation=text(input,"confirmationText",30,false),note=text(input,"confirmationNote",100,false);
        if(!String.valueOf(id).equals(confirmation))throw bad("请输入当前账号ID以确认");
        access.requireAccess(id);automation.lockMutationUntilCompletion(id);
        Map<String,Object> payload=new TreeMap<>();payload.put("accountId",id);payload.put("requestId",requestId);payload.put("expectedVersion",expected);payload.put("confirmationText",confirmation);payload.put("confirmationNote",note);
        String fingerprint=hash("DELETE"+write(payload));
        jdbc.update("INSERT INTO xianyu_account_local_command(tenant_id,xianyu_account_id,request_id,command_type,payload_hash,result_json) VALUES(?,?,?,'DELETE',?,'') ON DUPLICATE KEY UPDATE id=id",tenant(),id,requestId,fingerprint);
        var stored=jdbc.queryForMap("SELECT id,xianyu_account_id accountId,command_type commandType,payload_hash payloadHash,result_json resultJson FROM xianyu_account_local_command WHERE tenant_id=? AND request_id=? FOR UPDATE",tenant(),requestId);
        if(!String.valueOf(stored.get("resultJson")).isBlank()){
            if(!fingerprint.equals(stored.get("payloadHash"))||!"DELETE".equals(stored.get("commandType"))||((Number)stored.get("accountId")).longValue()!=id)throw new BusinessException(409,"requestId已用于其他命令或载荷");
            var result=parse((String)stored.get("resultJson"),new TypeReference<Map<String,Object>>(){});result.put("replayed",true);return result;
        }
        var accounts=jdbc.queryForList("SELECT id,account_note accountNote,unb FROM xianyu_account WHERE tenant_id=? AND id=? FOR UPDATE",tenant(),id);
        if(accounts.isEmpty())throw new BusinessException(404,"账号不存在");
        var account=accounts.getFirst();String currentNote=account.get("accountNote")==null?"":String.valueOf(account.get("accountNote")).trim();
        String display=currentNote.isEmpty()?String.valueOf(account.get("unb")):currentNote;
        if(!note.equals(display))throw bad("确认备注与当前展示名称不一致，请重新加载");
        jdbc.update("INSERT IGNORE INTO xianyu_account_local_profile(tenant_id,xianyu_account_id) VALUES(?,?)",tenant(),id);
        long version=jdbc.queryForObject("SELECT row_version FROM xianyu_account_local_profile WHERE tenant_id=? AND xianyu_account_id=? FOR UPDATE",Long.class,tenant(),id);
        if(version!=expected)throw new BusinessException(409,"账号版本已变化，请重新加载");
        var preflight=deletePreflight(id);if(!Boolean.TRUE.equals(preflight.get("canDelete")))throw new BusinessException(409,"账号仍有业务历史或关联资源，请归档并保留历史");
        XianyuOperationLog audit=new XianyuOperationLog();audit.setXianyuAccountId(id);audit.setOperationType("ACCOUNT_LOCAL_DELETE");audit.setOperationModule("ACCOUNT");audit.setOperationStatus(1);audit.setTargetType("ACCOUNT");audit.setTargetId(String.valueOf(id));audit.setRequestId(requestId);audit.setOutcomeState("DELETED");audit.setDataSource("LOCAL_COMMAND");audits.logRequired(audit);
        jdbc.update("DELETE FROM xianyu_account_group_member WHERE tenant_id=? AND xianyu_account_id=?",tenant(),id);
        jdbc.update("DELETE FROM xianyu_account_local_profile WHERE tenant_id=? AND xianyu_account_id=?",tenant(),id);
        if(jdbc.update("DELETE FROM xianyu_account WHERE tenant_id=? AND id=?",tenant(),id)!=1)throw new BusinessException(409,"账号删除失败，事务已回滚");
        var result=new LinkedHashMap<String,Object>();result.put("commandId",stored.get("id"));result.put("requestId",requestId);result.put("accountId",id);result.put("replayed",false);result.put("localResult","DELETED");result.put("connectionOutcome","STOP_PENDING");
        jdbc.update("UPDATE xianyu_account_local_command SET result_json=? WHERE tenant_id=? AND request_id=?",write(result),tenant(),requestId);return result;
    }

    private void requireDeleteOwner() {
        Long userId=com.xianyusmart.context.UserContext.getUserId();
        if(userId==null||!permissions.hasPermission(userId,PermissionCatalog.ACTION_ACCOUNT_DELETE))throw new BusinessException(403,"没有永久删除账号的权限");
        if(!isDeleteOwner())throw new BusinessException(403,"永久删除仅限当前经营主体所有者或平台管理员");
    }

    private boolean isDeleteOwner() {
        Long userId=com.xianyusmart.context.UserContext.getUserId();
        return userId!=null && jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=? AND status=1 AND (role='ADMIN' OR (tenant_id=? AND member_role='OWNER'))",Integer.class,userId,tenant())==1;
    }

    public void recordConnectionOutcome(Map<String,Object> result,String outcome) {
        if(!Set.of("STOPPED","STOP_PENDING","STOP_FAILED","CONNECTED","VERIFICATION_REQUIRED","DISCONNECTED").contains(outcome))
            throw new IllegalArgumentException("Unsupported connection outcome");
        result.put("connectionOutcome",outcome);
        jdbc.update("UPDATE xianyu_account_local_command SET result_json=? WHERE tenant_id=? AND id=? AND request_id=?",
                write(result),tenant(),result.get("commandId"),result.get("requestId"));
    }

    private void requireNotBusy(Long id){
        int count=jdbc.queryForObject("SELECT COUNT(*) FROM xianyu_goods_order WHERE tenant_id=? AND xianyu_account_id=? AND (delivery_status='PROCESSING' OR (external_attempt_started=1 AND (delivery_status IS NULL OR delivery_status NOT IN ('COMPLETED','SKIPPED','FAILED'))))",Integer.class,tenant(),id);
        count+=jdbc.queryForObject("SELECT COUNT(*) FROM xianyu_goods_auto_reply_record WHERE tenant_id=? AND xianyu_account_id=? AND state=2",Integer.class,tenant(),id);
        if(count>0)throw new BusinessException(409,"账号存在进行中或结果待核对的外发任务，请先处理后再停用或归档");
    }
    private List<Long> groups(JsonNode value){
        if(value==null||!value.isArray()||value.size()>30)throw bad("分组必须为最多30项的数组");var ids=new TreeSet<Long>();for(JsonNode node:value){if(!node.isIntegralNumber()||!node.canConvertToLong()||node.longValue()<1)throw bad("分组ID无效");ids.add(node.longValue());}
        for(Long id:ids)if(jdbc.queryForObject("SELECT COUNT(*) FROM xianyu_account_group WHERE tenant_id=? AND id=?",Integer.class,tenant(),id)!=1)throw bad("分组不属于当前经营主体");return new ArrayList<>(ids);
    }
    private List<String> tags(JsonNode value){
        if(value==null||!value.isArray()||value.size()>20)throw bad("标签必须为最多20项的数组");var tags=new TreeSet<String>();for(JsonNode tag:value){if(!tag.isTextual()||tag.textValue().trim().isEmpty()||tag.textValue().trim().length()>30)throw bad("标签长度须为1至30字符");tags.add(tag.textValue().trim());}var result=new ArrayList<>(tags);if(write(result).length()>4096)throw bad("标签内容过长");return result;
    }
    private void owned(Long id){access.requireAccess(id);if(id==null||jdbc.queryForObject("SELECT COUNT(*) FROM xianyu_account WHERE tenant_id=? AND id=?",Integer.class,tenant(),id)!=1)throw new BusinessException(404,"账号不存在");}
    private Long tenant(){Long id=TenantContext.get();if(id==null)throw new BusinessException(403,"经营主体上下文缺失");return id;}
    private long number(JsonNode input,String key,long minimum){JsonNode node=input.get(key);if(node==null||!node.isIntegralNumber()||!node.canConvertToLong()||node.longValue()<minimum)throw bad(key+"无效或缺失");return node.longValue();}
    private String text(JsonNode input,String key,int max,boolean empty){JsonNode node=input.get(key);if(node==null||!node.isTextual())throw bad(key+"必须为文本");String value=node.textValue().trim();if((!empty&&value.isEmpty())||value.length()>max)throw bad(key+"长度无效");return value;}
    private boolean enabled(Object value){return value instanceof Number n&&n.intValue()==1;}
    private Map<String,Object> diff(Object before,Object after){var result=new LinkedHashMap<String,Object>();result.put("before",before);result.put("after",after);return result;}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("本地命令序列化失败",e);}}
    private <T>T parse(String value,TypeReference<T> type){try{return json.readValue(value,type);}catch(Exception e){throw new IllegalStateException("本地命令结果损坏",e);}}
    private String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private BusinessException bad(String message){return new BusinessException(400,message);}
}
