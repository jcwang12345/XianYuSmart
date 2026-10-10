package com.xianyusmart.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.common.ResultObject;
import com.xianyusmart.controller.dto.UniformCardDeliveryReqDTO;
import com.xianyusmart.entity.SysUser;
import com.xianyusmart.interceptor.AccessControlInterceptor;
import com.xianyusmart.service.AutoDeliveryConfigService;
import com.xianyusmart.service.PermissionCatalog;
import com.xianyusmart.service.PlatformPermissionService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UniformCardDeliveryBoundaryTest {
    private final ObjectMapper json=new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final AutoDeliveryConfigService service=mock(AutoDeliveryConfigService.class);
    private final PlatformPermissionService permissions=mock(PlatformPermissionService.class);
    private final SysUser actor=new SysUser();
    private MockMvc mvc;
    private static final String ROUTE="/api/auto-delivery-config/uniform-card";
    private static final String BODY="""
            {"xianyuAccountId":1,"xyGoodsId":"G-1","configId":10,"enabled":true,"confirmAllVariantsSameEntitlement":true}
            """;
    @BeforeEach void setup(){
        actor.setId(8L); actor.setTenantId(7L); actor.setStatus(1); actor.setRole(SysUser.ROLE_USER); actor.setMemberRole("OPERATOR");
        when(permissions.getPermissionCodeSet(actor)).thenReturn(Set.of(PermissionCatalog.MENU_AUTO_DELIVERY,PermissionCatalog.ACTION_DELIVERY_WRITE));
        when(service.configureUniformCard(any())).thenReturn(ResultObject.success(null));
        var controller=new AutoDeliveryConfigController();
        ReflectionTestUtils.setField(controller,"autoDeliveryConfigService",service);
        mvc=MockMvcBuilders.standaloneSetup(controller).setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .addInterceptors(new AccessControlInterceptor(permissions)).build();
    }
    @Test void regularWriterCanConfigure() throws Exception{
        mvc.perform(post(ROUTE).requestAttr("currentUser",actor).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        verify(service).configureUniformCard(any());
    }
    @Test void missingWriterPermissionCannotConfigure() throws Exception{
        when(permissions.getPermissionCodeSet(actor)).thenReturn(Set.of(PermissionCatalog.MENU_AUTO_DELIVERY));
        mvc.perform(post(ROUTE).requestAttr("currentUser",actor).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
        verifyNoInteractions(service);
    }
    @Test void anonymousCannotConfigure() throws Exception{
        mvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
        verifyNoInteractions(service);
    }
    @ParameterizedTest @ValueSource(strings={"skuId","kamiConfigIds","deliveryMode","tenantId","command","unknown"})
    void extraFieldsRejected(String name){
        var body=assertDoesNotThrow(()->json.readTree(BODY));
        ((com.fasterxml.jackson.databind.node.ObjectNode)body).put(name,"unexpected");
        assertThrows(JsonMappingException.class,()->json.treeToValue(body,UniformCardDeliveryReqDTO.class));
    }
    @Test void invalidAccountFailsValidation() throws Exception{
        mvc.perform(post(ROUTE).requestAttr("currentUser",actor).contentType(MediaType.APPLICATION_JSON).content(BODY.replace("\"xianyuAccountId\":1","\"xianyuAccountId\":0")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
