package com.xianyusmart.service;

import com.xianyusmart.context.AccountScopeContext;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationAssignmentServiceTest {
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="D2_FG_NATIVE",matches="true")
    void nativeImportedMessagesUseEventTimeAndOnlyRepliesAfterInquiry() throws Exception {
        long account=Long.parseLong(System.getenv("D2_FG_ACCOUNT_A"));
        long secondAccount=Long.parseLong(System.getenv("D2_FG_ACCOUNT_B"));
        assertTrue(account>0 && secondAccount>0 && account!=secondAccount);
        var dataSource=new org.springframework.jdbc.datasource.DriverManagerDataSource(
            "jdbc:mysql://127.0.0.1:13308/xianyusmart?serverTimezone=UTC&sslMode=DISABLED",System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD"));
        try(var connection=dataSource.getConnection()) {
            var db=new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true));
            var manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.getDataSource());
            var transaction=manager.getTransaction(new org.springframework.transaction.support.DefaultTransactionDefinition());
            long connectionId=db.queryForObject("SELECT CONNECTION_ID()",Long.class);
            String session="FG4-LOCAL-"+java.util.UUID.randomUUID();
            try {
                assertEquals(67,db.queryForObject("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1",Integer.class));
                assertEquals(5,db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('xianyu_account','xianyu_cookie','xianyu_chat_message','conversation_assignment','xianyu_buyer_profile') AND engine='InnoDB'",Integer.class));
                assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM xianyu_account WHERE id IN (?,?)",Integer.class,account,secondAccount));
                for(long id:new long[]{account,secondAccount})
                    db.update("INSERT INTO xianyu_account (id,tenant_id,account_note,unb,status) VALUES (?,1,?,?,0)",id,"FG-ROLLBACK-QA", "FG-SELLER-"+id);
                assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM xianyu_account WHERE id=? AND tenant_id=1",Integer.class,account));
                assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM xianyu_cookie WHERE xianyu_account_id IN (?,?)",Integer.class,account,secondAccount));
                String seller=db.queryForObject("SELECT unb FROM xianyu_account WHERE id=?",String.class,account);
                long now=db.queryForObject("SELECT CAST(UNIX_TIMESTAMP(NOW(3))*1000 AS SIGNED)",Long.class);
                long inquiry=now-600000,reply=now-420000;
                for(var row:List.of(new Object[]{seller,now-3600000},new Object[]{"FG_LOCAL_BUYER",inquiry},new Object[]{seller,reply}))
                    db.update("INSERT INTO xianyu_chat_message (tenant_id,xianyu_account_id,pnm_id,s_id,sender_user_id,message_time,msg_content,complete_msg) VALUES (1,?,?,?,?,?,?,'{}')",account,java.util.UUID.randomUUID().toString(),session,row[0],row[1],"LOCAL_SYNTHETIC");
                for(var row:List.of(new Object[]{"-no-reply",seller,inquiry-120000},new Object[]{"-no-reply","FG_LOCAL_BUYER",inquiry},new Object[]{"-missing","FG_LOCAL_BUYER",null},new Object[]{"-future","FG_LOCAL_BUYER",now+3600000}))
                    db.update("INSERT INTO xianyu_chat_message (tenant_id,xianyu_account_id,pnm_id,s_id,sender_user_id,message_time,msg_content,complete_msg) VALUES (1,?,?,?,?,?,?,'{}')",account,java.util.UUID.randomUUID().toString(),session+row[0],row[1],row[2],"LOCAL_SYNTHETIC");
                TenantContext.set(1L);AccountScopeContext.set(false,java.util.Set.of(account));
                assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                new ConversationAssignmentService(db).refresh();
                var times=db.queryForMap("SELECT CAST(UNIX_TIMESTAMP(first_message_time)*1000 AS SIGNED) inquiry,CAST(UNIX_TIMESTAMP(first_response_time)*1000 AS SIGNED) reply,CAST(UNIX_TIMESTAMP(sla_due_time)*1000 AS SIGNED) due FROM conversation_assignment WHERE tenant_id=1 AND xianyu_account_id=? AND session_id=?",account,session);
                assertEquals(inquiry,((Number)times.get("inquiry")).longValue(),"import time must not replace event time");
                assertEquals(reply,((Number)times.get("reply")).longValue(),"seller event before inquiry must not count as first response");
                assertEquals(inquiry+300000,((Number)times.get("due")).longValue());
                assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM conversation_assignment WHERE tenant_id=1 AND xianyu_account_id=? AND session_id IN (?,?)",Integer.class,account,session+"-missing",session+"-future"));
                assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM conversation_assignment WHERE tenant_id=1 AND xianyu_account_id=? AND session_id=? AND first_response_time IS NULL",Integer.class,account,session+"-no-reply"));
                db.update("UPDATE conversation_assignment SET first_response_time=FROM_UNIXTIME(?/1000.0) WHERE tenant_id=1 AND xianyu_account_id=? AND session_id=?",inquiry-120000,account,session);
                var projection=new ConversationAssignmentService(db);projection.refresh();
                assertEquals(reply,db.queryForObject("SELECT CAST(UNIX_TIMESTAMP(first_response_time)*1000 AS SIGNED) FROM conversation_assignment WHERE tenant_id=1 AND xianyu_account_id=? AND session_id=?",Long.class,account,session));
                projection.markHistorySync(account,session,3,500,true,null);
                var before=db.queryForMap("SELECT history_sync_status,history_coverage_status,history_last_synced_time FROM conversation_assignment WHERE tenant_id=1 AND xianyu_account_id=? AND session_id=?",account,session);
                assertEquals("FULL",before.get("history_coverage_status"));
                projection.markHistorySync(account,session,0,500,false,"HISTORY_SYNC_FAILED");
                var after=db.queryForMap("SELECT history_sync_status,history_coverage_status,history_last_synced_time FROM conversation_assignment WHERE tenant_id=1 AND xianyu_account_id=? AND session_id=?",account,session);
                assertEquals("FAILED",after.get("history_sync_status"));assertEquals("PARTIAL",after.get("history_coverage_status"));assertEquals(before.get("history_last_synced_time"),after.get("history_last_synced_time"));
                com.xianyusmart.service.impl.ChatMessageServiceImplHistoryTest.verifyNativeHistoryPersistence(db,account,secondAccount,session,now);
                assertEquals(connectionId,db.queryForObject("SELECT CONNECTION_ID()",Long.class));
            } finally {
                manager.rollback(transaction);
                TenantContext.clear();AccountScopeContext.clear();
                for(String table:List.of("xianyu_account","xianyu_cookie","xianyu_chat_message","conversation_assignment","xianyu_buyer_profile")) {
                    String column=table.equals("xianyu_account")?"id":"xianyu_account_id";
                    assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE "+column+" IN (?,?)",Integer.class,account,secondAccount),table+" rollback residue");
                    assertEquals(0,new JdbcTemplate(dataSource).queryForObject("SELECT COUNT(*) FROM "+table+" WHERE "+column+" IN (?,?)",Integer.class,account,secondAccount),table+" independent visibility residue");
                }
            }
        }
    }

    private JdbcTemplate jdbcTemplate;
    private ConversationAssignmentService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new ConversationAssignmentService(jdbcTemplate);
        TenantContext.set(6L);
        UserContext.set(4L, "message-tester", 6L);
        AccountScopeContext.clear();
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);
    }

    @AfterEach
    void tearDown() {
        AccountScopeContext.clear();
        TenantContext.clear();
        UserContext.clear();
    }

    @Test
    void transientProjectionDeadlockRetriesWithoutDuplicatingBusinessOperations() {
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenThrow(new org.springframework.dao.CannotAcquireLockException("simulated deadlock"))
                .thenReturn(0);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service.refresh());
        verify(jdbcTemplate,times(5)).update(anyString(),any(Object[].class));
    }

    @Test
    void retryRollsBackFailedProjectionTransactionBeforeStartingNext() {
        var manager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        var first = new org.springframework.transaction.support.SimpleTransactionStatus();
        var second = new org.springframework.transaction.support.SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(first, second);
        service.setProjectionTransactionManager(manager);
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
            .thenThrow(new org.springframework.dao.CannotAcquireLockException("deadlock")).thenReturn(0);
        service.refresh();
        var order = org.mockito.Mockito.inOrder(manager);
        order.verify(manager).getTransaction(any());
        order.verify(manager).rollback(first);
        order.verify(manager).getTransaction(any());
        order.verify(manager).commit(second);
    }

    @Test
    void callerOwnedTransactionIsNotPartiallyRetried() {
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenThrow(new org.springframework.dao.CannotAcquireLockException("deadlock"));
            org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.CannotAcquireLockException.class, () -> service.refresh());
            verify(jdbcTemplate).update(anyString(), any(Object[].class));
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void persistentProjectionLockFailureRemainsVisibleAndRetriesAreBounded() {
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenThrow(new org.springframework.dao.CannotAcquireLockException("persistent lock"));
        var failure = org.junit.jupiter.api.Assertions.assertThrows(com.xianyusmart.exception.BusinessException.class,() -> service.refresh());
        org.junit.jupiter.api.Assertions.assertEquals(409, failure.getCode());
        verify(jdbcTemplate,times(3)).update(anyString(),any(Object[].class));
    }

    @Test
    void unrelatedProjectionFailureIsNeverRetried() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenThrow(new IllegalArgumentException("invalid SQL contract"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,() -> service.refresh());
        verify(jdbcTemplate).update(anyString(),any(Object[].class));
    }

    @Test
    void twelveSameTenantRefreshesDoNotOverlapProjectionStatements() throws Exception {
        var active = new java.util.concurrent.atomic.AtomicInteger();
        var peak = new java.util.concurrent.atomic.AtomicInteger();
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                Thread.sleep(2);
                return 0;
            } finally {
                active.decrementAndGet();
            }
        });
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(12)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int index = 0; index < 12; index++) futures.add(executor.submit(() -> {
                TenantContext.set(6L);
                try {
                    start.await();
                    return service.refresh();
                } finally {
                    TenantContext.clear();
                }
            }));
            start.countDown();
            for (var future : futures) org.junit.jupiter.api.Assertions.assertEquals(0,
                    future.get(5, java.util.concurrent.TimeUnit.SECONDS));
        }
        org.junit.jupiter.api.Assertions.assertEquals(1, peak.get());
        verify(jdbcTemplate, times(48)).update(anyString(), any(Object[].class));
    }

    @Test
    void refreshUsesMySql57CompatibleAggregateInHavingClause() {
        service.refresh();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(4)).update(sqlCaptor.capture(), any(Object[].class));
        List<String> statements = sqlCaptor.getAllValues();
        String insert = statements.stream()
                .filter(sql -> sql.contains("INSERT INTO conversation_assignment"))
                .findFirst()
                .orElseThrow();

        assertTrue(insert.contains("HAVING MIN(CASE WHEN messages.sender_user_id <> account.unb"));
        assertFalse(insert.contains("HAVING first_message_time"));
        String buyerProjection = statements.stream()
                .filter(sql -> sql.contains("INSERT INTO xianyu_buyer_profile"))
                .findFirst()
                .orElseThrow();
        assertTrue(buyerProjection.contains("assignment.buyer_user_id IS NOT NULL"));
        assertTrue(buyerProjection.contains("ON DUPLICATE KEY UPDATE"));
        String expiredTakeoverProjection = statements.stream()
                .filter(sql -> sql.contains("auto_reply_state='AUTO_READY'"))
                .findFirst().orElseThrow();
        assertTrue(expiredTakeoverProjection.contains("manual_takeover_state='AUTO'"));
        assertTrue(expiredTakeoverProjection.contains("NOT IN ('OPEN','CLAIMED')"));
    }
}
