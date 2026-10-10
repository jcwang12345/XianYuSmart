package com.xianyusmart.service.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.service.AccountService;
import com.xianyusmart.utils.XianyuApiCallUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderDetailFetcherQuantityTest {
    static Stream<Object> validQuantities() {
        return Stream.of("3", "003", 3, 3L, (short) 3, (byte) 3, 3.0d, 3.0f,
                new BigDecimal("3.000"), new BigInteger("3"));
    }

    static Stream<Object> invalidQuantities() {
        return Stream.of("", " ", " 3", "3 ", "+3", "-1", "0", "1.5", "3.0", "1e3",
                "not-a-count", "2147483648", "999999999999999999999999", 0, -1,
                Integer.MIN_VALUE, 2147483648L, Long.MAX_VALUE, 1.5d, 0.1f, Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, new BigDecimal("1.5"),
                new BigDecimal("2147483648"), new BigInteger("999999999999999999999999"),
                Boolean.TRUE, Map.of("quantity", 1));
    }

    @ParameterizedTest
    @MethodSource("validQuantities")
    void acceptsOnlyExactPositiveIntegerValue(Object value) {
        assertEquals(3, OrderDetailFetcher.parsePositiveQuantity(value));
        assertEquals(3, parsePrice(value).buyNum);
    }

    @ParameterizedTest
    @MethodSource("invalidQuantities")
    void malformedFractionalOrOverflowQuantityRemainsUnknown(Object value) {
        assertNull(OrderDetailFetcher.parsePositiveQuantity(value));
        assertNull(parsePrice(value).buyNum);
    }

    @Test
    void missingQuantityOrPriceModuleNeverDefaultsToOne() {
        assertNull(OrderDetailFetcher.parsePositiveQuantity(null));
        assertNull(parsePrice(null).buyNum);
        OrderDetailFetcher.OrderDetailInfo info = new OrderDetailFetcher.OrderDetailInfo();
        ReflectionTestUtils.invokeMethod(new OrderDetailFetcher(), "parsePriceInfo", Map.of(), info);
        assertNull(info.buyNum);
    }

    @Test
    void maxIntegerRemainsExactRatherThanOverflowing() {
        assertEquals(Integer.MAX_VALUE, OrderDetailFetcher.parsePositiveQuantity("2147483647"));
        assertEquals(Integer.MAX_VALUE, OrderDetailFetcher.parsePositiveQuantity(new BigDecimal("2147483647.000")));
    }

    @Test
    void observedOrdinarySellerShapeReadsExactLongOrderAndCommonGoodsWithoutItemIdentity() throws Exception {
        Map<String, Object> module = ordinarySellerModule();
        OrderDetailFetcher.OrderDetailInfo info = fetch(module, "request-order", "request-goods");
        assertNotNull(info);
        assertEquals("5127502862002001606", info.orderId);
        assertEquals("1088432591594", info.goodsId);
        assertEquals("synthetic-buyer", info.buyerUserId);
        assertEquals("PAID", info.tradeStatus);
        assertEquals(1, info.buyNum);
    }

    @Test
    void observedPostVoucherShippedStatusIsRecognizedWithoutReinferringPayment() throws Exception {
        Map<String, Object> module = ordinarySellerModule();
        common(module).put("orderStatus", "已发货");
        common(module).put("consignTime", "2026-10-09 15:26:00");
        OrderDetailFetcher.OrderDetailInfo info = fetch(module, "request-order", "request-goods");
        assertNotNull(info);
        assertEquals("SHIPPED", info.tradeStatus);
        assertEquals("5127502862002001606", info.orderId);
        assertEquals("1088432591594", info.goodsId);
        assertEquals("2026-10-09 15:26:00", info.consignTime);
        assertNull(OrderDetailFetcher.tradeStatus("发货结果待核对"));
    }

    @Test
    void commonAndItemOfficialProductIdentityConflictRejectsEntireDetail() throws Exception {
        Map<String, Object> module = ordinarySellerModule();
        module.put("merchantItemVO", Map.of("itemId", 999L, "title", "synthetic item"));
        assertNull(fetch(module, "5127502862002001606", "1088432591594"));
    }

    @Test
    void equalCommonAndItemIdentityRetainsVerifiedGoods() throws Exception {
        Map<String, Object> module = ordinarySellerModule();
        module.put("merchantItemVO", Map.of("itemId", 1088432591594L));
        assertEquals("1088432591594", fetch(module, "request-order", "request-goods").goodsId);
    }

    @Test
    void existingMerchantItemIdentityContractStillWorksWithoutCommonItemField() throws Exception {
        Map<String, Object> module = ordinarySellerModule();
        common(module).remove("itemId");
        module.put("merchantItemVO", Map.of("itemId", 1088432591594L));
        assertEquals("1088432591594", fetch(module, "request-order", "request-goods").goodsId);
    }

    @Test
    void absentGoodsIdentityNeverUsesRequestedProductId() throws Exception {
        Map<String, Object> module = ordinarySellerModule();
        common(module).remove("itemId");
        OrderDetailFetcher.OrderDetailInfo info = fetch(module, "5127502862002001606", "1088432591594");
        assertNotNull(info);
        assertNull(info.goodsId);
    }

    @Test
    void absentCommonOrderNeverUsesRequestIdOrUiOrderText() throws Exception {
        Map<String, Object> module = ordinarySellerModule();
        common(module).remove("orderId");
        module.put("orderInfoVO", Map.of("orderInfoList", List.of(Map.of("value", "5127502862002001606"))));
        OrderDetailFetcher.OrderDetailInfo info = fetch(module, "5127502862002001606", "1088432591594");
        assertNotNull(info);
        assertNull(info.orderId);
    }

    @Test
    void missingBuyerIdDoesNotInferBuyerFromOtherFields() throws Exception {
        Map<String, Object> module = ordinarySellerModule();
        module.put("merchantBuyerVO", Map.of("userNick", "synthetic buyer"));
        OrderDetailFetcher.OrderDetailInfo info = fetch(module, "5127502862002001606", "1088432591594");
        assertNotNull(info);
        assertNull(info.buyerUserId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> common(Map<String, Object> module) {
        return (Map<String, Object>) module.get("merchantCommonData");
    }

    private Map<String, Object> ordinarySellerModule() {
        Map<String, Object> common = new HashMap<>();
        // Numeric order identity is beyond JavaScript's safe integer range, but is exact as Jackson Long.
        common.put("orderId", 5127502862002001606L);
        common.put("itemId", 1088432591594L);
        common.put("orderStatus", "待发货");
        common.put("paySuccessTime", "2026-10-09 14:34:35");
        Map<String, Object> module = new HashMap<>();
        module.put("merchantCommonData", common);
        module.put("merchantItemVO", Map.of("title", "synthetic item", "itemInfoLines", List.of()));
        module.put("merchantBuyerVO", Map.of("buyerId", "synthetic-buyer", "userNick", "synthetic buyer"));
        module.put("merchantPriceVO", Map.of("buyNum", 1, "totalPrice", "0.01"));
        return module;
    }

    private OrderDetailFetcher.OrderDetailInfo fetch(Map<String, Object> module, String orderId, String goodsId) throws Exception {
        AccountService accounts = mock(AccountService.class);
        XianyuApiCallUtils api = mock(XianyuApiCallUtils.class);
        OrderDetailFetcher fetcher = new OrderDetailFetcher();
        ReflectionTestUtils.setField(fetcher, "accountService", accounts);
        ReflectionTestUtils.setField(fetcher, "xianyuApiCallUtils", api);
        ReflectionTestUtils.setField(fetcher, "skuResolver", mock(SkuResolver.class));
        when(accounts.getCookieByAccountId(1L)).thenReturn("synthetic-cookie");
        String response = new ObjectMapper().writeValueAsString(Map.of("data", Map.of("module", module)));
        when(api.callApiWithRetry(eq(1L), eq("mtop.taobao.idle.trade.merchant.full.info"), anyMap(), eq("synthetic-cookie")))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(true, response, null, false));
        return fetcher.fetch(1L, goodsId, orderId);
    }

    private OrderDetailFetcher.OrderDetailInfo parsePrice(Object value) {
        Map<String, Object> price = new HashMap<>();
        price.put("buyNum", value);
        OrderDetailFetcher.OrderDetailInfo info = new OrderDetailFetcher.OrderDetailInfo();
        ReflectionTestUtils.invokeMethod(new OrderDetailFetcher(), "parsePriceInfo",
                Map.of("merchantPriceVO", price), info);
        return info;
    }
}
