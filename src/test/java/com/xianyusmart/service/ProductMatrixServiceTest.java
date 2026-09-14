package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.AccountScopeContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.entity.XianyuGoodsConfig;
import com.xianyusmart.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.dao.DuplicateKeyException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductMatrixServiceTest {

    private JdbcTemplate jdbcTemplate;
    private NamedParameterJdbcTemplate namedJdbc;
    private AccountAccessService accountAccessService;
    private OperationLogService operationLogService;
    private ProductBatchQaMockService qaMockService;
    private ProductMatrixService service;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        namedJdbc = mock(NamedParameterJdbcTemplate.class);
        accountAccessService = mock(AccountAccessService.class);
        operationLogService = mock(OperationLogService.class);
        qaMockService = mock(ProductBatchQaMockService.class);
        service = new ProductMatrixService(jdbcTemplate, namedJdbc, accountAccessService,
                operationLogService, new ObjectMapper(), qaMockService);
        UserContext.set(4L, "product-tester", 9L);
        when(namedJdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Integer.class))).thenReturn(0);
        when(namedJdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        AccountScopeContext.clear();
        UserContext.clear();
    }

    @Test
    void emptyProductPageDoesNotPresentUnknownStockOrPriceAsZero() {
        Map<String, Object> result = service.list(null);

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertNull(summary.get("averagePrice"));
        assertNull(summary.get("knownStockTotal"));
        assertEquals("UNSYNCED", summary.get("metricCoverageStatus"));
        assertEquals("FILTERED_RESULT", result.get("summaryScope"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void exactGoodsOrOuterIdMatchIsRankedBeforePrefixMatches() {
        service.list(new ProductMatrixService.ProductFilter(
                "QA-OUTER-1", List.of(), null, "ALL", null, null, 7, 1, 20));

        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<SqlParameterSource> params = org.mockito.ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(namedJdbc).query(sql.capture(), params.capture(), any(RowMapper.class));
        assertTrue(sql.getValue().contains("CASE WHEN goods.xy_good_id=:exactSearch OR goods.outer_id=:exactSearch THEN 0 ELSE 1 END"));
        assertEquals("QA-OUTER-1", params.getValue().getValue("exactSearch"));
        assertEquals("%QA-OUTER-1%", params.getValue().getValue("search"));
    }

    @Test
    void skuEvidenceRejectsDeclaredCountWithoutSyncedChildren() {
        Map<String, Object> evidence = ProductMatrixService.skuEvidence(
                Map.of("skuCount", 4, "coverageStatus", "FULL"), List.of());

        assertEquals(4, evidence.get("declaredCount"));
        assertEquals(0, evidence.get("verifiedCount"));
        assertEquals("UNSYNCED", evidence.get("coverageStatus"));
        assertEquals(false, evidence.get("consistent"));
        assertTrue(String.valueOf(evidence.get("message")).contains("不能视为无 SKU"));
    }

    @Test
    void skuEvidenceAcceptsMatchingMultiSkuRows() {
        List<Map<String, Object>> rows = List.of(Map.of("skuId", "1"), Map.of("skuId", "2"),
                Map.of("skuId", "3"), Map.of("skuId", "4"));

        Map<String, Object> evidence = ProductMatrixService.skuEvidence(
                Map.of("skuCount", 4, "coverageStatus", "FULL"), rows);

        assertEquals(4, evidence.get("verifiedCount"));
        assertEquals("FULL", evidence.get("coverageStatus"));
        assertEquals(true, evidence.get("consistent"));
    }

    @Test
    void skuEvidenceOnlyCallsZeroVerifiedForFullProductCoverage() {
        Map<String, Object> verifiedEmpty = ProductMatrixService.skuEvidence(
                Map.of("skuCount", 0, "coverageStatus", "FULL"), List.of());
        Map<String, Object> unknownEmpty = ProductMatrixService.skuEvidence(
                Map.of("skuCount", 0, "coverageStatus", "PARTIAL"), List.of());

        assertEquals("EMPTY_VERIFIED", verifiedEmpty.get("coverageStatus"));
        assertEquals("PARTIAL", unknownEmpty.get("coverageStatus"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void fieldDiffContainsReadableBeforeAndAfterForChangedFieldsOnly() {
        Map<String, Object> diff = ProductMatrixService.fieldDiff("LOCAL_ONLY",
                mapWithNulls("title", "旧标题", "supportPolicy", null, "location", "上海"),
                mapWithNulls("title", "新标题", "supportPolicy", "七天售后", "location", "上海"),
                Map.of("title", "商品标题", "supportPolicy", "支持政策", "location", "所在地"));

        assertEquals(2, diff.get("changedFieldCount"));
        assertEquals(List.of("title", "supportPolicy"), diff.get("changedFields"));
        Map<String, Object> fields = (Map<String, Object>) diff.get("fields");
        assertEquals(Map.of("label", "商品标题", "before", "旧标题", "after", "新标题"), fields.get("title"));
        Map<String, Object> support = (Map<String, Object>) fields.get("supportPolicy");
        assertNull(support.get("before"));
        assertEquals("七天售后", support.get("after"));
        assertEquals(false, fields.containsKey("location"));
    }

    @Test
    void fieldDiffMakesNoopExplicitWithoutInventingAChange() {
        Map<String, Object> before = Map.of("autoReplyEnabled", true);
        Map<String, Object> diff = ProductMatrixService.fieldDiff("LOCAL_ONLY", before, before,
                Map.of("autoReplyEnabled", "自动回复"));

        assertEquals(0, diff.get("changedFieldCount"));
        assertEquals(List.of(), diff.get("changedFields"));
        assertEquals(Map.of(), diff.get("fields"));
    }

    @Test
    void automationFirstSaveSuppliesRequiredRatingContent() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        ProductMatrixService.AutomationUpdate command = new ProductMatrixService.AutomationUpdate(
                true, true, false, true, false, "req-automation-first-save");

        service.updateAutomation(2L, "goods-1", command);

        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<Object[]> args = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, atLeast(1)).update(sql.capture(), args.capture());
        boolean found = false;
        for (int index = 0; index < sql.getAllValues().size(); index++) {
            if (sql.getAllValues().get(index).contains("INSERT INTO xianyu_goods_config")) {
                assertTrue(sql.getAllValues().get(index).contains("xianyu_auto_rate_content"));
                assertEquals(9, args.getAllValues().get(index).length);
                assertEquals(XianyuGoodsConfig.DEFAULT_AUTO_RATE_CONTENT, args.getAllValues().get(index)[6]);
                found = true;
            }
        }
        assertTrue(found);
    }

    @Test
    void automationReplayReturnsWithoutUpdatingOrAuditingAgain() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "after_json", "{\"autoDeliveryEnabled\":false,\"autoReplyEnabled\":true,"
                        + "\"autoRateEnabled\":false,\"autoPolishEnabled\":false,"
                        + "\"humanTakeoverEnabled\":false}")));
        ProductMatrixService.AutomationUpdate command = new ProductMatrixService.AutomationUpdate(
                false, true, false, false, false, "req-automation-replay");

        Map<String, Object> result = service.updateAutomation(2L, "goods-1", command);

        assertEquals(true, result.get("idempotentReplay"));
        assertEquals(true, result.get("autoReplyEnabled"));
        assertEquals("req-automation-replay", result.get("requestId"));
        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
        assertTrue(sql.getValue().contains("INSERT IGNORE INTO xianyu_goods_event"));
        verify(operationLogService, never()).log(any(com.xianyusmart.entity.XianyuOperationLog.class));
    }

    @Test
    void automationReplayRejectsRequestIdReusedForDifferentPayload() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "after_json", "{\"autoDeliveryEnabled\":false,\"autoReplyEnabled\":false,"
                        + "\"autoRateEnabled\":false,\"autoPolishEnabled\":false,"
                        + "\"humanTakeoverEnabled\":false}")));
        ProductMatrixService.AutomationUpdate command = new ProductMatrixService.AutomationUpdate(
                false, true, false, false, false, "req-automation-conflict");

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.updateAutomation(2L, "goods-1", command));

        assertEquals(409, error.getCode());
        assertTrue(error.getMessage().contains("不同的自动化配置"));
        verify(operationLogService, never()).log(any(com.xianyusmart.entity.XianyuOperationLog.class));
    }

    @Test
    void batchPreviewShowsExactAccountProductAndConflictScope() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(product(0, "PLATFORM_LIST_SYNC")));
        ProductMatrixService.BatchRequest request = request("CHANGE_PRICE", Map.of("price", "19.90"), null);

        ProductMatrixService.BatchPreview preview = service.previewBatch(request);

        assertEquals(1, preview.selectedCount());
        assertEquals(1, preview.accountCount());
        assertEquals(1, preview.conflictCount());
        assertEquals(0, preview.executableCount());
        assertTrue(preview.items().getFirst().conflictMessage().contains("禁止创建只改本地缓存"));
        verify(accountAccessService, atLeast(1)).requireAccess(2L);
    }

    @Test
    void qaMockPreviewBypassesPlatformReadinessButMakesIsolationExplicit() {
        when(qaMockService.isEligible(9L, 2L, "goods-1")).thenReturn(true);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(product(1, "MANUAL_IMPORT")));

        ProductMatrixService.BatchPreview preview = service.previewBatch(request("DELETE", Map.of(), null));

        assertEquals("QA_MOCK", preview.executionChannel());
        assertEquals(1, preview.executableCount());
        assertTrue(preview.confirmationSummary().contains("不触达平台"));
        assertTrue(preview.executionNotice().contains("不发起平台网络请求"));
    }

    @Test
    void batchPricePreviewRejectsMoreThanTwoDecimalPlacesBeforeCreatingToken() {
        for (String price : List.of("0.001", "1.234")) {
            ProductMatrixService.BatchRequest request = request("CHANGE_PRICE", Map.of("price", price), null);

            BusinessException error = assertThrows(BusinessException.class, () -> service.previewBatch(request));

            assertEquals(400, error.getCode());
            assertTrue(error.getMessage().contains("最多保留两位小数"));
        }
        verify(jdbcTemplate, never()).queryForList(anyString(), any(Object[].class));
    }

    @Test
    void batchPricePreviewAcceptsTwoDecimalsAndExactUpperLimit() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(product(0, "PLATFORM_LIST_SYNC")));

        for (String price : List.of("12.34", "9999999.99")) {
            ProductMatrixService.BatchPreview preview = service.previewBatch(
                    request("CHANGE_PRICE", Map.of("price", price), null));

            assertFalse(preview.previewToken().isBlank());
        }
    }

    @Test
    void batchPricePreviewRejectsValueAboveUpperLimit() {
        ProductMatrixService.BatchRequest request = request(
                "CHANGE_PRICE", Map.of("price", "10000000.00"), null);

        BusinessException error = assertThrows(BusinessException.class, () -> service.previewBatch(request));

        assertEquals(400, error.getCode());
        assertTrue(error.getMessage().contains("9999999.99"));
        verify(jdbcTemplate, never()).queryForList(anyString(), any(Object[].class));
    }

    @Test
    void creatingBatchRequiresLatestExactConfirmationText() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            return sql.contains("xianyu_goods_batch_job") ? List.of() : List.of(product(0, "PLATFORM_LIST_SYNC"));
        });
        ProductMatrixService.BatchRequest request = request("SYNC", Map.of(), "过期的确认范围");

        BusinessException error = assertThrows(BusinessException.class, () -> service.createBatch(request));

        assertEquals(400, error.getCode());
        assertTrue(error.getMessage().contains("确认范围已变化"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void supportedBatchPersistsJobAndItemsWithIdempotentRequest() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            return sql.contains("SELECT id, batch_id") ? List.of() : List.of(product(0, "PLATFORM_LIST_SYNC"));
        });
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(77L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("SELECT job.*")) return List.of(batch(77L));
            if (sql.contains("xianyu_goods_batch_item item")) return List.of();
            return List.of();
        });
        ProductMatrixService.BatchRequest draft = request("SYNC", Map.of(), null);
        ProductMatrixService.BatchPreview preview = service.previewBatch(draft);
        ProductMatrixService.BatchRequest confirmed = confirmedRequest("SYNC", Map.of(), preview);

        Map<String, Object> result = service.createBatch(confirmed);

        assertEquals(77L, result.get("jobId"));
        assertEquals(false, result.get("idempotentReplay"));
        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<Object[]> args = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, atLeast(2)).update(sql.capture(), args.capture());
        boolean jobShape = false;
        boolean itemShape = false;
        for (int i = 0; i < sql.getAllValues().size(); i++) {
            if (sql.getAllValues().get(i).contains("INSERT INTO xianyu_goods_batch_job")) {
                assertEquals(17, args.getAllValues().get(i).length);
                jobShape = true;
            }
            if (sql.getAllValues().get(i).contains("INSERT INTO xianyu_goods_batch_item")) {
                assertEquals(12, args.getAllValues().get(i).length);
                itemShape = true;
            }
        }
        assertTrue(jobShape && itemShape);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void concurrentIdempotencyWinnerIsReturnedAsSuccessfulReplay() {
        Map<String, Object> winner = Map.of("id", 77L, "batch_id", "PB-WINNER");
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenAnswer(new org.mockito.stubbing.Answer<>() {
                    int replayReads;
                    @Override public List<Map<String, Object>> answer(org.mockito.invocation.InvocationOnMock call) {
                        String sql = call.getArgument(0);
                        if (sql.contains("idempotency_key")) return replayReads++ == 0 ? List.of() : List.of(winner);
                        return List.of(product(0, "PLATFORM_LIST_SYNC"));
                    }
                });
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenAnswer(call -> {
            if (((String) call.getArgument(0)).contains("INSERT INTO xianyu_goods_batch_job")) {
                throw new DuplicateKeyException("concurrent winner");
            }
            return 1;
        });
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(call -> {
            String sql = call.getArgument(0);
            if (sql.contains("SELECT job.*")) return List.of(batch(77L));
            return List.of();
        });
        ProductMatrixService.BatchRequest draft = request("SYNC", Map.of(), null);
        ProductMatrixService.BatchPreview preview = service.previewBatch(draft);

        Map<String, Object> result = service.createBatch(confirmedRequest("SYNC", Map.of(), preview));

        assertEquals(77L, result.get("jobId"));
        assertEquals(true, result.get("idempotentReplay"));
        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeast(1)).update(sql.capture(), any(Object[].class));
        assertFalse(sql.getAllValues().stream().anyMatch(value -> value.contains("INSERT INTO xianyu_goods_batch_item")));
        verify(operationLogService, never()).log(any(com.xianyusmart.entity.XianyuOperationLog.class));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void retryClearsCurrentFailureEvidenceAndRearmsTerminalNotification() {
        Map<String, Object> failedJob = new LinkedHashMap<>();
        failedJob.put("jobId", 77L);
        failedJob.put("batchId", "PB-FAIL-ONCE");
        failedJob.put("status", "FAILED");
        failedJob.put("maxOperationsPerMinute", 30);
        Map<String, Object> failedItem = new LinkedHashMap<>();
        failedItem.put("itemId", 88L);
        failedItem.put("accountId", 2L);
        failedItem.put("goodsId", "goods-1");
        failedItem.put("status", "FAILED");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> ((String) invocation.getArgument(0)).contains("SELECT job.*")
                        ? List.of(failedJob) : List.of(failedItem));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        Map<String, Object> result = service.retryBatchFailures(77L, "retry-fail-once", List.of(88L));

        assertEquals(1, result.get("retriedCount"));
        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeast(3)).update(sql.capture(), any(Object[].class));
        assertTrue(sql.getAllValues().stream().anyMatch(value -> value.contains("status='QUEUED'")
                && value.contains("error_code=NULL") && value.contains("result_json=NULL")
                && value.contains("platform_request_id=NULL") && value.contains("outcome_state='QUEUED'")));
        assertTrue(sql.getAllValues().stream().anyMatch(value -> value.contains("notification_sent=0")
                && value.contains("notification_evidence_json=NULL")));
    }

    @Test
    void restrictedBatchStatusDoesNotTurnAllCancelledOrUnknownIntoFailed() {
        assertEquals("CANCELLED", ProductMatrixService.visibleBatchStatus(
                "CANCELLED", 334, 0, 0, 0, 0, 0, 334, 0));
        assertEquals("UNKNOWN", ProductMatrixService.visibleBatchStatus(
                "FAILED", 34, 0, 0, 0, 34, 0, 0, 0));
        assertEquals("SKIPPED", ProductMatrixService.visibleBatchStatus(
                "FAILED", 10, 0, 0, 0, 0, 10, 0, 0));
        assertEquals("PARTIAL_SUCCESS", ProductMatrixService.visibleBatchStatus(
                "PARTIAL_SUCCESS", 34, 0, 32, 0, 2, 0, 0, 0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void restrictedBatchRedactsFullScopeConfirmationAndNotificationEvidence() {
        AccountScopeContext.set(false, Set.of(101L));
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("jobId", 3L);
        job.put("operationType", "SYNC");
        job.put("status", "PARTIAL_SUCCESS");
        job.put("maxOperationsPerMinute", 30);
        job.put("confirmationSummary", "确认对3个店铺的100个商品执行同步");
        job.put("notificationEvidence", Map.of("successCount", 80, "failedCount", 15, "unknownCount", 5));
        List<Map<String, Object>> visible = new ArrayList<>();
        for (int index = 0; index < 32; index++) visible.add(Map.of("status", "SUCCEEDED", "accountId", 101L));
        for (int index = 0; index < 2; index++) visible.add(Map.of("status", "UNKNOWN", "accountId", 101L));

        service.redactJobToVisibleItems(job, visible);

        assertEquals(34L, job.get("selectedCount"));
        assertEquals(1L, job.get("accountCount"));
        assertEquals("PARTIAL_SUCCESS", job.get("status"));
        assertEquals("当前权限范围内：1个店铺、34个商品，操作：同步", job.get("confirmationSummary"));
        Map<String, Object> evidence = (Map<String, Object>) job.get("notificationEvidence");
        assertEquals(32L, evidence.get("successCount"));
        assertEquals(2L, evidence.get("unknownCount"));
        assertEquals(true, evidence.get("visibleScopeOnly"));
        assertNull(evidence.get("externalDispatched"));
    }

    private ProductMatrixService.BatchRequest request(String operation, Map<String, Object> params, String confirmation) {
        return new ProductMatrixService.BatchRequest("req-products", "idem-products", operation, "EXPLICIT_IDS",
                List.of(new ProductMatrixService.ProductRef(2L, "goods-1")), List.of(), null,
                params, 10, confirmation, null);
    }

    private ProductMatrixService.BatchRequest confirmedRequest(String operation, Map<String, Object> params,
                                                                ProductMatrixService.BatchPreview preview) {
        return new ProductMatrixService.BatchRequest("req-products", "idem-products", operation, "EXPLICIT_IDS",
                List.of(new ProductMatrixService.ProductRef(2L, "goods-1")), List.of(), null,
                params, 10, preview.confirmationSummary(), preview.previewToken());
    }

    private Map<String, Object> product(int status, String source) {
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("title", "测试商品");
        product.put("status", status);
        product.put("product_source", source);
        product.put("sync_status", "SUCCEEDED");
        product.put("coverage_status", "PARTIAL");
        product.put("row_version", 1L);
        product.put("account_status", 1);
        product.put("credential_ready", 1);
        product.put("sold_price", "10.00");
        product.put("stock", 2);
        return product;
    }

    private Map<String, Object> mapWithNulls(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    private Map<String, Object> batch(Long id) {
        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("jobId", id);
        batch.put("batchId", "PB-TEST");
        batch.put("operationType", "SYNC");
        batch.put("status", "QUEUED");
        return batch;
    }
}
