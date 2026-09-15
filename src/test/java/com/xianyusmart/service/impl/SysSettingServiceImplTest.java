package com.xianyusmart.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xianyusmart.config.rag.DynamicAIChatClientManager;
import com.xianyusmart.config.rag.DynamicVectorStoreManager;
import com.xianyusmart.entity.XianyuSysSetting;
import com.xianyusmart.mapper.XianyuSysSettingMapper;
import com.xianyusmart.service.bo.GetSettingReqBO;
import com.xianyusmart.service.bo.GetSettingRespBO;
import com.xianyusmart.service.bo.SaveSettingReqBO;
import com.xianyusmart.service.OperationLogService;
import com.xianyusmart.entity.XianyuOperationLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SysSettingServiceImplTest {

    private XianyuSysSettingMapper mapper;
    private SysSettingServiceImpl service;
    private OperationLogService operationLogService;

    @BeforeEach
    void setUp() {
        mapper = mock(XianyuSysSettingMapper.class);
        service = new SysSettingServiceImpl();
        operationLogService = mock(OperationLogService.class);
        ReflectionTestUtils.setField(service, "sysSettingMapper", mapper);
        ReflectionTestUtils.setField(service, "operationLogService", operationLogService);
        ReflectionTestUtils.setField(service, "dynamicAIChatClientManager", mock(DynamicAIChatClientManager.class));
        ReflectionTestUtils.setField(service, "dynamicVectorStoreManager", mock(DynamicVectorStoreManager.class));
    }

    @Test
    void writeOnlySettingNeverReturnsItsValue() {
        XianyuSysSetting setting = setting("ai_api_key", "top-secret");
        setting.setUpdatedTime("2026-09-15 22:00:00.000");
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(setting);
        GetSettingReqBO request = new GetSettingReqBO();
        request.setSettingKey("ai_api_key");

        GetSettingRespBO response = service.getSetting(request);

        assertNull(response.getSettingValue());
        assertTrue(response.getConfigured());
        assertEquals("2026-09-15 22:00:00.000", response.getUpdatedTime());
    }

    @Test
    void blankSecretUpdatePreservesExistingCredential() {
        XianyuSysSetting existing = setting("email_smtp_password", "existing-secret");
        existing.setId(9L);
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        SaveSettingReqBO request = new SaveSettingReqBO();
        request.setSettingKey("email_smtp_password");
        request.setSettingValue("");
        request.setSettingDesc("SMTP credential");

        service.saveSetting(request);

        ArgumentCaptor<XianyuSysSetting> saved = ArgumentCaptor.forClass(XianyuSysSetting.class);
        verify(mapper).updateById(saved.capture());
        assertEquals("existing-secret", saved.getValue().getSettingValue());
        ArgumentCaptor<XianyuOperationLog> audit = ArgumentCaptor.forClass(XianyuOperationLog.class);
        verify(operationLogService).logRequired(audit.capture());
        assertTrue(audit.getValue().getFieldDiffJson().contains("已配置"));
        assertTrue(!audit.getValue().getFieldDiffJson().contains("existing-secret"));
    }

    @Test
    void ordinarySettingRemainsReadable() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(setting("ai_model", "model-1"));
        GetSettingReqBO request = new GetSettingReqBO();
        request.setSettingKey("ai_model");

        GetSettingRespBO response = service.getSetting(request);

        assertEquals("model-1", response.getSettingValue());
        assertNull(response.getConfigured());
    }

    @Test
    void menuLayoutAuditContainsBeforeAndAfterWithRequestId() {
        XianyuSysSetting existing = setting("menu_layout", "{\"groups\":[\"before\"]}");
        existing.setId(7L);
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        SaveSettingReqBO request = new SaveSettingReqBO();
        request.setSettingKey("menu_layout");
        request.setSettingValue("{\"groups\":[\"after\"]}");
        request.setSettingDesc("租户菜单布局");
        request.setRequestId("qa-menu-layout-audit");

        service.saveSetting(request);

        ArgumentCaptor<XianyuOperationLog> audit = ArgumentCaptor.forClass(XianyuOperationLog.class);
        verify(operationLogService).logRequired(audit.capture());
        assertEquals("MENU_LAYOUT_UPDATE", audit.getValue().getOperationType());
        assertEquals("qa-menu-layout-audit", audit.getValue().getRequestId());
        assertTrue(audit.getValue().getFieldDiffJson().contains("before"));
        assertTrue(audit.getValue().getFieldDiffJson().contains("after"));
    }

    private XianyuSysSetting setting(String key, String value) {
        XianyuSysSetting setting = new XianyuSysSetting();
        setting.setSettingKey(key);
        setting.setSettingValue(value);
        setting.setSettingDesc(key);
        return setting;
    }
}
