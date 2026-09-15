package com.xianyusmart.service;

import com.xianyusmart.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 商品发布前本地内容和交付配置预检。 */
@Service
public class ProductContentPolicyService {

    private static final List<String> DEFAULT_TERMS = List.of(
            "微信收款", "支付宝收款", "刷单", "返现诱导", "站外交易");
    private final SysSettingService settingService;

    public ProductContentPolicyService(SysSettingService settingService) {
        this.settingService = settingService;
    }

    /** Read-only inspection used by the publishing editor before a platform preflight is created. */
    public Map<String, Object> inspect(Map<String, Object> request) {
        Map<String, Object> safeRequest = request == null ? Map.of() : request;
        List<String> terms = configuredTerms();
        List<FieldText> fields = buyerFacingFields(safeRequest);
        List<Map<String, Object>> findings = new ArrayList<>();
        LinkedHashSet<String> matchedTerms = new LinkedHashSet<>();
        LinkedHashSet<String> matchedFields = new LinkedHashSet<>();
        for (String term : terms) {
            if (term.isBlank()) continue;
            String normalizedTerm = term.toLowerCase(Locale.ROOT);
            for (FieldText field : fields) {
                String value = field.value();
                int start = value.toLowerCase(Locale.ROOT).indexOf(normalizedTerm);
                if (start < 0) continue;
                int end = Math.min(value.length(), start + term.length());
                Map<String, Object> finding = new LinkedHashMap<>();
                finding.put("code", "PROHIBITED_TERM");
                finding.put("severity", "BLOCKER");
                finding.put("field", field.path());
                finding.put("fieldLabel", field.label());
                finding.put("term", term);
                finding.put("start", start);
                finding.put("end", end);
                finding.put("snippet", snippet(value, start, end));
                finding.put("message", field.label() + "命中风险词“" + term + "”");
                finding.put("suggestion", "删除站外交易、诱导或不实承诺表述，并用可在闲鱼内履约的事实重新描述。修改后重新校验。");
                findings.add(finding);
                matchedTerms.add(term);
                matchedFields.add(field.path());
            }
        }
        boolean valid = findings.isEmpty();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", valid);
        result.put("policyVersion", policyVersion(terms));
        result.put("source", "SYSTEM_SETTING");
        result.put("checkedAt", Instant.now());
        result.put("checkedFieldCount", fields.size());
        result.put("matchedFieldCount", matchedFields.size());
        result.put("blockerCount", findings.size());
        result.put("warningCount", 0);
        result.put("highestSeverity", valid ? "CLEAR" : "BLOCKER");
        result.put("matchedTerms", List.copyOf(matchedTerms));
        result.put("findings", findings);
        result.put("summary", valid ? "本地内容策略未发现已配置风险词" : "发布内容命中 " + findings.size() + " 项阻断风险");
        result.put("nextAction", valid ? "继续执行平台类目、图片和通道能力预检。" : "按字段修改命中内容后重新校验；当前结果不会生成发布预检凭证。");
        return result;
    }

    public Map<String, Object> validate(Map<String, Object> request) {
        Map<String, Object> safeRequest = request == null ? Map.of() : request;
        Map<String, Object> result = inspect(safeRequest);
        if (!Boolean.TRUE.equals(result.get("valid"))) throw new BusinessException(400, String.valueOf(result.get("summary")));
        String deliveryMethod = text(safeRequest.get("deliveryMethod"));
        if (!deliveryMethod.isBlank() && !List.of("线上交付", "快递发货", "当面交易").contains(deliveryMethod)) {
            throw new IllegalArgumentException("交付方式无效");
        }
        String productType = text(safeRequest.get("productType"));
        if (!productType.isBlank() && !List.of("VIRTUAL", "PHYSICAL").contains(productType)) {
            throw new IllegalArgumentException("商品类型无效");
        }
        if ("PHYSICAL".equals(productType) && "线上交付".equals(deliveryMethod)) {
            throw new IllegalArgumentException("实物商品不能选择线上交付");
        }
        if ("VIRTUAL".equals(productType) && "快递发货".equals(deliveryMethod)) {
            throw new IllegalArgumentException("虚拟商品不能选择快递发货");
        }
        if ("快递发货".equals(deliveryMethod) && Boolean.FALSE.equals(safeRequest.get("freeShipping"))
                && text(safeRequest.get("freightTemplateId")).isBlank()) {
            throw new IllegalArgumentException("不包邮的快递商品必须填写平台运费模板 ID");
        }
        Object videos = safeRequest.get("videos");
        if (videos instanceof List<?> list && !list.isEmpty()) {
            throw new IllegalArgumentException("当前账号尚未验证视频发布能力，请先在能力矩阵确认后再使用");
        }
        Object skus = safeRequest.get("skus");
        if (skus instanceof List<?> list && list.size() > 1) {
            throw new IllegalArgumentException("当前账号尚未验证多规格发布能力，已阻止静默丢失 SKU");
        }
        result.put("deliveryMethod", deliveryMethod.isBlank() ? "线上交付" : deliveryMethod);
        result.put("checks", List.of("买家可见文本", "风险词", "媒体能力", "SKU能力", "交付方式"));
        return result;
    }

    private List<FieldText> buyerFacingFields(Map<String, Object> request) {
        List<FieldText> fields = new ArrayList<>();
        addField(fields, "name", "商品标题", request.get("name"));
        addField(fields, "description", "商品详情", request.get("description"));
        addField(fields, "supportPolicy", "支持政策", request.get("supportPolicy"));
        addField(fields, "afterSalesPolicy", "售后政策", request.get("afterSalesPolicy"));
        addField(fields, "serviceArea", "服务范围", request.get("serviceArea"));
        if (request.get("categoryAttributes") instanceof Map<?, ?> attributes) {
            attributes.forEach((key, value) -> addField(fields, "categoryAttributes." + key,
                    "类目属性“" + key + "”", value));
        }
        if (request.get("skus") instanceof List<?> skus) {
            for (int index = 0; index < skus.size(); index++) {
                Object sku = skus.get(index);
                if (!(sku instanceof Map<?, ?> item)) continue;
                Object values = item.get("values");
                if (values instanceof Map<?, ?> dimensions) {
                    int skuIndex = index;
                    dimensions.forEach((key, value) -> addField(fields,
                            "skus[" + skuIndex + "].values." + key, "SKU 规格“" + key + "”", value));
                }
            }
        }
        return fields;
    }

    private void addField(List<FieldText> fields, String path, String label, Object value) {
        String content = text(value);
        if (!content.isBlank()) fields.add(new FieldText(path, label, content));
    }

    private String snippet(String value, int start, int end) {
        int from = Math.max(0, start - 18);
        int to = Math.min(value.length(), end + 18);
        return (from > 0 ? "…" : "") + value.substring(from, to) + (to < value.length() ? "…" : "");
    }

    private String policyVersion(List<String> terms) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", terms).getBytes(StandardCharsets.UTF_8));
            return "terms-" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private List<String> configuredTerms() {
        String value = settingService.getSettingValue("product_prohibited_terms");
        if (value == null || value.isBlank()) return DEFAULT_TERMS;
        return Arrays.stream(value.split("[,，\\n]"))
                .map(String::trim).filter(term -> !term.isBlank()).distinct().toList();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private record FieldText(String path, String label, String value) {}
}
