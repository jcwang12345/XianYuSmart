package com.xianyusmart.service;
import com.xianyusmart.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountAutomationPolicyTest {
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="D2_R03_NATIVE",matches="true")
    void nativeMysqlLifecycleCommandsAndRequiredAuditRollback() throws Exception {
        try {
            Class.forName("com.xianyusmart.service.NativeR03Support").getMethod(
                    "true".equals(System.getenv("D2_R03_ATOMICITY")) ? "verifyAtomicityLifecycle" : "verifyLifecycle").invoke(null);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception error) throw error;
            if (failure.getCause() instanceof Error error) throw error;
            throw failure;
        }
    }
    private AccountAutomationPolicy policy(String state,int reply,int delivery){
        var jdbc=mock(JdbcTemplate.class);when(jdbc.queryForList(anyString(),eq(900001L))).thenReturn(List.of(Map.of("lifecycleState",state,"autoReplyAllowed",reply,"autoDeliveryAllowed",delivery)));return new AccountAutomationPolicy(jdbc);
    }
    @Test void deliveryPolicyBlocksAutomaticDiscoveryButNotConnectionOrManualSync(){
        var p=policy("ACTIVE",0,0);assertFalse(p.allows(900001L,AccountAutomationPolicy.Action.DISCOVERY));assertFalse(p.allows(900001L,AccountAutomationPolicy.Action.DELIVERY));assertFalse(p.allows(900001L,AccountAutomationPolicy.Action.REPLY));assertTrue(p.allows(900001L,AccountAutomationPolicy.Action.CONNECT));assertTrue(p.allows(900001L,AccountAutomationPolicy.Action.SYNC));
    }
    @Test void disabledAndArchivedBlockAllNewActions(){for(String state:List.of("DISABLED","ARCHIVED"))for(var action:AccountAutomationPolicy.Action.values())assertFalse(policy(state,1,1).allows(900001L,action));}
    @Test void activeExternalExecutionMakesMutationExplicitlyBusy()throws Exception{
        var p=policy("ACTIVE",1,1);var active=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()){
            Future<?> worker=executor.submit(()->p.guarded(900001L,AccountAutomationPolicy.Action.DELIVERY,()->{active.countDown();try{assertTrue(release.await(6,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return true;}));
            assertTrue(active.await(3,TimeUnit.SECONDS));TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.initSynchronization();
            try{var error=assertThrows(BusinessException.class,()->p.lockMutationUntilCompletion(900001L));assertEquals(409,error.getCode());}
            finally{release.countDown();TransactionSynchronizationManager.clearSynchronization();TransactionSynchronizationManager.setActualTransactionActive(false);}
            worker.get(3,TimeUnit.SECONDS);
        }finally{release.countDown();}
    }
    @Test void mutationLockHoldsUntilTransactionCompletes()throws Exception{
        var p=policy("ACTIVE",1,1);TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.initSynchronization();
        try(var executor=Executors.newSingleThreadExecutor()){
            p.lockMutationUntilCompletion(900001L);var started=new CountDownLatch(1);
            Future<?> worker=executor.submit(()->{started.countDown();return p.guarded(900001L,AccountAutomationPolicy.Action.REPLY,()->true);});
            assertTrue(started.await(3,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->worker.get(100,TimeUnit.MILLISECONDS));
            for(var synchronization:TransactionSynchronizationManager.getSynchronizations())synchronization.afterCompletion(0);
            assertEquals(true,worker.get(3,TimeUnit.SECONDS));
        }finally{TransactionSynchronizationManager.clearSynchronization();TransactionSynchronizationManager.setActualTransactionActive(false);}
    }
}
