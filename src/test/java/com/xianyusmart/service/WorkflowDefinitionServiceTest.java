package com.xianyusmart.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkflowDefinitionServiceTest {

    private final WorkflowDefinitionService service = new WorkflowDefinitionService();

    @Test
    void sortsAValidSinglePathByDependencyInsteadOfCanvasOrder() {
        Map<String, Object> definition = Map.of(
                "nodes", List.of(node("publish", "PUBLISH"), node("trigger", "TRIGGER"),
                        node("material", "MATERIAL"), node("search", "SEARCH"),
                        node("collect", "COLLECT"), node("filter", "FILTER")),
                "edges", List.of(edge("trigger", "search"), edge("search", "filter"),
                        edge("filter", "collect"), edge("collect", "material"), edge("material", "publish")));

        assertEquals(List.of("trigger", "search", "filter", "collect", "material", "publish"),
                service.validateAndSort(definition).stream().map(item -> item.get("id")).toList());
    }

    @Test
    void rejectsCyclesAndDisconnectedNodes() {
        Map<String, Object> definition = Map.of(
                "nodes", List.of(node("trigger", "TRIGGER"), node("search", "SEARCH"), node("filter", "FILTER")),
                "edges", List.of(edge("trigger", "search"), edge("search", "filter"), edge("filter", "search")));

        assertThrows(IllegalArgumentException.class, () -> service.validateAndSort(definition));
    }

    @Test
    void rejectsBusinessOrderThatCouldBypassEvidenceCollection() {
        Map<String, Object> definition = Map.of(
                "nodes", List.of(node("trigger", "TRIGGER"), node("collect", "COLLECT"), node("search", "SEARCH")),
                "edges", List.of(edge("trigger", "collect"), edge("collect", "search")));

        assertThrows(IllegalArgumentException.class, () -> service.validateAndSort(definition));
    }

    @Test
    void rejectsDuplicateBusinessNodeTypes() {
        Map<String, Object> definition = Map.of(
                "nodes", List.of(node("trigger", "TRIGGER"), node("search-1", "SEARCH"), node("search-2", "SEARCH")),
                "edges", List.of(edge("trigger", "search-1"), edge("search-1", "search-2")));

        assertThrows(IllegalArgumentException.class, () -> service.validateAndSort(definition));
    }

    @Test
    void rejectsMoreThanTwentyFourNodesEvenBeforeOtherValidation() {
        List<Map<String, Object>> nodes = java.util.stream.IntStream.range(0, 25)
                .mapToObj(index -> node("node-" + index, index == 0 ? "TRIGGER" : "SEARCH"))
                .toList();
        assertThrows(IllegalArgumentException.class,
                () -> service.validateAndSort(Map.of("nodes", nodes, "edges", List.of())));
    }

    private Map<String, Object> node(String id, String type) {
        return Map.of("id", id, "type", type, "name", id, "config", Map.of());
    }

    private Map<String, Object> edge(String source, String target) {
        return Map.of("source", source, "target", target);
    }
}
