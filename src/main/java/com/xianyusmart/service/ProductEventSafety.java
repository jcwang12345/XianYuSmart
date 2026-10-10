package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

/** A closed product-event schema; arbitrary historical JSON is never a public snapshot. */
@Service
public class ProductEventSafety {
    private static final Set<String> FIELDS = Set.of("title", "price", "stock", "statusBucket", "skuCount",
            "coverageStatus", "supportPolicy", "location", "skuSummary");
    private static final Set<String> AUTOMATION = Set.of("autoDeliveryEnabled", "autoReplyEnabled",
            "autoRateEnabled", "autoPolishEnabled", "humanTakeoverEnabled");
    private static final Pattern PRIVATE_TEXT = Pattern.compile(
            "(?i)(cookie|token|authorization|password|passwd|secret|session|bearer|transcript|私聊|聊天记录|联系人|收货地址|微信|wechat|wxid_|手机号|电话[:：=]|QQ[:：=]|_m_h5_tk|_tb_token_|message\\s*[:=]|body\\s*[:=])"
                    + "|[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}|(?<![0-9])(?:\\+?86[- ]?)?1[3-9][0-9]{9}(?![0-9])"
                    + "|eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+|\\bsk-[A-Za-z0-9_-]{16,}"
                    + "|(?:路|街|巷).{0,20}[0-9一二三四五六七八九十]+号|[0-9]+(?:栋|室)");
    private final ObjectMapper json;

    public ProductEventSafety(ObjectMapper applicationMapper) {
        // Application ID serializers may stringify Long. Signed canonical JSON must use portable JSON numbers.
        this.json = new ObjectMapper();
        this.json.configure(com.fasterxml.jackson.core.JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN,true);
    }

    public record Projection(Map<String, Object> content, List<String> redactedFields, boolean contentOmitted) {}

    public Projection snapshot(Object input, boolean strict, boolean historical) {
        Map<String, Object> result = new TreeMap<>();
        Set<String> omitted = new TreeSet<>();
        if (input == null) return new Projection(result, List.of(), false);
        if (!(input instanceof Map<?, ?> map)) {
            if (strict) throw invalid();
            return new Projection(result, List.of("$content"), true);
        }
        if (strict && map.size() > 100) throw invalid();
        for (var entry : map.entrySet()) {
            String field = String.valueOf(entry.getKey());
            if (!FIELDS.contains(field) && !(historical && AUTOMATION.contains(field))) {
                omitted.add("$unknown");
                continue;
            }
            try {
                Object value = value(field, entry.getValue(), strict, omitted);
                if (value != OMIT) result.put(field, value);
            } catch (BusinessException invalid) {
                if (strict) throw invalid;
                omitted.add(field);
            }
        }
        return new Projection(result, List.copyOf(omitted), !omitted.isEmpty());
    }

    private static final Object OMIT = new Object();
    private Object value(String field, Object input, boolean strict, Set<String> omitted) {
        if (input == null) return null;
        if (AUTOMATION.contains(field)) {
            if (!(input instanceof Boolean)) throw invalid();
            return input;
        }
        return switch (field) {
            case "title", "supportPolicy", "location", "skuId" -> {
                if (!(input instanceof String text)) throw invalid();
                int limit = strict ? switch (field) { case "title" -> 256; case "supportPolicy" -> 500; default -> 100; }
                        : switch(field) { case "title" -> 500; case "supportPolicy" -> 1000; case "location" -> 255; default -> 100; };
                if (text.length() > limit) { if (strict) throw invalid(); omitted.add(field); yield OMIT; }
                if (PRIVATE_TEXT.matcher(text).find() || text.chars().anyMatch(c -> c < 32 && c != 9 && c != 10)) {
                    omitted.add(field); yield OMIT;
                }
                yield text;
            }
            case "price" -> {
                if (!(input instanceof Number) && strict) throw invalid();
                BigDecimal price;
                try { price = new BigDecimal(input.toString()).setScale(2, RoundingMode.UNNECESSARY); }
                catch (RuntimeException error) { throw invalid(); }
                if (price.signum() < 0 || price.compareTo(new BigDecimal("9999999.99")) > 0) throw invalid();
                yield price.stripTrailingZeros();
            }
            case "stock", "skuCount" -> {
                if (!(input instanceof Number)) throw invalid();
                long value;
                try { value = new BigDecimal(input.toString()).longValueExact(); }
                catch (RuntimeException error) { throw invalid(); }
                if (value < 0 || value > (field.equals("stock") ? Integer.MAX_VALUE : 10000)) throw invalid();
                yield (int)value;
            }
            case "statusBucket", "coverageStatus" -> {
                Set<String> allowed = field.equals("statusBucket")
                        ? Set.of("ON_SALE", "SOLD", "OFF_SHELF", "OTHER", "UNKNOWN")
                        : Set.of("FULL", "PARTIAL", "UNSYNCED", "EMPTY_VERIFIED");
                if (!(input instanceof String) || !allowed.contains(input)) throw invalid();
                yield input;
            }
            case "skuSummary" -> {
                if (!(input instanceof List<?> list) || list.size() > 50) throw invalid();
                List<Object> skus = new ArrayList<>();
                for (Object sku : list) {
                    if (!(sku instanceof Map<?, ?> map)) throw invalid();
                    Map<String, Object> clean = new TreeMap<>();
                    for (var entry : map.entrySet()) {
                        String key = String.valueOf(entry.getKey());
                        if (!Set.of("skuId", "price", "stock").contains(key)) { omitted.add("$skuUnknown"); continue; }
                        Object safe = value(key, entry.getValue(), strict, omitted);
                        if (safe != OMIT) clean.put(key, safe);
                    }
                    skus.add(clean);
                }
                yield skus;
            }
            default -> throw invalid();
        };
    }

