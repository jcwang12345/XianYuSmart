package com.xianyusmart.service.impl;

import com.xianyusmart.service.AccountService;
import com.xianyusmart.service.OrderService;
import com.xianyusmart.utils.XianyuApiCallUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplConsignTest {

    @Mock
    private AccountService accountService;
    @Mock
    private XianyuApiCallUtils apiCallUtils;

    private OrderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new OrderServiceImpl();
        ReflectionTestUtils.setField(service, "accountService", accountService);
        ReflectionTestUtils.setField(service, "xianyuApiCallUtils", apiCallUtils);
        when(accountService.getCookieByAccountId(3L)).thenReturn("cookie");
    }

    @Test
    void regularSellerFallbackUsesArrayPicList() {
        when(apiCallUtils.callApiWithRetry(eq(3L),
                eq("mtop.taobao.idle.logistics.merchant.consign.dummy"),
                anyMap(), eq("cookie"), anyMap()))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(false, "{}",
                        "FAIL_BIZ_IDLE_USER_UNAUTHORIZED::无需邮寄发货", false));
        when(apiCallUtils.callApiWithRetry(eq(3L),
                eq("mtop.taobao.idle.logistic.consign.dummy"),
                anyMap(), eq("cookie")))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(true,
                        "{\"ret\":[\"SUCCESS::调用成功\"],\"data\":{}}", null, false));

        String result = service.consignDummyDelivery(
                3L, "order-1", "下载地址", List.of("https://img.example/a.jpg"));

        assertEquals(OrderService.CONSIGN_SUCCESS, result);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Map> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(apiCallUtils).callApiWithRetry(eq(3L),
                eq("mtop.taobao.idle.logistic.consign.dummy"),
                dataCaptor.capture(), eq("cookie"));
        Object picList = dataCaptor.getValue().get("picList");
        assertFalse(picList instanceof String);
        assertArrayEquals(new String[]{"https://img.example/a.jpg"}, (String[]) picList);
        assertEquals(Boolean.TRUE, dataCaptor.getValue().get("newUnconsign"));
    }

    @Test
    void fishShopSuccessDoesNotCallRegularSellerEndpoint() {
        when(apiCallUtils.callApiWithRetry(eq(3L),
                eq("mtop.taobao.idle.logistics.merchant.consign.dummy"),
                anyMap(), eq("cookie"), anyMap()))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(true,
                        "{\"ret\":[\"SUCCESS::调用成功\"],\"data\":{}}", null, false));

        assertEquals(OrderService.CONSIGN_SUCCESS,
                service.consignDummyDelivery(3L, "order-1", "内容", List.of()));
        verify(apiCallUtils, never()).callApiWithRetry(eq(3L),
                eq("mtop.taobao.idle.logistic.consign.dummy"), anyMap(), eq("cookie"));
    }

    @Test
    void regularSellerPlatformBusyIsDeferredInsteadOfFailed() {
        when(apiCallUtils.callApiWithRetry(eq(3L),
                eq("mtop.taobao.idle.logistics.merchant.consign.dummy"),
                anyMap(), eq("cookie"), anyMap()))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(false, "{}",
                        "FAIL_BIZ_IDLE_USER_UNAUTHORIZED::无需邮寄发货", false));
        when(apiCallUtils.callApiWithRetry(eq(3L),
                eq("mtop.taobao.idle.logistic.consign.dummy"),
                anyMap(), eq("cookie")))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(false, "{}",
                        "CONDIGN_ACTIVITY_ERROR::活动期间系统挤爆了，请在两小时后重试~", false));

        assertEquals(OrderService.CONSIGN_PLATFORM_BUSY,
                service.consignDummyDelivery(3L, "order-2", "内容", List.of()));
    }

    @Test
    void merchantEndpointPlatformBusyIsDeferredWithoutCallingFallback() {
        when(apiCallUtils.callApiWithRetry(eq(3L),
                eq("mtop.taobao.idle.logistics.merchant.consign.dummy"),
                anyMap(), eq("cookie"), anyMap()))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(false, "{}",
                        "活动期间系统挤爆了，请在两小时后重试~::CONDIGN_ACTIVITY_ERROR", false));

        assertEquals(OrderService.CONSIGN_PLATFORM_BUSY,
                service.consignDummyDelivery(3L, "order-3", "内容", List.of()));
        verify(apiCallUtils, never()).callApiWithRetry(eq(3L),
                eq("mtop.taobao.idle.logistic.consign.dummy"), anyMap(), eq("cookie"));
    }
}
