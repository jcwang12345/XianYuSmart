package com.xianyusmart.service.impl;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.common.ResultObject;
import com.xianyusmart.controller.dto.ItemDetailReqDTO;
import com.xianyusmart.controller.dto.ItemDetailRespDTO;
import com.xianyusmart.controller.dto.ItemListReqDTO;
import com.xianyusmart.controller.dto.ItemListRespDTO;
import com.xianyusmart.entity.XianyuGoodsInfo;
import com.xianyusmart.service.AccountService;
import com.xianyusmart.service.CookieRefreshService;
import com.xianyusmart.service.GoodsInfoService;
import com.xianyusmart.service.ItemDetailSyncService;
import com.xianyusmart.service.PermissionCatalog;
import com.xianyusmart.service.PlatformPermissionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ItemServiceDetailIsolationTest {

    @ParameterizedTest
    @CsvSource({
            "FAIL_BIZ_FORBIDDEN,BUSINESS_REJECTION,PLATFORM_UNAVAILABLE",
            "FAIL_SYS_API_NOT_FOUNDED,API_NOT_AVAILABLE,PLATFORM_UNAVAILABLE",
            "FAIL_SYS_API_NOT_FOUND,API_NOT_AVAILABLE,PLATFORM_UNAVAILABLE",
            "FAIL_SYS_TOKEN_EXOIRED,CREDENTIAL_EXPIRED,CREDENTIAL_EXPIRED",
            "FAIL_SYS_TOKEN_EXPIRED,CREDENTIAL_EXPIRED,CREDENTIAL_EXPIRED",
            "FAIL_SYS_USER_VALIDATE,VERIFICATION_REQUIRED,VERIFICATION_REQUIRED",
            "FAIL_SYS_NO_PERMISSION,PERMISSION_DENIED,PLATFORM_PERMISSION_DENIED",
            "FAIL_SYS_ACCESS_DENIED,PERMISSION_DENIED,PLATFORM_PERMISSION_DENIED",
            "FAIL_SYS_TRAFFIC_LIMIT,RATE_LIMITED,RATE_LIMITED"
    })
    void logsKnownOwnListRejectionsWithoutPrivatePlatformText(String code, String category, String errorCode)
            throws Exception {
        String ret = code + "::Cookie=PRIVATE_COOKIE; token=PRIVATE_TOKEN; sign=PRIVATE_SIGN";
        CapturedList result = readSyntheticList(Map.of("ret", List.of(ret),
                "data", Map.of("private", "PRIVATE_BODY"), "PRIVATE_KEY", "PRIVATE_VALUE"));

        assertRejectedList(result, errorCode);
        assertTrue(result.logs().contains("platformRetCode=" + code + ", rejectionClass=" + category));
        assertTrue(result.logs().contains("retSha256=" + sha256(ret)));
        assertFalse(result.logs().contains("PRIVATE_"));
        assertFalse(result.logs().contains("::"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAIL_PRIVATE_UNKNOWN", "failure=PRIVATE_TEXT", "FAIL_PRIVATE\nINJECTED",
            "FAIL_ABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZ_PRIVATE_TOO_LONG"})
    void hidesUnknownAndMalformedRetTokensEvenWhenTheyLookLikeCodes(String code) throws Exception {
        String ret = code + "::PRIVATE_DESCRIPTION";
        CapturedList result = readSyntheticList(Map.of("ret", List.of(ret)));

        assertRejectedList(result, "PLATFORM_UNAVAILABLE");
        assertTrue(result.logs().contains("platformRetCode=UNKNOWN, rejectionClass=UNKNOWN"));
        assertTrue(result.logs().contains("retSha256=" + sha256(ret)));
        assertFalse(result.logs().contains("PRIVATE"));
        assertFalse(result.logs().contains("INJECTED"));
    }

    @Test
    void malformedRetDoesNotLeakObjectContentsOrPretendToBeVerifiedEmpty() throws Exception {
        CapturedList result = readSyntheticList(Map.of("ret", List.of(Map.of("PRIVATE_KEY", "PRIVATE_VALUE"))));

        assertRejectedList(result, "INVALID_RESPONSE");
        assertTrue(result.logs().contains("platformRetCode=UNKNOWN, rejectionClass=INVALID_RESPONSE, retSha256=NOT_AVAILABLE"));
        assertFalse(result.logs().contains("PRIVATE"));
    }

    @Test
    void successTokenWithInvalidListRetainsStructuralFailure() throws Exception {
        CapturedList result = readSyntheticList(Map.of("ret", List.of("SUCCESS::PRIVATE_DESCRIPTION"),
                "data", Map.of("PRIVATE_KEY", "PRIVATE_VALUE")));

        assertRejectedList(result, "INVALID_LIST_STRUCTURE");
        assertTrue(result.logs().contains("platformRetCode=SUCCESS, rejectionClass=INVALID_LIST_RESPONSE"));
        assertFalse(result.logs().contains("PRIVATE"));
    }

    private record CapturedList(ResultObject<ItemListRespDTO> result, String logs) {}

    private CapturedList readSyntheticList(Map<String, Object> response) throws Exception {
        String json = new ObjectMapper().writeValueAsString(response);
        ItemServiceImpl service = new ItemServiceImpl() {
            @Override
            protected String callOnSaleListApi(Map<String, Object> data, String cookie, Long accountId) {
                assertEquals(9L, accountId);
                return json;
            }
        };
        AccountService accounts = mock(AccountService.class);
        GoodsInfoService goods = mock(GoodsInfoService.class);
        CookieRefreshService refresh = mock(CookieRefreshService.class);
        when(accounts.getCookieByAccountId(9L)).thenReturn("_m_h5_tk=PRIVATE_COOKIE; unb=9;");
        ReflectionTestUtils.setField(service, "accountService", accounts);
        ReflectionTestUtils.setField(service, "goodsInfoService", goods);
        ReflectionTestUtils.setField(service, "cookieRefreshService", refresh);
        ItemListReqDTO request = new ItemListReqDTO();
        request.setCookieId("9");
        request.setPageNumber(1);
        request.setPageSize(50);
        Logger logger = (Logger) LoggerFactory.getLogger(ItemServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ResultObject<ItemListRespDTO> result = service.fetchOnSalePage(request);
            verifyNoInteractions(goods);
            if ("CREDENTIAL_EXPIRED".equals(result.getData().getErrorCode())) {
                verify(refresh).refreshCookie(9L);
            } else {
                verifyNoInteractions(refresh);
            }
            return new CapturedList(result, appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n")));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private void assertRejectedList(CapturedList captured, String errorCode) {
        assertEquals(502, captured.result().getCode());
        ItemListRespDTO data = captured.result().getData();
        assertEquals(errorCode, data.getErrorCode());
        assertFalse(data.getSuccess());
        assertFalse(data.getStructureValidated());
        assertFalse(data.getTerminalPage());
        assertEquals(0, data.getSavedCount());
        assertEquals(0, data.getCurrentCount());
        assertTrue(data.getItems().isEmpty());
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsCrossAccountDetailRefreshBeforePlatformRead() {
        GoodsInfoService goodsInfoService = mock(GoodsInfoService.class);
        ItemDetailSyncService syncService = mock(ItemDetailSyncService.class);
        ItemServiceImpl service = new ItemServiceImpl();
        ReflectionTestUtils.setField(service, "goodsInfoService", goodsInfoService);
        ReflectionTestUtils.setField(service, "itemDetailSyncService", syncService);
        XianyuGoodsInfo item = new XianyuGoodsInfo();
        item.setXyGoodId("12345678");
        item.setXianyuAccountId(7L);
        when(goodsInfoService.getByXyGoodId("12345678")).thenReturn(item);
        ItemDetailReqDTO request = new ItemDetailReqDTO();
        request.setXyGoodId("12345678");
        request.setCookieId("8");

        ResultObject<ItemDetailRespDTO> result = service.getItemDetail(request);

        assertEquals(403, result.getCode());
        assertTrue(result.getMsg().contains("归属不一致"));
        verify(syncService, never()).syncSingleItemWithResult(8L, "12345678");
        verify(goodsInfoService, never()).updateDetailInfo(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verify(goodsInfoService, never()).updateSkuCount(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void rejectsRefreshWithoutGoodsWritePermissionBeforePlatformRead() {
        GoodsInfoService goodsInfoService = mock(GoodsInfoService.class);
        ItemDetailSyncService syncService = mock(ItemDetailSyncService.class);
        PlatformPermissionService permissionService = mock(PlatformPermissionService.class);
        ItemServiceImpl service = new ItemServiceImpl();
        ReflectionTestUtils.setField(service, "goodsInfoService", goodsInfoService);
        ReflectionTestUtils.setField(service, "itemDetailSyncService", syncService);
        ReflectionTestUtils.setField(service, "platformPermissionService", permissionService);
        XianyuGoodsInfo item = new XianyuGoodsInfo();
        item.setXyGoodId("12345678");
        item.setXianyuAccountId(7L);
        item.setDetailInfo("");
        when(goodsInfoService.getByXyGoodId("12345678")).thenReturn(item);
        when(permissionService.hasPermission(99L, PermissionCatalog.ACTION_GOODS_WRITE))
                .thenReturn(false);
        ItemDetailReqDTO request = new ItemDetailReqDTO();
        request.setXyGoodId("12345678");

        com.xianyusmart.context.UserContext.set(99L, "readonly", 1L);
        try {
            ResultObject<ItemDetailRespDTO> result = service.getItemDetail(request);

            assertEquals(403, result.getCode());
            verify(syncService, never()).syncSingleItemWithResult(
                    org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
        } finally {
            com.xianyusmart.context.UserContext.clear();
        }
    }
}