    public Map<String, Object> diff(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> fields = new TreeMap<>();
        var keys = new TreeSet<>(before.keySet()); keys.addAll(after.keySet());
        for (String field : keys) if (!Objects.equals(before.get(field), after.get(field))) {
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("label", label(field)); change.put("before", before.get(field)); change.put("after", after.get(field));
            fields.put(field, change);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1); result.put("mode", "SANITIZED_CONTENT");
        result.put("changedFieldCount", fields.size()); result.put("changedFields", List.copyOf(fields.keySet()));
        result.put("fields", fields);
        return result;
    }

    /** Preserve known local edit and automation changes while discarding unknown historical wrappers. */
    public Map<String, Object> historicalDiff(Object input) {
        Map<String, Object> fields = new TreeMap<>();
        if (input instanceof Map<?, ?> map && map.get("fields") instanceof Map<?, ?> original) {
            for (var entry : original.entrySet()) {
                String field = String.valueOf(entry.getKey());
                if ((!FIELDS.contains(field) && !AUTOMATION.contains(field)) || !(entry.getValue() instanceof Map<?, ?> change)) continue;
                var old = snapshot(Collections.singletonMap(field, change.get("before")), false, true);
                var next = snapshot(Collections.singletonMap(field, change.get("after")), false, true);
                if (!old.content().containsKey(field) || !next.content().containsKey(field)) continue;
                Map<String, Object> safe = new LinkedHashMap<>();
                safe.put("label", label(field)); safe.put("before", old.content().get(field)); safe.put("after", next.content().get(field));
                fields.put(field, safe);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1); result.put("mode", "SANITIZED_CONTENT");
        result.put("changedFieldCount", fields.size()); result.put("changedFields", List.copyOf(fields.keySet())); result.put("fields", fields);
        if (input instanceof Map<?, ?> map) {
            Object occurredAt = map.get("occurredAt");
            if (occurredAt instanceof String text) try { result.put("occurredAt", java.time.Instant.parse(text).toString()); } catch (RuntimeException ignored) {}
            // Metadata is regenerated from allowed field names; never echo arbitrary omitted keys.
            if (Boolean.TRUE.equals(map.get("contentOmitted"))) result.put("contentOmitted", true);
            if (map.get("redactedFields") instanceof List<?> list) result.put("redactedFields", list.stream()
                    .filter(v -> v instanceof String && (FIELDS.contains(v) || Set.of("$unknown", "$skuUnknown").contains(v))).distinct().sorted().toList());
        }
        return result;
    }

    public Map<String, Object> safeContent(Object before, Object after, Object fieldDiff) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("before", snapshot(before, false, true).content());
        result.put("after", snapshot(after, false, true).content());
        result.put("fieldDiff", historicalDiff(fieldDiff));
        return result;
    }

    public boolean hasContent(Map<String, Object> snapshot) {
        return !((Map<?, ?>)snapshot.get("before")).isEmpty() || !((Map<?, ?>)snapshot.get("after")).isEmpty()
                || !((Map<?, ?>)((Map<?, ?>)snapshot.get("fieldDiff")).get("fields")).isEmpty();
    }

    public String safeText(String input) { return input == null || input.length() > 1000 || PRIVATE_TEXT.matcher(input).find() ? null : input; }
    private String label(String field) {
        return switch(field) {
            case "title" -> "商品标题"; case "price" -> "价格"; case "stock" -> "库存";
            case "supportPolicy" -> "支持政策"; case "location" -> "所在地";
            case "autoDeliveryEnabled" -> "自动发货"; case "autoReplyEnabled" -> "自动回复";
            case "autoRateEnabled" -> "自动评价"; case "autoPolishEnabled" -> "自动擦亮";
            case "humanTakeoverEnabled" -> "人工接管"; default -> field;
        };
    }

    public String canonical(Object value) {
        try { return json.writeValueAsString(sorted(value, 0)); }
        catch (BusinessException rejected) { throw rejected; }
        catch (Exception error) { throw new BusinessException(400, "商品安全内容编码无效"); }
    }
    private Object sorted(Object value, int depth) {
        if (depth > 8) throw invalid();
        if (value instanceof Map<?, ?> map) {
            if (map.size() > 100) throw invalid();
            Map<String, Object> result = new TreeMap<>(); map.forEach((k,v) -> result.put(String.valueOf(k), sorted(v, depth+1))); return result;
        }
        if (value instanceof List<?> list) {
            if (list.size() > 100) throw invalid();
            return list.stream().map(v -> sorted(v, depth+1)).toList();
        }
        return value;
    }
    public String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(value).getBytes(StandardCharsets.UTF_8))); }
        catch (BusinessException rejected) { throw rejected; }
        catch (Exception error) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private BusinessException invalid() { return new BusinessException(400, "商品安全内容字段类型或边界无效"); }
}
