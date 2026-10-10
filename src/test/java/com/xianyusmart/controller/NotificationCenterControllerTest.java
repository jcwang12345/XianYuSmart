package com.xianyusmart.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.service.*;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.Map;

class NotificationCenterControllerTest {
    @Test void optionalQueryParametersReachInboxAndOldGetContractRemains() throws Exception {
        var inbox=mock(NotificationInboxService.class);
        when(inbox.list(any(),any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(Map.of("total",0));
        var mvc=MockMvcBuilders.standaloneSetup(new NotificationCenterController(mock(NotificationCenterService.class),inbox)).build();
        mvc.perform(get("/api/notifications/inbox").param("view","ALL").param("severity","ERROR").param("eventType","DELIVERY_EXCEPTION").param("handlingStatus","IN_PROGRESS").param("deliveryStatus","PARTIAL")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        verify(inbox).list("ALL",null,null,null,null,"ERROR","DELIVERY_EXCEPTION","IN_PROGRESS","PARTIAL");
        mvc.perform(get("/api/notifications/inbox")).andExpect(status().isOk());
        verify(inbox).list(null,null,null,null,null,null,null,null,null);
    }
    @Test void invalidFilterUsesExistingBusiness400EnvelopeWithoutQueries() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var inbox=new NotificationInboxService(jdbc,mock(AccountAccessService.class),new ObjectMapper());
        var mvc=MockMvcBuilders.standaloneSetup(new NotificationCenterController(mock(NotificationCenterService.class),inbox)).setControllerAdvice(new GlobalExceptionHandler()).build();
        try { TenantContext.set(1L);
            mvc.perform(get("/api/notifications/inbox").param("deliveryStatus","UNKNOWN")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(400));
            verifyNoInteractions(jdbc);
        } finally {TenantContext.clear();}
    }
}
