package com.xianyusmart.service;

import com.xianyusmart.entity.XianyuGoodsSku;
import com.xianyusmart.entity.XianyuGoodsSkuProperty;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ItemDetailSnapshotServiceTest {

    @Test
    void writesDetailSkuPropertiesAndCountAsOneOrderedSnapshot() {
        GoodsInfoService goodsInfo = mock(GoodsInfoService.class);
        GoodsSkuService skus = mock(GoodsSkuService.class);
        GoodsSkuPropertyService properties = mock(GoodsSkuPropertyService.class);
        ItemDetailSnapshotService service = new ItemDetailSnapshotService(goodsInfo, skus, properties);
        List<XianyuGoodsSku> skuList = List.of(new XianyuGoodsSku());
        List<XianyuGoodsSkuProperty> propertyList = List.of(new XianyuGoodsSkuProperty());
        when(goodsInfo.updateDetailInfo(7L, "123", "详情")).thenReturn(true);
        when(goodsInfo.updateSkuCount(7L, "123", 1)).thenReturn(true);

        service.save(7L, "123", "详情", skuList, propertyList);

        var order = inOrder(goodsInfo, skus, properties);
        order.verify(goodsInfo).updateDetailInfo(7L, "123", "详情");
        order.verify(skus).saveSkus("123", 7L, skuList);
        order.verify(properties).saveProperties("123", 7L, propertyList);
        order.verify(goodsInfo).updateSkuCount(7L, "123", 1);
    }

    @Test
    void stopsSnapshotWhenDetailWriteFails() {
        GoodsInfoService goodsInfo = mock(GoodsInfoService.class);
        GoodsSkuService skus = mock(GoodsSkuService.class);
        GoodsSkuPropertyService properties = mock(GoodsSkuPropertyService.class);
        ItemDetailSnapshotService service = new ItemDetailSnapshotService(goodsInfo, skus, properties);
        when(goodsInfo.updateDetailInfo(7L, "123", "详情")).thenReturn(false);

        assertThrows(IllegalStateException.class,
                () -> service.save(7L, "123", "详情", List.of(), List.of()));

        verify(skus, never()).saveSkus("123", 7L, List.of());
        verify(properties, never()).saveProperties("123", 7L, List.of());
    }

    @Test
    void lateCountFailureRaisesExceptionForTransactionRollback() {
        GoodsInfoService goodsInfo = mock(GoodsInfoService.class);
        GoodsSkuService skus = mock(GoodsSkuService.class);
        GoodsSkuPropertyService properties = mock(GoodsSkuPropertyService.class);
        ItemDetailSnapshotService service = new ItemDetailSnapshotService(goodsInfo, skus, properties);
        List<XianyuGoodsSku> skuList = List.of(new XianyuGoodsSku());
        List<XianyuGoodsSkuProperty> propertyList = List.of(new XianyuGoodsSkuProperty());
        when(goodsInfo.updateDetailInfo(7L, "123", "详情")).thenReturn(true);
        when(goodsInfo.updateSkuCount(7L, "123", 1)).thenReturn(false);

        assertThrows(IllegalStateException.class,
                () -> service.save(7L, "123", "详情", skuList, propertyList));

        verify(skus).saveSkus("123", 7L, skuList);
        verify(properties).saveProperties("123", 7L, propertyList);
    }
}
