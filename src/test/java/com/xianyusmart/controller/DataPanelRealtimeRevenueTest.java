package com.xianyusmart.controller;

import com.xianyusmart.mapper.XianyuGoodsOrderMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

class DataPanelRealtimeRevenueTest {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    void noConfirmedDeliveryReturnsUnknownInsteadOfZero() {
        XianyuGoodsOrderMapper mapper = mock(XianyuGoodsOrderMapper.class);
        when(mapper.sumConfirmedDeliveryAmountByOrderDate(LocalDate.now(BUSINESS_ZONE).toString())).thenReturn(null);
        DataPanelController controller = new DataPanelController();
        ReflectionTestUtils.setField(controller, "orderMapper", mapper);

        var result = controller.getRealtimeRevenue();

        assertEquals(200, result.getCode());
        assertNull(result.getData());
        verify(mapper).sumConfirmedDeliveryAmountByOrderDate(LocalDate.now(BUSINESS_ZONE).toString());
        verify(mapper, never()).sumDeliverySuccessAmount();
    }

    @Test
    void confirmedDeliveryUsesTodaysAmountOnly() {
        XianyuGoodsOrderMapper mapper = mock(XianyuGoodsOrderMapper.class);
        when(mapper.sumConfirmedDeliveryAmountByOrderDate(LocalDate.now(BUSINESS_ZONE).toString())).thenReturn(12.34);
        DataPanelController controller = new DataPanelController();
        ReflectionTestUtils.setField(controller, "orderMapper", mapper);

        var result = controller.getRealtimeRevenue();

        assertEquals(200, result.getCode());
        assertEquals(12.34, result.getData());
        verify(mapper, never()).sumDeliverySuccessAmount();
    }
}
