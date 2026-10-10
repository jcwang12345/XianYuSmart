package com.xianyusmart.controller;
import com.xianyusmart.service.PdcaProgressService;import com.xianyusmart.exception.BusinessException;
import org.junit.jupiter.api.Test;import org.springframework.mock.web.*;import java.util.Map;
import static org.mockito.Mockito.*;import static org.junit.jupiter.api.Assertions.*;
class PdcaProgressControllerTest {
 @Test void readsAreNoStoreAndUnknownOrDuplicateParametersNeverReachService(){var service=mock(PdcaProgressService.class);var controller=new PdcaProgressController(service);var request=new MockHttpServletRequest("GET","/api/pdca/overview");var response=new MockHttpServletResponse();when(service.overview(null)).thenReturn(Map.of("snapshotAvailable",false));assertFalse((Boolean)controller.overview(request,response).getData().get("snapshotAvailable"));assertEquals("no-store",response.getHeader("Cache-Control"));clearInvocations(service);request.addParameter("tenant","2");assertEquals(400,assertThrows(BusinessException.class,()->controller.overview(request,response)).getCode());verifyNoInteractions(service);var duplicate=new MockHttpServletRequest();duplicate.addParameter("type","bug","requirement");assertThrows(BusinessException.class,()->controller.items(duplicate,new MockHttpServletResponse()));verifyNoInteractions(service);}
}
