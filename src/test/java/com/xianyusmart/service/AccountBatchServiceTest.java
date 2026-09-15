package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.entity.MerchantTask;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.mapper.MerchantTaskMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountBatchServiceTest {
    private final AccountMatrixService matrix = mock(AccountMatrixService.class);
    private final AccountAccessService access = mock(AccountAccessService.class);
    private final AccountBatchExecutionService execution = mock(AccountBatchExecutionService.class);
    private final MerchantTaskMapper tasks = mock(MerchantTaskMapper.class);
    private final OperationLogService logs = mock(OperationLogService.class);
    private AccountBatchService service;

    @BeforeEach void setUp() {
        TenantContext.set(1L);
        service = new AccountBatchService(matrix, access, execution, tasks, logs, new ObjectMapper());
    }
    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void previewReportsExecutableAndConflictWithoutTurningUnknownIntoZero() {
        when(matrix.accountDetail(101L)).thenReturn(account(101L, 1, "CONNECTED"));
        when(matrix.accountDetail(102L)).thenReturn(account(102L, 0, "UNKNOWN"));
        when(execution.isQaEligible(1L, 101L)).thenReturn(true);
        when(execution.isQaEligible(1L, 102L)).thenReturn(true);

        AccountBatchService.Preview preview = service.preview(request("SYNC", List.of(101L, 102L)));
        assertEquals(2, preview.selectedCount());
        assertEquals(1, preview.executableCount());
        assertEquals(1, preview.conflictCount());
        assertEquals("QA_MOCK", preview.executionChannel());
        assertEquals("账号已停用", preview.items().get(1).conflictMessage());
        assertFalse(preview.previewToken().isBlank());
    }

    @Test
    void filterSnapshotResolvesAcrossPagesAndHonorsExclusions() {
        when(matrix.listAccounts(null, null, null, null, 1, 100)).thenReturn(
                new AccountMatrixService.MatrixPage(List.of(account(101L, 1, "CONNECTED")), 2, 1, 100, 2));
        when(matrix.listAccounts(null, null, null, null, 2, 100)).thenReturn(
                new AccountMatrixService.MatrixPage(List.of(account(102L, 1, "CONNECTED")), 2, 2, 100, 2));
        AccountBatchService.Request request = new AccountBatchService.Request("qa-filter", "DISABLE", "FILTER_SNAPSHOT",
                List.of(), List.of(101L), new AccountBatchService.Filter(null, null, null, null), null, null);

        AccountBatchService.Preview preview = service.preview(request);
        assertEquals(1, preview.selectedCount());
        assertEquals(102L, preview.items().getFirst().accountId());
    }

    @Test
    void createPersistsOneTaskPerExecutableAccountAndAuditsBatch() {
        when(matrix.accountDetail(101L)).thenReturn(account(101L, 1, "CONNECTED"));
        when(execution.isQaEligible(1L, 101L)).thenReturn(true);
        when(tasks.selectByRequestKey(eq(1L), eq("ACCOUNT_SYNC"), anyString())).thenReturn(null);
        MerchantTask stored = new MerchantTask(); stored.setId(11L); stored.setTaskType("ACCOUNT_SYNC");
        stored.setXianyuAccountId(101L); stored.setStatus(0);
        when(tasks.selectByBatchId(eq(1L), anyString())).thenReturn(List.of(), List.of(stored));
        AccountBatchService.Preview preview = service.preview(request("SYNC", List.of(101L)));
        AccountBatchService.Request create = new AccountBatchService.Request("qa-create", "SYNC", "EXPLICIT",
                List.of(101L), List.of(), new AccountBatchService.Filter(null, null, null, null),
                preview.confirmationSummary(), preview.previewToken());

        Map<String, Object> result = service.create(create);
        assertEquals("QUEUED", result.get("status"));
        assertEquals(false, result.get("idempotentReplay"));
        ArgumentCaptor<MerchantTask> captor = ArgumentCaptor.forClass(MerchantTask.class);
        verify(tasks).insert(captor.capture());
        assertEquals("ACCOUNT_SYNC", captor.getValue().getTaskType());
        assertEquals(1, captor.getValue().getMaxAttempts());
        verify(logs).logRequired(any());
    }

    @Test
    void idempotentReplayReturnsExistingBatchWithoutRevalidatingChangedAccountState() {
        when(matrix.accountDetail(101L)).thenReturn(account(101L, 1, "CONNECTED"));
        when(execution.isQaEligible(1L, 101L)).thenReturn(true);
        when(tasks.selectByRequestKey(eq(1L), eq("ACCOUNT_SYNC"), anyString())).thenReturn(null);
        MerchantTask stored = new MerchantTask();
        stored.setId(12L);
        stored.setTaskType("ACCOUNT_SYNC");
        stored.setXianyuAccountId(101L);
        stored.setStatus(2);
        when(tasks.selectByBatchId(eq(1L), anyString())).thenReturn(List.of(), List.of(stored), List.of(stored), List.of(stored), List.of(stored), List.of(stored));
        doAnswer(invocation -> {
            MerchantTask inserted = invocation.getArgument(0);
            stored.setRequestJson(inserted.getRequestJson());
            return 1;
        }).when(tasks).insert(any());
        AccountBatchService.Preview preview = service.preview(request("SYNC", List.of(101L)));
        AccountBatchService.Request replay = new AccountBatchService.Request("qa-replay", "SYNC", "EXPLICIT",
                List.of(101L), List.of(), new AccountBatchService.Filter(null, null, null, null),
                preview.confirmationSummary(), preview.previewToken());

        Map<String, Object> first = service.create(replay);
        assertEquals(false, first.get("idempotentReplay"));
        clearInvocations(matrix, access, execution, logs);

        Map<String, Object> result = service.create(replay);

        assertEquals("SUCCEEDED", result.get("status"));
        assertEquals(true, result.get("idempotentReplay"));
        verifyNoInteractions(matrix);
        verifyNoInteractions(logs);

        AccountBatchService.Request changedAccounts = new AccountBatchService.Request("qa-replay", "SYNC", "EXPLICIT",
                List.of(102L), List.of(), replay.filter(), replay.confirmationText(), replay.previewToken());
        AccountBatchService.Request changedExclusions = new AccountBatchService.Request("qa-replay", "SYNC", "EXPLICIT",
                List.of(101L), List.of(999L), replay.filter(), replay.confirmationText(), replay.previewToken());
        AccountBatchService.Request changedFilter = new AccountBatchService.Request("qa-replay", "SYNC", "EXPLICIT",
                List.of(101L), List.of(), new AccountBatchService.Filter("不同筛选", null, null, null),
                replay.confirmationText(), replay.previewToken());
        assertEquals(409, assertThrows(BusinessException.class, () -> service.create(changedAccounts)).getCode());
        assertEquals(409, assertThrows(BusinessException.class, () -> service.create(changedExclusions)).getCode());
        assertEquals(409, assertThrows(BusinessException.class, () -> service.create(changedFilter)).getCode());
    }

    private AccountBatchService.Request request(String operation, List<Long> ids) {
        return new AccountBatchService.Request("qa-preview", operation, "EXPLICIT", ids, List.of(),
                new AccountBatchService.Filter(null, null, null, null), null, null);
    }
    private static Map<String, Object> account(long id, int status, String connection) {
        return Map.of("accountId", id, "accountNote", "QA " + id, "accountStatus", status,
                "connectionStatus", connection, "authorizationStatus", "AUTHORIZED");
    }
}
