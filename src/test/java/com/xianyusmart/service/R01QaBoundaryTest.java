package com.xianyusmart.service;

import com.xianyusmart.service.impl.OrderServiceImpl;
import com.xianyusmart.service.impl.WebSocketServiceImpl;
import com.xianyusmart.service.delivery.OrderDetailFetcher;
import com.xianyusmart.utils.XianyuApiCallUtils;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class R01QaBoundaryTest {
    @Test void rejectedQaObjectsNeverFallThroughToRealOrderNetwork() {
        var qa=mock(R01QaPlatformService.class); var real=mock(XianyuApiCallUtils.class);
        when(qa.handles(1L,"QA-R01-forbidden")).thenReturn(true);
        when(qa.detailMap(1L,"QA-R01-forbidden")).thenThrow(new IllegalStateException("guard denied"));
        when(qa.consign(eq(1L),eq("QA-R01-forbidden"))).thenThrow(new IllegalStateException("guard denied"));
        when(qa.confirm(1L,"QA-R01-forbidden")).thenThrow(new IllegalStateException("guard denied"));
        var service=new OrderServiceImpl(); ReflectionTestUtils.setField(service,"r01Qa",qa);
        ReflectionTestUtils.setField(service,"xianyuApiCallUtils",real);
        assertThrows(IllegalStateException.class,()->service.getOrderDetailMap(1L,"QA-R01-forbidden"));
        assertThrows(IllegalStateException.class,()->service.consignDummyDelivery(1L,"QA-R01-forbidden","QA",java.util.List.of()));
        assertThrows(IllegalStateException.class,()->service.confirmShipment(1L,"QA-R01-forbidden"));
        verifyNoInteractions(real);
    }
    @Test void acceptedQaOrderResultsNeverInvokeCookieOrTransport() {
        var qa=mock(R01QaPlatformService.class); var real=mock(XianyuApiCallUtils.class); var accounts=mock(AccountService.class);
        when(qa.handles(1L,"QA-R01-safe")).thenReturn(true);
        when(qa.consign(1L,"QA-R01-safe")).thenReturn(OrderService.CONSIGN_SUCCESS);
        when(qa.detailMap(1L,"QA-R01-safe")).thenReturn(java.util.Map.of("mock",true));
        when(qa.confirm(1L,"QA-R01-safe")).thenReturn("QA-confirmed");
        var service=new OrderServiceImpl(); ReflectionTestUtils.setField(service,"r01Qa",qa);
        ReflectionTestUtils.setField(service,"xianyuApiCallUtils",real); ReflectionTestUtils.setField(service,"accountService",accounts);
        assertEquals(OrderService.CONSIGN_SUCCESS,service.consignDummyDelivery(1L,"QA-R01-safe","QA",java.util.List.of()));
        assertEquals(java.util.Map.of("mock",true),service.getOrderDetailMap(1L,"QA-R01-safe"));
        assertEquals("QA-confirmed",service.confirmShipment(1L,"QA-R01-safe"));
        verifyNoInteractions(real,accounts);
    }
    @Test void rejectedQaDetailCannotUseCookieOrRealTransport() {
        var qa=mock(R01QaPlatformService.class); var real=mock(XianyuApiCallUtils.class); var accounts=mock(AccountService.class);
        when(qa.handles(1L,"QA-R01-forbidden")).thenReturn(true);
        when(qa.detail(1L,"QA-R01-forbidden")).thenThrow(new IllegalStateException("guard denied"));
        var fetcher=new OrderDetailFetcher(); ReflectionTestUtils.setField(fetcher,"r01Qa",qa);
        ReflectionTestUtils.setField(fetcher,"xianyuApiCallUtils",real); ReflectionTestUtils.setField(fetcher,"accountService",accounts);
        assertThrows(IllegalStateException.class,()->fetcher.fetch(1L,"QA-R01-goods","QA-R01-forbidden"));
        verifyNoInteractions(real,accounts);
    }
    @Test void rejectedQaSendEscapesBeforeOrdinaryFailureFallback() {
        var qa=mock(R01QaPlatformService.class); when(qa.handles(1L,"QA-R01-session")).thenReturn(true);
        when(qa.send(1L,"QA-R01-session","QA-R01-buyer")).thenThrow(new IllegalStateException("guard denied"));
        var socket=new WebSocketServiceImpl(); ReflectionTestUtils.setField(socket,"r01Qa",qa);
        assertThrows(IllegalStateException.class,()->socket.sendMessageWithResult(1L,"QA-R01-session","QA-R01-buyer","QA"));
    }
}
