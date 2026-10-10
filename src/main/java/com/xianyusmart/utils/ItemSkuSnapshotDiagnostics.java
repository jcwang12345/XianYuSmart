package com.xianyusmart.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shape-only observations for an already received detail response. This does not
 * select, normalize, or validate a SKU snapshot, and never includes field values
 * or discovers field names from the response.
 */
public final class ItemSkuSnapshotDiagnostics {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<String> OBSERVED_PATHS = List.of(
            "data.skuList", "data.idleItemSkuList", "data.itemSkuList",
            "data.skuInfo", "data.skuInfo.skuList", "data.skuInfo.itemSkuList",
            "data.skuDO", "data.skuDO.skuList", "data.skuBase", "data.skuBase.skus",
            "data.itemDO.skuList", "data.itemDO.idleItemSkuList", "data.itemDO.itemSkuList",
            "data.itemDO.skuInfo", "data.itemDO.skuInfo.skuList", "data.itemDO.skuInfo.itemSkuList",
            "data.itemDO.skuDO", "data.itemDO.skuDO.skuList",
            "data.itemDO.skuBase", "data.itemDO.skuBase.skus",
            "data.itemDO.hasSku", "data.itemDO.skuCount", "data.itemDO.simpleItem");

    private ItemSkuSnapshotDiagnostics() {
    }

    public record Diagnostic(String responseShape, String selectedShape, Map<String, String> pathShapes) {
    }

    public static Diagnostic describe(String receivedResponse, JsonNode selectedSkuNode) {
        JsonNode response;
        try {
            response = receivedResponse == null ? null : OBJECT_MAPPER.readTree(receivedResponse);
        } catch (Exception ignored) {
            // A diagnostic must neither expose parse errors nor change sync outcomes.
            return new Diagnostic("UNPARSEABLE", shape(selectedSkuNode), Map.of());
        }
        Map<String, String> paths = new LinkedHashMap<>();
        for (String path : OBSERVED_PATHS) {
            JsonNode node = response;
            for (String segment : path.split("\\.")) {
                node = node == null ? null : node.path(segment);
            }
            paths.put(path, shape(node));
        }
        return new Diagnostic(shape(response), shape(selectedSkuNode),
                Collections.unmodifiableMap(paths));
    }

    private static String shape(JsonNode node) {
        if (node == null || node.isMissingNode()) return "MISSING";
        if (node.isNull()) return "NULL";
        if (node.isArray()) return "ARRAY(count=" + node.size() + ")";
        if (node.isObject()) return "OBJECT";
        if (node.isTextual()) return "STRING";
        if (node.isNumber()) return "NUMBER";
        if (node.isBoolean()) return "BOOLEAN";
        return "OTHER";
    }
}
