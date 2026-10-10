package com.xianyusmart.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xianyusmart.common.ResultObject;
import com.xianyusmart.controller.dto.ReceiptFollowUpUpdateReqDTO;
import com.xianyusmart.entity.SysUser;
import com.xianyusmart.interceptor.AccessControlInterceptor;
import com.xianyusmart.service.AutoDeliveryConfigService;
import com.xianyusmart.service.PermissionCatalog;
import com.xianyusmart.service.PlatformPermissionService;
import jakarta.validation.Valid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReceiptFollowUpUpdateBoundaryTest {

    private static final String ROUTE = "/api/auto-delivery-config/receipt-follow-up";
    private static final String MESSAGES = "[\"确认收货后如有疑问请联系我\",\"感谢支持\"]";
    private static final Set<String> WRITE_PERMISSIONS = Set.of(
            PermissionCatalog.MENU_AUTO_DELIVERY, PermissionCatalog.ACTION_DELIVERY_WRITE);

    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private AutoDeliveryConfigService configService;
    private PlatformPermissionService permissionService;
    private SysUser actor;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        configService = mock(AutoDeliveryConfigService.class);
        permissionService = mock(PlatformPermissionService.class);
        actor = new SysUser();
        actor.setId(900001L);
        actor.setTenantId(900001L);
        actor.setRole(SysUser.ROLE_USER);
        actor.setMemberRole("OPERATOR");
        actor.setStatus(1);
        when(permissionService.getPermissionCodeSet(actor)).thenReturn(WRITE_PERMISSIONS);
        when(configService.updateReceiptFollowUp(any(ReceiptFollowUpUpdateReqDTO.class)))
                .thenReturn(ResultObject.success(null));

        AutoDeliveryConfigController controller = new AutoDeliveryConfigController();
        ReflectionTestUtils.setField(controller, "autoDeliveryConfigService", configService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .addInterceptors(new AccessControlInterceptor(permissionService))
                .build();
    }

    @Test
    void requestContractContainsOnlyFiveAllowedFieldsAndReadsTheirValues() throws Exception {
        Set<String> properties = mapper.getDeserializationConfig()
                .introspect(mapper.constructType(ReceiptFollowUpUpdateReqDTO.class))
                .findProperties().stream().map(property -> property.getName()).collect(Collectors.toSet());
        assertEquals(Set.of("xianyuAccountId", "xyGoodsId", "skuId",
                "receiptFollowUpMessages", "receiptFollowUpIntervalSeconds"), properties);

        ReceiptFollowUpUpdateReqDTO request = mapper.treeToValue(validRequest(), ReceiptFollowUpUpdateReqDTO.class);
        assertRequestValues(request);
    }

    @Test
    void skuIdIsOptional() throws Exception {
        ObjectNode body = validRequest();
        body.remove("skuId");

        ReceiptFollowUpUpdateReqDTO request = mapper.treeToValue(body, ReceiptFollowUpUpdateReqDTO.class);

        assertNull(request.getSkuId());
        assertEquals(900001L, request.getXianyuAccountId());
        assertEquals(MESSAGES, request.getReceiptFollowUpMessages());
    }

    @ParameterizedTest
    @ValueSource(strings = {"deliveryMode", "fixedTemplateId", "kamiConfigIds", "voucherDeliveryEnabled",
            "chatDeliveryEnabled", "autoConfirmShipment", "autoDeliveryContent", "xianyuGoodsId",
            "unknown", "arbitraryUnexpectedField"})
    void unknownFieldsAreRejectedEvenWhenMapperGloballyIgnoresThem(String field) {
        ObjectNode body = validRequest().put(field, "unexpected");

        assertThrows(JsonMappingException.class,
                () -> mapper.treeToValue(body, ReceiptFollowUpUpdateReqDTO.class));
    }

    @Test
    void nullUnknownFieldCannotBypassTheContract() {
        ObjectNode body = validRequest().putNull("unknown");

        assertThrows(JsonMappingException.class,
                () -> mapper.treeToValue(body, ReceiptFollowUpUpdateReqDTO.class));
    }

    @Test
    void endpointIsPublicPostWithValidatedRequestBodyAndVoidResult() throws Exception {
        RequestMapping controllerMapping = AutoDeliveryConfigController.class.getAnnotation(RequestMapping.class);
        assertArrayEquals(new String[]{"/api/auto-delivery-config"}, controllerMapping.value());
        var method = AutoDeliveryConfigController.class.getDeclaredMethod(
                "updateReceiptFollowUp", ReceiptFollowUpUpdateReqDTO.class);
        assertTrue(Modifier.isPublic(method.getModifiers()));
        PostMapping endpointMapping = method.getAnnotation(PostMapping.class);
        assertNotNull(endpointMapping);
        assertArrayEquals(new String[]{"/receipt-follow-up"}, endpointMapping.value());
        assertNotNull(method.getParameters()[0].getAnnotation(Valid.class));
        assertNotNull(method.getParameters()[0].getAnnotation(RequestBody.class));
        ParameterizedType resultType = (ParameterizedType) method.getGenericReturnType();
        assertEquals(ResultObject.class, resultType.getRawType());
        assertArrayEquals(new Class<?>[]{Void.class}, resultType.getActualTypeArguments());
    }

    @Test
    void bothExistingPermissionsAllowTheNewEndpointAndDelegateOnlyTheNarrowRequest() throws Exception {
        mvc.perform(post(ROUTE).requestAttr("currentUser", actor)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(validRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        ArgumentCaptor<ReceiptFollowUpUpdateReqDTO> request = ArgumentCaptor.forClass(ReceiptFollowUpUpdateReqDTO.class);
        verify(configService).updateReceiptFollowUp(request.capture());
        assertRequestValues(request.getValue());
        verifyNoMoreInteractions(configService);
    }

    @ParameterizedTest
    @MethodSource("incompletePermissions")
    void existingPrefixPermissionRulesDenyMenuOnlyOrActionOnly(Set<String> permissions) throws Exception {
        when(permissionService.getPermissionCodeSet(actor)).thenReturn(permissions);

        mvc.perform(post(ROUTE).requestAttr("currentUser", actor)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(validRequest())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        verifyNoInteractions(configService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"deliveryMode", "fixedTemplateId", "kamiConfigIds", "voucherDeliveryEnabled",
            "chatDeliveryEnabled", "autoConfirmShipment", "unknown"})
    void httpUnknownFieldsFailBeforeCallingTheService(String field) throws Exception {
        ObjectNode body = validRequest().put(field, "unexpected");

        mvc.perform(post(ROUTE).requestAttr("currentUser", actor)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(configService);
    }

    private static Stream<Set<String>> incompletePermissions() {
        return Stream.of(Set.of(PermissionCatalog.MENU_AUTO_DELIVERY),
                Set.of(PermissionCatalog.ACTION_DELIVERY_WRITE));
    }

    private ObjectNode validRequest() {
        return mapper.createObjectNode()
                .put("xianyuAccountId", 900001L)
                .put("xyGoodsId", "900001000001")
                .put("skuId", "sku-1")
                .put("receiptFollowUpMessages", MESSAGES)
                .put("receiptFollowUpIntervalSeconds", 30);
    }

    private void assertRequestValues(ReceiptFollowUpUpdateReqDTO request) {
        assertEquals(900001L, request.getXianyuAccountId());
        assertEquals("900001000001", request.getXyGoodsId());
        assertEquals("sku-1", request.getSkuId());
        assertEquals(MESSAGES, request.getReceiptFollowUpMessages());
        assertEquals(30, request.getReceiptFollowUpIntervalSeconds());
    }
}
