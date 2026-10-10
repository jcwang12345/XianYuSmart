package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BuyerProfileServiceRetryTest {
    private final XianyuBuyerProfileMapper profiles = mock(XianyuBuyerProfileMapper.class);
    private final XianyuAccountMapper accounts = mock(XianyuAccountMapper.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private BuyerProfileService service;

    @BeforeEach
    void setUp() {
        service = new BuyerProfileService(profiles, accounts, new ObjectMapper(),
                mock(XianyuGoodsOrderMapper.class), mock(XianyuChatMessageMapper.class),
                mock(XianyuBuyerProfileRequestMapper.class), mock(OperationLogService.class));
        service.setMessageProjectionTransactionManager(transactions);
        XianyuAccount account = new XianyuAccount();
        account.setTenantId(7L);
        when(accounts.selectById(101L)).thenReturn(account);
        when(transactions.getTransaction(any())).thenAnswer(invocation -> {
            TransactionDefinition definition = invocation.getArgument(0);
            assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, definition.getPropagationBehavior());
            return new SimpleTransactionStatus();
        });
    }

    @Test
    void transientDeadlockRollsBackBeforeNewTransactionAndCommitsOnce() {
        when(profiles.touch(anyLong(), anyLong(), anyString(), any(), any()))
                .thenThrow(new CannotAcquireLockException("1213")).thenReturn(1);
        service.touchFromMessage(101L, "buyer", "name", 1700000000000L);
        var order = inOrder(transactions, profiles);
        order.verify(transactions).getTransaction(any());
        order.verify(profiles).touch(eq(7L), eq(101L), eq("buyer"), eq("name"), any());
        order.verify(transactions).rollback(any());
        order.verify(transactions).getTransaction(any());
        order.verify(profiles).touch(eq(7L), eq(101L), eq("buyer"), eq("name"), any());
        order.verify(transactions).commit(any());
        verify(transactions, times(1)).commit(any());
    }

    @Test
    void persistentDeadlockFailsAfterExactlyThreeRolledBackAttempts() {
        when(profiles.touch(anyLong(), anyLong(), anyString(), any(), any()))
                .thenThrow(new CannotAcquireLockException("1213"));
        assertThrows(CannotAcquireLockException.class,
                () -> service.touchFromMessage(101L, "buyer", "name", 1700000000000L));
        verify(profiles, times(3)).touch(anyLong(), anyLong(), anyString(), any(), any());
        verify(transactions, times(3)).rollback(any());
        verify(transactions, never()).commit(any());
    }

    @Test
    void nonLockFailureRollsBackWithoutRetry() {
        when(profiles.touch(anyLong(), anyLong(), anyString(), any(), any()))
                .thenThrow(new IllegalArgumentException("invalid contract"));
        assertThrows(IllegalArgumentException.class,
                () -> service.touchFromMessage(101L, "buyer", "name", 1700000000000L));
        verify(profiles).touch(anyLong(), anyLong(), anyString(), any(), any());
        verify(transactions).rollback(any());
    }

    @Test
    void interruptedConflictDoesNotRetryOrClearInterrupt() {
        when(profiles.touch(anyLong(), anyLong(), anyString(), any(), any()))
                .thenThrow(new CannotAcquireLockException("1213"));
        Thread.currentThread().interrupt();
        try {
            assertThrows(CannotAcquireLockException.class,
                    () -> service.touchFromMessage(101L, "buyer", "name", 1700000000000L));
            assertTrue(Thread.currentThread().isInterrupted());
            verify(profiles).touch(anyLong(), anyLong(), anyString(), any(), any());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void ordinaryTouchRetainsCallerTransactionSemanticsAndDoesNotRetry() {
        when(profiles.touch(anyLong(), anyLong(), anyString(), any(), any()))
                .thenThrow(new CannotAcquireLockException("1213"));
        assertThrows(CannotAcquireLockException.class,
                () -> service.touch(101L, "buyer", "name", 1700000000000L));
        verifyNoInteractions(transactions);
        verify(profiles).touch(anyLong(), anyLong(), anyString(), any(), any());
    }
}
