package com.xianyusmart.controller;

import com.xianyusmart.controller.dto.ItemVerificationEntryDTO;
import com.xianyusmart.service.ItemVerificationEntryService;
import com.xianyusmart.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ItemVerificationEntryControllerTest {
    @Test void responsesAreNoStoreAndConsumeOnlyExplicitEndpoint() {
        var service = mock(ItemVerificationEntryService.class);
        var controller = new ItemController(); ReflectionTestUtils.setField(controller, "verificationEntries", service);
        var target = new ItemVerificationEntryDTO.Target(7L, "123", "handle");
        when(service.metadata(7L, "123")).thenReturn(new ItemVerificationEntryDTO.Metadata(false,null,null,null,null,"unavailable"));
        var response = new MockHttpServletResponse();
        assertEquals(200, controller.verificationEntry(target, response).getCode());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        verify(service, never()).consume(any(), any(), any());
        when(service.consume(7L,"123","handle")).thenReturn(new ItemVerificationEntryDTO.Consumed("SECRET_SENTINEL"));
        response = new MockHttpServletResponse();
        var result = controller.consumeVerificationEntry(target, response);
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals("no-cache", response.getHeader("Pragma"));
        assertFalse(result.toString().contains("SECRET_SENTINEL"));
    }
    @Test void deniedOwnerCannotGetOrConsumeEntry() {
        var service = mock(ItemVerificationEntryService.class);
        var controller = new ItemController(); ReflectionTestUtils.setField(controller,"verificationEntries",service);
        var target = new ItemVerificationEntryDTO.Target(7L,"123","handle");
        when(service.metadata(7L,"123")).thenThrow(new BusinessException(403,"denied"));
        when(service.consume(7L,"123","handle")).thenThrow(new BusinessException(403,"denied"));
        assertThrows(BusinessException.class, () -> controller.verificationEntry(target,new MockHttpServletResponse()));
        assertThrows(BusinessException.class, () -> controller.consumeVerificationEntry(target,new MockHttpServletResponse()));
    }
}
