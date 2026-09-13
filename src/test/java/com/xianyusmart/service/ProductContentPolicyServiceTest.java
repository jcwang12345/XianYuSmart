package com.xianyusmart.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductContentPolicyServiceTest {

    @Test
    void configurableRiskTermBlocksPublish() {
        SysSettingService settings = mock(SysSettingService.class);
        when(settings.getSettingValue("product_prohibited_terms")).thenReturn("站外联系,诱导好评");
        ProductContentPolicyService service = new ProductContentPolicyService(settings);

        assertThrows(IllegalArgumentException.class,
                () -> service.validate(Map.of("name", "请勿站外联系", "description", "正常描述")));
    }

    @Test
    void unsupportedVideoIsExplicitlyRejectedInsteadOfDropped() {
        SysSettingService settings = mock(SysSettingService.class);
        ProductContentPolicyService service = new ProductContentPolicyService(settings);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.validate(Map.of("name", "插件", "videos", List.of("video.mp4"))));
        assertEquals("当前账号尚未验证视频发布能力，请先在能力矩阵确认后再使用", error.getMessage());
    }
}
