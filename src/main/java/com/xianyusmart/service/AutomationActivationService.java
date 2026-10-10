package com.xianyusmart.service;

import com.xianyusmart.entity.XianyuGoodsOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Stable, restart-safe enrollment boundary: enabling engagement never backfills historical orders. */
@Service
public class AutomationActivationService {
    private static final Set<String> KINDS = Set.of("AUTO_RATE", "REVIEW_INVITE");
    private static final DateTimeFormatter TIME = new DateTimeFormatterBuilder()
            .appendPattern("uuuu-MM-dd HH:mm:ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
            .toFormatter().withResolverStyle(java.time.format.ResolverStyle.STRICT);
    private final JdbcTemplate jdbc;

    public AutomationActivationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Called before polling; the first observed enabled configuration enrolls, not the first completed order. */
    @Transactional
    public void initializeEnabled() {
        List<Map<String, Object>> enabled = jdbc.queryForList("""
                SELECT tenant_id,xianyu_account_id,xy_goods_id,'AUTO_RATE' AS kind
                  FROM xianyu_goods_config WHERE xianyu_auto_rate_on IN (1,2)
                UNION
                SELECT tenant_id,xianyu_account_id,xy_goods_id,'REVIEW_INVITE' AS kind
                  FROM xianyu_goods_auto_delivery_config
                 WHERE receipt_follow_up_messages IS NOT NULL
                   AND receipt_follow_up_messages NOT IN ('','[]')
                ORDER BY tenant_id,xianyu_account_id,xy_goods_id,kind
                """);
        for (Map<String, Object> row : enabled) {
            ensureActivated(String.valueOf(row.get("kind")), ((Number) row.get("tenant_id")).longValue(),
                    ((Number) row.get("xianyu_account_id")).longValue(), String.valueOf(row.get("xy_goods_id")));
        }
    }

    @Transactional
    public void ensureActivated(String kind, Long tenant, Long account, String goods) {
        String key = key(kind, account, goods);
        if (tenant == null) throw new IllegalArgumentException("缺少自动化租户");
        // Same account lock as engagement admission. Scope every raw SQL explicitly to this tenant.
        jdbc.queryForObject("SELECT id FROM xianyu_account WHERE tenant_id=? AND id=? FOR UPDATE",
                Long.class, tenant, account);
        if (!jdbc.queryForList("SELECT setting_value FROM xianyu_sys_setting WHERE tenant_id=? AND setting_key=? FOR UPDATE",
                tenant, key).isEmpty()) return;
        Long watermark = jdbc.queryForObject("""
                SELECT COALESCE(MAX(id),0) FROM xianyu_goods_order
                 WHERE tenant_id=? AND xianyu_account_id=? AND xy_goods_id=?
                """, Long.class, tenant, account, goods);
        Timestamp now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)", Timestamp.class);
        if (now == null || watermark == null) throw new IllegalStateException("无法确定自动化生效边界");
        jdbc.update("""
                INSERT INTO xianyu_sys_setting (tenant_id,setting_key,setting_value,setting_desc)
                VALUES (?,?,?,'评价与收货邀评首次启用边界；不补发历史订单')
                """, tenant, key, now.toLocalDateTime().toString() + "|" + watermark);
    }

    public boolean allowsOrder(String kind, XianyuGoodsOrder order) {
        if (order == null || order.getTenantId() == null || order.getId() == null) return false;
        String key = key(kind, order.getXianyuAccountId(), order.getXyGoodsId());
        List<String> values = jdbc.queryForList("""
                SELECT setting_value FROM xianyu_sys_setting WHERE tenant_id=? AND setting_key=?
                """, String.class, order.getTenantId(), key);
        if (values.size() != 1) return false;
        try {
            String[] boundary = values.getFirst().split("\\|", -1);
            if (boundary.length != 2) return false;
            LocalDateTime since = LocalDateTime.parse(boundary[0]);
            long watermark = Long.parseLong(boundary[1]);
            LocalDateTime localCreated = parseTime(order.getCreateTime());
            LocalDateTime platformCreated = parseTime(order.getOrderCreateTime());
            // Late-imported historical orders and unverified timestamps must not become eligible.
            return watermark >= 0 && order.getId() > watermark
                    && localCreated != null && localCreated.isAfter(since)
                    && platformCreated != null && platformCreated.isAfter(since);
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    static LocalDateTime parseTime(String value) {
        if (value == null || value.isBlank()) return null;
        try { return LocalDateTime.parse(value.trim().replace('T', ' '), TIME); }
        catch (RuntimeException invalid) { return null; }
    }

    private static String key(String kind, Long account, String goods) {
        if (!KINDS.contains(kind) || account == null || account <= 0 || goods == null
                || !goods.matches("[A-Za-z0-9_-]{1,40}")) {
            throw new IllegalArgumentException("自动化生效范围不合法");
        }
        return "engagement_since:" + kind + ":" + account + ":" + goods;
    }
}
