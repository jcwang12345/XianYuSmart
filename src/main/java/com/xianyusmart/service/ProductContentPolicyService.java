package com.xianyusmart.service;

import org.springframework.stereotype.Service;

import java.util.Arrays;
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

    public Map<String, Object> validate(Map<String, Object> request) {
        String title = text(request.get("name"));
        String description = text(request.get("description"));
        String content = (title + "\n" + description).toLowerCase(Locale.ROOT);
        LinkedHashSet<String> matched = new LinkedHashSet<>();
        for (String term : configuredTerms()) {
            if (!term.isBlank() && content.contains(term.toLowerCase(Locale.ROOT))) matched.add(term);
        }
        if (!matched.isEmpty()) {
            throw new IllegalArgumentException("发布内容命中风险词：" + String.join("、", matched));
        }
        String deliveryMethod = text(request.get("deliveryMethod"));
        if (!deliveryMethod.isBlank() && !List.of("线上交付", "快递发货", "当面交易").contains(deliveryMethod)) {
            throw new IllegalArgumentException("交付方式无效");
        }
        String productType = text(request.get("productType"));
        if (!productType.isBlank() && !List.of("VIRTUAL", "PHYSICAL").contains(productType)) {
            throw new IllegalArgumentException("商品类型无效");
        }
        if ("PHYSICAL".equals(productType) && "线上交付".equals(deliveryMethod)) {
            throw new IllegalArgumentException("实物商品不能选择线上交付");
        }
        if ("VIRTUAL".equals(productType) && "快递发货".equals(deliveryMethod)) {
            throw new IllegalArgumentException("虚拟商品不能选择快递发货");
        }
        if ("快递发货".equals(deliveryMethod) && Boolean.FALSE.equals(request.get("freeShipping"))
                && text(request.get("freightTemplateId")).isBlank()) {
            throw new IllegalArgumentException("不包邮的快递商品必须填写平台运费模板 ID");
        }
        Object videos = request.get("videos");
        if (videos instanceof List<?> list && !list.isEmpty()) {
            throw new IllegalArgumentException("当前账号尚未验证视频发布能力，请先在能力矩阵确认后再使用");
        }
        Object skus = request.get("skus");
        if (skus instanceof List<?> list && list.size() > 1) {
            throw new IllegalArgumentException("当前账号尚未验证多规格发布能力，已阻止静默丢失 SKU");
        }
        return Map.of(
                "valid", true,
                "matchedTerms", List.of(),
                "deliveryMethod", deliveryMethod.isBlank() ? "线上交付" : deliveryMethod,
                "checks", List.of("标题与详情", "风险词", "媒体能力", "SKU能力", "交付方式")
        );
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
}
