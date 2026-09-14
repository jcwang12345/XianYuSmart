package com.xianyusmart.exception;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    @Test
    void illegalArgumentIsAClientValidationError() {
        Map<String, Object> result = new GlobalExceptionHandler()
                .handleIllegalArgumentException(new IllegalArgumentException("商品价格最多保留两位小数"));

        assertEquals(400, result.get("code"));
        assertEquals("商品价格最多保留两位小数", result.get("message"));
    }
}
