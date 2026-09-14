package com.xianyusmart.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.config.JacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.http.MockHttpInputMessage;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AIChatControllerDateTimeTest {

    private MappingJackson2HttpMessageConverter converter;

    @BeforeEach
    void setUp() {
        org.springframework.http.converter.json.Jackson2ObjectMapperBuilder builder =
                new org.springframework.http.converter.json.Jackson2ObjectMapperBuilder();
        new JacksonConfig().jacksonCustomizer().customize(builder);
        ObjectMapper mapper = builder.build();
        converter = new MappingJackson2HttpMessageConverter(mapper);
    }

    @Test
    void acceptsBrowserDateTimeLocalWithMinutesSecondsAndMillis() throws Exception {
        assertEquals(LocalDateTime.of(2026, 9, 15, 19, 14), parse("2026-09-15T19:14"));
        assertEquals(LocalDateTime.of(2026, 9, 15, 19, 14, 13), parse("2026-09-15T19:14:13"));
        assertEquals(LocalDateTime.of(2026, 9, 15, 19, 14, 13, 887_000_000),
                parse("2026-09-15T19:14:13.887"));
    }

    @Test
    void rejectsMalformedDateTimeAsUnreadableRequest() {
        assertThrows(HttpMessageNotReadableException.class, () -> parse("2026/09/15 19:14"));
    }

    private LocalDateTime parse(String value) throws Exception {
        String json = "{\"accountId\":101,\"goodsId\":\"QA-GOODS-0999\","
                + "\"fixedMaterial\":\"QA\",\"effectiveTime\":\"" + value + "\","
                + "\"requestId\":\"qa-date-test\"}";
        MockHttpInputMessage input = new MockHttpInputMessage(json.getBytes(StandardCharsets.UTF_8));
        AIChatController.FixedMaterialReqDTO dto = (AIChatController.FixedMaterialReqDTO)
                converter.read(AIChatController.FixedMaterialReqDTO.class, input);
        return dto.getEffectiveTime();
    }
}
