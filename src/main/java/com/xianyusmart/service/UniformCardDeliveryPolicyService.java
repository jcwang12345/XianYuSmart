package com.xianyusmart.service;

import com.xianyusmart.context.TenantContext;
import com.xianyusmart.entity.XianyuGoodsAutoDeliveryConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Explicit merchant consent for a single SKU-independent card pool. This is not
 * evidence that the platform item has no SKUs or that its SKU snapshot is FULL.
 * The caller owns access checks, account locking and audit in its transaction.
 */
@Service
public class UniformCardDeliveryPolicyService {
    private static final String KEY_PREFIX = "uniform_card_delivery:";
    private static final String VALUE_PREFIX = "v1:";

    private final JdbcTemplate jdbc;

    public UniformCardDeliveryPolicyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Persist or revoke explicit consent for this config only, inside the caller's transaction. */
    @Transactional
    public void configure(Long tenantId, XianyuGoodsAutoDeliveryConfig config, boolean enabled) {
        requireTenant(tenantId);
        requireIdentity(config);
        String key = key(config.getId());
        if (enabled) {
            String value = VALUE_PREFIX + fingerprint(validate(tenantId,
                    config.getXianyuAccountId(), config.getXyGoodsId(), config));
            List<String> stored = settings(tenantId, key, true);
            if (stored.size() > 1) throw new IllegalStateException("通用卡密授权记录不唯一");
            if (stored.isEmpty()) {
                jdbc.update("""
                        INSERT INTO xianyu_sys_setting (tenant_id, setting_key, setting_value, setting_desc)
                        VALUES (?, ?, ?, '商家明确授权：本商品所有规格交付同一权益卡密')
                        """, tenantId, key, value);
            } else if (!value.equals(stored.getFirst())) {
                int updated = jdbc.update("""
                        UPDATE xianyu_sys_setting SET setting_value=?
                        WHERE tenant_id=? AND setting_key=?
                        """, value, tenantId, key);
                if (updated != 1) throw new IllegalStateException("通用卡密授权更新冲突");
            }
            return;
        }
        // Revocation must still be scoped to the persisted config identity, but
        // must work after a pool/channel change has made the old consent invalid.
        List<Map<String, Object>> owned = jdbc.queryForList("""
                SELECT id FROM xianyu_goods_auto_delivery_config
                WHERE tenant_id=? AND id=? AND xianyu_account_id=? AND xy_goods_id=?
                """, tenantId, config.getId(), config.getXianyuAccountId(), config.getXyGoodsId());
        if (owned.size() != 1) throw new IllegalStateException("发货配置不存在或不属于当前账号商品");
        if (settings(tenantId, key, true).size() > 1) throw new IllegalStateException("通用卡密授权记录不唯一");
        jdbc.update("DELETE FROM xianyu_sys_setting WHERE tenant_id=? AND setting_key=?", tenantId, key);
    }

    /** Fail closed on absent consent, context, ownership, resource or mapping conflicts. */
    public boolean permits(Long accountId, String goodsId, XianyuGoodsAutoDeliveryConfig config) {
        Long tenantId = TenantContext.get();
        if (tenantId == null || tenantId <= 0 || config == null || config.getId() == null
                || !Objects.equals(accountId, config.getXianyuAccountId())
                || !Objects.equals(goodsId, config.getXyGoodsId())) return false;
        try {
            String current = VALUE_PREFIX + fingerprint(validate(tenantId, accountId, goodsId, config));
            List<String> stored = settings(tenantId, key(config.getId()), false);
            return stored.size() == 1 && current.equals(stored.getFirst());
        } catch (RuntimeException invalidOrUnavailable) {
            return false;
        }
    }

