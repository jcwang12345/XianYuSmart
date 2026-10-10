package com.xianyusmart.service.impl;

import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.entity.XianyuGoodsAutoDeliveryConfig;
import com.xianyusmart.entity.XianyuKamiConfig;
import com.xianyusmart.entity.XianyuKamiItem;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.mapper.XianyuKamiConfigMapper;
import com.xianyusmart.mapper.XianyuKamiItemMapper;
import com.xianyusmart.service.ExternalKamiProvisionService;
import com.xianyusmart.service.KamiConfigService;
import com.xianyusmart.service.UniformCardDeliveryPolicyService;
import com.xianyusmart.service.delivery.DeliveryContext;
import com.xianyusmart.service.delivery.DeliveryExecution;
import com.xianyusmart.service.delivery.KamiDeliveryStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KamiReservationTest {
    private static final long TENANT = 7L;
    private static final long ACCOUNT = 11L;
    private static final long POOL = 101L;
    private static final String ORDER = "order-9";

    private KamiConfigServiceImpl service;
    private FakeJdbc jdbc;
    private XianyuKamiItemMapper items;
    private XianyuKamiConfigMapper configs;
    private ExternalKamiProvisionService external;
    private UniformCardDeliveryPolicyService uniform;
    private XianyuGoodsAutoDeliveryConfig deliveryConfig;

    @BeforeEach
    void setUp() {
        UserContext.clear();
        TenantContext.set(TENANT);
        jdbc = new FakeJdbc();
        items = mock(XianyuKamiItemMapper.class);
        configs = mock(XianyuKamiConfigMapper.class);
        external = mock(ExternalKamiProvisionService.class);
        uniform = mock(UniformCardDeliveryPolicyService.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(ignored -> mock(TransactionStatus.class));
        service = new KamiConfigServiceImpl();
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbc);
        ReflectionTestUtils.setField(service, "kamiItemMapper", items);
        ReflectionTestUtils.setField(service, "kamiConfigMapper", configs);
        ReflectionTestUtils.setField(service, "externalKamiProvisionService", external);
        ReflectionTestUtils.setField(service, "uniformCardDeliveryPolicyService", uniform);
        ReflectionTestUtils.setField(service, "transactionManager", manager);
        deliveryConfig = new XianyuGoodsAutoDeliveryConfig();
        deliveryConfig.setId(201L);
        deliveryConfig.setXianyuAccountId(ACCOUNT);
        deliveryConfig.setXyGoodsId("goods-5");
        deliveryConfig.setDeliveryMode(2);
        deliveryConfig.setKamiConfigIds("101");
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        UserContext.clear();
    }

    @Test
    void absentTenantOrRevokedSharedLinkStopsBeforeAnySupply() {
        TenantContext.clear();
        assertEquals(403, assertThrows(BusinessException.class,
                () -> service.reserveKami(POOL, ORDER, ACCOUNT, 1)).getCode());
        verifyNoInteractions(items, external);
        TenantContext.set(TENANT);
        jdbc.linked = false;
        assertEquals(403, assertThrows(BusinessException.class,
                () -> service.reserveKami(POOL, ORDER, ACCOUNT, 1)).getCode());
        verifyNoInteractions(items, external);
    }

    @Test
    void anotherPoolReservedDeliveredOrReviewCardIsHardConflict() {
        for (int status : List.of(1, 2, 3)) {
            when(items.lockFulfilledByOrderAcrossPools(TENANT, ACCOUNT, ORDER))
                    .thenReturn(List.of(card(202L, status)));
            assertEquals(409, assertThrows(BusinessException.class,
                    () -> service.reserveKami(POOL, ORDER, ACCOUNT, 1)).getCode());
        }
        verifyNoInteractions(external);
        verify(items, never()).lockAvailable(eq(POOL), anyInt());
    }

    @Test
    void samePoolExistingReservedQuantityIsIdempotent() {
        XianyuKamiItem reserved = card(POOL, 2);
        when(items.lockFulfilledByOrderAcrossPools(TENANT, ACCOUNT, ORDER))
                .thenReturn(List.of(reserved));
        assertEquals(List.of(reserved), service.reserveKami(POOL, ORDER, ACCOUNT, 1));
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.reserveKami(POOL, ORDER, ACCOUNT, 2)).getCode());
        verifyNoInteractions(external);
        verify(items, never()).lockAvailable(eq(POOL), anyInt());
    }

    @Test
    void uniformLocalReservationRechecksConsentInsideOwnedAccountLock() {
        when(uniform.permits(ACCOUNT, "goods-5", deliveryConfig)).thenReturn(false, true);
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.reserveForDelivery(POOL, ORDER, ACCOUNT, 1, deliveryConfig, true)).getCode());
        verifyNoInteractions(external);
        when(items.lockFulfilledByOrderAcrossPools(TENANT, ACCOUNT, ORDER)).thenReturn(List.of());
        when(items.lockReservedByOrder(POOL, ACCOUNT, ORDER)).thenReturn(List.of());
        XianyuKamiConfig pool = pool("LOCAL");
        when(configs.lockById(POOL)).thenReturn(pool);
        XianyuKamiItem available = card(POOL, 0);
        available.setId(300L);
        when(items.lockAvailable(POOL, 1)).thenReturn(List.of(available));
        when(items.reserve(any(), eq(ACCOUNT), eq(ORDER), any(), eq(1L))).thenReturn(1);
        List<XianyuKamiItem> result = service.reserveForDelivery(
                POOL, ORDER, ACCOUNT, 1, deliveryConfig, true);
        assertSame(available, result.getFirst());
        assertEquals(2, available.getStatus());
        assertEquals(ORDER, available.getOrderId());
        verifyNoInteractions(external);
    }

    @Test
    void uniformApiPoolCannotCallExternalSupply() {
        jdbc.source = "API";
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.reserveForDelivery(POOL, ORDER, ACCOUNT, 1, deliveryConfig, true)).getCode());
        verifyNoInteractions(uniform, external);
    }

    @Test
    void ordinaryApiPoolRetainsShortTransactionProvisioning() {
        jdbc.source = "API";
        when(items.lockFulfilledByOrderAcrossPools(TENANT, ACCOUNT, ORDER)).thenReturn(List.of());
        XianyuKamiConfig pool = pool("API");
        when(configs.selectById(POOL)).thenReturn(pool);
        XianyuKamiItem supplied = card(POOL, 2);
        when(external.reserve(pool, ORDER, ACCOUNT, 1)).thenReturn(List.of(supplied));
        assertEquals(List.of(supplied), service.reserveKami(POOL, ORDER, ACCOUNT, 1));
        verify(external).reserve(pool, ORDER, ACCOUNT, 1);
    }

    @Test
    void apiSamePoolReservedStillUsesExternalRequestStateMachine() {
        jdbc.source = "API";
        when(items.lockFulfilledByOrderAcrossPools(TENANT, ACCOUNT, ORDER))
                .thenReturn(List.of(card(POOL, 2)));
        XianyuKamiConfig pool = pool("API");
        when(configs.selectById(POOL)).thenReturn(pool);
        XianyuKamiItem accepted = card(POOL, 2);
        when(external.reserve(pool, ORDER, ACCOUNT, 1)).thenReturn(List.of(accepted));
        assertEquals(List.of(accepted), service.reserveKami(POOL, ORDER, ACCOUNT, 1));
        verify(external).reserve(pool, ORDER, ACCOUNT, 1);
    }

    @Test
    void expiredWorkerCannotReserveCommitReleaseOrMarkReview() {
        jdbc.claimActive = false;
        try (var scope = new DeliveryExecution("stale-token", () -> true, () -> true)) {
            assertThrows(DeliveryExecution.LeaseLostException.class,
                    () -> service.reserveKami(POOL, ORDER, ACCOUNT, 1));
            assertThrows(DeliveryExecution.LeaseLostException.class,
                    () -> service.commitReservation(ORDER, ACCOUNT, "goods-5", "buyer", "Buyer"));
            assertThrows(DeliveryExecution.LeaseLostException.class,
                    () -> service.releaseReservation(ORDER, ACCOUNT));
            assertThrows(DeliveryExecution.LeaseLostException.class,
                    () -> service.markReservationReviewRequired(ORDER, ACCOUNT));
        }
        verifyNoInteractions(items, external);
    }

    @Test
    void activeWorkerCanReuseOnlyItsLockedSamePoolReservation() {
        XianyuKamiItem reserved = card(POOL, 2);
        when(items.lockFulfilledByOrderAcrossPools(TENANT, ACCOUNT, ORDER))
                .thenReturn(List.of(reserved));
        try (var scope = new DeliveryExecution("stale-token", () -> true, () -> true)) {
            assertEquals(List.of(reserved), service.reserveKami(POOL, ORDER, ACCOUNT, 1));
        }
        assertEquals(1, jdbc.leaseChecks);
    }

    @Test
    void commitWithoutReservedOrMatchingUsedQuantityIsNotSilentSuccess() {
        when(items.findByOrderAndStatus(ACCOUNT, ORDER, 2)).thenReturn(List.of());
        when(items.findByOrderAndStatus(ACCOUNT, ORDER, 1)).thenReturn(List.of());
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.commitReservation(ORDER, ACCOUNT, "goods-5", "buyer", "Buyer")).getCode());
        XianyuKamiItem used = card(POOL, 1);
        when(items.findByOrderAndStatus(ACCOUNT, ORDER, 1)).thenReturn(List.of(used));
        service.commitReservation(ORDER, ACCOUNT, "goods-5", "buyer", "Buyer");
        jdbc.expectedQuantity = 2;
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.commitReservation(ORDER, ACCOUNT, "goods-5", "buyer", "Buyer")).getCode());
        verify(items, never()).commitReservation(ACCOUNT, ORDER);
    }

    @Test
    void strategyPropagatesUniformRequirementInsteadOfDowngradingIt() {
        KamiConfigService dependency = mock(KamiConfigService.class);
        KamiDeliveryStrategy strategy = new KamiDeliveryStrategy();
        ReflectionTestUtils.setField(strategy, "kamiConfigService", dependency);
        XianyuKamiItem reserved = card(POOL, 2);
        reserved.setKamiContent("CODE-1");
        when(dependency.reserveForDelivery(POOL, ORDER, ACCOUNT, 1, deliveryConfig, true))
                .thenReturn(List.of(reserved));
        DeliveryContext context = DeliveryContext.builder()
                .accountId(ACCOUNT).xyGoodsId("goods-5").orderId(ORDER)
                .quantity(1).deliveryConfig(deliveryConfig).uniformCardRequired(true).build();
        assertEquals("CODE-1", strategy.resolve(context));
        verify(dependency).reserveForDelivery(POOL, ORDER, ACCOUNT, 1, deliveryConfig, true);
        verify(dependency, never()).reserveKami(POOL, ORDER, ACCOUNT, 1);
    }

    private static XianyuKamiItem card(Long poolId, int status) {
        XianyuKamiItem item = new XianyuKamiItem();
        item.setKamiConfigId(poolId);
        item.setStatus(status);
        item.setOrderId(ORDER);
        item.setReservedAccountId(ACCOUNT);
        return item;
    }

    private static XianyuKamiConfig pool(String source) {
        XianyuKamiConfig pool = new XianyuKamiConfig();
        pool.setId(POOL);
        pool.setTenantId(TENANT);
        pool.setConfigVersion(1L);
        pool.setSourceType(source);
        return pool;
    }

    private static final class FakeJdbc extends JdbcTemplate {
        boolean linked = true;
        boolean accountActive = true;
        boolean claimActive = true;
        int leaseChecks;
        String source = "LOCAL";
        int expectedQuantity = 1;

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (sql.contains("SELECT expected_quantity FROM xianyu_goods_order")) {
                return List.of(Map.of("expected_quantity", expectedQuantity));
            }
            if (sql.contains("FROM xianyu_goods_order")) {
                if (!sql.contains("FOR UPDATE")) throw new AssertionError("Lease row must be locked");
                leaseChecks++;
                return claimActive && "stale-token".equals(args[3])
                        ? List.of(Map.of("id", 40L)) : List.of();
            }
            if (sql.contains("FROM xianyu_account")) {
                if (!sql.contains("FOR UPDATE")) throw new AssertionError("Account row must be locked");
                return accountActive && Long.valueOf(TENANT).equals(args[0])
                        && Long.valueOf(ACCOUNT).equals(args[1])
                        ? List.of(Map.of("id", ACCOUNT)) : List.of();
            }
            if (sql.contains("FROM xianyu_kami_config config")) {
                if (!sql.contains("FOR UPDATE")) throw new AssertionError("Pool/link rows must be locked");
                return linked && Long.valueOf(ACCOUNT).equals(args[0])
                        && Long.valueOf(TENANT).equals(args[1]) && Long.valueOf(POOL).equals(args[2])
                        ? List.of(Map.of("id", POOL, "source_type", source)) : List.of();
            }
            throw new AssertionError("Unexpected SQL: " + sql);
        }

        @Override
        public int update(String sql, Object... args) {
            if (sql.contains("xianyu_kami_inventory_event")) return 1;
            throw new AssertionError("Unexpected SQL: " + sql);
        }
    }
}
