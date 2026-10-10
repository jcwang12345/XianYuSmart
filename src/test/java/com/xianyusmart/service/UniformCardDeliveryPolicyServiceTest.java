package com.xianyusmart.service;

import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.entity.XianyuGoodsAutoDeliveryConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UniformCardDeliveryPolicyServiceTest {
    private static final long TENANT = 7L;
    private static final long ACCOUNT = 11L;
    private static final long CONFIG = 19L;
    private static final String GOODS = "item-23";
    private static final String KEY = "uniform_card_delivery:" + CONFIG;

    private FakeJdbc jdbc;
    private UniformCardDeliveryPolicyService policy;
    private XianyuGoodsAutoDeliveryConfig config;

    @BeforeEach
    void setUp() {
        UserContext.clear();
        TenantContext.set(TENANT);
        jdbc = new FakeJdbc();
        policy = new UniformCardDeliveryPolicyService(jdbc);
        config = new XianyuGoodsAutoDeliveryConfig();
        config.setId(CONFIG);
        config.setXianyuAccountId(ACCOUNT);
        config.setXianyuGoodsId(31L);
        config.setXyGoodsId(GOODS);
        config.setDeliveryMode(2);
        config.setSkuId(null);
        config.setKamiConfigIds("101, 102");
        config.setVoucherDeliveryEnabled(1);
        config.setChatDeliveryEnabled(0);
        jdbc.mapping.put("id", CONFIG);
        jdbc.mapping.put("xianyu_goods_id", 31L);
        jdbc.mapping.put("delivery_mode", 2);
        jdbc.mapping.put("sku_id", null);
        jdbc.mapping.put("kami_config_ids", "101,102");
        jdbc.mapping.put("voucher_delivery_enabled", 1);
        jdbc.mapping.put("chat_delivery_enabled", 0);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
        UserContext.clear();
    }

    @Test
    void explicitConsentPermitsOneOwnedMappingWithoutPretendingSkuCompleteness() {
        assertFalse(policy.permits(ACCOUNT, GOODS, config));
        policy.configure(TENANT, config, true);
        assertTrue(jdbc.values(TENANT, KEY).getFirst().matches("v1:[0-9a-f]{64}"));
        assertTrue(policy.permits(ACCOUNT, GOODS, config));
        assertEquals(1, jdbc.settings.size());
        // Changing only whitespace does not change the canonical ordered pool list.
        jdbc.mapping.put("kami_config_ids", "101, 102");
        assertTrue(policy.permits(ACCOUNT, GOODS, config));
    }

    @Test
    void missingTenantAndInactiveAccountFailClosed() {
        policy.configure(TENANT, config, true);
        TenantContext.clear();
        assertFalse(policy.permits(ACCOUNT, GOODS, config));
        assertThrows(IllegalStateException.class, () -> policy.configure(TENANT, config, true));
        TenantContext.set(TENANT);
        jdbc.accountActive = false;
        assertFalse(policy.permits(ACCOUNT, GOODS, config));
    }

    @Test
    void accountGoodsAndTenantMustMatchTheExactOwnedConfig() {
        policy.configure(TENANT, config, true);
        assertFalse(policy.permits(ACCOUNT + 1, GOODS, config));
        assertFalse(policy.permits(ACCOUNT, "another-item", config));
        TenantContext.set(TENANT + 1);
        assertFalse(policy.permits(ACCOUNT, GOODS, config));
        TenantContext.set(TENANT);
        config.setXianyuGoodsId(99L);
        assertFalse(policy.permits(ACCOUNT, GOODS, config));
    }

    @Test
    void otherMappingsAndChangesToResourcesInvalidateConsent() {
        policy.configure(TENANT, config, true);
        jdbc.otherMapping = true;
        assertFalse(policy.permits(ACCOUNT, GOODS, config));
        jdbc.otherMapping = false;
        jdbc.mapping.put("kami_config_ids", "102,101");
        config.setKamiConfigIds("102,101");
        assertFalse(policy.permits(ACCOUNT, GOODS, config));
        policy.configure(TENANT, config, true);
        assertTrue(policy.permits(ACCOUNT, GOODS, config));
        jdbc.linkedPools = Set.of(101L);
        assertFalse(policy.permits(ACCOUNT, GOODS, config));
    }

    @Test
    void channelOrPoolVersionAndSourceChangesRequireFreshExplicitConsent() {
        policy.configure(TENANT, config, true);
        jdbc.mapping.put("voucher_delivery_enabled", 0);
        jdbc.mapping.put("chat_delivery_enabled", 1);
        config.setVoucherDeliveryEnabled(0);
        config.setChatDeliveryEnabled(1);
        assertFalse(policy.permits(ACCOUNT, GOODS, config));
        policy.configure(TENANT, config, true);
        assertTrue(policy.permits(ACCOUNT, GOODS, config));
        jdbc.poolVersion = 2L;
        assertFalse(policy.permits(ACCOUNT, GOODS, config));
        policy.configure(TENANT, config, true);
        jdbc.poolSource = "API";
        assertFalse(policy.permits(ACCOUNT, GOODS, config));
        assertThrows(IllegalStateException.class, () -> policy.configure(TENANT, config, true));
    }

    @Test
    void malformedOrRepeatedPoolAndMissingChannelCannotBeEnrolled() {
        config.setKamiConfigIds("101,101");
        assertThrows(IllegalStateException.class, () -> policy.configure(TENANT, config, true));
        config.setKamiConfigIds("101,102");
        jdbc.mapping.put("voucher_delivery_enabled", 0);
        config.setVoucherDeliveryEnabled(0);
        assertThrows(IllegalStateException.class, () -> policy.configure(TENANT, config, true));
        jdbc.mapping.put("voucher_delivery_enabled", 1);
        config.setVoucherDeliveryEnabled(1);
        jdbc.mapping.put("sku_id", "real-sku");
        assertThrows(IllegalStateException.class, () -> policy.configure(TENANT, config, true));
    }

    @Test
    void revokeDeletesOnlyCurrentTenantConfigAndDuplicateSettingFailsClosed() {
        policy.configure(TENANT, config, true);
        jdbc.settings.put("8:" + KEY, new ArrayList<>(List.of("foreign")));
        policy.configure(TENANT, config, false);
        assertFalse(policy.permits(ACCOUNT, GOODS, config));
        assertEquals(List.of("foreign"), jdbc.values(8L, KEY));
        jdbc.settings.put(TENANT + ":" + KEY, new ArrayList<>(List.of("v1:a", "v1:b")));
        assertFalse(policy.permits(ACCOUNT, GOODS, config));
        assertThrows(IllegalStateException.class, () -> policy.configure(TENANT, config, true));
    }

    private static class FakeJdbc extends JdbcTemplate {
        final Map<String, Object> mapping = new HashMap<>();
        final Map<String, List<String>> settings = new HashMap<>();
        Set<Long> linkedPools = Set.of(101L, 102L);
        boolean accountActive = true;
        boolean otherMapping;
        long poolVersion = 1L;
        String poolSource = "LOCAL";

        List<String> values(Long tenant, String key) {
            return settings.getOrDefault(tenant + ":" + key, List.of());
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (sql.contains("FROM xianyu_account WHERE")) {
                return Long.valueOf(TENANT).equals(args[0]) && Long.valueOf(ACCOUNT).equals(args[1])
                        ? List.of(Map.of("status", accountActive ? 1 : -1)) : List.of();
            }
            if (sql.contains("FROM xianyu_goods\n")) {
                return Long.valueOf(TENANT).equals(args[0]) && Long.valueOf(ACCOUNT).equals(args[1])
                        && GOODS.equals(args[2]) ? List.of(Map.of("id", 31L)) : List.of();
            }
            if (sql.contains("FROM xianyu_goods_auto_delivery_config")) {
                if (sql.contains("AND id=?")) {
                    return Long.valueOf(TENANT).equals(args[0]) && Long.valueOf(CONFIG).equals(args[1])
                            && Long.valueOf(ACCOUNT).equals(args[2]) && GOODS.equals(args[3])
                            ? List.of(Map.of("id", CONFIG)) : List.of();
                }
                if (!Long.valueOf(TENANT).equals(args[0]) || !Long.valueOf(ACCOUNT).equals(args[1])
                        || !GOODS.equals(args[2])) return List.of();
                return otherMapping ? List.of(mapping, Map.of("id", 99L)) : List.of(mapping);
            }
            if (sql.contains("FROM xianyu_kami_config config")) {
                return Long.valueOf(TENANT).equals(args[1]) && Long.valueOf(ACCOUNT).equals(args[2])
                        && linkedPools.contains(args[0])
                        ? List.of(Map.of("id", args[0], "config_version", poolVersion,
                                "source_type", poolSource)) : List.of();
            }
            throw new AssertionError("Unexpected SQL: " + sql);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
            if (!sql.contains("FROM xianyu_sys_setting")) throw new AssertionError("Unexpected SQL: " + sql);
            return (List<T>) values((Long) args[0], (String) args[1]);
        }

        @Override
        public int update(String sql, Object... args) {
            if (sql.contains("INSERT INTO xianyu_sys_setting")) {
                settings.put(args[0] + ":" + args[1], new ArrayList<>(List.of((String) args[2])));
                return 1;
            }
            if (sql.contains("UPDATE xianyu_sys_setting")) {
                settings.put(args[1] + ":" + args[2], new ArrayList<>(List.of((String) args[0])));
                return 1;
            }
            if (sql.contains("DELETE FROM xianyu_sys_setting")) {
                settings.remove(args[0] + ":" + args[1]);
                return 1;
            }
            throw new AssertionError("Unexpected SQL: " + sql);
        }
    }
}
