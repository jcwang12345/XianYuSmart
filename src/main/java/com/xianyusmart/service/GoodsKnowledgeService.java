package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.entity.XianyuOperationLog;
import com.xianyusmart.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** IM-04 商品级版本化知识；AI 只能读取当前处于有效区间的 ACTIVE 版本。 */
@Service
public class GoodsKnowledgeService {

    private final JdbcTemplate jdbcTemplate;
    private final AccountAccessService accountAccessService;
    private final OperationLogService operationLogService;
    private final ObjectMapper objectMapper;
    private final Supplier<String> requestAttemptTokenSupplier;

    @Autowired
    public GoodsKnowledgeService(JdbcTemplate jdbcTemplate,
                                 AccountAccessService accountAccessService,
                                 OperationLogService operationLogService,
                                 ObjectMapper objectMapper) {
        this(jdbcTemplate, accountAccessService, operationLogService, objectMapper,
                () -> UUID.randomUUID().toString());
    }

    GoodsKnowledgeService(JdbcTemplate jdbcTemplate,
                          AccountAccessService accountAccessService,
                          OperationLogService operationLogService,
                          ObjectMapper objectMapper,
                          Supplier<String> requestAttemptTokenSupplier) {
        this.jdbcTemplate = jdbcTemplate;
        this.accountAccessService = accountAccessService;
        this.operationLogService = operationLogService;
        this.objectMapper = objectMapper;
        this.requestAttemptTokenSupplier = requestAttemptTokenSupplier;
    }

