package com.xianyusmart.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ItemSkuSnapshotDiagnosticsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void observesOnlyFixedPathsAndDistinguishesMissingNullAndEmptyArray() throws Exception {
        String response = """
                {"data":{"skuInfo":{"skuList":[]},"skuDO":null,"itemDO":{
                  "skuList":[],"idleItemSkuList":null,"skuCount":0,"hasSku":false,"simpleItem":"true",
                  "skuInfo":{"itemSkuList":[{"skuId":"private-value"}]}
                }}}
                """;
        var selected = objectMapper.readTree(response).path("data").path("itemDO").path("skuList");

        var diagnostic = ItemSkuSnapshotDiagnostics.describe(response, selected);

        assertEquals("OBJECT", diagnostic.responseShape());
        assertEquals("ARRAY(count=0)", diagnostic.selectedShape());
        assertEquals("ARRAY(count=0)", diagnostic.pathShapes().get("data.skuInfo.skuList"));
        assertEquals("NULL", diagnostic.pathShapes().get("data.skuDO"));
        assertEquals("MISSING", diagnostic.pathShapes().get("data.skuDO.skuList"));
        assertEquals("MISSING", diagnostic.pathShapes().get("data.itemDO.itemSkuList"));
        assertEquals("ARRAY(count=1)", diagnostic.pathShapes().get("data.itemDO.skuInfo.itemSkuList"));
        assertEquals("NUMBER", diagnostic.pathShapes().get("data.itemDO.skuCount"));
        assertEquals("BOOLEAN", diagnostic.pathShapes().get("data.itemDO.hasSku"));
        assertEquals("STRING", diagnostic.pathShapes().get("data.itemDO.simpleItem"));
        assertEquals(23, diagnostic.pathShapes().size());
        assertThrows(UnsupportedOperationException.class,
                () -> diagnostic.pathShapes().put("new-field", "OBJECT"));
    }

    @Test
    void neverIncludesContentScalarValuesOrUnknownFieldNames() throws Exception {
        String response = """
                {"ret":["SUCCESS::private-ret-value"],"cookie":"cookie-private-value",
                 "unknown-private-field-name":{"skuList":["unknown-private-array-value"]},
                 "data":{"buyer":{"name":"buyer-private-value"},
                   "skuInfo":{"unknown-nested-private-field":"nested-private-value"},
                   "itemDO":{"desc":"description-private-value","price":91234567,"quantity":87654321,
                     "skuCount":71234567,"hasSku":"has-sku-private-value","simpleItem":"simple-private-value",
                     "skuList":[{"skuId":"sku-private-value","features":"activation-private-value"}]}}}
                """;
        var selected = objectMapper.readTree(response).path("data").path("itemDO").path("skuList");

        String loggable = ItemSkuSnapshotDiagnostics.describe(response, selected).toString();

        for (String secret : List.of("private-ret-value", "cookie-private-value", "unknown-private-field-name",
                "unknown-private-array-value", "unknown-nested-private-field", "nested-private-value",
                "buyer-private-value", "description-private-value", "91234567", "87654321", "71234567",
                "has-sku-private-value", "simple-private-value", "sku-private-value", "activation-private-value")) {
            assertFalse(loggable.contains(secret), secret);
        }
        assertTrue(loggable.contains("ARRAY(count=1)"));
        assertFalse(loggable.contains("quantity"));
        assertFalse(loggable.contains("price"));
        assertFalse(loggable.contains("cookie"));
        assertFalse(loggable.contains("buyer"));
        assertFalse(loggable.contains("desc"));
    }

    @Test
    void treatsMalformedResponseAsDiagnosticFailureWithoutExposingInputOrException() {
        var diagnostic = ItemSkuSnapshotDiagnostics.describe("invalid-private-response", null);

        assertEquals("UNPARSEABLE", diagnostic.responseShape());
        assertEquals("MISSING", diagnostic.selectedShape());
        assertTrue(diagnostic.pathShapes().isEmpty());
        assertFalse(diagnostic.toString().contains("invalid-private-response"));
    }

    @Test
    void doesNotDiscoverArraysAtUnlistedLocationsOrMakeThemTheSelectedSnapshot() {
        String response = "{\"data\":{\"other\":{\"skuList\":[{\"skuId\":\"private\"}]},\"itemDO\":{}}}";

        var diagnostic = ItemSkuSnapshotDiagnostics.describe(response, null);

        assertEquals("MISSING", diagnostic.selectedShape());
        assertEquals("MISSING", diagnostic.pathShapes().get("data.itemDO.skuList"));
        assertFalse(diagnostic.toString().contains("other"));
        assertFalse(diagnostic.toString().contains("private"));
    }
}
