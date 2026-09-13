package com.xianyusmart.service;

import com.xianyusmart.context.UserContext;
import com.xianyusmart.entity.XianyuOperationLog;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.mapper.XianyuOperationLogMapper;
import com.xianyusmart.service.impl.OperationLogServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationLogServiceImplTest {

    private XianyuOperationLogMapper mapper;
    private OperationLogServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(XianyuOperationLogMapper.class);
        service = new OperationLogServiceImpl(mapper);
        UserContext.set(8L, "auditor", 1L);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void querySupportsAllAuditDimensionsAndNormalizesOutcome() {
        XianyuOperationLog log = new XianyuOperationLog();
        log.setId(1L);
        when(mapper.selectByPage(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(log));
        when(mapper.countByCondition(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        OperationLogService.AuditLogQuery query = new OperationLogService.AuditLogQuery(
                12L, "UPDATE", "商品", 1, "platform_confirmed", "operator",
                "req-1", 100L, 200L, "关键字", 2, 30);

        Map<String, Object> result = service.queryLogs(query);

        assertEquals(1, result.get("total"));
        verify(mapper).selectByPage(eq(12L), eq("UPDATE"), eq("商品"), eq(1),
                eq("PLATFORM_CONFIRMED"), eq("operator"), eq("req-1"), eq(100L), eq(200L),
                eq("关键字"), eq(30), eq(30));
    }

    @Test
    void invalidTimeRangeIsRejected() {
        OperationLogService.AuditLogQuery query = new OperationLogService.AuditLogQuery(
                null, null, null, null, null, null, null,
                200L, 100L, null, 1, 20);
        BusinessException error = assertThrows(BusinessException.class, () -> service.queryLogs(query));
        assertEquals(400, error.getCode());
    }

    @Test
    void exportWritesCsvAndAuditsTheExport() {
        XianyuOperationLog item = new XianyuOperationLog();
        item.setOperationDesc("包含,逗号");
        item.setOutcomeState("LOCAL_SUCCESS");
        when(mapper.selectByPage(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(item));
        when(mapper.countByCondition(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        String csv = service.exportCsv(new OperationLogService.AuditLogQuery(
                null, null, null, null, null, null, "export-req",
                null, null, null, 1, 20));

        assertTrue(csv.contains("\"包含,逗号\""));
        ArgumentCaptor<XianyuOperationLog> captor = ArgumentCaptor.forClass(XianyuOperationLog.class);
        verify(mapper).insert(captor.capture());
        assertEquals("AUDIT_EXPORT", captor.getValue().getOperationType());
        assertEquals("LOCAL_SUCCESS", captor.getValue().getOutcomeState());
        assertEquals("auditor", captor.getValue().getOperatorUsername());
    }
}
