package com.xianyusmart.service.impl;

import com.xianyusmart.entity.XianyuGoodsInfo;
import com.xianyusmart.mapper.XianyuGoodsInfoMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoodsInfoPublishedSnapshotTest {

    @Test
    void verifiedPublishPersistsStockCategorySourceAndChannelTogether() {
        XianyuGoodsInfoMapper mapper = mock(XianyuGoodsInfoMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any())).thenReturn(1);
        GoodsInfoServiceImpl service = new GoodsInfoServiceImpl();
        ReflectionTestUtils.setField(service, "goodsInfoMapper", mapper);

        boolean saved = service.savePublishedGoods(
                "QA-PUBLISHED-41", 101L, "核对标题", "https://example.test/cover.png",
                "[]", "详情", "/qa/products/QA-PUBLISHED-41", "12.34", 52,
                "OFFICE_PLUGIN", "办公软件与插件", "QA_LOCAL", "SUCCEEDED");

        assertTrue(saved);
        ArgumentCaptor<XianyuGoodsInfo> captor = ArgumentCaptor.forClass(XianyuGoodsInfo.class);
        verify(mapper).insert(captor.capture());
        XianyuGoodsInfo row = captor.getValue();
        assertEquals(52, row.getStock());
        assertEquals("OFFICE_PLUGIN", row.getCategoryId());
        assertEquals("办公软件与插件", row.getCategoryName());
        assertEquals("SYSTEM_PUBLISH", row.getProductSource());
        assertEquals("QA_LOCAL", row.getPublishChannel());
        assertEquals("SUCCEEDED", row.getSyncStatus());
    }
}
