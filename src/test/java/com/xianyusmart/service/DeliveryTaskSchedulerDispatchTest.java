package com.xianyusmart.service;

import com.xianyusmart.context.*;
import com.xianyusmart.entity.XianyuGoodsOrder;
import com.xianyusmart.mapper.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeliveryTaskSchedulerDispatchTest {
    final XianyuAccountMapper accounts = mock(XianyuAccountMapper.class);
    final DeliveryTaskService tasks = mock(DeliveryTaskService.class);
    final BuyerProfileService buyers = mock(BuyerProfileService.class);
    @AfterEach void clear() { TenantContext.clear(); UserContext.clear(); AccountScopeContext.clear(); }
    DeliveryTaskScheduler scheduler(Executor executor) {
        var scheduler = new DeliveryTaskScheduler(tasks, mock(AutoDeliveryService.class),
                mock(XianyuGoodsOrderMapper.class), mock(XianyuKamiItemMapper.class), accounts,
                mock(OrderService.class), mock(PendingOrderPollService.class), mock(WebSocketService.class), buyers, executor);
        ReflectionTestUtils.setField(scheduler, "claimBatchSize", 20);
        return scheduler;
    }
    XianyuGoodsOrder task(long id, long tenant, long account) {
        var task = new XianyuGoodsOrder(); task.setId(id); task.setTenantId(tenant);
        task.setXianyuAccountId(account); task.setBuyerUserId("synthetic-buyer"); return task;
    }
    @Test void claimAlwaysReceivesTenantContext() {
        when(accounts.selectDeliveryTaskTenantIds()).thenReturn(List.of(1L));
        when(tasks.claimDueTasks(anyString(), anyInt())).thenAnswer(call -> {
            assertEquals(1L, TenantContext.get(), "claim must receive its tenant"); return List.of();
        });
        scheduler(Runnable::run).dispatchDueTasks();
        verify(tasks).claimDueTasks(anyString(), eq(20)); assertNull(TenantContext.get());
    }
    @Test void claimsDistinctValidTenantsWithBoundedBatchAndContinuesAfterFailure() {
        var scheduler = scheduler(Runnable::run);
        ReflectionTestUtils.setField(scheduler, "claimBatchSize", 999);
        when(accounts.selectDeliveryTaskTenantIds()).thenReturn(Arrays.asList(null, 0L, -1L, 1L, 1L, 2L));
        var seen = new ArrayList<Long>();
        when(tasks.claimDueTasks(anyString(), eq(100))).thenAnswer(call -> {
            seen.add(TenantContext.get());
            if (TenantContext.get().equals(1L)) throw new IllegalStateException("synthetic failure");
            return List.of();
        });
        scheduler.dispatchDueTasks();
        assertEquals(List.of(1L, 2L), seen); assertNull(TenantContext.get());
        verify(accounts, never()).selectReconnectableAccounts();
    }
    @Test void closedGateDoesNotEnumerateOrClaim() {
        var scheduler = scheduler(Runnable::run); var policy = new BackgroundPlatformActivityPolicy();
        ReflectionTestUtils.setField(policy, "enabled", false);
        ReflectionTestUtils.setField(scheduler, "backgroundPlatformActivityPolicy", policy);
        scheduler.dispatchDueTasks(); verifyNoInteractions(accounts, tasks, buyers);
    }
    @Test void scopedCallerCannotClaimAnotherTenantOrExecuteAnotherAccount() {
        UserContext.set(9L, "synthetic-user", 1L); AccountScopeContext.set(false, Set.of(11L));
        when(accounts.selectDeliveryTaskTenantIds()).thenReturn(List.of(1L, 2L));
        when(tasks.claimDueTasks(anyString(), eq(20))).thenAnswer(call -> {
            assertEquals(1L, TenantContext.get()); assertEquals(9L, UserContext.getUserId());
            assertEquals(Set.of(11L), AccountScopeContext.get().accountIds());
            return List.of(task(1, 2, 11), task(2, 1, 22));
        });
        scheduler(Runnable::run).dispatchDueTasks();
        verify(tasks, times(1)).claimDueTasks(anyString(), eq(20)); verifyNoInteractions(buyers);
        assertEquals(1L, TenantContext.get()); assertEquals(9L, UserContext.getUserId());
        assertEquals(Set.of(11L), AccountScopeContext.get().accountIds());
    }
    @Test void rejectsMismatchedTasksAndContinuesAfterExecutorRejection() {
        when(accounts.selectDeliveryTaskTenantIds()).thenReturn(List.of(1L, 2L));
        when(tasks.claimDueTasks(anyString(), eq(20))).thenAnswer(call ->
                TenantContext.get().equals(1L) ? List.of(task(1, 2, 11), task(2, 1, 11)) : List.of(task(3, 2, 22)));
        var submitted = new ArrayList<Runnable>();
        scheduler(command -> { submitted.add(command); if (submitted.size() == 1) throw new RejectedExecutionException(); })
                .dispatchDueTasks();
        assertEquals(2, submitted.size()); verify(tasks, times(2)).claimDueTasks(anyString(), eq(20));
        assertNull(TenantContext.get()); verifyNoInteractions(buyers);
    }
    @Test void asyncTaskReceivesCallerScopeAndRestoresWorkerContext() {
        UserContext.set(9L, "synthetic-user", 1L); AccountScopeContext.set(false, Set.of(11L));
        when(accounts.selectDeliveryTaskTenantIds()).thenReturn(List.of(1L));
        when(tasks.claimDueTasks(anyString(), eq(20))).thenReturn(List.of(task(1, 1, 11)));
        var submitted = new ArrayList<Runnable>(); scheduler(submitted::add).dispatchDueTasks();
        when(buyers.automationBlockReason(11L, "synthetic-buyer")).thenAnswer(call -> {
            assertEquals(1L, TenantContext.get()); assertEquals(9L, UserContext.getUserId());
            assertEquals(Set.of(11L), AccountScopeContext.get().accountIds()); return "synthetic-block";
        });
        UserContext.set(88L, "worker", 8L); TenantContext.set(7L); AccountScopeContext.set(false, Set.of(77L));
        submitted.get(0).run();
        verify(tasks).markReviewRequired(1L, "synthetic-block");
        assertEquals(7L, TenantContext.get()); assertEquals(88L, UserContext.getUserId());
        assertEquals(8L, UserContext.getTenantId()); assertEquals(Set.of(77L), AccountScopeContext.get().accountIds());
    }
    @Test void userWithoutTenantCannotConvertEnumerationIntoAuthority() {
        UserContext.set(9L, "synthetic-user", null);
        when(accounts.selectDeliveryTaskTenantIds()).thenReturn(List.of(1L));
        scheduler(Runnable::run).dispatchDueTasks(); verifyNoInteractions(tasks, buyers);
        assertEquals(9L, UserContext.getUserId()); assertNull(TenantContext.get());
    }
}
