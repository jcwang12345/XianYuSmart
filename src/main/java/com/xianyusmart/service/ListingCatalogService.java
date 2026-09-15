package com.xianyusmart.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 版本化发布目录。当前数据源是本地参考目录，不冒充闲鱼平台类目真值。
 */
@Service
public class ListingCatalogService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ListingCatalogService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Catalog active(String listingType) {
        String type = normalizeType(listingType);
        List<Map<String, Object>> versions = jdbcTemplate.queryForList("""
                SELECT id,version_code,source,verification_status,effective_time
                  FROM xianyu_listing_catalog_version
                 WHERE active=1 ORDER BY effective_time DESC,id DESC LIMIT 1
                """);
        if (versions.isEmpty()) throw new BusinessException(503, "商品类目目录尚未配置，请稍后重试");
        Map<String, Object> version = versions.get(0);
        Long versionId = ((Number) version.get("id")).longValue();

        Map<String, Map<String, Object>> industries = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT industry_code,industry_name
                  FROM xianyu_listing_catalog_industry
                 WHERE catalog_version_id=? AND listing_type=?
                 ORDER BY sort_order,id
                """, rs -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", rs.getString("industry_code"));
            item.put("name", rs.getString("industry_name"));
            item.put("leafCategories", new ArrayList<Map<String, Object>>());
            industries.put(rs.getString("industry_code"), item);
        }, versionId, type);

        Map<String, Map<String, Object>> leaves = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT industry_code,category_code,category_name
                  FROM xianyu_listing_catalog_category
                 WHERE catalog_version_id=? AND listing_type=?
                 ORDER BY sort_order,id
                """, rs -> {
            Map<String, Object> leaf = new LinkedHashMap<>();
            leaf.put("code", rs.getString("category_code"));
            leaf.put("name", rs.getString("category_name"));
            leaf.put("source", String.valueOf(version.get("source")));
            leaf.put("attributes", new ArrayList<Map<String, Object>>());
            leaves.put(rs.getString("category_code"), leaf);
            Map<String, Object> industry = industries.get(rs.getString("industry_code"));
            if (industry != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> children = (List<Map<String, Object>>) industry.get("leafCategories");
                children.add(leaf);
            }
        }, versionId, type);

        jdbcTemplate.query("""
                SELECT category_code,attribute_code,attribute_name,control_type,required_flag,options_json
                  FROM xianyu_listing_catalog_attribute
                 WHERE catalog_version_id=? ORDER BY sort_order,id
                """, rs -> {
            Map<String, Object> leaf = leaves.get(rs.getString("category_code"));
            if (leaf == null) return;
            Map<String, Object> attribute = new LinkedHashMap<>();
            attribute.put("code", rs.getString("attribute_code"));
            attribute.put("name", rs.getString("attribute_name"));
            attribute.put("controlType", rs.getString("control_type"));
            attribute.put("required", rs.getBoolean("required_flag"));
            attribute.put("options", readOptions(rs.getString("options_json")));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> attributes = (List<Map<String, Object>>) leaf.get("attributes");
            attributes.add(attribute);
        }, versionId);

        return new Catalog(
                String.valueOf(version.get("version_code")),
                String.valueOf(version.get("source")),
                String.valueOf(version.get("verification_status")),
                type,
                List.copyOf(industries.values()),
                Map.copyOf(leaves)
        );
    }

    private List<String> readOptions(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return objectMapper.readValue(json, new TypeReference<>() { }); }
        catch (Exception ignored) { return List.of(); }
    }

    static String normalizeType(String value) {
        if ("PHYSICAL".equalsIgnoreCase(value)) return "PHYSICAL";
        if ("SERVICE".equalsIgnoreCase(value)) return "SERVICE";
        return "VIRTUAL";
    }

    public record Catalog(String version, String source, String verificationStatus,
                          String listingType, List<Map<String, Object>> industries,
                          Map<String, Map<String, Object>> leaves) {
        public Map<String, Object> leaf(String code) {
            return code == null ? null : leaves.get(code);
        }
    }
}