    private String validate(Long tenantId, Long accountId, String goodsId,
                            XianyuGoodsAutoDeliveryConfig supplied) {
        requireTenant(tenantId);
        requireIdentity(supplied);
        if (!Objects.equals(accountId, supplied.getXianyuAccountId())
                || !Objects.equals(goodsId, supplied.getXyGoodsId())) {
            throw new IllegalStateException("发货配置与账号商品不一致");
        }
        List<Map<String, Object>> accounts = jdbc.queryForList(
                "SELECT status FROM xianyu_account WHERE tenant_id=? AND id=?", tenantId, accountId);
        if (accounts.size() != 1 || !Long.valueOf(1).equals(number(accounts.getFirst(), "status"))) {
            throw new IllegalStateException("账号不存在、已停用或租户不一致");
        }
        List<Map<String, Object>> goods = jdbc.queryForList("""
                SELECT id FROM xianyu_goods
                WHERE tenant_id=? AND xianyu_account_id=? AND xy_good_id=?
                """, tenantId, accountId, goodsId);
        if (goods.size() != 1) throw new IllegalStateException("商品不属于当前账号租户");

        List<Map<String, Object>> mappings = jdbc.queryForList("""
                SELECT id, xianyu_goods_id, delivery_mode, sku_id, kami_config_ids,
                       voucher_delivery_enabled, chat_delivery_enabled
                FROM xianyu_goods_auto_delivery_config
                WHERE tenant_id=? AND xianyu_account_id=? AND xy_goods_id=?
                """, tenantId, accountId, goodsId);
        if (mappings.size() != 1) throw new IllegalStateException("商品存在其他规格或通用发货映射");
        Map<String, Object> mapping = mappings.getFirst();
        if (!Objects.equals(number(mapping, "id"), supplied.getId())
                || !Objects.equals(number(mapping, "xianyu_goods_id"), number(goods.getFirst(), "id"))
                || !Objects.equals(supplied.getXianyuGoodsId(), number(goods.getFirst(), "id"))
                || !Long.valueOf(2).equals(number(mapping, "delivery_mode"))
                || !Integer.valueOf(2).equals(supplied.getDeliveryMode())
                || mapping.get("sku_id") != null || supplied.getSkuId() != null
                || !validChannels(mapping, supplied)) {
            throw new IllegalStateException("通用卡密配置模式、渠道或商品映射无效");
        }
        List<Long> poolIds = canonicalPoolIds(text(mapping, "kami_config_ids"));
        if (!poolIds.equals(canonicalPoolIds(supplied.getKamiConfigIds()))) {
            throw new IllegalStateException("卡密仓库配置已变化");
        }
        List<String> poolVersions = new ArrayList<>();
        for (Long poolId : poolIds) {
            List<Map<String, Object>> linked = jdbc.queryForList("""
                    SELECT config.id, config.config_version, config.source_type
                    FROM xianyu_kami_config config
                    JOIN xianyu_kami_config_account link
                      ON link.kami_config_id=config.id AND link.tenant_id=config.tenant_id
                    WHERE config.id=? AND config.tenant_id=?
                      AND link.xianyu_account_id=?
                    """, poolId, tenantId, accountId);
            if (linked.size() != 1) throw new IllegalStateException("卡密仓库不存在或未共享给当前账号");
            Long version = number(linked.getFirst(), "config_version");
            String source = text(linked.getFirst(), "source_type");
            if (version == null || version <= 0 || !"LOCAL".equals(source)) {
                throw new IllegalStateException("商品统一卡密目前仅支持有效的本地卡密仓库");
            }
            poolVersions.add(poolId + ":" + version + ":" + source);
        }
        return encode(tenantId, supplied.getId(), accountId, goodsId, 2, "NULL",
                poolVersions, number(mapping, "voucher_delivery_enabled"),
                number(mapping, "chat_delivery_enabled"));
    }

    private boolean validChannels(Map<String, Object> mapping, XianyuGoodsAutoDeliveryConfig supplied) {
        Long voucher = number(mapping, "voucher_delivery_enabled");
        Long chat = number(mapping, "chat_delivery_enabled");
        return (Long.valueOf(0).equals(voucher) || Long.valueOf(1).equals(voucher))
                && (Long.valueOf(0).equals(chat) || Long.valueOf(1).equals(chat))
                && (Long.valueOf(1).equals(voucher) || Long.valueOf(1).equals(chat))
                && Objects.equals(voucher, longValue(supplied.getVoucherDeliveryEnabled()))
                && Objects.equals(chat, longValue(supplied.getChatDeliveryEnabled()));
    }

    private List<Long> canonicalPoolIds(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalStateException("通用卡密未绑定仓库");
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (String part : raw.split(",", -1)) {
            String id = part.trim();
            if (!id.matches("[1-9][0-9]*")) throw new IllegalStateException("卡密仓库编号无效");
            try {
                if (!ids.add(Long.parseLong(id))) throw new IllegalStateException("卡密仓库编号重复");
            } catch (NumberFormatException invalid) {
                throw new IllegalStateException("卡密仓库编号无效", invalid);
            }
        }
        return new ArrayList<>(ids);
    }

    private List<String> settings(Long tenantId, String key, boolean lock) {
        return jdbc.queryForList("""
                SELECT setting_value FROM xianyu_sys_setting
                WHERE tenant_id=? AND setting_key=?
                """ + (lock ? " FOR UPDATE" : ""), String.class, tenantId, key);
    }

    private static String encode(Object... values) {
        StringBuilder result = new StringBuilder();
        for (Object value : values) {
            String text = String.valueOf(value);
            result.append(text.length()).append(':').append(text);
        }
        return result.toString();
    }

    private static String fingerprint(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    private static String key(Long configId) {
        if (configId == null || configId <= 0) throw new IllegalArgumentException("发货配置ID无效");
        return KEY_PREFIX + configId;
    }

    private static void requireTenant(Long tenantId) {
        if (tenantId == null || tenantId <= 0 || !tenantId.equals(TenantContext.get())) {
            throw new IllegalStateException("缺少一致的租户上下文");
        }
    }

    private static void requireIdentity(XianyuGoodsAutoDeliveryConfig config) {
        if (config == null || config.getId() == null || config.getId() <= 0
                || config.getXianyuAccountId() == null || config.getXianyuAccountId() <= 0
                || config.getXyGoodsId() == null || config.getXyGoodsId().isBlank()) {
            throw new IllegalArgumentException("发货配置账号商品身份无效");
        }
    }

    private static Long number(Map<String, Object> row, String field) {
        Object value = row.get(field);
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Long longValue(Integer value) {
        return value == null ? null : value.longValue();
    }

    private static String text(Map<String, Object> row, String field) {
        Object value = row.get(field);
        return value == null ? null : value.toString();
    }
}
