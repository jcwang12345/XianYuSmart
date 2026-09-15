package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.entity.XianyuOperationLog;
import com.xianyusmart.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** V6-AI-06 关键词规则的完整版本保存；旧的零散接口保留兼容但新页面只走此入口。 */
@Service
public class KeywordRuleVersionService {
    private static final Set<String> MATCH_TYPES = Set.of("EXACT", "CONTAINS", "REGEX");
    private final JdbcTemplate jdbcTemplate;
    private final AccountAccessService accountAccessService;
    private final OperationLogService operationLogService;
    private final ObjectMapper objectMapper;

    public KeywordRuleVersionService(JdbcTemplate jdbcTemplate,
                                     AccountAccessService accountAccessService,
                                     OperationLogService operationLogService,
                                     ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.accountAccessService = accountAccessService;
        this.operationLogService = operationLogService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String,Object> save(Long ruleId, SaveCommand command) {
        if (command == null) throw new BusinessException(400, "规则配置不能为空");
        String requestId = required(command.requestId(), "requestId", 80);
        String keyword = required(command.keyword(), "关键词", 200);
        String matchType = required(command.matchType(), "匹配方式", 16).toUpperCase(Locale.ROOT);
        if (!MATCH_TYPES.contains(matchType)) throw new BusinessException(400, "匹配方式无效");
        if ("REGEX".equals(matchType)) validateRegex(keyword);
        int priority = command.priority() == null ? 100 : command.priority();
        if (priority < -1000 || priority > 1000) throw new BusinessException(400, "优先级必须在 -1000 到 1000 之间");
        boolean enabled = command.enabled() == null || command.enabled();
        LocalDateTime effectiveTime = command.effectiveTime() == null ? LocalDateTime.now() : command.effectiveTime();
        LocalDateTime expiresTime = command.expiresTime();
        if (expiresTime != null && !expiresTime.isAfter(effectiveTime)) throw new BusinessException(400, "失效时间必须晚于生效时间");
        List<ContentCommand> contents = normalizeContents(command.contents(), enabled);
        List<Long> accountIds = normalizeAccounts(command.accountIds());
        String payloadHash = payloadHash(ruleId, keyword, matchType, priority, enabled, effectiveTime,
                expiresTime, accountIds, contents);

        Map<String,Object> replay = replay(requestId, payloadHash);
        if (replay != null) return replay;
        Map<String,Object> rule = lockRule(ruleId);
        Long ownerAccountId = number(rule.get("accountId"));
        accountAccessService.requireAccess(ownerAccountId);
        validateAccounts(accountIds);
        if (!accountIds.contains(ownerAccountId)) throw new BusinessException(400, "适用账号必须包含规则所属账号");

        ensureLegacySnapshot(rule, accountIds);
        int versionNo = ((Number) rule.getOrDefault("versionNo", 1)).intValue() + 1;
        int matchMode = "EXACT".equals(matchType) ? 2 : "REGEX".equals(matchType) ? 3 : 1;
        String sharingScope = accountIds.size() > 1 ? "ACCOUNT" : "GOODS";
        jdbcTemplate.update("""
                UPDATE xianyu_keyword_reply_rule
                   SET keyword=?,match_mode=?,match_type=?,priority=?,enabled=?,version_no=?,
                       effective_time=?,expires_time=?,sharing_scope=?
                 WHERE tenant_id=? AND id=?
                """, keyword, matchMode, matchType, priority, enabled ? 1 : 0, versionNo,
                effectiveTime, expiresTime, sharingScope, tenant(), ruleId);
        jdbcTemplate.update("DELETE FROM xianyu_keyword_reply_content WHERE tenant_id=? AND rule_id=?", tenant(), ruleId);
        for (ContentCommand content : contents) {
            jdbcTemplate.update("""
                    INSERT INTO xianyu_keyword_reply_content
                        (tenant_id,rule_id,reply_text,reply_image_url,version_no,status,effective_time,expires_time)
                    VALUES (?,?,?,?,?,'ACTIVE',?,?)
                    """, tenant(), ruleId, trim(content.replyText()), trim(content.replyImageUrl()),
                    versionNo, effectiveTime, expiresTime);
        }
        jdbcTemplate.update("DELETE FROM xianyu_keyword_reply_rule_account WHERE tenant_id=? AND rule_id=?", tenant(), ruleId);
        for (Long accountId : accountIds) {
            jdbcTemplate.update("""
                    INSERT INTO xianyu_keyword_reply_rule_account(rule_id,tenant_id,xianyu_account_id)
                    VALUES (?,?,?)
                    """, ruleId, tenant(), accountId);
        }
        jdbcTemplate.update("""
                INSERT INTO xianyu_keyword_reply_rule_version
                    (tenant_id,xianyu_account_id,rule_id,version_no,keyword,match_type,priority,enabled,
                     is_fallback,sharing_scope,account_ids_json,contents_json,effective_time,expires_time,
                     request_id,request_payload_hash,created_by,created_username)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, tenant(), ownerAccountId, ruleId, versionNo, keyword, matchType, priority,
                enabled ? 1 : 0, intValue(rule.get("isFallback")), sharingScope, json(accountIds), json(contents),
                effectiveTime, expiresTime, requestId, payloadHash, UserContext.getUserId(), UserContext.getUsername());
        XianyuOperationLog log = new XianyuOperationLog();
        log.setXianyuAccountId(ownerAccountId); log.setOperationType("KEYWORD_RULE_VERSION_SAVE");
        log.setOperationModule("自动回复"); log.setOperationDesc("保存关键词规则版本 V" + versionNo);
        log.setOperationStatus(1); log.setTargetType("KEYWORD_RULE"); log.setTargetId(String.valueOf(ruleId));
        log.setRequestId(requestId); log.setIdempotencyKey(requestId); log.setOutcomeState("LOCAL_SUCCESS");
        log.setDataSource("LOCAL"); log.setRequestParams(json(Map.of("ruleId", ruleId, "payloadHash", payloadHash)));
        log.setResponseResult(json(Map.of("versionNo", versionNo, "platformWrite", false)));
        operationLogService.logRequired(log);
        Map<String,Object> result = version(requestId);
        result.put("idempotentReplay", false);
        return result;
    }

    @Transactional
    public Map<String,Object> versions(Long ruleId) {
        Map<String,Object> rule = lockRule(ruleId);
        accountAccessService.requireAccess(number(rule.get("accountId")));
        List<Map<String,Object>> records = jdbcTemplate.queryForList("""
                SELECT id,rule_id ruleId,version_no versionNo,keyword,match_type matchType,priority,enabled,
                       is_fallback isFallback,sharing_scope sharingScope,account_ids_json accountIdsJson,
                       contents_json contentsJson,effective_time effectiveTime,expires_time expiresTime,
                       request_id requestId,created_username createdUsername,created_time createdTime
                  FROM xianyu_keyword_reply_rule_version
                 WHERE tenant_id=? AND rule_id=? ORDER BY version_no DESC
                """, tenant(), ruleId);
        records.forEach(this::decodeVersion);
        return Map.of("ruleId", ruleId, "records", records,
                "dataNotice", "版本是不可变快照；过期或停用规则不会参与自动回复，历史版本不会被删除。" );
    }

    private void ensureLegacySnapshot(Map<String,Object> rule, List<Long> fallbackAccounts) {
        Long ruleId = number(rule.get("id"));
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM xianyu_keyword_reply_rule_version WHERE tenant_id=? AND rule_id=?",
                Long.class, tenant(), ruleId);
        if (count != null && count > 0) return;
        List<Long> accounts = jdbcTemplate.queryForList("""
                SELECT xianyu_account_id FROM xianyu_keyword_reply_rule_account
                 WHERE tenant_id=? AND rule_id=? ORDER BY xianyu_account_id
                """, Long.class, tenant(), ruleId);
        if (accounts.isEmpty()) accounts = fallbackAccounts;
        List<Map<String,Object>> contents = jdbcTemplate.queryForList("""
                SELECT reply_text replyText,reply_image_url replyImageUrl FROM xianyu_keyword_reply_content
                 WHERE tenant_id=? AND rule_id=? ORDER BY id
                """, tenant(), ruleId);
        jdbcTemplate.update("""
                INSERT INTO xianyu_keyword_reply_rule_version
                    (tenant_id,xianyu_account_id,rule_id,version_no,keyword,match_type,priority,enabled,
                     is_fallback,sharing_scope,account_ids_json,contents_json,effective_time,expires_time,
                     request_id,request_payload_hash,created_by,created_username)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'LEGACY',NULL,'历史数据')
                """, tenant(), rule.get("accountId"), ruleId, rule.get("versionNo"), rule.get("keyword"),
                rule.get("matchType"), rule.get("priority"), rule.get("enabled"), rule.get("isFallback"),
                rule.get("sharingScope"), json(accounts), json(contents), rule.get("effectiveTime"),
                rule.get("expiresTime"), "legacy-keyword-rule-" + ruleId);
    }

    private Map<String,Object> replay(String requestId, String payloadHash) {
        List<Map<String,Object>> rows = jdbcTemplate.queryForList("""
                SELECT request_payload_hash requestPayloadHash FROM xianyu_keyword_reply_rule_version
                 WHERE tenant_id=? AND request_id=?
                """, tenant(), requestId);
        if (rows.isEmpty()) return null;
        if (!payloadHash.equals(String.valueOf(rows.getFirst().get("requestPayloadHash")))) {
            throw new BusinessException(409, "相同 requestId 已绑定不同的关键词规则版本");
        }
        Map<String,Object> result = version(requestId);
        result.put("idempotentReplay", true);
        return result;
    }

    private Map<String,Object> version(String requestId) {
        Map<String,Object> result = new LinkedHashMap<>(jdbcTemplate.queryForMap("""
                SELECT id,rule_id ruleId,version_no versionNo,keyword,match_type matchType,priority,enabled,
                       is_fallback isFallback,sharing_scope sharingScope,account_ids_json accountIdsJson,
                       contents_json contentsJson,effective_time effectiveTime,expires_time expiresTime,
                       request_id requestId,created_username createdUsername,created_time createdTime
                  FROM xianyu_keyword_reply_rule_version WHERE tenant_id=? AND request_id=?
                """, tenant(), requestId));
        decodeVersion(result);
        result.put("platformWrite", false);
        return result;
    }

    private Map<String,Object> lockRule(Long ruleId) {
        if (ruleId == null || ruleId <= 0) throw new BusinessException(400, "规则ID无效");
        List<Map<String,Object>> rows = jdbcTemplate.queryForList("""
                SELECT id,xianyu_account_id accountId,xy_goods_id goodsId,keyword,match_type matchType,
                       priority,enabled,version_no versionNo,is_fallback isFallback,sharing_scope sharingScope,
                       effective_time effectiveTime,expires_time expiresTime
                  FROM xianyu_keyword_reply_rule WHERE tenant_id=? AND id=? FOR UPDATE
                """, tenant(), ruleId);
        if (rows.isEmpty()) throw new BusinessException(404, "关键词规则不存在或无权访问");
        return new LinkedHashMap<>(rows.getFirst());
    }

    private List<ContentCommand> normalizeContents(List<ContentCommand> values, boolean enabled) {
        List<ContentCommand> result = new ArrayList<>();
        if (values != null) for (ContentCommand value : values) {
            if (value == null) continue;
            String text = trim(value.replyText());
            String image = trim(value.replyImageUrl());
            if (text == null && image == null) continue;
            if (text != null && text.length() > 1000) throw new BusinessException(400, "单条回复文字不能超过1000字");
            if (image != null && (!image.startsWith("https://") || image.length() > 2000)) {
                throw new BusinessException(400, "回复图片必须是有效的 HTTPS 地址");
            }
            result.add(new ContentCommand(text, image));
        }
        if (enabled && result.isEmpty()) throw new BusinessException(400, "启用规则前至少配置一条回复内容");
        if (result.size() > 20) throw new BusinessException(400, "每条规则最多20条回复内容");
        return List.copyOf(result);
    }

    private List<Long> normalizeAccounts(List<Long> values) {
        if (values == null) throw new BusinessException(400, "至少选择一个适用账号");
        List<Long> result = values.stream().filter(v -> v != null && v > 0).distinct().sorted().toList();
        if (result.isEmpty()) throw new BusinessException(400, "至少选择一个适用账号");
        return result;
    }

    private void validateAccounts(List<Long> accountIds) {
        for (Long accountId : accountIds) {
            accountAccessService.requireAccess(accountId);
            Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM xianyu_account WHERE tenant_id=? AND id=?",
                    Long.class, tenant(), accountId);
            if (count == null || count == 0) throw new BusinessException(404, "适用账号不存在或无权访问");
        }
    }

    private void validateRegex(String value) {
        try { Pattern.compile(value); }
        catch (PatternSyntaxException e) { throw new BusinessException(400, "正则表达式无效：" + e.getDescription()); }
    }

    private String payloadHash(Long ruleId,String keyword,String matchType,int priority,boolean enabled,
                               LocalDateTime effective,LocalDateTime expires,List<Long> accounts,List<ContentCommand> contents) {
        Map<String,Object> payload = new LinkedHashMap<>();
        payload.put("ruleId",ruleId); payload.put("keyword",keyword); payload.put("matchType",matchType);
        payload.put("priority",priority); payload.put("enabled",enabled); payload.put("effectiveTime",effective.toString());
        payload.put("expiresTime",expires==null?null:expires.toString()); payload.put("accountIds",accounts); payload.put("contents",contents);
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json(payload).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("无法生成规则请求指纹",e); }
    }

    private void decodeVersion(Map<String,Object> row) {
        row.put("accountIds", readJson(String.valueOf(row.remove("accountIdsJson"))));
        row.put("contents", readJson(String.valueOf(row.remove("contentsJson"))));
    }
    private Object readJson(String value) { try { return objectMapper.readValue(value,Object.class); } catch(Exception e) { return List.of(); } }
    private String json(Object value) { try { return objectMapper.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException("无法序列化规则",e); } }
    private Long tenant() { Long value=TenantContext.get(); if(value==null) throw new BusinessException(401,"缺少经营主体上下文"); return value; }
    private Long number(Object value) { return value instanceof Number n?n.longValue():Long.valueOf(String.valueOf(value)); }
    private int intValue(Object value) { return value instanceof Number n?n.intValue():Integer.parseInt(String.valueOf(value)); }
    private String required(String value,String label,int max) { String result=trim(value); if(result==null) throw new BusinessException(400,label+"不能为空"); if(result.length()>max) throw new BusinessException(400,label+"不能超过"+max+"个字符"); return result; }
    private String trim(String value) { return value==null||value.trim().isEmpty()?null:value.trim(); }

    public record SaveCommand(String keyword,String matchType,Integer priority,Boolean enabled,
                              LocalDateTime effectiveTime,LocalDateTime expiresTime,List<Long> accountIds,
                              List<ContentCommand> contents,String requestId) {}
    public record ContentCommand(String replyText,String replyImageUrl) {}
}
