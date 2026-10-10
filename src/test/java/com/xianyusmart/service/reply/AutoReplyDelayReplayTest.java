package com.xianyusmart.service.reply;

import com.xianyusmart.entity.XianyuGoodsAutoReplyRecord;
import com.xianyusmart.event.chatMessageEvent.ChatMessageData;
import com.xianyusmart.mapper.XianyuGoodsAutoReplyRecordMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AutoReplyDelayReplayTest {
    @Test
    void twelveConcurrentReplaysDoNotCancelTheOriginalPendingReply() throws Exception {
        AutoReplyDelayServiceImpl service = new AutoReplyDelayServiceImpl();
        com.xianyusmart.service.AccountPolicyFixture.active(service);
        var manager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(ignored -> new org.springframework.transaction.support.SimpleTransactionStatus());
        service.setSubmissionTransactionManager(manager);
        XianyuGoodsAutoReplyRecordMapper records = mock(XianyuGoodsAutoReplyRecordMapper.class);
        ReplyConfigProvider config = mock(ReplyConfigProvider.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> scheduled = mock(ScheduledFuture.class);
        AtomicBoolean inserted = new AtomicBoolean();
        when(records.existsMessage(1L, "QA-R01-same-pnm")).thenAnswer(ignored -> inserted.get() ? 1 : 0);
        when(records.insert(any(XianyuGoodsAutoReplyRecord.class))).thenAnswer(invocation -> {
            Thread.sleep(5);
            XianyuGoodsAutoReplyRecord record = invocation.getArgument(0);
            record.setId(1L);
            inserted.set(true);
            return 1;
        });
        when(config.getDelaySeconds(1L, "QA-R01-goods")).thenReturn(1);
        doReturn(scheduled).when(scheduler).schedule(any(Runnable.class), eq(1L), eq(TimeUnit.SECONDS));
        ReflectionTestUtils.setField(service, "autoReplyRecordMapper", records);
        ReflectionTestUtils.setField(service, "takeoverManager", mock(HumanTakeoverManager.class));
        ReflectionTestUtils.setField(service, "configProvider", config);
        ReflectionTestUtils.setField(service, "scheduler", scheduler);
        ChatMessageData message = new ChatMessageData();
        message.setXianyuAccountId(1L);
        message.setSId("QA-R01-session");
        message.setPnmId("QA-R01-same-pnm");
        message.setXyGoodsId("QA-R01-goods");
        message.setMsgContent("QA-R01 consultation");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(12)) {
            var futures = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 12; i++) futures.add(executor.submit(() -> {
                start.await();
                service.submitDelayTask(message);
                return null;
            }));
            start.countDown();
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
        }
        verify(records).insert(any(XianyuGoodsAutoReplyRecord.class));
        verify(records).cancelPendingBySessionExcept(1L, "QA-R01-session", 1L);
        verify(scheduler).schedule(any(Runnable.class), eq(1L), eq(TimeUnit.SECONDS));
        verify(scheduled, never()).cancel(anyBoolean());
        assertEquals(1, ((java.util.Map<?, ?>) ReflectionTestUtils.getField(service, "pendingTasks")).size());
    }
    @Test
    void failedReplacementKeepsThePreviousTimerAndRollsBack() {
        Fixture f = new Fixture();
        ScheduledFuture<?> previous = mock(ScheduledFuture.class);
        ((java.util.Map<String, ScheduledFuture<?>>) ReflectionTestUtils.getField(f.service, "pendingTasks"))
                .put("1_QA-R01-session", previous);
        when(f.records.insert(any(XianyuGoodsAutoReplyRecord.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("test only"));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
                () -> f.service.submitDelayTask(f.message));
        verify(previous, never()).cancel(anyBoolean());
        verify(f.manager).rollback(any());
        verify(f.records, never()).cancelPendingBySessionExcept(anyLong(), anyString(), anyLong());
        verifyNoInteractions(f.scheduler);
    }

    @Test
    void rejectedSchedulerLeavesCommittedTaskForDatabaseRecovery() {
        Fixture f = new Fixture();
        when(f.scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenThrow(new RejectedExecutionException("test only"));
        org.junit.jupiter.api.Assertions.assertThrows(RejectedExecutionException.class,
                () -> f.service.submitDelayTask(f.message));
        verify(f.manager).commit(any());
        verify(f.records).insert(any(XianyuGoodsAutoReplyRecord.class));
        verify(f.records, never()).cancelById(anyLong());
        assertEquals(0, ((java.util.Map<?, ?>) ReflectionTestUtils.getField(f.service, "pendingTasks")).size());
    }

    @Test
    void sellerTakeoverDuringSubmissionCancelsAndBlocksAutomaticExecution() throws Exception {
        Fixture f = new Fixture();
        HumanTakeoverManager takeover = mock(HumanTakeoverManager.class);
        ReplyConfigProvider config = mock(ReplyConfigProvider.class);
        AtomicBoolean takenOver = new AtomicBoolean();
        CountDownLatch inserting = new CountDownLatch(1), release = new CountDownLatch(1);
        when(takeover.isTakenOver(1L, "QA-R01-session")).thenAnswer(ignored -> takenOver.get());
        doAnswer(ignored -> { takenOver.set(true); return null; }).when(takeover)
                .takeover(eq(1L), eq("QA-R01-goods"), eq("QA-R01-session"), anyInt());
        when(config.isHumanInterventionEnabled(1L, "QA-R01-goods")).thenReturn(true);
        ReflectionTestUtils.setField(f.service, "takeoverManager", takeover);
        ReflectionTestUtils.setField(f.service, "configProvider", config);
        when(f.records.insert(any(XianyuGoodsAutoReplyRecord.class))).thenAnswer(invocation -> {
            inserting.countDown(); org.junit.jupiter.api.Assertions.assertTrue(release.await(5, TimeUnit.SECONDS));
            ((XianyuGoodsAutoReplyRecord) invocation.getArgument(0)).setId(42L); return 1;
        });
        ScheduledFuture<?> scheduled = mock(ScheduledFuture.class);
        doReturn(scheduled).when(f.scheduler).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> submission = executor.submit(() -> f.service.submitDelayTask(f.message));
            org.junit.jupiter.api.Assertions.assertTrue(inserting.await(5, TimeUnit.SECONDS));
            Future<?> manual = executor.submit(() -> f.service.recordSellerManualReply(1L, "QA-R01-goods", "QA-R01-session"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!takenOver.get() && System.nanoTime() < deadline) Thread.onSpinWait();
            release.countDown(); submission.get(5, TimeUnit.SECONDS); manual.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        verify(scheduled).cancel(false);
        verify(f.records).cancelPendingBySession(1L, "QA-R01-session");
        var accounts = mock(com.xianyusmart.mapper.XianyuAccountMapper.class);
        var account = new com.xianyusmart.entity.XianyuAccount();
        account.setId(1L); account.setTenantId(1L); account.setStatus(1);
        when(accounts.selectById(1L)).thenReturn(account);
        var reply = mock(com.xianyusmart.service.AutoReplyService.class);
        ReflectionTestUtils.setField(f.service, "accountMapper", accounts);
        ReflectionTestUtils.setField(f.service, "autoReplyService", reply);
        ReflectionTestUtils.invokeMethod(f.service, "executeClaimedTask", 42L, java.util.List.of(f.message), "test-lease");
        verify(f.records).cancelById(42L);
        verifyNoInteractions(reply);
        assertEquals(0, ((java.util.Map<?, ?>) ReflectionTestUtils.getField(f.service, "pendingTasks")).size());
    }

    private static class Fixture {
        final AutoReplyDelayServiceImpl service = new AutoReplyDelayServiceImpl();
        final XianyuGoodsAutoReplyRecordMapper records = mock(XianyuGoodsAutoReplyRecordMapper.class);
        final org.springframework.transaction.PlatformTransactionManager manager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        final ChatMessageData message = new ChatMessageData();
        Fixture() {
            com.xianyusmart.service.AccountPolicyFixture.active(service);
            when(manager.getTransaction(any())).thenAnswer(ignored -> new org.springframework.transaction.support.SimpleTransactionStatus());
            service.setSubmissionTransactionManager(manager);
            when(records.insert(any(XianyuGoodsAutoReplyRecord.class))).thenAnswer(invocation -> {
                ((XianyuGoodsAutoReplyRecord) invocation.getArgument(0)).setId(42L); return 1;
            });
            ReflectionTestUtils.setField(service, "autoReplyRecordMapper", records);
            ReflectionTestUtils.setField(service, "takeoverManager", mock(HumanTakeoverManager.class));
            ReflectionTestUtils.setField(service, "configProvider", mock(ReplyConfigProvider.class));
            ReflectionTestUtils.setField(service, "scheduler", scheduler);
            message.setXianyuAccountId(1L); message.setSId("QA-R01-session");
            message.setPnmId("QA-R01-new"); message.setXyGoodsId("QA-R01-goods");
        }
    }

}