    @Transactional
    public Map<String, Object> save(SaveCommand command) {
        if (command == null) throw new BusinessException(400, "商品知识参数不能为空");
        Long accountId = requireGoods(command.accountId(), command.goodsId());
        String goodsId = required(command.goodsId(), "商品ID", 100);
        String content = required(command.content(), "知识内容", 5000);
        String requestId = required(command.requestId(), "requestId", 80);
        LocalDateTime effectiveTime = command.effectiveTime() == null ? LocalDateTime.now() : command.effectiveTime();
        LocalDateTime expiresTime = command.expiresTime();
        if (expiresTime != null && !expiresTime.isAfter(effectiveTime)) {
            throw new BusinessException(400, "失效时间必须晚于生效时间");
        }
        boolean activate = command.activate() == null || command.activate();
        String sourceType = source(command.sourceType());
        String payloadHash = payloadHash(accountId, goodsId, content, command.effectiveTime(),
                expiresTime, activate, sourceType);
        if (activate && effectiveTime.isAfter(LocalDateTime.now().plusSeconds(5))) {
            throw new BusinessException(400, "立即启用的版本不能设置为未来生效；请先保存草稿后再启用");
        }
        Map<String, Object> replay = saveReplay(accountId, goodsId, content, command.effectiveTime(),
                expiresTime, activate, sourceType, payloadHash, requestId, false);
        if (replay != null) return replay;
        // Lock the product rather than the optional automation config. Knowledge can be prepared
        // before auto-reply is enabled, and the lock serializes version_no allocation per product.
        jdbcTemplate.queryForList("""
                SELECT id FROM xianyu_goods
                 WHERE tenant_id=? AND xianyu_account_id=? AND xy_good_id=? FOR UPDATE
                """, tenant(), accountId, goodsId);
        // A concurrent exact replay for the same product waits on the product lock. Re-check after
        // acquiring it so only the winning request allocates a version or supersedes active data.
        replay = saveReplay(accountId, goodsId, content, command.effectiveTime(), expiresTime,
                activate, sourceType, payloadHash, requestId, true);
        if (replay != null) return replay;
        Integer versionNo = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(version_no),0)+1 FROM xianyu_goods_knowledge_version
                 WHERE tenant_id=? AND xianyu_account_id=? AND xy_goods_id=?
                """, Integer.class, tenant(), accountId, goodsId);
        String attemptToken = required(requestAttemptTokenSupplier.get(), "请求尝试标识", 64);
        jdbcTemplate.update("""
                INSERT INTO xianyu_goods_knowledge_version
                    (tenant_id,xianyu_account_id,xy_goods_id,version_no,content,source_type,status,
                     effective_time,expires_time,activated_time,created_by,created_username,request_id,
                     request_payload_hash,request_attempt_token)
                VALUES (?,?,?,?,?,?,'DRAFT',?,?,NULL,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)
                """, tenant(), accountId, goodsId, versionNo, content, sourceType,
                effectiveTime, expiresTime, UserContext.getUserId(), UserContext.getUsername(), requestId,
                payloadHash, attemptToken);
        Map<String, Object> persisted = requestRow(requestId, true);
        validateReplayPayload(persisted, accountId, goodsId, content, command.effectiveTime(), expiresTime,
                activate, sourceType, payloadHash);
        if (!attemptToken.equals(String.valueOf(persisted.get("requestAttemptToken")))) {
            Map<String, Object> result = version(number(persisted.get("id")));
            result.put("idempotentReplay", true);
            return result;
        }
        Long versionId = number(persisted.get("id"));
        if (activate) {
            supersedeActive(accountId, goodsId, versionId);
            jdbcTemplate.update("""
                    UPDATE xianyu_goods_knowledge_version
                       SET status='ACTIVE',activated_time=NOW(3),invalidated_time=NULL
                     WHERE tenant_id=? AND id=?
                    """, tenant(), versionId);
            updateActiveConfig(accountId, goodsId, versionId, content);
        }
        Map<String, Object> created = version(versionId);
        audit("GOODS_KNOWLEDGE_VERSION_CREATE", created, requestId,
                Map.of("versionNo", versionNo, "activate", activate), "LOCAL_SUCCESS");
        created.put("idempotentReplay", false);
        return created;
    }

    @Transactional
    public Map<String, Object> activate(Long versionId, String requestId) {
        Map<String, Object> row = lockedVersion(versionId);
        accountAccessService.requireAccess(number(row.get("accountId")));
        String safeRequestId = required(requestId, "requestId", 80);
        Map<String, Object> replay = actionReplay(versionId, "ACTIVATE", safeRequestId);
        if (replay != null) return replay;
        LocalDateTime effective = localTime(row.get("effectiveTime"));
        LocalDateTime expires = localTime(row.get("expiresTime"));
        LocalDateTime now = LocalDateTime.now();
        if (effective != null && effective.isAfter(now.plusSeconds(5))) throw new BusinessException(409, "该版本尚未到生效时间");
        if (expires != null && !expires.isAfter(now)) throw new BusinessException(409, "已过期版本不能启用");
        Long accountId = number(row.get("accountId"));
        String goodsId = String.valueOf(row.get("goodsId"));
        supersedeActive(accountId, goodsId, versionId);
        jdbcTemplate.update("""
                UPDATE xianyu_goods_knowledge_version
                   SET status='ACTIVE',activated_time=NOW(3),invalidated_time=NULL
                 WHERE tenant_id=? AND id=?
                """, tenant(), versionId);
        updateActiveConfig(accountId, goodsId, versionId, String.valueOf(row.get("content")));
        recordAction(versionId, "ACTIVATE", safeRequestId);
        Map<String, Object> result = version(versionId);
        audit("GOODS_KNOWLEDGE_VERSION_ACTIVATE", result, safeRequestId,
                Map.of("versionNo", result.get("versionNo")), "LOCAL_SUCCESS");
        result.put("idempotentReplay", false);
        return result;
    }

    @Transactional
    public Map<String, Object> expire(Long versionId, String requestId) {
        Map<String, Object> row = lockedVersion(versionId);
        accountAccessService.requireAccess(number(row.get("accountId")));
        String safeRequestId = required(requestId, "requestId", 80);
        Map<String, Object> replay = actionReplay(versionId, "EXPIRE", safeRequestId);
        if (replay != null) return replay;
        jdbcTemplate.update("""
                UPDATE xianyu_goods_knowledge_version
                   SET status='EXPIRED',invalidated_time=NOW(3)
                 WHERE tenant_id=? AND id=?
                """, tenant(), versionId);
        jdbcTemplate.update("""
                UPDATE xianyu_goods_config SET fixed_material=NULL,active_knowledge_version_id=NULL
                 WHERE tenant_id=? AND xianyu_account_id=? AND xy_goods_id=? AND active_knowledge_version_id=?
                """, tenant(), number(row.get("accountId")), row.get("goodsId"), versionId);
        recordAction(versionId, "EXPIRE", safeRequestId);
        Map<String, Object> result = version(versionId);
        audit("GOODS_KNOWLEDGE_VERSION_EXPIRE", result, safeRequestId,
                Map.of("versionNo", result.get("versionNo")), "LOCAL_SUCCESS");
        result.put("idempotentReplay", false);
        return result;
    }

    public Map<String, Object> view(Long accountId, String goodsId) {
        requireGoods(accountId, goodsId);
        ActiveKnowledge active = effective(accountId, goodsId);
        List<Map<String, Object>> versions = jdbcTemplate.queryForList("""
                SELECT id,version_no versionNo,content,status,source_type sourceType,effective_time effectiveTime,
                       expires_time expiresTime,activated_time activatedTime,invalidated_time invalidatedTime,
                       created_username createdUsername,created_time createdTime,
                       CASE WHEN status='ACTIVE' AND effective_time<=NOW(3)
                                  AND (expires_time IS NULL OR expires_time>NOW(3)) THEN 'EFFECTIVE'
                            WHEN status='ACTIVE' AND expires_time IS NOT NULL AND expires_time<=NOW(3) THEN 'EXPIRED'
                            WHEN status='ACTIVE' AND effective_time>NOW(3) THEN 'NOT_YET_EFFECTIVE'
                            ELSE status END effectiveStatus
                  FROM xianyu_goods_knowledge_version
                 WHERE tenant_id=? AND xianyu_account_id=? AND xy_goods_id=?
                 ORDER BY version_no DESC LIMIT 50
                """, tenant(), accountId, required(goodsId, "商品ID", 100));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fixedMaterial", active == null ? null : active.content());
        result.put("activeVersionId", active == null ? null : active.id());
        result.put("activeVersionNo", active == null ? null : active.versionNo());
        result.put("effectiveTime", active == null ? null : active.effectiveTime());
        result.put("expiresTime", active == null ? null : active.expiresTime());
        result.put("status", active == null ? "NO_EFFECTIVE_VERSION" : "EFFECTIVE");
        result.put("versions", versions);
        result.put("dataNotice", "AI 只使用已启用且处于有效时间区间的版本；失效版本和过期二维码不会进入回复上下文。");
        return result;
    }

    /** 后台回复链路读取；不依赖当前操作人的页面权限，但始终受 tenant_id 隔离。 */
    public ActiveKnowledge effective(Long accountId, String goodsId) {
        if (accountId == null || goodsId == null || goodsId.isBlank() || TenantContext.get() == null) return null;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id,version_no versionNo,content,effective_time effectiveTime,expires_time expiresTime
                  FROM xianyu_goods_knowledge_version
                 WHERE tenant_id=? AND xianyu_account_id=? AND xy_goods_id=? AND status='ACTIVE'
                   AND effective_time<=NOW(3) AND (expires_time IS NULL OR expires_time>NOW(3))
                 ORDER BY version_no DESC LIMIT 1
                """, tenant(), accountId, goodsId.trim());
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.getFirst();
        return new ActiveKnowledge(number(row.get("id")), intValue(row.get("versionNo")),
                String.valueOf(row.get("content")), localTime(row.get("effectiveTime")), localTime(row.get("expiresTime")));
    }

    private Long requireGoods(Long accountId, String goodsId) {
        if (accountId == null || accountId <= 0) throw new BusinessException(400, "账号ID无效");
        accountAccessService.requireAccess(accountId);
        String normalized = required(goodsId, "商品ID", 100);
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM xianyu_goods
                 WHERE tenant_id=? AND xianyu_account_id=? AND xy_good_id=?
                """, Long.class, tenant(), accountId, normalized);
        if (count == null || count == 0) throw new BusinessException(404, "商品不存在或无权访问");
        return accountId;
    }

    private void supersedeActive(Long accountId, String goodsId, Long exceptId) {
        if (exceptId == null) {
            jdbcTemplate.update("""
                    UPDATE xianyu_goods_knowledge_version SET status='SUPERSEDED',invalidated_time=NOW(3)
                     WHERE tenant_id=? AND xianyu_account_id=? AND xy_goods_id=? AND status='ACTIVE'
                    """, tenant(), accountId, goodsId);
        } else {
            jdbcTemplate.update("""
                    UPDATE xianyu_goods_knowledge_version SET status='SUPERSEDED',invalidated_time=NOW(3)
                     WHERE tenant_id=? AND xianyu_account_id=? AND xy_goods_id=? AND status='ACTIVE' AND id<>?
                    """, tenant(), accountId, goodsId, exceptId);
        }
    }

    private void updateActiveConfig(Long accountId, String goodsId, Long versionId, String content) {
        jdbcTemplate.update("""
                UPDATE xianyu_goods_config SET fixed_material=?,active_knowledge_version_id=?
                 WHERE tenant_id=? AND xianyu_account_id=? AND xy_goods_id=?
                """, content, versionId, tenant(), accountId, goodsId);
    }

    private Map<String, Object> lockedVersion(Long id) {
        if (id == null || id <= 0) throw new BusinessException(400, "知识版本ID无效");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id,xianyu_account_id accountId,xy_goods_id goodsId,version_no versionNo,content,status,
                       effective_time effectiveTime,expires_time expiresTime
                  FROM xianyu_goods_knowledge_version WHERE tenant_id=? AND id=? FOR UPDATE
                """, tenant(), id);
        if (rows.isEmpty()) throw new BusinessException(404, "商品知识版本不存在或无权访问");
        return new LinkedHashMap<>(rows.getFirst());
    }

    private Map<String, Object> version(Long id) {
        return new LinkedHashMap<>(jdbcTemplate.queryForMap("""
                SELECT id,xianyu_account_id accountId,xy_goods_id goodsId,version_no versionNo,content,status,
                       source_type sourceType,effective_time effectiveTime,expires_time expiresTime,
                       activated_time activatedTime,invalidated_time invalidatedTime,request_id requestId,
                       created_username createdUsername,created_time createdTime
                  FROM xianyu_goods_knowledge_version WHERE tenant_id=? AND id=?
                 FOR UPDATE
                """, tenant(), id));
    }

    private Map<String, Object> saveReplay(Long accountId, String goodsId, String content,
                                           LocalDateTime requestedEffectiveTime, LocalDateTime expiresTime,
                                           boolean activate, String sourceType, String payloadHash,
                                           String requestId, boolean forUpdate) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id,xianyu_account_id accountId,xy_goods_id goodsId,content,source_type sourceType,
                       effective_time effectiveTime,expires_time expiresTime,activated_time activatedTime,
                       request_payload_hash requestPayloadHash,request_attempt_token requestAttemptToken
                  FROM xianyu_goods_knowledge_version WHERE tenant_id=? AND request_id=?
                """ + (forUpdate ? " FOR UPDATE" : ""), tenant(), requestId);
        if (rows.isEmpty()) return null;
        Map<String, Object> prior = new LinkedHashMap<>(rows.getFirst());
        validateReplayPayload(prior, accountId, goodsId, content, requestedEffectiveTime, expiresTime,
                activate, sourceType, payloadHash);
        Map<String, Object> result = version(number(prior.get("id")));
        result.put("idempotentReplay", true);
        return result;
    }

    private Map<String, Object> requestRow(String requestId, boolean forUpdate) {
        return new LinkedHashMap<>(jdbcTemplate.queryForMap("""
                SELECT id,xianyu_account_id accountId,xy_goods_id goodsId,content,source_type sourceType,
                       effective_time effectiveTime,expires_time expiresTime,activated_time activatedTime,
                       request_payload_hash requestPayloadHash,request_attempt_token requestAttemptToken
                  FROM xianyu_goods_knowledge_version WHERE tenant_id=? AND request_id=?
                """ + (forUpdate ? " FOR UPDATE" : ""), tenant(), requestId));
    }

    private void validateReplayPayload(Map<String, Object> prior, Long accountId, String goodsId,
                                       String content, LocalDateTime requestedEffectiveTime,
                                       LocalDateTime expiresTime, boolean activate, String sourceType,
                                       String payloadHash) {
        if (!accountId.equals(number(prior.get("accountId")))
                || !goodsId.equals(String.valueOf(prior.get("goodsId")))) {
            throw new BusinessException(409, "requestId 已用于其他商品知识版本");
        }
        String persistedHash = prior.get("requestPayloadHash") == null
                ? "" : String.valueOf(prior.get("requestPayloadHash"));
        if (!persistedHash.isBlank() && !"LEGACY".equals(persistedHash)) {
            if (!payloadHash.equals(persistedHash)) {
                throw new BusinessException(409, "requestId 已用于不同的商品知识内容或有效期");
            }
            return;
        }
        boolean legacyActivate = prior.get("activatedTime") != null;
        boolean legacyMatch = content.equals(String.valueOf(prior.get("content")))
                && sourceType.equals(String.valueOf(prior.get("sourceType")))
                && Objects.equals(expiresTime, localTime(prior.get("expiresTime")))
                && (requestedEffectiveTime == null
                    || Objects.equals(requestedEffectiveTime, localTime(prior.get("effectiveTime"))))
                && activate == legacyActivate;
        if (!legacyMatch) {
            throw new BusinessException(409, "requestId 已用于不同的商品知识请求");
        }
    }

    String payloadHash(Long accountId, String goodsId, String content,
                       LocalDateTime requestedEffectiveTime, LocalDateTime expiresTime,
                       boolean activate, String sourceType) {
        String canonical = field(accountId) + field(goodsId) + field(content) +
                field(requestedEffectiveTime) + field(expiresTime) + field(activate) + field(sourceType);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String field(Object value) {
        String text = value == null ? "<null>" : String.valueOf(value);
        return text.length() + ":" + text + "|";
    }

    private Map<String, Object> actionReplay(Long versionId, String actionType, String requestId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT knowledge_version_id versionId,action_type actionType
                  FROM xianyu_goods_knowledge_action_request
                 WHERE tenant_id=? AND request_id=?
                """, tenant(), requestId);
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.getFirst();
        if (!versionId.equals(number(row.get("versionId"))) || !actionType.equals(String.valueOf(row.get("actionType")))) {
            throw new BusinessException(409, "requestId 已用于其他商品知识动作");
        }
        Map<String, Object> result = version(versionId);
        result.put("idempotentReplay", true);
        return result;
    }

    private void recordAction(Long versionId, String actionType, String requestId) {
        jdbcTemplate.update("""
                INSERT INTO xianyu_goods_knowledge_action_request
                    (tenant_id,knowledge_version_id,action_type,request_id)
                VALUES (?,?,?,?)
                """, tenant(), versionId, actionType, requestId);
    }

    private void audit(String type, Map<String, Object> row, String requestId, Object request, String outcome) {
        XianyuOperationLog log = new XianyuOperationLog();
        log.setXianyuAccountId(number(row.get("accountId")));
        log.setOperationType(type);
        log.setOperationModule("商品知识");
        log.setOperationDesc("商品知识版本：" + type);
        log.setOperationStatus(1);
        log.setTargetType("GOODS_KNOWLEDGE_VERSION");
        log.setTargetId(String.valueOf(row.get("id")));
        log.setRequestId(requestId);
        log.setIdempotencyKey(requestId);
        log.setOutcomeState(outcome);
        log.setDataSource("LOCAL");
        log.setRequestParams(json(request));
        log.setResponseResult(json(Map.of("goodsId", row.get("goodsId"), "versionNo", row.get("versionNo"),
                "status", row.get("status"))));
        operationLogService.log(log);
    }

    private Long tenant() {
        Long tenantId = TenantContext.get();
        if (tenantId == null) throw new BusinessException(401, "缺少经营主体上下文");
        return tenantId;
    }

    private String source(String value) {
        String normalized = value == null || value.isBlank() ? "MANUAL" : value.trim().toUpperCase();
        return switch (normalized) { case "MANUAL", "GOODS_DETAIL", "LEGACY_IMPORT" -> normalized; default -> "MANUAL"; };
    }

    private String required(String value, String label, int max) {
        if (value == null || value.trim().isEmpty()) throw new BusinessException(400, label + "不能为空");
        String normalized = value.trim();
        if (normalized.length() > max) throw new BusinessException(400, label + "不能超过" + max + "个字符");
        return normalized;
    }

    private Long number(Object value) { return value instanceof Number n ? n.longValue() : Long.valueOf(String.valueOf(value)); }
    private Integer intValue(Object value) { return value instanceof Number n ? n.intValue() : Integer.valueOf(String.valueOf(value)); }
    private LocalDateTime localTime(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime time) return time;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime();
        return LocalDateTime.parse(String.valueOf(value).replace(' ', 'T'));
    }
    private String json(Object value) { try { return objectMapper.writeValueAsString(value); } catch (Exception e) { return "{}"; } }

    public record ActiveKnowledge(Long id, Integer versionNo, String content,
                                  LocalDateTime effectiveTime, LocalDateTime expiresTime) {}
    public record SaveCommand(Long accountId, String goodsId, String content, LocalDateTime effectiveTime,
                              LocalDateTime expiresTime, Boolean activate, String sourceType, String requestId) {}
}
