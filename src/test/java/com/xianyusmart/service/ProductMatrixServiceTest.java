package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductMatrixServiceTest {

    private JdbcTemplate jdbcTemplate;
    private NamedParameterJdbcTemplate namedJdbc;
    private AccountAccessService accountAccessService;
    private OperationLogService operationLogService;
    private ProductMatrixService service;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        namedJdbc = mock(NamedParameterJdbcTemplate.class);
        accountAccessService = mock(AccountAccessService.class);
        operationLogService = mock(OperationLogService.class);
        service = new ProductMatrixService(jdbcTemplate, namedJdbc, accountAccessService,
                operationLogService, new ObjectMapper());
        UserContext.set(4L, "product-tester", 9L);
        when(namedJdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Integer.class))).thenReturn(0);
        when(namedJdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
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
                assertEquals(16, args.getAllValues().get(i).length);
                jobShape = true;
            }
            if (sql.getAllValues().get(i).contains("INSERT INTO xianyu_goods_batch_item")) {
                assertEquals(12, args.getAllValues().get(i).length);
                itemShape = true;
            }
        }
        assertTrue(jobShape && itemShape);
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

    private Map<String, Object> batch(Long id) {
        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("jobId", id);
        batch.put("batchId", "PB-TEST");
        batch.put("operationType", "SYNC");
        batch.put("status", "QUEUED");
        return batch;
    }
}
