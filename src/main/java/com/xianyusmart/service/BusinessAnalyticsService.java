package com.xianyusmart.service;

import com.xianyusmart.context.AccountScopeContext;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.entity.XianyuOperationLog;
import com.xianyusmart.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 经营分析读模型。所有值都携带来源与覆盖度；没有同步记录时返回 null，而不是伪造为 0。
 */
@Service
public class BusinessAnalyticsService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;
    private final AccountAccessService accountAccessService;
    private final AccountGroupService accountGroupService;
    private final AccountMatrixService accountMatrixService;
    private final OperationLogService operationLogService;

    public BusinessAnalyticsService(JdbcTemplate jdbcTemplate,
                                    NamedParameterJdbcTemplate namedJdbcTemplate,
                                    AccountAccessService accountAccessService,
                                    AccountGroupService accountGroupService,
                                    AccountMatrixService accountMatrixService,
                                    OperationLogService operationLogService) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = namedJdbcTemplate;
        this.accountAccessService = accountAccessService;
        this.accountGroupService = accountGroupService;
        this.accountMatrixService = accountMatrixService;
        this.operationLogService = operationLogService;
    }

    /**
     * 经营罗盘自己的只读范围入口。仪表板用户不应为了选择店铺而被迫获得账号管理权限。
     */
    public Map<String, Object> scopeOptions() {
        List<Long> accountIds = scopedAccounts(null, null);
        Map<String, Object> result = new LinkedHashMap<>();
        if (accountIds.isEmpty()) {
            result.put("accounts", List.of());
            result.put("groups", List.of());
        } else {
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("tenant", tenant())
                    .addValue("accounts", accountIds);
            result.put("accounts", namedJdbcTemplate.queryForList("""
                    SELECT id,account_note accountNote
                      FROM xianyu_account
                     WHERE tenant_id=:tenant AND id IN (:accounts)
                     ORDER BY id
                    """, params));
            result.put("groups", namedJdbcTemplate.queryForList("""
                    SELECT groups.id,MAX(groups.group_name) groupName,MAX(groups.color) color,
                           MAX(groups.description) description,MAX(groups.sort_order) sortOrder,
                           COUNT(DISTINCT member.id) accountCount
                      FROM xianyu_account_group groups
                      JOIN xianyu_account_group_member member
                        ON member.tenant_id=groups.tenant_id AND member.group_id=groups.id
                     WHERE groups.tenant_id=:tenant AND member.xianyu_account_id IN (:accounts)
                     GROUP BY groups.id
                    HAVING COUNT(DISTINCT member.id)=(
                           SELECT COUNT(*) FROM xianyu_account_group_member all_member
                            WHERE all_member.tenant_id=:tenant AND all_member.group_id=groups.id)
                     ORDER BY MAX(groups.sort_order),groups.id
                    """, params));
        }
        result.put("accountSummary", accountMatrixService.summary());
        return result;
    }

    public Map<String, Object> overview(LocalDate start, LocalDate end, Long accountId, Long groupId) {
        DateRange range = range(start, end);
        List<Long> accounts = scopedAccounts(accountId, groupId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("range", Map.of("start", range.start(), "end", range.end(), "days", range.days()));
        result.put("scope", Map.of("accountId", accountId == null ? "" : accountId,
                "groupId", groupId == null ? "" : groupId, "accountCount", accounts.size()));
        result.put("summary", aggregate(range.start(), range.end(), accounts));
        DateRange previous = range.previous();
        result.put("previous", aggregate(previous.start(), previous.end(), accounts));
        result.put("trend", trend(range.start(), range.end(), accounts));
        result.put("shopRank", shopRank(range.start(), range.end(), accounts));
        result.put("productRank", productRank(range.start(), range.end(), accounts));
        result.put("anomalies", anomalies(range.start(), range.end(), accounts));
        result.put("productAnomalies", productAnomalies(range.start(),range.end(),accounts));
        result.put("funnel", funnel((Map<String,Object>) result.get("summary")));
        result.put("definitions", Map.of(
                "gmv", "本期已同步支付订单金额之和；退款回溯单独展示，不静默冲减",
                "paidBuyerCount", "按店铺日去重的支付买家，跨店/跨日可能重复，当前口径会明确标为部分覆盖",
                "exposureCount", "仅使用平台同步曝光，不由本地消息或订单反推",
                "activeProductCount", "所选范围内，按自然日汇总各店动销商品后取单日峰值；不冒充跨日去重商品数",
                "coverage", "FULL=所选店铺和日期均完整；PARTIAL=仅部分来源/店铺/日期；UNSYNCED=无可用样本"));
        if (accountId != null) {
            result.put("fanMetrics", Map.of("newFollowers", "UNSYNCED", "lostFollowers", "UNSYNCED",
                    "netFollowers", "UNSYNCED", "followerGmv", "UNSYNCED",
                    "notice", "当前接入通道尚未同步粉丝日明细，不以0代替"));
        }
        result.put("generatedAt", Instant.now());
        return result;
    }

    @Transactional
    public Map<String, Object> refreshLocal(LocalDate start, LocalDate end, String requestId) {
        DateRange range = range(start, end);
        String request = required(requestId, "requestId", 80);
        List<Long> accounts = scopedAccounts(null, null);
        int rows = 0;
        for (Long accountId : accounts) {
            for (LocalDate day = range.start(); !day.isAfter(range.end()); day = day.plusDays(1)) {
                rows += refreshAccountDay(accountId, day);
            }
        }
        XianyuOperationLog log = new XianyuOperationLog();
        log.setOperationType("ANALYTICS_LOCAL_REFRESH");
        log.setOperationModule("经营分析");
        log.setOperationDesc("刷新本地经营日指标：" + range.start() + " 至 " + range.end());
        log.setOperationStatus(1);
        log.setTargetType("SHOP_METRIC_DAILY");
        log.setTargetId(range.start() + ":" + range.end());
        log.setRequestId(request);
        log.setIdempotencyKey(request);
        log.setOutcomeState("LOCAL_SUCCESS");
        log.setDataSource("LOCAL_EVENT");
        operationLogService.log(log);
        return Map.of("accountCount", accounts.size(), "upsertedRows", rows,
                "coverageStatus", "PARTIAL", "source", "LOCAL_EVENT");
    }

    /** 后台只重算最近两天的本地事实，不调用平台，不执行任何外部操作。 */
    @Scheduled(cron = "0 15 * * * *")
    public void refreshRecentLocalFacts() {
        List<Long> tenants = jdbcTemplate.queryForList("SELECT DISTINCT tenant_id FROM xianyu_account", Long.class);
        for (Long tenant : tenants) {
            try {
                TenantContext.set(tenant);
                LocalDate today = LocalDate.now(BUSINESS_ZONE);
                List<Long> accounts = jdbcTemplate.queryForList(
                        "SELECT id FROM xianyu_account WHERE tenant_id=? ORDER BY id", Long.class, tenant);
                for (Long accountId : accounts) {
                    refreshAccountDay(accountId, today.minusDays(1));
                    refreshAccountDay(accountId, today);
                }
            } finally {
                TenantContext.clear();
            }
        }
    }

    private int refreshAccountDay(Long accountId, LocalDate day) {
        java.sql.Timestamp from = java.sql.Timestamp.valueOf(day.atStartOfDay());
        java.sql.Timestamp to = java.sql.Timestamp.valueOf(day.plusDays(1).atStartOfDay());
        Map<String, Object> order = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) sampleSize,
                       SUM(CASE WHEN pay_success_time IS NOT NULL OR order_status_code IN ('PAID','TO_SHIP','SHIPPED','COMPLETED') THEN 1 ELSE 0 END) paidOrderCount,
                       COUNT(DISTINCT CASE WHEN pay_success_time IS NOT NULL OR order_status_code IN ('PAID','TO_SHIP','SHIPPED','COMPLETED') THEN buyer_user_id END) paidBuyerCount,
                       SUM(CASE WHEN pay_success_time IS NOT NULL OR order_status_code IN ('PAID','TO_SHIP','SHIPPED','COMPLETED') THEN order_amount END) gmv,
                       SUM(refund_amount) refundAmount,
                       SUM(CASE WHEN refund_status NOT IN ('NONE','UNKNOWN') THEN 1 ELSE 0 END) refundOrderCount
                  FROM xianyu_goods_order
                 WHERE tenant_id=? AND xianyu_account_id=? AND create_time>=? AND create_time<?
                """, tenant(), accountId, from, to);
        Map<String, Object> message = jdbcTemplate.queryForMap("""
                SELECT COUNT(DISTINCT CASE WHEN msg.sender_user_id<>account.unb OR account.unb IS NULL THEN msg.s_id END) inquiryCount,
                       COUNT(DISTINCT CASE WHEN msg.sender_user_id=account.unb THEN msg.s_id END) repliedInquiryCount,
                       COUNT(*) messageSample
                  FROM xianyu_chat_message msg
                  JOIN xianyu_account account ON account.id=msg.xianyu_account_id AND account.tenant_id=msg.tenant_id
                 WHERE msg.tenant_id=? AND msg.xianyu_account_id=? AND msg.create_time>=? AND msg.create_time<?
                """, tenant(), accountId, from, to);
        Long activeProducts = number(order.get("sampleSize")) == 0 ? null : jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT CASE WHEN pay_success_time IS NOT NULL
                       OR order_status_code IN ('PAID','TO_SHIP','SHIPPED','COMPLETED') THEN xy_goods_id END)
                  FROM xianyu_goods_order WHERE tenant_id=? AND xianyu_account_id=? AND create_time>=? AND create_time<?
                """, Long.class, tenant(), accountId, from, to);
        long sample = number(order.get("sampleSize")) + number(message.get("messageSample"));
        if (sample == 0 && activeProducts == null) return 0;
        return jdbcTemplate.update("""
                INSERT INTO xianyu_shop_metric_daily
                (tenant_id,xianyu_account_id,metric_date,gmv,paid_order_count,paid_buyer_count,
                 refund_amount,refund_order_count,inquiry_count,replied_inquiry_count,active_product_count,
                 source,sync_status,coverage_status,sample_size,synced_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,'LOCAL_EVENT','SUCCEEDED','PARTIAL',?,NOW(3))
                ON DUPLICATE KEY UPDATE gmv=VALUES(gmv),paid_order_count=VALUES(paid_order_count),
                 paid_buyer_count=VALUES(paid_buyer_count),refund_amount=VALUES(refund_amount),
                 refund_order_count=VALUES(refund_order_count),inquiry_count=VALUES(inquiry_count),
                 replied_inquiry_count=VALUES(replied_inquiry_count),
                 active_product_count=COALESCE(VALUES(active_product_count),active_product_count),
                 source='LOCAL_EVENT',sync_status='SUCCEEDED',coverage_status='PARTIAL',
                 sample_size=VALUES(sample_size),synced_at=NOW(3)
                """, tenant(), accountId, Date.valueOf(day), order.get("gmv"), nullableLong(order.get("paidOrderCount")),
                nullableLong(order.get("paidBuyerCount")), order.get("refundAmount"), nullableLong(order.get("refundOrderCount")),
                nullableLong(message.get("inquiryCount")), nullableLong(message.get("repliedInquiryCount")), activeProducts, sample);
    }

    private Map<String, Object> aggregate(LocalDate start, LocalDate end, List<Long> accounts) {
        if (accounts.isEmpty()) return emptyAggregate();
        String sql = """
                SELECT SUM(gmv) gmv,SUM(paid_order_count) paidOrderCount,SUM(paid_buyer_count) paidBuyerCount,
                       SUM(refund_amount) refundAmount,SUM(refund_order_count) refundOrderCount,
                       SUM(exposure_count) exposureCount,SUM(visitor_count) visitorCount,
                       SUM(inquiry_count) inquiryCount,SUM(replied_inquiry_count) repliedInquiryCount,
                       COUNT(*) sampleDays,
                       COUNT(DISTINCT xianyu_account_id) coveredAccountCount,MAX(synced_at) syncedAt,
                       COUNT(DISTINCT source) sourceCount,MIN(source) singleSource,
                       SUM(sample_size) sampleSize,
                       SUM(CASE WHEN coverage_status='FULL' THEN 1 ELSE 0 END) fullRows
                  FROM xianyu_shop_metric_daily
                 WHERE tenant_id=:tenant AND xianyu_account_id IN (:accounts)
                   AND metric_date BETWEEN :start AND :end
                """;
        Map<String, Object> row = namedJdbcTemplate.queryForMap(sql, params(start, end, accounts));
        long rows = number(row.get("sampleDays"));
        Map<String, Object> result = new LinkedHashMap<>();
        for (String metric : List.of("gmv","paidOrderCount","paidBuyerCount","refundAmount","refundOrderCount",
                "exposureCount","visitorCount","inquiryCount","repliedInquiryCount")) {
            result.put(metric, rows == 0 ? null : row.get(metric));
        }
        result.put("activeProductCount", rows == 0 ? null : activeProductPeak(start, end, accounts));
        result.put("source", rows == 0 ? "NONE" : number(row.get("sourceCount")) == 1 ? row.get("singleSource") : "MIXED");
        result.put("syncStatus", rows == 0 ? "UNSYNCED" : "SUCCEEDED");
        result.put("coverageStatus", rows == 0 ? "UNSYNCED" :
                (number(row.get("coveredAccountCount")) == accounts.size() && number(row.get("fullRows")) == rows ? "FULL" : "PARTIAL"));
        result.put("coveredAccountCount", number(row.get("coveredAccountCount")));
        result.put("requestedAccountCount", accounts.size());
        result.put("sampleDays", rows);
        result.put("sampleSize", number(row.get("sampleSize")));
        result.put("syncedAt", row.get("syncedAt"));
        addDerivedMetrics(result);
        return result;
    }

    private Long activeProductPeak(LocalDate start, LocalDate end, List<Long> accounts) {
        Number value = namedJdbcTemplate.queryForObject("""
                SELECT MAX(day_active) FROM (
                    SELECT metric_date,SUM(active_product_count) day_active
                      FROM xianyu_shop_metric_daily
                     WHERE tenant_id=:tenant AND xianyu_account_id IN (:accounts)
                       AND metric_date BETWEEN :start AND :end
                       AND active_product_count IS NOT NULL
                     GROUP BY metric_date
                ) daily_active
                """, params(start, end, accounts), Number.class);
        return value == null ? null : value.longValue();
    }

    private List<Map<String, Object>> trend(LocalDate start, LocalDate end, List<Long> accounts) {
        Map<LocalDate, Map<String, Object>> known = new LinkedHashMap<>();
        if (!accounts.isEmpty()) {
            namedJdbcTemplate.query("""
                    SELECT metric_date,SUM(gmv) gmv,SUM(paid_order_count) paidOrderCount,
                           SUM(inquiry_count) inquiryCount,SUM(exposure_count) exposureCount,
                           COUNT(DISTINCT xianyu_account_id) coveredAccountCount,MAX(synced_at) syncedAt
                      FROM xianyu_shop_metric_daily
                     WHERE tenant_id=:tenant AND xianyu_account_id IN (:accounts)
                       AND metric_date BETWEEN :start AND :end GROUP BY metric_date ORDER BY metric_date
                    """, params(start, end, accounts), rs -> {
                Map<String, Object> item = new LinkedHashMap<>();
                LocalDate day = rs.getDate("metric_date").toLocalDate();
                item.put("date", day); item.put("gmv", rs.getBigDecimal("gmv"));
                item.put("paidOrderCount", nullableLong(rs, "paidOrderCount"));
                item.put("inquiryCount", nullableLong(rs, "inquiryCount"));
                item.put("exposureCount", nullableLong(rs, "exposureCount"));
                item.put("coveredAccountCount", rs.getLong("coveredAccountCount"));
                item.put("coverageStatus", rs.getLong("coveredAccountCount") == accounts.size() ? "PARTIAL" : "PARTIAL");
                item.put("syncedAt", rs.getTimestamp("syncedAt"));
                known.put(day, item);
            });
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            Map<String, Object> item = known.get(day);
            if (item == null) {
                item = new LinkedHashMap<>();
                item.put("date", day); item.put("gmv", null); item.put("paidOrderCount", null);
                item.put("inquiryCount", null); item.put("exposureCount", null);
                item.put("coveredAccountCount", 0); item.put("coverageStatus", "UNSYNCED"); item.put("syncedAt", null);
            }
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> shopRank(LocalDate start, LocalDate end, List<Long> accounts) {
        if (accounts.isEmpty()) return List.of();
        return namedJdbcTemplate.queryForList("""
                SELECT metric.xianyu_account_id accountId,account.account_note accountNote,
                       SUM(metric.gmv) gmv,SUM(metric.paid_order_count) paidOrderCount,
                       SUM(metric.paid_buyer_count) paidBuyerCount,
                       SUM(metric.refund_amount) refundAmount,SUM(metric.refund_order_count) refundOrderCount,
                       SUM(metric.exposure_count) exposureCount,SUM(metric.visitor_count) visitorCount,
                       SUM(metric.inquiry_count) inquiryCount,SUM(metric.replied_inquiry_count) repliedInquiryCount,
                       MAX(metric.active_product_count) activeProductCount,COUNT(*) sampleDays,
                       CASE WHEN SUM(metric.paid_order_count)>0
                            THEN SUM(metric.refund_order_count)/SUM(metric.paid_order_count) END refundRate,
                       CASE WHEN SUM(metric.inquiry_count)>0
                            THEN SUM(metric.replied_inquiry_count)/SUM(metric.inquiry_count) END replyRate,
                       CASE WHEN COUNT(DISTINCT metric.source)=1 THEN MIN(metric.source) ELSE 'MIXED' END source,
                       MAX(metric.synced_at) syncedAt,
                       CASE WHEN MIN(metric.coverage_status)='FULL' AND MAX(metric.coverage_status)='FULL'
                            THEN 'FULL' ELSE 'PARTIAL' END coverageStatus
                  FROM xianyu_shop_metric_daily metric
                  JOIN xianyu_account account ON account.id=metric.xianyu_account_id
                       AND account.tenant_id=metric.tenant_id
                 WHERE metric.tenant_id=:tenant AND metric.xianyu_account_id IN (:accounts)
                   AND metric.metric_date BETWEEN :start AND :end
                 GROUP BY metric.xianyu_account_id,account.account_note
                 ORDER BY SUM(metric.gmv) IS NULL,SUM(metric.gmv) DESC LIMIT 100
                """, params(start, end, accounts));
    }

    private List<Map<String,Object>> productRank(LocalDate start,LocalDate end,List<Long> accounts) {
        if(accounts.isEmpty()) return List.of();
        return namedJdbcTemplate.queryForList("""
                SELECT metric.xianyu_account_id accountId,account.account_note accountNote,
                       metric.xy_goods_id goodsId,MAX(goods.title) title,MAX(goods.cover_pic) coverPic,
                       SUM(metric.exposure_count) exposureCount,SUM(metric.visitor_count) visitorCount,
                       SUM(metric.click_count) clickCount,SUM(metric.favorite_count) favoriteCount,
                       SUM(metric.inquiry_count) inquiryCount,SUM(metric.paid_order_count) paidOrderCount,
                       SUM(metric.paid_amount) paidAmount,
                       CASE WHEN SUM(metric.exposure_count)>0
                            THEN SUM(metric.click_count)/SUM(metric.exposure_count) END clickRate,
                       CASE WHEN SUM(metric.visitor_count)>0
                            THEN SUM(metric.paid_order_count)/SUM(metric.visitor_count) END paymentRate,
                       COUNT(DISTINCT metric.metric_date) sampleDays,
                       CASE WHEN COUNT(DISTINCT metric.source)=1 THEN MIN(metric.source) ELSE 'MIXED' END source,
                       MAX(metric.synced_at) syncedAt,
                       CASE WHEN MIN(metric.coverage_status)='FULL' AND MAX(metric.coverage_status)='FULL' THEN 'FULL' ELSE 'PARTIAL' END coverageStatus
                  FROM xianyu_goods_metric_daily metric
                  JOIN xianyu_account account ON account.id=metric.xianyu_account_id
                       AND account.tenant_id=metric.tenant_id
                  LEFT JOIN xianyu_goods goods ON goods.tenant_id=metric.tenant_id
                       AND goods.xianyu_account_id=metric.xianyu_account_id AND goods.xy_good_id=metric.xy_goods_id
                 WHERE metric.tenant_id=:tenant AND metric.xianyu_account_id IN (:accounts)
                   AND metric.metric_date BETWEEN :start AND :end
                 GROUP BY metric.xianyu_account_id,account.account_note,metric.xy_goods_id
                 ORDER BY SUM(metric.paid_amount) IS NULL,SUM(metric.paid_amount) DESC LIMIT 100
                """,params(start,end,accounts));
    }

    private Map<String,Object> funnel(Map<String,Object> summary) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("exposure",summary.get("exposureCount")); result.put("visit",summary.get("visitorCount"));
        result.put("inquiry",summary.get("inquiryCount")); result.put("payment",summary.get("paidOrderCount"));
        result.put("deal",summary.get("paidOrderCount")); result.put("coverageStatus",summary.get("coverageStatus"));
        result.put("notice","任一上游指标未同步时保留空值，不补0、不计算虚假转化率"); return result;
    }

    private void addDerivedMetrics(Map<String,Object> result) {
        result.put("averageOrderValue", ratio(result.get("gmv"),result.get("paidOrderCount")));
        result.put("refundRate", ratio(result.get("refundOrderCount"),result.get("paidOrderCount")));
        result.put("replyRate", ratio(result.get("repliedInquiryCount"),result.get("inquiryCount")));
        result.put("visitRate", ratio(result.get("visitorCount"),result.get("exposureCount")));
        result.put("inquiryRate", ratio(result.get("inquiryCount"),result.get("visitorCount")));
        result.put("paymentRate", ratio(result.get("paidOrderCount"),result.get("visitorCount")));
    }

    private BigDecimal ratio(Object numerator,Object denominator) {
        if(!(numerator instanceof Number) || !(denominator instanceof Number)) return null;
        BigDecimal d=new BigDecimal(denominator.toString()); if(d.compareTo(BigDecimal.ZERO)==0)return null;
        return new BigDecimal(numerator.toString()).divide(d,6,java.math.RoundingMode.HALF_UP);
    }

    private List<Map<String, Object>> anomalies(LocalDate start, LocalDate end, List<Long> accounts) {
        if (accounts.isEmpty()) return List.of();
        List<Map<String, Object>> rows = namedJdbcTemplate.queryForList("""
                SELECT xianyu_account_id accountId,metric_date metricDate,gmv,paid_order_count paidOrderCount,
                       inquiry_count inquiryCount,refund_order_count refundOrderCount,source,coverage_status coverageStatus,synced_at syncedAt
                  FROM xianyu_shop_metric_daily
                 WHERE tenant_id=:tenant AND xianyu_account_id IN (:accounts) AND metric_date BETWEEN :start AND :end
                   AND ((refund_order_count IS NOT NULL AND paid_order_count IS NOT NULL AND paid_order_count>0
                         AND refund_order_count/paid_order_count>=0.3)
                     OR sync_status IN ('FAILED','PARTIAL'))
                 ORDER BY metric_date DESC,xianyu_account_id LIMIT 100
                """, params(start, end, accounts));
        for (Map<String, Object> row : rows) {
            long paid = number(row.get("paidOrderCount"));
            long refunds = number(row.get("refundOrderCount"));
            boolean refundRisk = paid > 0 && refunds * 10 >= paid * 3;
            row.put("anomalyType", refundRisk ? "HIGH_REFUND_RATE" : "DATA_SYNC_DEGRADED");
            row.put("severity", refundRisk ? "HIGH" : "WARNING");
            row.put("title", refundRisk ? "退款订单比例偏高" : "经营数据同步不完整");
            row.put("recommendation", refundRisk
                    ? "进入该店订单，核对退款原因、商品与售后承诺"
                    : "先检查同步来源与覆盖范围，缺失数据不参与经营判断");
            row.put("targetRoute", refundRisk ? "/orders?accountId=" + row.get("accountId")
                    : "/accounts?accountId=" + row.get("accountId"));
        }
        return rows;
    }

    private List<Map<String,Object>> productAnomalies(LocalDate start,LocalDate end,List<Long> accounts){
        if(accounts.isEmpty())return List.of();
        List<Map<String,Object>> rows = namedJdbcTemplate.queryForList("""
                SELECT metric.xianyu_account_id accountId,metric.xy_goods_id goodsId,MAX(goods.title) title,
                       SUM(metric.exposure_count) exposureCount,SUM(metric.click_count) clickCount,
                       SUM(metric.inquiry_count) inquiryCount,SUM(metric.paid_order_count) paidOrderCount,
                       CASE
                        WHEN SUM(metric.exposure_count)>=100 AND SUM(metric.click_count) IS NOT NULL
                             AND SUM(metric.click_count)/SUM(metric.exposure_count)<0.01 THEN 'HIGH_EXPOSURE_LOW_CLICK'
                        WHEN SUM(metric.inquiry_count)>=5 AND SUM(metric.paid_order_count) IS NOT NULL
                             AND SUM(metric.paid_order_count)=0 THEN 'HIGH_INQUIRY_LOW_PAYMENT'
                        WHEN MAX(goods.stock) IS NOT NULL AND MAX(goods.stock)<=2 THEN 'LOW_STOCK'
                       END anomalyType,
                       CASE WHEN COUNT(DISTINCT metric.source)=1 THEN MIN(metric.source) ELSE 'MIXED' END source,
                       CASE WHEN MIN(metric.coverage_status)='FULL' AND MAX(metric.coverage_status)='FULL' THEN 'FULL' ELSE 'PARTIAL' END coverageStatus,
                       MAX(metric.synced_at) syncedAt
                  FROM xianyu_goods_metric_daily metric
                  LEFT JOIN xianyu_goods goods ON goods.tenant_id=metric.tenant_id
                       AND goods.xianyu_account_id=metric.xianyu_account_id AND goods.xy_good_id=metric.xy_goods_id
                 WHERE metric.tenant_id=:tenant AND metric.xianyu_account_id IN (:accounts)
                   AND metric.metric_date BETWEEN :start AND :end
                 GROUP BY metric.xianyu_account_id,metric.xy_goods_id
                HAVING anomalyType IS NOT NULL ORDER BY exposureCount DESC,inquiryCount DESC LIMIT 100
                """,params(start,end,accounts));
        for (Map<String,Object> row : rows) {
            String type = String.valueOf(row.get("anomalyType"));
            switch (type) {
                case "HIGH_EXPOSURE_LOW_CLICK" -> {
                    row.put("severity", "HIGH");
                    row.put("title", "高曝光、低点击");
                    row.put("recommendation", "优先检查主图、标题和价格竞争力；不要直接降价，先对比同类商品");
                }
                case "HIGH_INQUIRY_LOW_PAYMENT" -> {
                    row.put("severity", "HIGH");
                    row.put("title", "高咨询、低支付");
                    row.put("recommendation", "检查自动回复命中、售后承诺、交付说明与买家常见异议");
                }
                default -> {
                    row.put("severity", "WARNING");
                    row.put("title", "库存偏低");
                    row.put("recommendation", "核对平台库存与本地可交付库存，补货前避免承诺即时交付");
                }
            }
            row.put("targetRoute", "/goods?accountId=" + row.get("accountId") + "&search="
                    + java.net.URLEncoder.encode(String.valueOf(row.get("goodsId")), StandardCharsets.UTF_8));
        }
        return rows;
    }

    private Map<String, Object> emptyAggregate() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String metric : List.of("gmv","paidOrderCount","paidBuyerCount","refundAmount","refundOrderCount",
                "exposureCount","visitorCount","inquiryCount","repliedInquiryCount","activeProductCount")) result.put(metric, null);
        result.put("source", "NONE"); result.put("syncStatus", "UNSYNCED"); result.put("coverageStatus", "UNSYNCED");
        result.put("coveredAccountCount", 0); result.put("requestedAccountCount", 0);
        result.put("sampleDays", 0); result.put("sampleSize", 0); result.put("syncedAt", null);
        addDerivedMetrics(result);
        return result;
    }

    private List<Long> scopedAccounts(Long accountId, Long groupId) {
        if (accountId != null && groupId != null) throw new BusinessException(400, "账号与分组范围不能同时指定");
        List<Long> ids;
        if (accountId != null) {
            accountAccessService.requireAccess(accountId);
            Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM xianyu_account WHERE tenant_id=? AND id=?", Long.class, tenant(), accountId);
            if (count == null || count == 0) throw new BusinessException(404, "账号不存在");
            ids = List.of(accountId);
        } else if (groupId != null) {
            ids = accountGroupService.accountIds(groupId);
        } else {
            ids = jdbcTemplate.queryForList("SELECT id FROM xianyu_account WHERE tenant_id=? ORDER BY id", Long.class, tenant());
            AccountScopeContext.Scope scope = AccountScopeContext.get();
            if (scope != null && !scope.unrestricted()) ids = ids.stream().filter(scope.accountIds()::contains).toList();
        }
        return ids;
    }

    private MapSqlParameterSource params(LocalDate start, LocalDate end, List<Long> accounts) {
        return new MapSqlParameterSource().addValue("tenant", tenant()).addValue("accounts", accounts)
                .addValue("start", Date.valueOf(start)).addValue("end", Date.valueOf(end));
    }

    private DateRange range(LocalDate start, LocalDate end) {
        LocalDate normalizedEnd = end == null ? LocalDate.now(BUSINESS_ZONE) : end;
        LocalDate normalizedStart = start == null ? normalizedEnd.minusDays(6) : start;
        if (normalizedStart.isAfter(normalizedEnd)) throw new BusinessException(400, "开始日期不能晚于结束日期");
        long days = java.time.temporal.ChronoUnit.DAYS.between(normalizedStart, normalizedEnd) + 1;
        if (days > 366) throw new BusinessException(400, "单次最多查询366天");
        return new DateRange(normalizedStart, normalizedEnd, (int) days);
    }

    private Long tenant() { Long value=TenantContext.get(); if(value==null) throw new BusinessException(401,"缺少经营主体上下文"); return value; }
    private String required(String value,String label,int max){if(value==null||value.trim().isEmpty())throw new BusinessException(400,label+"不能为空");String text=value.trim();if(text.length()>max)throw new BusinessException(400,label+"过长");return text;}
    private long number(Object value){return value instanceof Number number ? number.longValue() : 0;}
    private Long nullableLong(Object value){return value instanceof Number number ? number.longValue() : null;}
    private Long nullableLong(java.sql.ResultSet rs,String column) throws java.sql.SQLException {long value=rs.getLong(column);return rs.wasNull()?null:value;}
    private record DateRange(LocalDate start,LocalDate end,int days){DateRange previous(){return new DateRange(start.minusDays(days),start.minusDays(1),days);}}
}
