package com.xianyusmart.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductContentPolicyServiceTest {

    @Test
    void configurableRiskTermBlocksPublish() {
        SysSettingService settings = mock(SysSettingService.class);
        when(settings.getSettingValue("product_prohibited_terms")).thenReturn("站外联系,诱导好评");
        ProductContentPolicyService service = new ProductContentPolicyService(settings);

        assertThrows(com.xianyusmart.exception.BusinessException.class,
                () -> service.validate(Map.of("name", "请勿站外联系", "description", "正常描述")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void inspectionReturnsFieldLevelFindingsWithoutCreatingAPlatformRequest() {
        SysSettingService settings = mock(SysSettingService.class);
        when(settings.getSettingValue("product_prohibited_terms")).thenReturn("站外联系,诱导好评");
        ProductContentPolicyService service = new ProductContentPolicyService(settings);

        Map<String, Object> result = service.inspect(Map.of(
                "name", "正版办公插件",
                "description", "请勿站外联系",
                "supportPolicy", "解决后请诱导好评",
                "categoryAttributes", Map.of("适用场景", "正常办公")));

        assertFalse((Boolean) result.get("valid"));
        assertEquals(2, result.get("blockerCount"));
        assertEquals(List.of("站外联系", "诱导好评"), result.get("matchedTerms"));
        List<Map<String, Object>> findings = (List<Map<String, Object>>) result.get("findings");
        assertEquals("description", findings.get(0).get("field"));
        assertEquals("BLOCKER", findings.get(0).get("severity"));
        assertTrue(String.valueOf(findings.get(0).get("suggestion")).contains("重新校验"));
        assertTrue(String.valueOf(result.get("policyVersion")).startsWith("terms-"));
        assertTrue(String.valueOf(result.get("nextAction")).contains("不会生成"));
    }

    @Test
    void safeInspectionIsExplicitAndVersionIsStable() {
        SysSettingService settings = mock(SysSettingService.class);
        when(settings.getSettingValue("product_prohibited_terms")).thenReturn("站外联系,诱导好评");
        ProductContentPolicyService service = new ProductContentPolicyService(settings);

        Map<String, Object> first = service.inspect(Map.of("name", "办公插件", "description", "群内提供安装指导"));
        Map<String, Object> second = service.inspect(Map.of("name", "另一商品", "description", "正常说明"));

        assertTrue((Boolean) first.get("valid"));
        assertEquals("CLEAR", first.get("highestSeverity"));
        assertEquals(first.get("policyVersion"), second.get("policyVersion"));
        assertEquals(List.of(), first.get("findings"));
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
