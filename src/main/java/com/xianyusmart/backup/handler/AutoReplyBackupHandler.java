package com.xianyusmart.backup.handler;

import com.xianyusmart.backup.DataBackupHandler;
import com.xianyusmart.entity.XianyuGoodsConfig;
import com.xianyusmart.entity.bo.KeywordReplyRuleBO;
import com.xianyusmart.service.KeywordReplyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class AutoReplyBackupHandler implements DataBackupHandler {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KeywordReplyService keywordReplyService;

    @Override
    public String getModuleKey() {
        return "autoReply";
    }

    @Override
    public String getModuleName() {
        return "自动回复";
    }

    @Override
    public Map<String, Object> exportData() {
        List<Map<String, Object>> configs = jdbcTemplate.queryForList(
                "SELECT c.xy_goods_id, c.xianyu_auto_reply_on, c.xianyu_auto_reply_context_on, c.fixed_material, a.unb " +
                "FROM xianyu_goods_config c " +
                "LEFT JOIN xianyu_account a ON c.xianyu_account_id = a.id " +
                "WHERE c.xianyu_auto_reply_on IS NOT NULL");

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> config : configs) {
            if (config.get("unb") == null) continue;
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("unb", config.get("unb"));
            map.put("xyGoodsId", config.get("xy_goods_id"));
            map.put("autoReplyOn", config.get("xianyu_auto_reply_on"));
            map.put("autoReplyContextOn", config.get("xianyu_auto_reply_context_on"));
            map.put("fixedMaterial", config.get("fixed_material"));
            result.add(map);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("autoReplyConfigs", result);
        List<Map<String, Object>> keywordRules = jdbcTemplate.queryForList(
                "SELECT r.id, r.xy_goods_id, r.keyword, r.match_mode, r.is_fallback, r.sharing_scope, a.unb " +
                "FROM xianyu_keyword_reply_rule r JOIN xianyu_account a ON a.id = r.xianyu_account_id");
        List<Map<String, Object>> keywordResult = new ArrayList<>();
        for (Map<String, Object> rule : keywordRules) {
            Long ruleId = ((Number) rule.get("id")).longValue();
            Map<String, Object> exported = new LinkedHashMap<>();
            exported.put("unb", rule.get("unb"));
            exported.put("xyGoodsId", rule.get("xy_goods_id"));
            exported.put("keyword", rule.get("keyword"));
            exported.put("matchMode", rule.get("match_mode"));
            exported.put("isFallback", rule.get("is_fallback"));
            exported.put("sharingScope", rule.get("sharing_scope"));
            exported.put("accountUnbs", jdbcTemplate.queryForList(
                    "SELECT a.unb FROM xianyu_keyword_reply_rule_account link " +
                            "JOIN xianyu_account a ON a.id = link.xianyu_account_id WHERE link.rule_id = ?",
                    String.class, ruleId));
            exported.put("contents", jdbcTemplate.queryForList(
                    "SELECT reply_text AS replyText, reply_image_url AS replyImageUrl " +
                            "FROM xianyu_keyword_reply_content WHERE rule_id = ? ORDER BY id", ruleId));
            keywordResult.add(exported);
        }
        data.put("keywordReplyRules", keywordResult);
        return data;
    }

    @Override
    public void importData(Map<String, Object> data, Map<String, Object> context) {
        if (data == null) return;

        @SuppressWarnings("unchecked")
        Map<String, Long> unbToAccountId = context.get("unbToAccountId") != null
                ? (Map<String, Long>) context.get("unbToAccountId")
                : Collections.emptyMap();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> configMaps = (List<Map<String, Object>>) data.get("autoReplyConfigs");
        if (configMaps == null) configMaps = Collections.emptyList();

        int skippedCount = 0;
        for (Map<String, Object> map : configMaps) {
            try {
                String unb = (String) map.get("unb");
                String xyGoodsId = (String) map.get("xyGoodsId");
                if (unb == null || xyGoodsId == null) continue;

                Long accountId = unbToAccountId.get(unb);
                if (accountId == null) {
                    log.warn("[AutoReplyBackup] 跳过: 找不到账号, unb={}, xyGoodsId={}", unb, xyGoodsId);
                    skippedCount++;
                    continue;
                }

                Integer autoReplyOn = map.get("autoReplyOn") != null ? ((Number) map.get("autoReplyOn")).intValue() : null;
                Integer autoReplyContextOn = map.get("autoReplyContextOn") != null ? ((Number) map.get("autoReplyContextOn")).intValue() : null;
                String fixedMaterial = (String) map.get("fixedMaterial");

                List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                        "SELECT * FROM xianyu_goods_config WHERE xianyu_account_id = ? AND xy_goods_id = ?",
                        accountId, xyGoodsId);

                if (existing.isEmpty()) {
                    jdbcTemplate.update(
                            "INSERT INTO xianyu_goods_config (xianyu_account_id, xy_goods_id, xianyu_auto_reply_on, xianyu_auto_rate_content, xianyu_auto_reply_context_on, fixed_material) VALUES (?, ?, ?, ?, ?, ?)",
                            accountId, xyGoodsId, autoReplyOn, XianyuGoodsConfig.DEFAULT_AUTO_RATE_CONTENT,
                            autoReplyContextOn, fixedMaterial);
                } else {
                    jdbcTemplate.update(
                            "UPDATE xianyu_goods_config SET xianyu_auto_reply_on = ?, xianyu_auto_reply_context_on = ?, fixed_material = ? WHERE xianyu_account_id = ? AND xy_goods_id = ?",
                            autoReplyOn, autoReplyContextOn, fixedMaterial, accountId, xyGoodsId);
                }
            } catch (Exception e) {
                log.warn("[AutoReplyBackup] 导入单条自动回复配置失败: {}", e.getMessage());
            }
        }
        if (skippedCount > 0) {
            log.warn("[AutoReplyBackup] 共跳过 {} 条数据（账号不存在）", skippedCount);
        }
        importKeywordRules(data, unbToAccountId);
    }

    private void importKeywordRules(Map<String, Object> data, Map<String, Long> unbToAccountId) {
        if (!(data.get("keywordReplyRules") instanceof List<?> rules)) return;
        for (Object value : rules) {
            if (!(value instanceof Map<?, ?> raw)) continue;
            Map<String, Object> map = new HashMap<>();
            raw.forEach((key, item) -> map.put(String.valueOf(key), item));
            try {
                Long ownerId = unbToAccountId.get(String.valueOf(map.get("unb")));
                String goodsId = String.valueOf(map.get("xyGoodsId"));
                if (ownerId == null || goodsId.isBlank()) continue;
                boolean fallback = number(map.get("isFallback"), 0) == 1;
                KeywordReplyRuleBO rule = fallback
                        ? keywordReplyService.ensureFallbackRule(ownerId, goodsId)
                        : keywordReplyService.addRule(ownerId, goodsId, String.valueOf(map.get("keyword")));
                keywordReplyService.updateMatchMode(rule.getId(), number(map.get("matchMode"), 1));
                List<Long> accountIds = map.get("accountUnbs") instanceof List<?> unbs
                        ? unbs.stream().map(String::valueOf).map(unbToAccountId::get)
                                .filter(Objects::nonNull).distinct().toList()
                        : List.of(ownerId);
                keywordReplyService.updateAccounts(rule.getId(), accountIds.isEmpty() ? List.of(ownerId) : accountIds);
                if (map.get("contents") instanceof List<?> contents && (rule.getContents() == null || rule.getContents().isEmpty())) {
                    for (Object contentValue : contents) {
                        if (!(contentValue instanceof Map<?, ?> content)) continue;
                        keywordReplyService.addContent(rule.getId(), text(content.get("replyText")), text(content.get("replyImageUrl")));
                    }
                }
            } catch (Exception e) {
                log.warn("[AutoReplyBackup] 导入关键词共享模板失败: {}", e.getMessage());
            }
        }
    }

    private int number(Object value, int defaultValue) {
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
