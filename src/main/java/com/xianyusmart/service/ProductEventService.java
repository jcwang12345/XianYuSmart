package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.entity.SysUser;
import com.xianyusmart.entity.XianyuOperationLog;
import com.xianyusmart.mapper.SysUserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 统一写入商品时间线并同步商品记录的来源证据。 */
@Service
public class ProductEventService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private OperationLogService operationLogs;
    private AccountAccessService access;
    private SysUserMapper users;
    private PlatformPermissionService permissions;
    private ProductEventSafety safety;
    private Environment environment;

    public ProductEventService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Autowired
    public ProductEventService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, OperationLogService operationLogs,
                               AccountAccessService access, SysUserMapper users, PlatformPermissionService permissions,
                               ProductEventSafety safety, Environment environment) {
        this(jdbcTemplate, objectMapper);
        this.operationLogs=operationLogs; this.access=access; this.users=users; this.permissions=permissions;
        this.safety=safety; this.environment=environment;
    }

    /** Read current identity, permissions and scope; a historical admin flag is not a tenant grant. */
    static SysUser requireManager(SysUserMapper users, PlatformPermissionService permissions,
                                  AccountAccessService access, Long accountId, String action) {
        Long tenant=TenantContext.get(), operator=UserContext.getUserId();
        if (tenant==null || operator==null || !Objects.equals(UserContext.getTenantId(),tenant) || users==null || permissions==null)
            throw new BusinessException(403,"当前租户管理权限不足");
        SysUser user=users.selectById(operator);
        if (user==null || !Objects.equals(user.getId(),operator) || !Integer.valueOf(1).equals(user.getStatus())
                || !Objects.equals(user.getTenantId(),tenant)
                || !(SysUser.ROLE_ADMIN.equalsIgnoreCase(user.getRole()) || "OWNER".equals(user.getMemberRole()) || "TENANT_ADMIN".equals(user.getMemberRole()))
                || !permissions.hasPermission(user,PermissionCatalog.MENU_GOODS) || !permissions.hasPermission(user,action))
            throw new BusinessException(403,"当前租户管理权限不足");
        access.requireAccess(accountId);
        AccountAccessService.Scope current=access.loadScope(user);
        if(current==null || (!current.unrestricted() && !current.accountIds().contains(accountId)))
            throw new BusinessException(403,"当前账号范围不允许此操作");
        return user;
    }

    @Transactional
    public Map<String,Object> observeQaProduct(Long accountId,String goodsId,String eventId,String occurredAt,
                                               Map<String,Object> before,Map<String,Object> after) {
        if(environment==null || !List.of(environment.getActiveProfiles()).contains("qa")
                || !environment.getProperty("app.r03.qa.enabled",Boolean.class,false))
            throw new BusinessException(403,"QA商品事件入口未启用");
        if(!Objects.equals(environment.getProperty("app.r03.qa.tenant-id",Long.class),tenant())
                || accountId==null || java.util.Arrays.stream(environment.getProperty("app.r03.qa.account-ids","").split(","))
                        .map(String::trim).noneMatch(String.valueOf(accountId)::equals))
            throw new BusinessException(403,"QA商品事件不在隔离白名单");
        SysUser actor=requireManager(users,permissions,access,accountId,PermissionCatalog.ACTION_SYSTEM_WRITE);
        if(eventId==null || !eventId.matches("(?i)QA-R03-[A-Za-z0-9_-]{1,65}") || goodsId==null
                || !goodsId.matches("QA-R03-[A-Za-z0-9_-]{1,90}")) throw new BusinessException(400,"QA商品事件标识无效");
        Instant observed;try { observed=Instant.parse(occurredAt); } catch(RuntimeException invalid) { throw new BusinessException(400,"商品观察时间无效"); }
        long tenant=tenant();
        var account=jdbcTemplate.queryForList("SELECT account_note,unb FROM xianyu_account WHERE tenant_id=? AND id=? FOR UPDATE",tenant,accountId);
        if(account.size()!=1 || !String.valueOf(account.getFirst().get("account_note")).startsWith("QA-R03-")
                || !String.valueOf(account.getFirst().get("unb")).startsWith("QA-R03-")
                || jdbcTemplate.queryForObject("SELECT COUNT(*) FROM xianyu_cookie WHERE xianyu_account_id=?",Integer.class,accountId)!=0)
            throw new BusinessException(403,"QA商品事件对象保护失败");
        if(jdbcTemplate.queryForList("SELECT id FROM xianyu_goods WHERE tenant_id=? AND xianyu_account_id=? AND xy_good_id=? FOR UPDATE",tenant,accountId,goodsId).size()!=1)
            throw new BusinessException(404,"QA商品不属于当前账号");
        // Authority is re-read inside the database transaction before the first persistent mutation.
        actor=requireManager(users,permissions,access,accountId,PermissionCatalog.ACTION_SYSTEM_WRITE);
        var old=safety.snapshot(before,true,false);var next=safety.snapshot(after,true,false);
        var diff=safety.diff(old.content(),next.content());diff.put("occurredAt",observed.toString());
        var redacted=new java.util.TreeSet<>(old.redactedFields());redacted.addAll(next.redactedFields());
        diff.put("redactedFields",List.copyOf(redacted));diff.put("contentOmitted",old.contentOmitted()||next.contentOmitted());
        Map<String,Object> content=new LinkedHashMap<>();content.put("before",old.content());content.put("after",next.content());content.put("fieldDiff",diff);
        String hash=safety.hash(content);
        var existing=jdbcTemplate.queryForList("""
                SELECT id,xy_goods_id,raw_snapshot_hash FROM xianyu_goods_event
                 WHERE tenant_id=? AND xianyu_account_id=? AND event_type='QA_PRODUCT_OBSERVED' AND request_id=? FOR UPDATE
                """,tenant,accountId,eventId);
        if(!existing.isEmpty()) {
            var row=existing.getFirst();
            if(existing.size()!=1 || !goodsId.equals(row.get("xy_goods_id")) || !hash.equals(row.get("raw_snapshot_hash")))
                throw new BusinessException(409,"事件ID已用于不同的安全载荷或商品目标");
            return qaProjection(((Number)row.get("id")).longValue(),eventId,hash,true);
        }
        jdbcTemplate.update("""
                INSERT INTO xianyu_goods_event(tenant_id,xianyu_account_id,xy_goods_id,event_type,event_origin,outcome_state,
                 data_source,operator_user_id,operator_username,request_id,idempotency_key,before_json,after_json,field_diff_json,raw_snapshot_hash)
                VALUES(?,?,?,'QA_PRODUCT_OBSERVED','QA','LOCAL_SUCCESS','QA_WEBHOOK',?,?,?,?,?,?,?,?)
                """,tenant,accountId,goodsId,actor.getId(),safety.safeText(actor.getUsername()),eventId,eventId,
                safety.canonical(old.content()),safety.canonical(next.content()),safety.canonical(diff),hash);
        Long row=jdbcTemplate.queryForObject("SELECT id FROM xianyu_goods_event WHERE tenant_id=? AND xianyu_account_id=? AND event_type='QA_PRODUCT_OBSERVED' AND request_id=?",Long.class,tenant,accountId,eventId);
        var log=new XianyuOperationLog();log.setXianyuAccountId(accountId);
        log.setOperatorUserId(actor.getId());log.setOperatorUsername(safety.safeText(actor.getUsername()));
        log.setOperationType("QA_PRODUCT_OBSERVED");log.setOperationModule("PRODUCT");log.setOperationDesc("隔离商品观察事件");
        log.setOperationStatus(1);log.setRequestId(eventId);log.setTargetType("PRODUCT");log.setTargetId(goodsId);
        log.setIdempotencyKey(eventId);log.setOutcomeState("LOCAL_SUCCESS");log.setDataSource("QA_WEBHOOK");
        log.setRequestParams(safety.canonical(Map.of("eventId",eventId,"snapshotHash",hash,"source","QA_WEBHOOK")));
        log.setResponseResult(safety.canonical(Map.of("eventRowId",row,"replayed",false)));operationLogs.logRequired(log);
        return qaProjection(row,eventId,hash,false);
    }

    private Map<String,Object> qaProjection(long row,String eventId,String hash,boolean replayed) {
        return Map.of("eventRowId",row,"eventId",eventId,"snapshotHash",hash,"snapshotHashBasis","SANITIZED_CONTENT",
                "dataSource","QA_WEBHOOK","platformVerified",false,"replayed",replayed);
    }

    public void publishCompleted(Long accountId, String goodsId, String requestId,
                                 String publishChannel, String outcomeState, Object result) {
        publishCompleted(accountId, goodsId, requestId, publishChannel, outcomeState, result, "PLATFORM_WEB");
    }

    public void publishCompleted(Long accountId, String goodsId, String requestId,
                                 String publishChannel, String outcomeState, Object result, String dataSource) {
        Long tenantId = tenant();
        Map<?, ?> resultMap = result instanceof Map<?, ?> map ? map : Map.of();
        Object fieldDifferences = resultMap.get("fieldDifferences");
        String verificationStatus = resultMap.get("verificationStatus") == null
                ? "VERIFIED" : String.valueOf(resultMap.get("verificationStatus"));
        boolean localSynced = !Boolean.FALSE.equals(resultMap.get("localSynced"));
        String syncStatus = "VERIFIED".equals(verificationStatus) && localSynced ? "SUCCEEDED" : "PARTIAL";
        jdbcTemplate.update("""
                INSERT INTO xianyu_goods_event
                (tenant_id, xianyu_account_id, xy_goods_id, event_type, event_origin, outcome_state,
                 data_source, operator_user_id, operator_username, request_id, idempotency_key,
                 after_json, field_diff_json)
                VALUES (?,?,?,'PUBLISH','USER',?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE outcome_state=VALUES(outcome_state), after_json=VALUES(after_json),
                                        field_diff_json=VALUES(field_diff_json)
                """, tenantId, accountId, goodsId, outcomeState, dataSource,
                UserContext.getUserId(), UserContext.getUsername(),
                requestId, requestId, json(result), fieldDifferences == null ? null : json(fieldDifferences));
        jdbcTemplate.update("""
                UPDATE xianyu_goods
                   SET product_source='SYSTEM_PUBLISH', publish_channel=?, last_sync_request_id=?,
                       sync_status=?, coverage_status='PARTIAL', last_synced_time=NOW(3)
                 WHERE tenant_id=? AND xianyu_account_id=? AND xy_good_id=?
                """, publishChannel, requestId, syncStatus, tenantId, accountId, goodsId);
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { throw new BusinessException(500, "商品事件序列化失败", e); }
    }

    private Long tenant() {
        Long tenantId = TenantContext.get();
        if (tenantId == null) throw new BusinessException(401, "缺少经营主体上下文");
        return tenantId;
    }
}
