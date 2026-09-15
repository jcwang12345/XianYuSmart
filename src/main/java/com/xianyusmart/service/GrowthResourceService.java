package com.xianyusmart.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.AccountScopeContext;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.entity.XianyuOperationLog;
import com.xianyusmart.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** V6 Wave 7 素材与货源的不可变版本、来源、许可、有效期和引用证据。 */
@Service
public class GrowthResourceService {

    private static final Set<String> TYPES = Set.of("MATERIAL", "SUPPLY");
    private static final Set<String> SOURCE_TYPES = Set.of(
            "MANUAL", "PLATFORM_SEARCH", "PLATFORM_SHOP", "SUPPLIER_IMPORT", "LOCAL_DRAFT", "LEGACY_IMPORT", "QA_FIXTURE");
    private static final Set<String> AUTHORIZATION_STATES = Set.of(
            "VERIFIED", "DECLARED", "PUBLIC_READ", "UNKNOWN", "NOT_APPLICABLE");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AccountAccessService accountAccessService;
    private final OperationLogService operationLogService;

    public GrowthResourceService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                 AccountAccessService accountAccessService,
                                 OperationLogService operationLogService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.accountAccessService = accountAccessService;
        this.operationLogService = operationLogService;
    }

    public List<Map<String, Object>> list(String type, Integer status) {
        String normalizedType = type(type);
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                SELECT r.id,r.resource_type,r.name,r.status,r.xianyu_account_id,r.xy_goods_id,r.stock,r.amount,r.data_json,
                       r.scheduled_time,r.last_run_time,r.created_time,r.updated_time,
                       v.id version_id,v.version_no,v.lifecycle_state,v.payload_fingerprint,v.source_type,
                       v.source_url,v.source_item_id,v.source_captured_time,v.authorization_status,
                       v.license_type,v.license_note,v.supplier_name,v.valid_from,v.valid_until,
                       v.operator_username version_operator,v.created_time version_created_time,
                       (SELECT COUNT(*) FROM growth_resource_version d
                         WHERE d.tenant_id=r.tenant_id AND d.payload_fingerprint=v.payload_fingerprint
                           AND d.resource_id<>r.id) duplicate_count,
                       (SELECT COUNT(*) FROM merchant_distribution md
                         WHERE md.tenant_id=r.tenant_id AND (md.supply_resource_id=r.id OR md.material_resource_id=r.id)) reference_count,
                       (SELECT COUNT(*) FROM growth_resource_goods_mapping gm
                         WHERE gm.tenant_id=r.tenant_id AND gm.resource_id=r.id
                           AND gm.mapping_status='ACTIVE'
                           AND (gm.valid_from IS NULL OR gm.valid_from<=NOW(3))
                           AND (gm.valid_until IS NULL OR gm.valid_until>NOW(3))) mapping_count
                  FROM merchant_resource r
                  LEFT JOIN growth_resource_version v ON v.id=(
                       SELECT vv.id FROM growth_resource_version vv
                        WHERE vv.tenant_id=r.tenant_id AND vv.resource_id=r.id
                        ORDER BY (vv.lifecycle_state='ACTIVE') DESC,vv.version_no DESC LIMIT 1)
                 WHERE r.tenant_id=? AND r.resource_type=?
                   AND (? IS NULL OR r.status=?)
                 ORDER BY r.updated_time DESC,r.id DESC
                """, (rs, rowNum) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rs.getLong("id"));
            item.put("resourceType", rs.getString("resource_type"));
            item.put("name", rs.getString("name"));
            item.put("status", rs.getInt("status"));
            item.put("accountId", nullableLong(rs.getObject("xianyu_account_id")));
            item.put("accountIds", accountIds(rs.getLong("id"), nullableLong(rs.getObject("xianyu_account_id"))));
            item.put("goodsId", rs.getString("xy_goods_id"));
            item.put("stock", rs.getInt("stock"));
            Map<String, Object> resourcePayload = jsonMap(rs.getString("data_json"));
            item.put("amount", exposedAmount(rs.getString("resource_type"), resourcePayload, rs.getBigDecimal("amount")));
            item.put("scheduledTime", instant(rs.getTimestamp("scheduled_time")));
            item.put("lastRunTime", instant(rs.getTimestamp("last_run_time")));
            item.put("createdTime", instant(rs.getTimestamp("created_time")));
            item.put("updatedTime", instant(rs.getTimestamp("updated_time")));
            Long versionId = nullableLong(rs.getObject("version_id"));
            item.put("version", versionId == null ? null : versionSummary(
                    versionId, rs.getInt("version_no"), rs.getString("lifecycle_state"),
                    rs.getString("payload_fingerprint"), rs.getString("source_type"), rs.getString("source_url"),
                    rs.getString("source_item_id"), instant(rs.getTimestamp("source_captured_time")),
                    rs.getString("authorization_status"), rs.getString("license_type"), rs.getString("license_note"),
                    rs.getString("supplier_name"), instant(rs.getTimestamp("valid_from")),
                    instant(rs.getTimestamp("valid_until")), rs.getString("version_operator"),
                    instant(rs.getTimestamp("version_created_time")), rs.getInt("duplicate_count"),
                    rs.getInt("reference_count"), rs.getInt("mapping_count"), rs.getString("resource_type")));
            return item;
        }, tenant(), normalizedType, status, status);
        return rows.stream()
                .filter(row -> canReadAccounts(castLongList(row.get("accountIds"))))
                .map(this::sanitizeResourceForScope)
                .toList();
    }

    public Map<String, Object> detail(Long resourceId) {
        Map<String, Object> resource = requireResource(resourceId, false);
        List<Map<String, Object>> versionHistory = versions(resourceId);
        resource.put("version", activeVersion(versionHistory));
        resource.put("versions", versionHistory);
        resource.put("goodsMappings", goodsMappings(resourceId));
        return resource;
    }

    Map<String, Object> activeVersion(List<Map<String, Object>> versionHistory) {
        if (versionHistory == null) return null;
        return versionHistory.stream()
                .filter(version -> "ACTIVE".equals(text(version.get("lifecycleState"))))
                .findFirst()
                .orElse(null);
    }

    public List<Map<String, Object>> versions(Long resourceId) {
        Map<String, Object> resource = requireResource(resourceId, false);
        String normalizedResourceType = text(resource.get("resourceType"));
        return jdbcTemplate.query("""
                SELECT id,version_no,lifecycle_state,request_id,request_fingerprint,payload_fingerprint,payload_json,source_type,
                       source_url,source_item_id,source_captured_time,authorization_status,license_type,license_note,
                       supplier_name,valid_from,valid_until,operator_user_id,operator_username,created_time
                  FROM growth_resource_version
                 WHERE tenant_id=? AND resource_id=? ORDER BY version_no DESC
                """, (rs, rowNum) -> {
            Map<String, Object> row = versionSummary(rs.getLong("id"), rs.getInt("version_no"),
                    rs.getString("lifecycle_state"), rs.getString("payload_fingerprint"), rs.getString("source_type"),
                    rs.getString("source_url"), rs.getString("source_item_id"),
                    instant(rs.getTimestamp("source_captured_time")), rs.getString("authorization_status"),
                    rs.getString("license_type"), rs.getString("license_note"), rs.getString("supplier_name"),
                    instant(rs.getTimestamp("valid_from")), instant(rs.getTimestamp("valid_until")),
                    rs.getString("operator_username"), instant(rs.getTimestamp("created_time")),
                    duplicateCount(rs.getString("payload_fingerprint"), resourceId), referenceCount(resourceId),
                    mappingCount(resourceId), normalizedResourceType);
            row.put("requestId", rs.getString("request_id"));
            row.put("requestFingerprint", rs.getString("request_fingerprint"));
            row.put("operatorUserId", nullableLong(rs.getObject("operator_user_id")));
            row.put("payload", sanitizePayloadForScope(jsonMap(rs.getString("payload_json"))));
            return row;
        }, tenant(), resourceId);
    }

    @Transactional
    public Map<String, Object> createVersion(Map<String, Object> request) {
        String requestId = requiredRequestId(request);
        String normalizedType = type(text(request.get("resourceType")));
        String name = text(request.get("name"));
        if (name.isBlank() || name.length() > 512) throw new BusinessException(400, "资源名称不能为空且不能超过512个字符");
        List<Long> accountIds = longList(request.get("accountIds"));
        if (accountIds.isEmpty()) {
            Long accountId = number(request.get("accountId"));
            if (accountId != null) accountIds = List.of(accountId);
        }
        if (accountIds.isEmpty()) throw new BusinessException(400, "素材或货源至少关联一个授权账号");
        accountIds.forEach(accountAccessService::requireAccess);

        Map<String, Object> payload = map(request.get("payload"));
        payload.put("name", name);
        payload.put("resourceType", normalizedType);
        payload.put("accountIds", accountIds);
        payload.put("status", intValue(request.get("status"), 1));
        payload.put("goodsMappings", normalizeGoodsMappings(payload.get("goodsMappings"), accountIds));
        validatePayload(normalizedType, request, payload);
        String payloadFingerprint = fingerprint(payload);
        Map<String, Object> requestSignature = new LinkedHashMap<>();
        requestSignature.put("resourceId", number(request.get("resourceId")));
        requestSignature.put("payload", payload);
        requestSignature.put("sourceType", sourceType(request));
        requestSignature.put("sourceUrl", blank(text(request.get("sourceUrl"))));
        requestSignature.put("sourceItemId", blank(text(request.get("sourceItemId"))));
        requestSignature.put("sourceCapturedTime", valueInstant(request.get("sourceCapturedTime")));
        requestSignature.put("authorizationStatus", authorization(request));
        requestSignature.put("licenseType", blank(text(request.get("licenseType"))));
        requestSignature.put("licenseNote", blank(text(request.get("licenseNote"))));
        requestSignature.put("supplierName", blank(text(request.get("supplierName"))));
        requestSignature.put("validFrom", valueInstant(request.get("validFrom")));
        requestSignature.put("validUntil", valueInstant(request.get("validUntil")));
        String requestFingerprint = fingerprint(requestSignature);
        List<Map<String, Object>> replay = jdbcTemplate.queryForList("""
                SELECT resource_id,request_fingerprint FROM growth_resource_version
                 WHERE tenant_id=? AND request_id=? FOR UPDATE
                """, tenant(), requestId);
        if (!replay.isEmpty()) {
            if (!requestFingerprint.equals(text(replay.get(0).get("request_fingerprint")))) {
                throw new BusinessException(409, "requestId 已用于不同资源版本，请生成新的请求 ID");
            }
            Map<String, Object> result = detail(number(replay.get(0).get("resource_id")));
            result.put("idempotentReplay", true);
            return result;
        }

        Long resourceId = number(request.get("resourceId"));
        Map<String, Object> before = null;
        if (resourceId == null) {
            jdbcTemplate.update("""
                    INSERT INTO merchant_resource
                    (tenant_id,resource_type,name,status,xianyu_account_id,xy_goods_id,stock,amount,data_json)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """, tenant(), normalizedType, name, intValue(request.get("status"), 1), accountIds.get(0),
                    blank(text(payload.get("goodsId"))), Math.max(0, intValue(payload.get("stock"), 0)),
                    storageAmount(normalizedType, payload.get("amount")), json(payload));
            resourceId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        } else {
            before = requireResource(resourceId, true);
            if (!normalizedType.equals(before.get("resourceType"))) throw new BusinessException(400, "资源类型创建后不可修改");
            jdbcTemplate.update("""
                    UPDATE merchant_resource SET name=?,status=?,xianyu_account_id=?,xy_goods_id=?,stock=?,amount=?,data_json=?
                     WHERE tenant_id=? AND id=?
                    """, name, intValue(request.get("status"), intValue(before.get("status"), 1)), accountIds.get(0),
                    blank(text(payload.get("goodsId"))), Math.max(0, intValue(payload.get("stock"), 0)),
                    storageAmount(normalizedType, payload.get("amount")), json(payload), tenant(), resourceId);
        }
        replaceAccounts(resourceId, accountIds);
        replaceGoodsMappings(resourceId, accountIds, payload.get("goodsMappings"));
        Integer nextVersion = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(version_no),0)+1 FROM growth_resource_version
                 WHERE tenant_id=? AND resource_id=?
                """, Integer.class, tenant(), resourceId);
        jdbcTemplate.update("""
                INSERT INTO growth_resource_version
                (tenant_id,resource_id,version_no,lifecycle_state,request_id,request_fingerprint,payload_fingerprint,payload_json,
                 source_type,source_url,source_item_id,source_captured_time,authorization_status,license_type,
                 license_note,supplier_name,valid_from,valid_until,operator_user_id,operator_username)
                VALUES (?,?,?,'DRAFT',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, tenant(), resourceId, nextVersion, requestId, requestFingerprint, payloadFingerprint, json(payload),
                sourceType(request), blank(text(request.get("sourceUrl"))), blank(text(request.get("sourceItemId"))),
                timestamp(request.get("sourceCapturedTime")), authorization(request), blank(text(request.get("licenseType"))),
                blank(text(request.get("licenseNote"))), blank(text(request.get("supplierName"))),
                timestamp(request.get("validFrom")), timestamp(request.get("validUntil")),
                UserContext.getUserId(), UserContext.getUsername());
        Map<String, Object> after = detail(resourceId);
        audit(resourceId, accountIds.get(0), "GROWTH_RESOURCE_VERSION_CREATE", requestId, before, after,
                Map.of("version", nextVersion, "payloadFingerprint", payloadFingerprint,
                        "requestFingerprint", requestFingerprint, "lifecycleState", "DRAFT"));
        return after;
    }

    @Transactional
    public Map<String, Object> activate(Long resourceId, Integer versionNo, Map<String, Object> request) {
        Map<String, Object> before = requireResource(resourceId, true);
        String requestId = requiredRequestId(request);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id,payload_json,valid_from,valid_until,activation_request_id FROM growth_resource_version
                 WHERE tenant_id=? AND resource_id=? AND version_no=? FOR UPDATE
                """, tenant(), resourceId, versionNo);
        if (rows.isEmpty()) throw new BusinessException(404, "资源版本不存在");
        List<Map<String, Object>> activationReplay = jdbcTemplate.queryForList("""
                SELECT resource_id,version_no FROM growth_resource_version
                 WHERE tenant_id=? AND activation_request_id=? FOR UPDATE
                """, tenant(), requestId);
        if (!activationReplay.isEmpty()) {
            if (!resourceId.equals(number(activationReplay.get(0).get("resource_id")))
                    || !versionNo.equals(intValue(activationReplay.get(0).get("version_no"), -1))) {
                throw new BusinessException(409, "requestId 已用于启用其他资源版本，请生成新的请求 ID");
            }
            Map<String, Object> replay = detail(resourceId);
            replay.put("idempotentReplay", true);
            return replay;
        }
        Instant now = Instant.now();
        Instant validFrom = databaseInstant(rows.get(0).get("valid_from"));
        Instant validUntil = databaseInstant(rows.get(0).get("valid_until"));
        if (validFrom != null && validFrom.isAfter(now)) throw new BusinessException(409, "该版本尚未到生效时间");
        if (validUntil != null && !validUntil.isAfter(now)) throw new BusinessException(409, "过期版本不能启用");
        Map<String, Object> versionPayload = jsonMap(text(rows.get(0).get("payload_json")));
        List<Long> versionAccounts = longList(versionPayload.get("accountIds"));
        if (versionAccounts.isEmpty()) versionAccounts = castLongList(before.get("accountIds"));
        if (versionAccounts.isEmpty()) {
            throw new BusinessException(409, "资源版本缺少适用账号，补充账号后才能启用");
        }
        versionAccounts.forEach(accountAccessService::requireAccess);
        if (!text(versionPayload.get("resourceType")).isBlank()
                && !text(before.get("resourceType")).equals(text(versionPayload.get("resourceType")))) {
            throw new BusinessException(409, "资源版本类型与主档不一致，禁止启用");
        }
        jdbcTemplate.update("""
                UPDATE growth_resource_version SET lifecycle_state='ARCHIVED'
                 WHERE tenant_id=? AND resource_id=? AND lifecycle_state='ACTIVE'
                """, tenant(), resourceId);
        jdbcTemplate.update("""
                UPDATE growth_resource_version SET lifecycle_state='ACTIVE',activation_request_id=?,activated_time=NOW(3)
                 WHERE tenant_id=? AND resource_id=? AND version_no=?
                """, requestId, tenant(), resourceId, versionNo);
        jdbcTemplate.update("""
                UPDATE merchant_resource SET name=?,status=?,xianyu_account_id=?,xy_goods_id=?,stock=?,amount=?,data_json=?
                 WHERE tenant_id=? AND id=?
                """, text(versionPayload.get("name")).isBlank() ? before.get("name") : text(versionPayload.get("name")),
                intValue(versionPayload.get("status"), intValue(before.get("status"), 1)), versionAccounts.get(0),
                blank(text(versionPayload.get("goodsId"))), Math.max(0, intValue(versionPayload.get("stock"), intValue(before.get("stock"), 0))),
                storageAmount(text(before.get("resourceType")), versionPayload.get("amount")),
                rows.get(0).get("payload_json"), tenant(), resourceId);
        replaceAccounts(resourceId, versionAccounts);
        replaceGoodsMappings(resourceId, versionAccounts, versionPayload.get("goodsMappings"));
        Map<String, Object> after = detail(resourceId);
        audit(resourceId, firstAccount(after), "GROWTH_RESOURCE_VERSION_ACTIVATE", requestId, before, after,
                Map.of("activeVersion", versionNo));
        return after;
    }

    private Map<String, Object> requireResource(Long resourceId, boolean write) {
        if (resourceId == null) throw new BusinessException(400, "资源 ID 不能为空");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id,resource_type,name,status,xianyu_account_id,xy_goods_id,stock,amount,data_json,
                       created_time,updated_time FROM merchant_resource WHERE tenant_id=? AND id=?
                """, tenant(), resourceId);
        if (rows.isEmpty()) throw new BusinessException(404, "素材或货源不存在");
        Map<String, Object> raw = rows.get(0);
        Long legacyAccount = number(raw.get("xianyu_account_id"));
        List<Long> accounts = accountIds(resourceId, legacyAccount);
        if (!canReadAccounts(accounts) || (write && accounts.stream().anyMatch(id -> !accountAccessService.canAccess(id)))) {
            throw new BusinessException(403, "当前成员无权访问该资源关联的全部账号");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", resourceId);
        result.put("resourceType", text(raw.get("resource_type")));
        result.put("name", text(raw.get("name")));
        result.put("status", intValue(raw.get("status"), 0));
        result.put("accountId", legacyAccount);
        result.put("accountIds", accounts);
        result.put("goodsId", raw.get("xy_goods_id"));
        result.put("stock", intValue(raw.get("stock"), 0));
        Map<String, Object> resourcePayload = jsonMap(text(raw.get("data_json")));
        result.put("amount", exposedAmount(text(raw.get("resource_type")), resourcePayload, raw.get("amount")));
        result.put("payload", resourcePayload);
        result.put("createdTime", databaseInstant(raw.get("created_time")));
        result.put("updatedTime", databaseInstant(raw.get("updated_time")));
        return write ? result : sanitizeResourceForScope(result);
    }

    private void validatePayload(String type, Map<String, Object> request, Map<String, Object> payload) {
        String source = sourceType(request);
        if (!("MANUAL".equals(source) || "LOCAL_DRAFT".equals(source)) && text(request.get("sourceUrl")).isBlank()) {
            throw new BusinessException(400, "非手工资源必须保存可追溯的来源链接");
        }
        authorization(request);
        Instant from = valueInstant(request.get("validFrom"));
        Instant until = valueInstant(request.get("validUntil"));
        if (from != null && until != null && !until.isAfter(from)) throw new BusinessException(400, "有效期结束时间必须晚于开始时间");
        if ("SUPPLY".equals(type)) {
            if (text(request.get("supplierName")).isBlank()) throw new BusinessException(400, "货源必须填写供应商或来源主体");
            if (payload.get("amount") == null) throw new BusinessException(400, "货源必须填写成本或参考价");
            if (text(payload.get("returnPolicy")).isBlank()) throw new BusinessException(400, "货源必须记录退换政策");
            if (text(payload.get("fulfillmentLeadTime")).isBlank()) throw new BusinessException(400, "货源必须记录履约时效");
        }
        if ("MATERIAL".equals(type) && text(request.get("licenseType")).isBlank()) {
            throw new BusinessException(400, "素材必须声明许可类型；无法确认时请选择 UNKNOWN");
        }
    }

    private List<Map<String, Object>> normalizeGoodsMappings(Object value, List<Long> allowedAccounts) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> mappings = new ArrayList<>();
        Set<String> seen = new java.util.LinkedHashSet<>();
        for (Object item : list) {
            Map<String, Object> raw = map(item);
            Long accountId = number(raw.get("accountId"));
            String goodsId = text(raw.get("goodsId"));
            String skuId = text(raw.get("skuId"));
            String status = text(raw.get("status")).toUpperCase();
            if (status.isBlank()) status = "ACTIVE";
            if (accountId == null || !allowedAccounts.contains(accountId)) {
                throw new BusinessException(400, "货源商品映射必须使用资源已关联的账号");
            }
            if (goodsId.isBlank() || goodsId.length() > 64 || skuId.length() > 128) {
                throw new BusinessException(400, "货源商品映射的商品 ID 或 SKU ID 格式无效");
            }
            if (!Set.of("ACTIVE", "INACTIVE").contains(status)) {
                throw new BusinessException(400, "货源商品映射状态仅支持 ACTIVE 或 INACTIVE");
            }
            Instant validFrom = valueInstant(raw.get("validFrom"));
            Instant validUntil = valueInstant(raw.get("validUntil"));
            if (validFrom != null && validUntil != null && !validUntil.isAfter(validFrom)) {
                throw new BusinessException(400, "货源商品映射结束时间必须晚于开始时间");
            }
            String key = accountId + "\u0000" + goodsId + "\u0000" + skuId;
            if (!seen.add(key)) throw new BusinessException(400, "货源商品映射不能重复");
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("accountId", accountId);
            normalized.put("goodsId", goodsId);
            normalized.put("skuId", skuId);
            normalized.put("status", status);
            normalized.put("validFrom", validFrom);
            normalized.put("validUntil", validUntil);
            mappings.add(normalized);
        }
        return mappings;
    }

    private void replaceGoodsMappings(Long resourceId, List<Long> accountIds, Object value) {
        List<Map<String, Object>> mappings = normalizeGoodsMappings(value, accountIds);
        jdbcTemplate.update("DELETE FROM growth_resource_goods_mapping WHERE tenant_id=? AND resource_id=?", tenant(), resourceId);
        for (Map<String, Object> mapping : mappings) {
            jdbcTemplate.update("""
                    INSERT INTO growth_resource_goods_mapping
                    (tenant_id,resource_id,xianyu_account_id,xy_goods_id,sku_id,mapping_status,valid_from,valid_until)
                    VALUES (?,?,?,?,?,?,?,?)
                    """, tenant(), resourceId, mapping.get("accountId"), mapping.get("goodsId"),
                    text(mapping.get("skuId")), mapping.get("status"), timestamp(mapping.get("validFrom")),
                    timestamp(mapping.get("validUntil")));
        }
    }

    private List<Map<String, Object>> goodsMappings(Long resourceId) {
        return jdbcTemplate.query("""
                SELECT id,xianyu_account_id,xy_goods_id,sku_id,mapping_status,valid_from,valid_until,created_time
                  FROM growth_resource_goods_mapping
                 WHERE tenant_id=? AND resource_id=? ORDER BY xianyu_account_id,xy_goods_id,sku_id
                """, (rs, rowNum) -> {
            Map<String, Object> mapping = new LinkedHashMap<>();
            mapping.put("id", rs.getLong("id"));
            mapping.put("accountId", rs.getLong("xianyu_account_id"));
            mapping.put("goodsId", rs.getString("xy_goods_id"));
            mapping.put("skuId", rs.getString("sku_id"));
            mapping.put("status", rs.getString("mapping_status"));
            Instant validFrom = instant(rs.getTimestamp("valid_from"));
            Instant validUntil = instant(rs.getTimestamp("valid_until"));
            mapping.put("validFrom", validFrom);
            mapping.put("validUntil", validUntil);
            mapping.put("validityState", validity(validFrom, validUntil));
            mapping.put("createdTime", instant(rs.getTimestamp("created_time")));
            return mapping;
        }, tenant(), resourceId).stream()
                .filter(mapping -> accountAccessService.canAccess(number(mapping.get("accountId"))))
                .toList();
    }

    private Map<String, Object> versionSummary(Long id, int version, String lifecycle, String fingerprint,
                                               String sourceType, String sourceUrl, String sourceItemId,
                                               Instant capturedAt, String authorization, String licenseType,
                                               String licenseNote, String supplierName, Instant validFrom,
                                               Instant validUntil, String operator, Instant createdAt,
                                               int duplicates, int references, int mappings, String resourceType) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("version", version);
        row.put("lifecycleState", lifecycle);
        row.put("payloadFingerprint", fingerprint);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type", sourceType);
        source.put("url", sourceUrl);
        source.put("itemId", sourceItemId);
        source.put("capturedAt", capturedAt);
        source.put("authorizationStatus", authorization);
        row.put("source", source);
        Map<String, Object> license = new LinkedHashMap<>();
        license.put("type", licenseType);
        license.put("note", licenseNote);
        row.put("license", license);
        row.put("supplierName", supplierName);
        row.put("validFrom", validFrom);
        row.put("validUntil", validUntil);
        row.put("validityState", validity(validFrom, validUntil));
        row.put("operatorUsername", operator);
        row.put("createdTime", createdAt);
        row.put("duplicateCount", duplicates);
        row.put("referenceCount", references);
        row.put("mappingCount", mappings);
        row.put("readiness", readiness(resourceType, lifecycle, authorization, licenseType, validFrom, validUntil, mappings));
        return row;
    }

    private Map<String, Object> readiness(String resourceType, String lifecycle, String authorization, String licenseType,
                                          Instant validFrom, Instant validUntil, int mappings) {
        List<String> blockers = new ArrayList<>();
        if (!"ACTIVE".equals(lifecycle)) blockers.add("版本尚未启用");
        if (authorization == null || "UNKNOWN".equals(authorization)) blockers.add("来源授权未确认");
        if ("MATERIAL".equals(resourceType)
                && (licenseType == null || "UNKNOWN".equalsIgnoreCase(licenseType))) blockers.add("素材许可未确认");
        if ("SUPPLY".equals(resourceType) && mappings == 0) blockers.add("货源尚未映射到商品");
        String validity = validity(validFrom, validUntil);
        if ("EXPIRED".equals(validity)) blockers.add("资源已过期");
        if ("FUTURE".equals(validity)) blockers.add("资源尚未生效");
        return Map.of("ready", blockers.isEmpty(), "blockers", blockers);
    }

    private String validity(Instant from, Instant until) {
        Instant now = Instant.now();
        if (from != null && from.isAfter(now)) return "FUTURE";
        if (until != null && !until.isAfter(now)) return "EXPIRED";
        return until == null ? "NO_EXPIRY" : "ACTIVE";
    }

    private int duplicateCount(String fingerprint, Long resourceId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM growth_resource_version
                 WHERE tenant_id=? AND payload_fingerprint=? AND resource_id<>?
                """, Integer.class, tenant(), fingerprint, resourceId);
        return count == null ? 0 : count;
    }

    private int referenceCount(Long resourceId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM merchant_distribution
                 WHERE tenant_id=? AND (supply_resource_id=? OR material_resource_id=?)
                """, Integer.class, tenant(), resourceId, resourceId);
        return count == null ? 0 : count;
    }

    private int mappingCount(Long resourceId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM growth_resource_goods_mapping
                 WHERE tenant_id=? AND resource_id=? AND mapping_status='ACTIVE'
                   AND (valid_from IS NULL OR valid_from<=NOW(3))
                   AND (valid_until IS NULL OR valid_until>NOW(3))
                """, Integer.class, tenant(), resourceId);
        return count == null ? 0 : count;
    }

    private void replaceAccounts(Long resourceId, List<Long> accountIds) {
        jdbcTemplate.update("DELETE FROM merchant_resource_account WHERE tenant_id=? AND resource_id=?", tenant(), resourceId);
        for (Long accountId : accountIds) {
            jdbcTemplate.update("""
                    INSERT INTO merchant_resource_account(resource_id,tenant_id,xianyu_account_id) VALUES (?,?,?)
                    """, resourceId, tenant(), accountId);
        }
    }

    private List<Long> accountIds(Long resourceId, Long fallback) {
        List<Long> ids = jdbcTemplate.queryForList("""
                SELECT xianyu_account_id FROM merchant_resource_account
                 WHERE tenant_id=? AND resource_id=? ORDER BY xianyu_account_id
                """, Long.class, tenant(), resourceId);
        return ids.isEmpty() && fallback != null ? List.of(fallback) : ids;
    }

    private boolean canReadAccounts(List<Long> accountIds) {
        AccountScopeContext.Scope scope = AccountScopeContext.get();
        if (scope == null || scope.unrestricted()) return true;
        return accountIds.stream().anyMatch(scope.accountIds()::contains);
    }

    private Map<String, Object> sanitizeResourceForScope(Map<String, Object> resource) {
        List<Long> visible = visibleAccountIds(castLongList(resource.get("accountIds")));
        Map<String, Object> sanitized = new LinkedHashMap<>(resource);
        sanitized.put("accountIds", visible);
        Long primary = number(resource.get("accountId"));
        sanitized.put("accountId", primary != null && accountAccessService.canAccess(primary)
                ? primary : (visible.isEmpty() ? null : visible.get(0)));
        sanitized.put("payload", sanitizePayloadForScope(map(resource.get("payload"))));
        return sanitized;
    }

    private Map<String, Object> sanitizePayloadForScope(Map<String, Object> payload) {
        Map<String, Object> sanitized = new LinkedHashMap<>(payload);
        if (payload.containsKey("accountIds")) {
            sanitized.put("accountIds", visibleAccountIds(longList(payload.get("accountIds"))));
        }
        Object mappingsValue = payload.get("goodsMappings");
        if (mappingsValue instanceof List<?> mappings) {
            sanitized.put("goodsMappings", mappings.stream().map(this::map)
                    .filter(mapping -> accountAccessService.canAccess(number(mapping.get("accountId"))))
                    .toList());
        }
        return sanitized;
    }

    private List<Long> visibleAccountIds(List<Long> accountIds) {
        AccountScopeContext.Scope scope = AccountScopeContext.get();
        if (scope == null || scope.unrestricted()) return accountIds;
        return accountIds.stream().filter(scope.accountIds()::contains).toList();
    }

    private Long firstAccount(Map<String, Object> resource) {
        List<Long> ids = castLongList(resource.get("accountIds"));
        return ids.isEmpty() ? number(resource.get("accountId")) : ids.get(0);
    }

    private void audit(Long resourceId, Long accountId, String type, String requestId,
                       Object before, Object after, Object diff) {
        XianyuOperationLog log = new XianyuOperationLog();
        log.setOperatorUserId(UserContext.getUserId());
        log.setOperatorUsername(UserContext.getUsername());
        log.setXianyuAccountId(accountId);
        log.setOperationType(type);
        log.setOperationModule("GROWTH_RESOURCE");
        log.setOperationDesc("素材/货源版本已安全保存并保留来源证据");
        log.setOperationStatus(1);
        log.setTargetType("GROWTH_RESOURCE");
        log.setTargetId(String.valueOf(resourceId));
        log.setRequestId(requestId);
        log.setIdempotencyKey(requestId);
        log.setOutcomeState("LOCAL_SUCCESS");
        log.setDataSource("LOCAL");
        log.setRequestParams(json(singleValue("before", before)));
        log.setResponseResult(json(singleValue("after", after)));
        log.setFieldDiffJson(json(diff));
        log.setCreateTime(System.currentTimeMillis());
        operationLogService.logRequired(log);
    }

    private String type(String value) {
        String normalized = text(value).toUpperCase();
        if (!TYPES.contains(normalized)) throw new BusinessException(400, "仅支持 MATERIAL 或 SUPPLY 版本资源");
        return normalized;
    }

    private String sourceType(Map<String, Object> request) {
        String source = text(request.get("sourceType")).toUpperCase();
        if (source.isBlank()) source = "MANUAL";
        if (!SOURCE_TYPES.contains(source)) throw new BusinessException(400, "来源类型无效");
        return source;
    }

    private String authorization(Map<String, Object> request) {
        String state = text(request.get("authorizationStatus")).toUpperCase();
        if (state.isBlank()) state = "UNKNOWN";
        if (!AUTHORIZATION_STATES.contains(state)) throw new BusinessException(400, "来源授权状态无效");
        return state;
    }

    private String requiredRequestId(Map<String, Object> request) {
        String value = text(request.get("requestId"));
        if (value.isBlank() || value.length() > 64) throw new BusinessException(400, "必须提供不超过64个字符的 requestId");
        return value;
    }

    private Long tenant() {
        Long tenant = TenantContext.get();
        if (tenant == null) tenant = UserContext.getTenantId();
        if (tenant == null) throw new IllegalStateException("缺少租户上下文");
        return tenant;
    }

    private String fingerprint(Object value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(json(canonical(value)).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("无法生成资源指纹", e);
        }
    }

    private Object canonical(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> sorted = new TreeMap<>();
            source.forEach((key, item) -> sorted.put(String.valueOf(key), canonical(item)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(this::canonical).toList();
        if (value instanceof BigDecimal decimal) return decimal.stripTrailingZeros().toPlainString();
        return value;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            throw new IllegalArgumentException("资源数据无法序列化", e);
        }
    }

    private Map<String, Object> jsonMap(String value) {
        if (value == null || value.isBlank()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private Map<String, Object> map(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> source) source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private List<Long> longList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(this::number).filter(java.util.Objects::nonNull).distinct().sorted().toList();
    }

    @SuppressWarnings("unchecked")
    private List<Long> castLongList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(this::number).filter(java.util.Objects::nonNull).toList();
    }

    private Long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return value == null || text(value).isBlank() ? null : Long.parseLong(text(value)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? fallback : Integer.parseInt(text(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private BigDecimal money(Object value) {
        try {
            BigDecimal result = value instanceof BigDecimal decimal ? decimal : new BigDecimal(text(value));
            if (result.signum() < 0 || result.scale() > 2) throw new NumberFormatException();
            return result;
        } catch (Exception e) {
            throw new BusinessException(400, "金额必须是非负且最多两位小数");
        }
    }

    /** merchant_resource.amount is a legacy NOT NULL column, so an omitted
     * material price uses an internal sentinel. The API only exposes that
     * value when the payload proves that the operator explicitly entered it. */
    BigDecimal storageAmount(String resourceType, Object value) {
        if ("MATERIAL".equals(resourceType)
                && (value == null || text(value).isBlank())) return BigDecimal.ZERO;
        return money(value);
    }

    Object exposedAmount(String resourceType, Map<String, Object> payload, Object storedValue) {
        if ("MATERIAL".equals(resourceType) && (payload == null || !payload.containsKey("amount"))) return null;
        return storedValue;
    }

    private Timestamp timestamp(Object value) {
        Instant instant = valueInstant(value);
        return instant == null ? null : Timestamp.from(instant);
    }

    private Instant valueInstant(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value == null || text(value).isBlank()) return null;
        try { return Instant.parse(text(value)); }
        catch (Exception ignored) {
            try { return LocalDateTime.parse(text(value)).atZone(ZoneId.systemDefault()).toInstant(); }
            catch (Exception e) { throw new BusinessException(400, "时间格式无效，请使用 ISO 时间"); }
        }
    }

    private Instant databaseInstant(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof LocalDateTime local) return local.atZone(ZoneId.systemDefault()).toInstant();
        return valueInstant(value);
    }

    private Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private Map<String, Object> singleValue(String key, Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(key, value);
        return result;
    }
    private Long nullableLong(Object value) { return value instanceof Number number ? number.longValue() : null; }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private String blank(String value) { return value == null || value.isBlank() ? null : value; }
}
