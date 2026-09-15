package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/** 统一写入商品时间线并同步商品记录的来源证据。 */
@Service
public class ProductEventService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ProductEventService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
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
