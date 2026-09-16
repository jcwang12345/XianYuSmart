package com.xianyusmart.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.entity.XianyuGoodsInfo;
import com.xianyusmart.service.AccountAccessService;
import com.xianyusmart.service.AccountService;
import com.xianyusmart.service.GoodsInfoService;
import com.xianyusmart.service.ItemDetailSnapshotService;
import com.xianyusmart.service.PlatformItemDetailFetchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ItemDetailSyncServiceImplTest {

    private AccountService accountService;
    private AccountAccessService accountAccessService;
    private GoodsInfoService goodsInfoService;
    private ItemDetailSnapshotService snapshotService;
    private PlatformItemDetailFetchService fetchService;
    private ItemDetailSyncServiceImpl service;

    @BeforeEach
    void setUp() {
        accountService = mock(AccountService.class);
        accountAccessService = mock(AccountAccessService.class);
        goodsInfoService = mock(GoodsInfoService.class);
        snapshotService = mock(ItemDetailSnapshotService.class);
        fetchService = mock(PlatformItemDetailFetchService.class);
        service = new ItemDetailSyncServiceImpl();
        ReflectionTestUtils.setField(service, "accountService", accountService);
        ReflectionTestUtils.setField(service, "accountAccessService", accountAccessService);
        ReflectionTestUtils.setField(service, "goodsInfoService", goodsInfoService);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "platformItemDetailFetchService", fetchService);
        ReflectionTestUtils.setField(service, "itemDetailSnapshotService", snapshotService);
        when(accountAccessService.canAccess(7L)).thenReturn(true);
        when(goodsInfoService.getByXyGoodIdAndAccountId("12345678", 7L))
                .thenReturn(new XianyuGoodsInfo());
        when(accountService.getCookieByAccountId(7L)).thenReturn("cookie=ok");
    }

    @Test
    void savesVerifiedSkuSnapshotFromUnifiedFetcher() {
        String response = "{\"ret\":[\"SUCCESS::调用成功\"],\"data\":{\"itemDO\":{"
                + "\"itemId\":\"12345678\",\"desc\":\"新详情\",\"skuList\":[{"
                + "\"skuId\":\"SKU-1\",\"price\":1990,\"quantity\":3,\"propertyList\":[{"
                + "\"propertyId\":1,\"propertyText\":\"版本\",\"valueId\":2,\"valueText\":\"专业版\"}]}]}}}";
        when(fetchService.fetch(7L, "12345678", "cookie=ok"))
                .thenReturn(new PlatformItemDetailFetchService.FetchResult(
                        PlatformItemDetailFetchService.Status.SUCCESS,
                        PlatformItemDetailFetchService.Source.PLATFORM_PAGE, response, null));
        assertTrue(service.syncSingleItem(7L, "12345678"));

        verify(snapshotService).save(eq(7L), eq("12345678"), eq("新详情"),
                org.mockito.ArgumentMatchers.argThat(list -> list.size() == 1),
                org.mockito.ArgumentMatchers.argThat(list -> list.size() == 1));
    }

    @Test
    void explicitEmptySkuSnapshotClearsOldRows() {
        String response = "{\"ret\":[\"SUCCESS::调用成功\"],\"data\":{\"itemDO\":{"
                + "\"itemId\":\"12345678\",\"desc\":\"新详情\",\"skuList\":[]}}}";
        when(fetchService.fetch(7L, "12345678", "cookie=ok"))
                .thenReturn(new PlatformItemDetailFetchService.FetchResult(
                        PlatformItemDetailFetchService.Status.SUCCESS,
                        PlatformItemDetailFetchService.Source.API, response, null));
        assertTrue(service.syncSingleItem(7L, "12345678"));

        verify(snapshotService).save(eq(7L), eq("12345678"), eq("新详情"),
                org.mockito.ArgumentMatchers.argThat(java.util.List::isEmpty),
                org.mockito.ArgumentMatchers.argThat(java.util.List::isEmpty));
    }

    @Test
    void unavailableResponseNeverDeletesExistingSkuSnapshot() {
        when(fetchService.fetch(7L, "12345678", "cookie=ok"))
                .thenReturn(new PlatformItemDetailFetchService.FetchResult(
                        PlatformItemDetailFetchService.Status.UNAVAILABLE, null, null, "网络波动"));

        assertFalse(service.syncSingleItem(7L, "12345678"));

        verify(snapshotService, never()).save(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), anyList(), anyList());
    }

    @Test
    void missingSkuFieldPreservesWholeExistingSnapshot() {
        String response = "{\"ret\":[\"SUCCESS::调用成功\"],\"data\":{\"itemDO\":{" 
                + "\"itemId\":\"12345678\",\"desc\":\"新详情\"}}}";
        when(fetchService.fetch(7L, "12345678", "cookie=ok"))
                .thenReturn(new PlatformItemDetailFetchService.FetchResult(
                        PlatformItemDetailFetchService.Status.SUCCESS,
                        PlatformItemDetailFetchService.Source.API, response, null));

        assertFalse(service.syncSingleItem(7L, "12345678"));

        verify(snapshotService, never()).save(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), anyList(), anyList());
    }

    @Test
    void malformedSkuNumericTypesPreserveWholeExistingSnapshot() {
        String textPrice = responseWithSku("\"price\":\"1990\",\"quantity\":3,"
                + "\"propertyList\":[{\"propertyId\":1,\"valueId\":2}]");
        String objectQuantity = responseWithSku("\"price\":1990,\"quantity\":{\"bad\":1},"
                + "\"propertyList\":[{\"propertyId\":1,\"valueId\":2}]");
        String textPropertyId = responseWithSku("\"price\":1990,\"quantity\":3,"
                + "\"propertyList\":[{\"propertyId\":\"1\",\"valueId\":2}]");
        String negativeValueId = responseWithSku("\"price\":1990,\"quantity\":3,"
                + "\"propertyList\":[{\"propertyId\":1,\"valueId\":-1}]");
        when(fetchService.fetch(7L, "12345678", "cookie=ok"))
                .thenReturn(success(textPrice), success(objectQuantity),
                        success(textPropertyId), success(negativeValueId));

        assertFalse(service.syncSingleItem(7L, "12345678"));
        assertFalse(service.syncSingleItem(7L, "12345678"));
        assertFalse(service.syncSingleItem(7L, "12345678"));
        assertFalse(service.syncSingleItem(7L, "12345678"));

        verify(snapshotService, never()).save(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), anyList(), anyList());
    }

    @Test
    void malformedSkuNumericRangesAndMissingValuesPreserveWholeExistingSnapshot() {
        String missingPrice = responseWithSku("\"quantity\":3,"
                + "\"propertyList\":[{\"propertyId\":1,\"valueId\":2}]");
        String missingQuantity = responseWithSku("\"price\":1990,"
                + "\"propertyList\":[{\"propertyId\":1,\"valueId\":2}]");
        String negativeQuantity = responseWithSku("\"price\":1990,\"quantity\":-1,"
                + "\"propertyList\":[{\"propertyId\":1,\"valueId\":2}]");
        String overflowingPrice = responseWithSku("\"price\":2147483648,\"quantity\":3,"
                + "\"propertyList\":[{\"propertyId\":1,\"valueId\":2}]");
        String zeroPropertyId = responseWithSku("\"price\":1990,\"quantity\":3,"
                + "\"propertyList\":[{\"propertyId\":0,\"valueId\":2}]");
        when(fetchService.fetch(7L, "12345678", "cookie=ok"))
                .thenReturn(success(missingPrice), success(missingQuantity), success(negativeQuantity),
                        success(overflowingPrice), success(zeroPropertyId));

        assertFalse(service.syncSingleItem(7L, "12345678"));
        assertFalse(service.syncSingleItem(7L, "12345678"));
        assertFalse(service.syncSingleItem(7L, "12345678"));
        assertFalse(service.syncSingleItem(7L, "12345678"));
        assertFalse(service.syncSingleItem(7L, "12345678"));

        verify(snapshotService, never()).save(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), anyList(), anyList());
    }

    @Test
    void snapshotWriteFailureDoesNotReportSuccess() {
        String response = responseWithSku("\"price\":1990,\"quantity\":3,"
                + "\"propertyList\":[{\"propertyId\":1,\"valueId\":2}]");
        when(fetchService.fetch(7L, "12345678", "cookie=ok")).thenReturn(success(response));
        org.mockito.Mockito.doThrow(new IllegalStateException("late write failure"))
                .when(snapshotService).save(eq(7L), eq("12345678"), eq("新详情"), anyList(), anyList());

        assertFalse(service.syncSingleItem(7L, "12345678"));
    }

    @Test
    void rejectsWrongAccountOwnershipBeforePlatformFetch() {
        when(accountAccessService.canAccess(8L)).thenReturn(true);
        when(goodsInfoService.getByXyGoodIdAndAccountId("12345678", 8L)).thenReturn(null);

        assertFalse(service.syncSingleItem(8L, "12345678"));

        verify(fetchService, never()).fetch(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    private PlatformItemDetailFetchService.FetchResult success(String response) {
        return new PlatformItemDetailFetchService.FetchResult(
                PlatformItemDetailFetchService.Status.SUCCESS,
                PlatformItemDetailFetchService.Source.API, response, null);
    }

    private String responseWithSku(String fields) {
        return "{\"ret\":[\"SUCCESS::调用成功\"],\"data\":{\"itemDO\":{"
                + "\"itemId\":\"12345678\",\"desc\":\"新详情\",\"skuList\":[{"
                + "\"skuId\":\"SKU-1\"," + fields + "}]}}}";
    }
}
