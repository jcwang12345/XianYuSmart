package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.entity.SysUser;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.mapper.SysUserMapper;
import org.springframework.mock.env.MockEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class ProductEventServiceTest {
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="D2_R03_NATIVE",matches="true")
    void nativeMysqlSafeContentReplayAndRequiredAuditRollback() throws Exception {
        try {
            Class.forName("com.xianyusmart.service.NativeR03Support").getMethod(
                    "true".equals(System.getenv("D2_R03_ATOMICITY")) ? "verifyAtomicityProduct" : "verifyProduct").invoke(null);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception error) throw error;
            if (failure.getCause() instanceof Error error) throw error;
            throw failure;
        }
    }
    record Harness(ProductEventService service,JdbcTemplate jdbc,OperationLogService logs,SysUserMapper users,PlatformPermissionService permissions,AccountAccessService access){}
    Harness qa() {
        TenantContext.set(1L);UserContext.set(7L,"qa",1L);
        var jdbc=mock(JdbcTemplate.class);var logs=mock(OperationLogService.class);var users=mock(SysUserMapper.class);var permissions=mock(PlatformPermissionService.class);var access=mock(AccountAccessService.class);
        var user=new SysUser();user.setId(7L);user.setTenantId(1L);user.setStatus(1);user.setMemberRole("OWNER");user.setRole("USER");
        when(users.selectById(7L)).thenReturn(user);when(permissions.hasPermission(any(SysUser.class),anyString())).thenReturn(true);when(access.loadScope(any())).thenReturn(new AccountAccessService.Scope(true,java.util.Set.of()));
        when(jdbc.queryForList(contains("FROM xianyu_account WHERE"),eq(1L),eq(99L))).thenReturn(List.of(Map.of("account_note","QA-R03-account","unb","QA-R03-account")));
        when(jdbc.queryForList(contains("FROM xianyu_goods WHERE"),eq(1L),eq(99L),eq("QA-R03-goods"))).thenReturn(List.of(Map.of("id",1L)));
        when(jdbc.queryForObject(contains("COUNT(*) FROM xianyu_cookie"),eq(Integer.class),eq(99L))).thenReturn(0);
        when(jdbc.queryForObject(contains("SELECT id FROM xianyu_goods_event"),eq(Long.class),eq(1L),eq(99L),eq("QA-R03-event"))).thenReturn(123L);
        var env=new MockEnvironment();env.setActiveProfiles("qa");env.setProperty("app.r03.qa.enabled","true");env.setProperty("app.r03.qa.tenant-id","1");env.setProperty("app.r03.qa.account-ids","99");
        return new Harness(new ProductEventService(jdbc,new ObjectMapper(),logs,access,users,permissions,new ProductEventSafety(new ObjectMapper()),env),jdbc,logs,users,permissions,access);
    }
    @Test void qaObservationOnlyInsertsSanitizedEventAndOneRequiredAudit() {
        var h=qa();var result=h.service.observeQaProduct(99L,"QA-R03-goods","QA-R03-event","2026-10-02T08:00:00Z",Map.of("title","old"),Map.of("title","new","token","synthetic-private"));
        assertEquals(false,result.get("replayed"));assertEquals("QA_WEBHOOK",result.get("dataSource"));
        var args=ArgumentCaptor.forClass(Object[].class);verify(h.jdbc).update(contains("INSERT INTO xianyu_goods_event"),args.capture());
        assertFalse(java.util.Arrays.toString(args.getValue()).contains("synthetic-private"));verify(h.logs).logRequired(any());verify(h.jdbc,never()).update(contains("UPDATE xianyu_goods"),any(Object[].class));
        var lock=ArgumentCaptor.forClass(String.class);verify(h.jdbc).queryForList(lock.capture(),eq(1L),eq(99L));assertTrue(lock.getValue().contains("FOR UPDATE"));
    }
    @Test void existingSafeHashReplaysWithoutInsertingOrReauditing() {
        var h=qa();var first=h.service.observeQaProduct(99L,"QA-R03-goods","QA-R03-event","2026-10-02T08:00:00Z",Map.of(),Map.of("stock",2));
        clearInvocations(h.jdbc,h.logs);
        when(h.jdbc.queryForList(contains("FROM xianyu_goods_event"),eq(1L),eq(99L),eq("QA-R03-event"))).thenReturn(List.of(Map.of("id",123L,"xy_goods_id","QA-R03-goods","raw_snapshot_hash",first.get("snapshotHash"))));
        var replay=h.service.observeQaProduct(99L,"QA-R03-goods","QA-R03-event","2026-10-02T08:00:00Z",Map.of(),Map.of("stock",2));
        assertEquals(true,replay.get("replayed"));assertEquals(123L,replay.get("eventRowId"));verify(h.jdbc,never()).update(anyString(),any(Object[].class));verifyNoInteractions(h.logs);
    }
    @Test void changedGoodsOrSafePayloadConflictsInsteadOfOverwriting() {
        var h=qa();when(h.jdbc.queryForList(contains("FROM xianyu_goods_event"),eq(1L),eq(99L),eq("QA-R03-event"))).thenReturn(List.of(Map.of("id",123L,"xy_goods_id","QA-R03-other","raw_snapshot_hash","different")));
        assertEquals(409,assertThrows(BusinessException.class,()->h.service.observeQaProduct(99L,"QA-R03-goods","QA-R03-event","2026-10-02T08:00:00Z",Map.of(),Map.of("stock",2))).getCode());verifyNoInteractions(h.logs);verify(h.jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void credentialAndCurrentScopeDenialsNeverWriteAnEvent() {
        var h=qa();when(h.jdbc.queryForObject(contains("COUNT(*) FROM xianyu_cookie"),eq(Integer.class),eq(99L))).thenReturn(1);
        assertEquals(403,assertThrows(BusinessException.class,()->h.service.observeQaProduct(99L,"QA-R03-goods","QA-R03-event","2026-10-02T08:00:00Z",Map.of(),Map.of())).getCode());verify(h.jdbc,never()).update(anyString(),any(Object[].class));
        when(h.access.loadScope(any())).thenReturn(new AccountAccessService.Scope(false,java.util.Set.of()));assertEquals(403,assertThrows(BusinessException.class,()->h.service.observeQaProduct(99L,"QA-R03-goods","QA-R03-event","2026-10-02T08:00:00Z",Map.of(),Map.of())).getCode());
    }
    @Test void requiredAuditFailurePropagatesForTheTransactionToRollback() {
        var h=qa();doThrow(new IllegalStateException("QA audit failure")).when(h.logs).logRequired(any());
        assertThrows(IllegalStateException.class,()->h.service.observeQaProduct(99L,"QA-R03-goods","QA-R03-event","2026-10-02T08:00:00Z",Map.of(),Map.of("stock",1)));
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        UserContext.clear();
    }

    @Test
    void publishEventPersistsFieldDifferencesAndPartialSyncWhenReadBackPending() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProductEventService service = new ProductEventService(jdbc, new ObjectMapper());
        TenantContext.set(1L);
        UserContext.set(7L, "qa", 1L);
        Map<String, Object> differences = Map.of(
                "status", "PARTIAL",
                "items", List.of(Map.of("field", "categoryId", "status", "UNAVAILABLE")));

        service.publishCompleted(101L, "QA-1", "request-1", "QA_LOCAL",
                "QA_CONFIRMED", Map.of(
                        "verificationStatus", "PENDING",
                        "localSynced", true,
                        "fieldDifferences", differences), "QA_FIXTURE");

        ArgumentCaptor<Object[]> eventArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("INSERT INTO xianyu_goods_event"), eventArgs.capture());
        assertEquals(11, eventArgs.getValue().length);
        assertTrue(String.valueOf(eventArgs.getValue()[10]).contains("categoryId"));

        ArgumentCaptor<Object[]> goodsArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("UPDATE xianyu_goods"), goodsArgs.capture());
        assertEquals("PARTIAL", goodsArgs.getValue()[2]);
    }
}
