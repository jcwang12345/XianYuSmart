package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class ProductMarketingServiceTest {
    private final ProductMarketingService service = new ProductMarketingService(
            mock(JdbcTemplate.class), mock(AccountAccessService.class), mock(OperationLogService.class),
            new ObjectMapper(), true, 1L, "101,102,103");

    @Test
    void fanPricesMustBePositiveTwoDecimalsAndStrictlyBelowProductPrice() {
        assertEquals(400, assertThrows(BusinessException.class, () -> service.validate(
                config("1.234", null, null, false, null, null, false, null), money("10.00"))).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.validate(
                config("10.00", null, null, false, null, null, false, null), money("10.00"))).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.validate(
                config("0", null, null, false, null, null, false, null), money("10.00"))).getCode());

        ProductMarketingService.Configuration result = service.validate(
                config("9.99", "8", null, false, null, null, false, null), money("10.00"));
        assertEquals(money("9.99"), result.fanAllPrice());
        assertEquals(money("8.00"), result.fanOldPrice());
        assertNull(result.fanBuyerPrice());
    }

    @Test
    void fanPriceBoundaryMessageUsesProductSalePriceTerminology() {
        BusinessException equalPrice = assertThrows(BusinessException.class, () -> service.validate(
                config("19.00", null, null, false, null, null, false, null), money("19.00")));
        BusinessException abovePrice = assertThrows(BusinessException.class, () -> service.validate(
                config("19.01", null, null, false, null, null, false, null), money("19.00")));

        assertEquals("全部粉丝价必须严格低于商品售价 19.00", equalPrice.getMessage());
        assertEquals("全部粉丝价必须严格低于商品售价 19.00", abovePrice.getMessage());
        assertEquals(money("18.99"), service.validate(
                config("18.99", null, null, false, null, null, false, null), money("19.00")).fanAllPrice());
    }

    @Test
    void disabledActivitiesDiscardStaleInputsInsteadOfWritingZero() {
        ProductMarketingService.Configuration result = service.validate(
                config(null, null, null, false, "5.00", 3, false, 10), money("20.00"));
        assertEquals(false, result.bargainEnabled());
        assertNull(result.bargainPrice());
        assertNull(result.bargainQuantity());
        assertEquals(false, result.coinEnabled());
        assertNull(result.coinDiscountPercent());
    }

    @Test
    void enabledBargainAndCoinRequireBoundedParameters() {
        assertEquals(400, assertThrows(BusinessException.class, () -> service.validate(
                config(null, null, null, true, null, 1, false, null), money("20.00"))).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.validate(
                config(null, null, null, true, "10.00", 0, false, null), money("20.00"))).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.validate(
                config(null, null, null, false, null, null, true, 100), money("20.00"))).getCode());

        ProductMarketingService.Configuration result = service.validate(
                config(null, null, null, true, "10.00", 2, true, 5), money("20.00"));
        assertEquals(money("10.00"), result.bargainPrice());
        assertEquals(2, result.bargainQuantity());
        assertEquals(5, result.coinDiscountPercent());
    }

    @Test
    void unknownProductPriceCannotBeSubstitutedWithZero() {
        assertEquals(409, assertThrows(BusinessException.class, () -> service.validate(
                config(null, null, null, false, null, null, false, null), null)).getCode());
    }

    private static ProductMarketingService.Configuration config(String all, String old, String buyer,
                                                                 Boolean bargain, String bargainPrice,
                                                                 Integer quantity, Boolean coin, Integer percent) {
        return new ProductMarketingService.Configuration(moneyOrNull(all), moneyOrNull(old), moneyOrNull(buyer),
                bargain, moneyOrNull(bargainPrice), quantity, coin, percent);
    }

    private static BigDecimal money(String value) { return new BigDecimal(value); }
    private static BigDecimal moneyOrNull(String value) { return value == null ? null : money(value); }
}
