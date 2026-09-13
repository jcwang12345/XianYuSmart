package com.xianyusmart.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XianyuApiCallUtilsTest {

    @Test
    void transportFailureWithoutResponseHasUnknownOutcome() {
        assertTrue(new XianyuApiCallUtils.ApiCallResult(false, null,
                "调用异常: Read timed out", false).isOutcomeUnknown());
        assertTrue(new XianyuApiCallUtils.ApiCallResult(false, null,
                "响应为空", false).isOutcomeUnknown());
    }

    @Test
    void explicitPlatformRejectionIsNotAnUnknownOutcome() {
        assertFalse(new XianyuApiCallUtils.ApiCallResult(false,
                "{\"ret\":[\"FAIL_BIZ\"]}", "平台拒绝", false).isOutcomeUnknown());
        assertFalse(new XianyuApiCallUtils.ApiCallResult(false,
                null, "账号风控冷却中", false).isOutcomeUnknown());
    }
}
