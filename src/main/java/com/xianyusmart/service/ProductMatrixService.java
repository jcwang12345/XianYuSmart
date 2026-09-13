package com.xianyusmart.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.AccountScopeContext;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.entity.XianyuOperationLog;
import com.xianyusmart.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** PRD-01～04 商品驾驶舱、360 详情、事件和批量任务。 */
@Service
public class ProductMatrixService {

    private static final Set<String> STATUS_BUCKETS = Set.of("ALL", "ON_SALE", "SOLD", "OFF_SHELF", "OTHER", "DRAFT");
    private static final Set<String> BATCH_OPERATIONS = Set.of(
            "ON_SALE", "OFF_SHELF", "CHANGE_PRICE", "CHANGE_STOCK", "POLISH", "DELETE", "SYNC");
    private static final Set<String> BATCH_STATUSES = Set.of(
            "PREVIEW", "QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "PARTIAL", "CANCELLED");
    private static final Set<String> ITEM_RETRYABLE = Set.of("FAILED", "UNKNOWN");

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final AccountAccessService accountAccessService;
    private final OperationLogService operationLogService;
    private final ObjectMapper objectMapper;

    public ProductMatrixService(JdbcTemplate jdbcTemplate,
                                NamedParameterJdbcTemplate namedJdbc,
                                AccountAccessService accountAccessService,
                                OperationLogService operationLogService,
                                ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbc = namedJdbc;
        this.accountAccessService = accountAccessService;
        this.operationLogService = operationLogService;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> list(ProductFilter filter) {
        ProductFilter normalized = normalizeFilter(filter);
        QueryParts base = queryParts(normalized, false);
        QueryParts withStatus = queryParts(normalized, true);
        Integer total = namedJdbc.queryForObject("SELECT COUNT(*) " + withStatus.fromWhere(), withStatus.params(), Integer.class);
        int safeTotal = total == null ? 0 : total;
        int offset = Math.min((normalized.page() - 1) * normalized.pageSize(), safeTotal);
        MapSqlParameterSource pageParams = withStatus.params().addValue("limit", normalized.pageSize()).addValue("offset", offset);
        List<Map<String, Object>> records = namedJdbc.query("""
                SELECT goods.*, account.account_note, account.unb
                """ + withStatus.fromWhere() + " ORDER BY goods.updated_time DESC, goods.id DESC LIMIT :limit OFFSET :offset",
                pageParams, (rs, rowNum) -> productRow(rs));

        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        for (String bucket : List.of("ALL", "ON_SALE", "SOLD", "OFF_SHELF", "OTHER", "DRAFT")) {
            MapSqlParameterSource params = copy(base.params());
            String predicate = statusPredicate(bucket);
            Integer count = namedJdbc.queryForObject("SELECT COUNT(*) " + base.fromWhere()
                    + (predicate.isBlank() ? "" : " AND " + predicate), params, Integer.class);
            statusCounts.put(bucket, count == null ? 0 : count);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("records", records);
        response.put("statusCounts", statusCounts);
        response.put("total", safeTotal);
        response.put("page", normalized.page());
        response.put("pageSize", normalized.pageSize());
        response.put("totalPages", (int) Math.ceil((double) safeTotal / normalized.pageSize()));
        response.put("summaryScope", "CURRENT_PAGE");
        response.put("summary", pageSummary(records));
        response.put("dataNotice", "商品主字段来自本地缓存；每行 source、syncStatus、coverageStatus、lastSyncedTime 表示其平台同步证据。");
        return response;
    }

    public Map<String, Object> detail(Long accountId, String goodsId) {
        requireProductAccess(accountId, goodsId);
        Long tenantId = requireTenant();
        List<Map<String, Object>> products = jdbcTemplate.query("""
                SELECT goods.*, account.account_note, account.unb
                  FROM xianyu_goods goods
                  JOIN xianyu_account account ON account.id=goods.xianyu_account_id AND account.tenant_id=goods.tenant_id
                 WHERE goods.tenant_id=? AND goods.xianyu_account_id=? AND goods.xy_good_id=?
                """, (rs, rowNum) -> productRow(rs), tenantId, accountId, goodsId);
        if (products.isEmpty()) throw new BusinessException(404, "商品不存在或不属于当前经营主体");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("basic", products.getFirst());
        response.put("skus", skus(accountId, goodsId));
        response.put("marketing", marketing(accountId, goodsId));
        response.put("metrics", Map.of(
                "day1", metricWindow(accountId, goodsId, 1),
                "day7", metricWindow(accountId, goodsId, 7),
                "day30", metricWindow(accountId, goodsId, 30)));
        response.put("timeline", events(accountId, goodsId, 200));
        return response;
    }

    public List<Map<String, Object>> events(Long accountId, String goodsId, Integer limit) {
        requireProductAccess(accountId, goodsId);
        int safeLimit = limit == null ? 100 : Math.max(1, Math.min(limit, 500));
        return jdbcTemplate.query("""
                SELECT id, event_type, event_origin, outcome_state, data_source, operator_user_id,
                       operator_username, request_id, idempotency_key, before_json, after_json,
                       field_diff_json, raw_snapshot_hash, platform_response_code, error_message, created_time
                  FROM xianyu_goods_event
                 WHERE tenant_id=? AND xianyu_account_id=? AND xy_goods_id=?
                 ORDER BY created_time DESC, id DESC LIMIT ?
                """, (rs, rowNum) -> eventRow(rs), requireTenant(), accountId, goodsId, safeLimit);
    }

    @Transactional
    public Map<String,Object> saveFilter(SavedFilterCommand command) {
        if(command==null) throw new BusinessException(400,"筛选器参数不能为空");
        String requestId=requireText(command.requestId(),"requestId",80);
        String name=requireText(command.name(),"筛选器名称",100);
        ProductFilter filter=normalizeFilter(command.filter());
        List<Map<String,Object>> replay=jdbcTemplate.queryForList(
                "SELECT id,filter_name name,filter_json filterJson,updated_time updatedTime FROM xianyu_saved_filter WHERE tenant_id=? AND request_id=?",
                requireTenant(),requestId);
        if(!replay.isEmpty()){Map<String,Object> result=new LinkedHashMap<>(replay.getFirst());result.put("filter",readJson((String)result.remove("filterJson")));result.put("idempotentReplay",true);return result;}
        jdbcTemplate.update("""
                INSERT INTO xianyu_saved_filter(tenant_id,user_id,filter_type,filter_name,filter_json,request_id)
                VALUES (?,?,'PRODUCT',?,?,?)
                ON DUPLICATE KEY UPDATE filter_json=VALUES(filter_json),request_id=VALUES(request_id)
                """,requireTenant(),UserContext.getUserId(),name,json(filter),requestId);
        Map<String,Object> result=listFilters().stream().filter(item->name.equals(item.get("name"))).findFirst().orElseThrow();
        result=new LinkedHashMap<>(result);result.put("idempotentReplay",false);
        audit(null,"PRODUCT_FILTER_SAVE","保存商品筛选器",requestId,"LOCAL_SUCCESS",command,result);
        return result;
    }

    public List<Map<String,Object>> listFilters(){
        List<Map<String,Object>> rows=jdbcTemplate.queryForList("""
                SELECT id,filter_name name,filter_json filterJson,updated_time updatedTime
                  FROM xianyu_saved_filter WHERE tenant_id=? AND user_id=? AND filter_type='PRODUCT'
                 ORDER BY updated_time DESC,id DESC
                """,requireTenant(),UserContext.getUserId());
        rows.forEach(row->row.put("filter",readJson((String)row.remove("filterJson"))));return rows;
    }

    @Transactional
    public void deleteFilter(Long id,String requestId){
        requireText(requestId,"requestId",80);
        int deleted=jdbcTemplate.update("DELETE FROM xianyu_saved_filter WHERE tenant_id=? AND user_id=? AND id=?",
                requireTenant(),UserContext.getUserId(),id);
        if(deleted==0)throw new BusinessException(404,"筛选器不存在");
        audit(null,"PRODUCT_FILTER_DELETE","删除商品筛选器",requestId,"LOCAL_SUCCESS",Map.of("id",id),null);
    }

    public String exportProducts(ProductFilter value,String requestId){
        requireText(requestId,"requestId",80);ProductFilter filter=normalizeFilter(value);QueryParts query=queryParts(filter,true);
        List<Map<String,Object>> products=namedJdbc.query("SELECT goods.*,account.account_note,account.unb "+query.fromWhere()
                +" ORDER BY goods.updated_time DESC,goods.id DESC LIMIT 10001",query.params(),(rs,row)->productRow(rs));
        if(products.size()>10000)throw new BusinessException(400,"单次最多导出10000个商品，请缩小筛选范围");
        StringBuilder csv=new StringBuilder("\uFEFF店铺,店铺ID,商品ID,标题,价格,库存,状态,来源,发布通道,同步状态,覆盖度,最后同步\r\n");
        for(Map<String,Object> item:products)csv.append(csv(item.get("accountNote"))).append(',').append(csv(item.get("accountId"))).append(',')
                .append(csv(item.get("goodsId"))).append(',').append(csv(item.get("title"))).append(',').append(csv(item.get("price"))).append(',')
                .append(csv(item.get("stock"))).append(',').append(csv(item.get("status"))).append(',').append(csv(item.get("source"))).append(',')
                .append(csv(item.get("publishChannel"))).append(',').append(csv(item.get("syncStatus"))).append(',')
                .append(csv(item.get("coverageStatus"))).append(',').append(csv(item.get("lastSyncedTime"))).append("\r\n");
        audit(null,"PRODUCT_EXPORT","导出商品经营报表",requestId,"LOCAL_SUCCESS",filter,Map.of("exportedCount",products.size()));return csv.toString();
    }

    public BatchPreview previewBatch(BatchRequest request) {
        BatchRequest normalized = normalizeBatchRequest(request, false);
        List<ProductRef> selected = resolveSelection(normalized);
        List<BatchCandidate> candidates = new ArrayList<>();
        int conflicts = 0;
        Set<Long> accounts = new LinkedHashSet<>();
        for (ProductRef ref : selected) {
            Map<String, Object> product = findProduct(ref.accountId(), ref.goodsId());
            String conflict = conflict(normalized.operationType(), normalized.operationParams(), product);
            if (conflict != null) conflicts++;
            accounts.add(ref.accountId());
            candidates.add(new BatchCandidate(ref.accountId(), ref.goodsId(), string(product.get("title")),
                    integer(product.get("status")), conflict == null, conflict));
        }
        int executable = selected.size() - conflicts;
        String confirmation = "确认对" + accounts.size() + "个店铺的" + executable + "个商品执行"
                + operationLabel(normalized.operationType()) + "，冲突" + conflicts + "个";
        return new BatchPreview(normalized.operationType(), normalized.selectionMode(), selected.size(),
                accounts.size(), conflicts, executable, confirmation, List.copyOf(candidates));
    }

    @Transactional
    public Map<String, Object> createBatch(BatchRequest request) {
        BatchRequest normalized = normalizeBatchRequest(request, true);
        Long tenantId = requireTenant();
        List<Map<String, Object>> replay = jdbcTemplate.queryForList(
                "SELECT id, batch_id FROM xianyu_goods_batch_job WHERE tenant_id=? AND request_id=?",
                tenantId, normalized.requestId());
        if (!replay.isEmpty()) {
            Long jobId = ((Number) replay.getFirst().get("id")).longValue();
            Map<String, Object> existing = batchDetail(jobId);
            existing.put("idempotentReplay", true);
            return existing;
        }
        BatchPreview preview = previewBatch(normalized);
        if (!preview.confirmationSummary().equals(normalized.confirmationText())) {
            throw new BusinessException(400, "确认范围已变化，请重新预检并使用最新确认文案");
        }
        if (preview.executableCount() == 0) throw new BusinessException(409, "所选商品全部存在冲突，无法创建任务");
        String batchId = "PB-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT);
        jdbcTemplate.update("""
                INSERT INTO xianyu_goods_batch_job
                (tenant_id, batch_id, request_id, operation_type, selection_mode, selection_query_json,
                 operation_params_json, status, selected_count, conflict_count, executable_count,
                 max_operations_per_minute, confirmation_summary, operator_user_id, operator_username)
                VALUES (?,?,?,?,?,?,?,'QUEUED',?,?,?,?,?,?,?)
                """, tenantId, batchId, normalized.requestId(), normalized.operationType(), normalized.selectionMode(),
                json(normalized.filter()), json(normalized.operationParams()), preview.selectedCount(), preview.conflictCount(),
                preview.executableCount(), normalized.maxOperationsPerMinute(), preview.confirmationSummary(),
                UserContext.getUserId(), UserContext.getUsername());
        Long jobId = jdbcTemplate.queryForObject(
                "SELECT id FROM xianyu_goods_batch_job WHERE tenant_id=? AND batch_id=?", Long.class, tenantId, batchId);
        if (jobId == null) throw new BusinessException(500, "批量任务创建后无法读取");
        for (BatchCandidate item : preview.items()) {
            jdbcTemplate.update("""
                    INSERT INTO xianyu_goods_batch_item
                    (tenant_id, batch_job_id, batch_id, xianyu_account_id, xy_goods_id, operation_type,
                     status, conflict_code, conflict_message, outcome_state)
                    VALUES (?,?,?,?,?,?,?, ?,?, 'UNKNOWN')
                    """, tenantId, jobId, batchId, item.accountId(), item.goodsId(), normalized.operationType(),
                    item.executable() ? "QUEUED" : "CONFLICT", item.executable() ? null : "PRECHECK_CONFLICT",
                    item.conflictMessage());
        }
        audit(null, "PRODUCT_BATCH_CREATE", "创建商品批量任务", normalized.requestId(),
                "LOCAL_SUCCESS", normalized, Map.of("batchId", batchId, "scope", preview.confirmationSummary()));
        Map<String, Object> result = batchDetail(jobId);
        result.put("idempotentReplay", false);
        return result;
    }

    public List<Map<String, Object>> batches(String status, Integer limit) {
        String normalizedStatus = normalizeOptional(status, BATCH_STATUSES, "批量任务状态");
        int safeLimit = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
        String scope = itemScopeCondition("item");
        String sql = """
                SELECT job.* FROM xianyu_goods_batch_job job
                 WHERE job.tenant_id=?
                   AND EXISTS (SELECT 1 FROM xianyu_goods_batch_item item
                                WHERE item.tenant_id=job.tenant_id AND item.batch_job_id=job.id
                """ + scope + ")" + (normalizedStatus == null ? "" : " AND job.status=?")
                + " ORDER BY job.created_time DESC, job.id DESC LIMIT ?";
        Object[] args = normalizedStatus == null
                ? new Object[]{requireTenant(), safeLimit}
                : new Object[]{requireTenant(), normalizedStatus, safeLimit};
        return jdbcTemplate.query(sql, (rs, rowNum) -> batchRow(rs), args);
    }

    public Map<String, Object> batchDetail(Long jobId) {
        if (jobId == null || jobId <= 0) throw new BusinessException(400, "批量任务ID无效");
        String scope = itemScopeCondition("item");
        List<Map<String, Object>> jobs = jdbcTemplate.query("""
                SELECT job.* FROM xianyu_goods_batch_job job
                 WHERE job.tenant_id=? AND job.id=?
                   AND EXISTS (SELECT 1 FROM xianyu_goods_batch_item item
                                WHERE item.tenant_id=job.tenant_id AND item.batch_job_id=job.id
                """ + scope + ")", (rs, rowNum) -> batchRow(rs), requireTenant(), jobId);
        if (jobs.isEmpty()) throw new BusinessException(404, "批量任务不存在或不在当前账号权限范围");
        Map<String, Object> result = jobs.getFirst();
        List<Map<String, Object>> items = jdbcTemplate.query("""
                SELECT item.*, goods.title, account.account_note
                  FROM xianyu_goods_batch_item item
                  LEFT JOIN xianyu_goods goods ON goods.tenant_id=item.tenant_id
                    AND goods.xianyu_account_id=item.xianyu_account_id AND goods.xy_good_id=item.xy_goods_id
                  JOIN xianyu_account account ON account.id=item.xianyu_account_id AND account.tenant_id=item.tenant_id
                 WHERE item.tenant_id=? AND item.batch_job_id=?
                """ + itemScopeCondition("item") + " ORDER BY item.id", (rs, rowNum) -> batchItemRow(rs),
                requireTenant(), jobId);
        result.put("items", items);
        return result;
    }

    @Transactional
    public Map<String, Object> retryBatchFailures(Long jobId, String requestId) {
        requireText(requestId, "requestId", 80);
        Map<String, Object> batch = batchDetail(jobId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) batch.get("items");
        int retried = 0;
        for (Map<String, Object> item : items) {
            String status = string(item.get("status"));
            if (!ITEM_RETRYABLE.contains(status)) continue;
            Long itemId = ((Number) item.get("itemId")).longValue();
            Long accountId = ((Number) item.get("accountId")).longValue();
            String goodsId = string(item.get("goodsId"));
            int inserted = jdbcTemplate.update("""
                    INSERT IGNORE INTO xianyu_goods_event
                    (tenant_id, xianyu_account_id, xy_goods_id, event_type, event_origin, outcome_state,
                     data_source, operator_user_id, operator_username, request_id)
                    VALUES (?,?,?,'BATCH_RETRY','USER','LOCAL_SUCCESS','LOCAL',?,?,?)
                    """, requireTenant(), accountId, goodsId, UserContext.getUserId(), UserContext.getUsername(), requestId);
            if (inserted == 0) continue;
            retried += jdbcTemplate.update("""
                    UPDATE xianyu_goods_batch_item
                       SET status='QUEUED', next_retry_time=NULL, error_message=NULL,
                           outcome_state='UNKNOWN', completed_time=NULL
                     WHERE tenant_id=? AND id=? AND status IN ('FAILED','UNKNOWN')
                    """, requireTenant(), itemId);
        }
        if (retried > 0) {
            jdbcTemplate.update("UPDATE xianyu_goods_batch_job SET status='QUEUED', completed_time=NULL WHERE tenant_id=? AND id=?",
                    requireTenant(), jobId);
        }
        audit(null, "PRODUCT_BATCH_RETRY", "重试商品批量任务失败项", requestId,
                "LOCAL_SUCCESS", Map.of("jobId", jobId), Map.of("retriedCount", retried));
        Map<String, Object> result = batchDetail(jobId);
        result.put("retriedCount", retried);
        result.put("idempotentReplay", retried == 0);
        return result;
    }

    public String exportBatchFailures(Long jobId, String requestId) {
        requireText(requestId, "requestId", 80);
        Map<String, Object> batch = batchDetail(jobId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) batch.get("items");
        StringBuilder csv = new StringBuilder("\uFEFF批次,店铺,账号ID,商品ID,标题,状态,结果层,冲突,错误,平台响应码\r\n");
        int count = 0;
        for (Map<String, Object> item : items) {
            if (!Set.of("FAILED", "UNKNOWN", "CONFLICT").contains(item.get("status"))) continue;
            count++;
            csv.append(csv(batch.get("batchId"))).append(',').append(csv(item.get("accountNote"))).append(',')
                    .append(csv(item.get("accountId"))).append(',').append(csv(item.get("goodsId"))).append(',')
                    .append(csv(item.get("title"))).append(',').append(csv(item.get("status"))).append(',')
                    .append(csv(item.get("outcomeState"))).append(',').append(csv(item.get("conflictMessage"))).append(',')
                    .append(csv(item.get("errorMessage"))).append(',').append(csv(item.get("platformResponseCode"))).append("\r\n");
        }
        audit(null, "PRODUCT_BATCH_EXPORT", "导出商品批量任务失败明细", requestId,
                "LOCAL_SUCCESS", Map.of("jobId", jobId), Map.of("exportedCount", count));
        return csv.toString();
    }

    private ProductFilter normalizeFilter(ProductFilter value) {
        ProductFilter filter = value == null ? new ProductFilter(null, null, null, null, null, 1, 20) : value;
        String bucket = filter.statusBucket() == null ? "ALL" : requireEnum(filter.statusBucket(), STATUS_BUCKETS, "商品状态");
        int page = filter.page() == null || filter.page() < 1 ? 1 : filter.page();
        int size = filter.pageSize() == null || filter.pageSize() < 1 ? 20 : Math.min(filter.pageSize(), 100);
        List<Long> accountIds = filter.accountIds() == null ? List.of() : filter.accountIds().stream()
                .filter(id -> id != null && id > 0).distinct().toList();
        accountIds.forEach(accountAccessService::requireAccess);
        return new ProductFilter(trim(filter.search()), accountIds, bucket, upper(filter.source()),
                upper(filter.publishChannel()), page, size);
    }

    private QueryParts queryParts(ProductFilter filter, boolean includeStatus) {
        MapSqlParameterSource params = new MapSqlParameterSource("tenantId", requireTenant());
        StringBuilder where = new StringBuilder(" FROM xianyu_goods goods JOIN xianyu_account account")
                .append(" ON account.id=goods.xianyu_account_id AND account.tenant_id=goods.tenant_id")
                .append(" WHERE goods.tenant_id=:tenantId");
        appendAccountScope(where, params, "goods", filter.accountIds());
        if (filter.search() != null) {
            where.append(" AND (goods.xy_good_id LIKE :search OR goods.title LIKE :search OR goods.outer_id LIKE :search)");
            params.addValue("search", "%" + filter.search() + "%");
        }
        if (filter.source() != null) {
            where.append(" AND goods.product_source=:source");
            params.addValue("source", filter.source());
        }
        if (filter.publishChannel() != null) {
            where.append(" AND goods.publish_channel=:channel");
            params.addValue("channel", filter.publishChannel());
        }
        if (includeStatus && !"ALL".equals(filter.statusBucket())) where.append(" AND ").append(statusPredicate(filter.statusBucket()));
        return new QueryParts(where.toString(), params);
    }

    private void appendAccountScope(StringBuilder where, MapSqlParameterSource params, String alias, List<Long> requested) {
        AccountScopeContext.Scope scope = AccountScopeContext.get();
        Set<Long> allowed = scope == null || scope.unrestricted() ? null : scope.accountIds();
        LinkedHashSet<Long> effective = new LinkedHashSet<>();
        if (requested != null && !requested.isEmpty()) effective.addAll(requested);
        if (allowed != null) {
            if (effective.isEmpty()) effective.addAll(allowed);
            else effective.retainAll(allowed);
        }
        if ((allowed != null || requested != null && !requested.isEmpty()) && effective.isEmpty()) {
            where.append(" AND 1=0");
        } else if (!effective.isEmpty()) {
            where.append(" AND ").append(alias).append(".xianyu_account_id IN (:accountIds)");
            params.addValue("accountIds", effective);
        }
    }

    private String statusPredicate(String bucket) {
        return switch (bucket) {
            case "ALL" -> "";
            case "ON_SALE" -> "goods.status=0 AND goods.product_source<>'LOCAL_DRAFT'";
            case "SOLD" -> "goods.status=2 AND goods.product_source<>'LOCAL_DRAFT'";
            case "OFF_SHELF" -> "goods.status IN (1,-1,-98) AND goods.product_source<>'LOCAL_DRAFT'";
            case "DRAFT" -> "goods.product_source='LOCAL_DRAFT'";
            default -> "goods.status NOT IN (0,1,2,-1,-98) AND goods.product_source<>'LOCAL_DRAFT'";
        };
    }

    private Map<String, Object> productRow(ResultSet rs) throws SQLException {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", rs.getLong("id"));
        map.put("goodsId", rs.getString("xy_good_id"));
        map.put("outerId", rs.getString("outer_id"));
        map.put("accountId", nullableLong(rs, "xianyu_account_id"));
        map.put("accountNote", rs.getString("account_note"));
        map.put("accountUnb", rs.getString("unb"));
        map.put("title", rs.getString("title"));
        map.put("coverPic", rs.getString("cover_pic"));
        map.put("galleryJson", rs.getString("info_pic"));
        map.put("detailJson", rs.getString("detail_info"));
        map.put("detailUrl", rs.getString("detail_url"));
        map.put("price", decimal(rs.getString("sold_price")));
        map.put("originalPrice", rs.getBigDecimal("original_price"));
        map.put("shippingFee", rs.getBigDecimal("shipping_fee"));
        map.put("shippingType", rs.getString("shipping_type"));
        map.put("skuCount", nullableInteger(rs, "sku_count"));
        map.put("stock", nullableInteger(rs, "stock"));
        int status = rs.getInt("status");
        map.put("status", rs.wasNull() ? null : status);
        map.put("statusBucket", statusBucket(status, rs.getString("product_source")));
        map.put("source", rs.getString("product_source"));
        map.put("publishChannel", rs.getString("publish_channel"));
        map.put("itemType", rs.getString("item_type"));
        map.put("categoryId", rs.getString("category_id"));
        map.put("categoryName", rs.getString("category_name"));
        map.put("businessMode", rs.getString("business_mode"));
        map.put("conditionCode", rs.getString("condition_code"));
        map.put("syncStatus", rs.getString("sync_status"));
        map.put("coverageStatus", rs.getString("coverage_status"));
        map.put("platformUpdatedTime", instant(rs, "platform_updated_time"));
        map.put("lastSyncedTime", instant(rs, "last_synced_time"));
        map.put("lastSyncRequestId", rs.getString("last_sync_request_id"));
        map.put("lastSyncErrorCode", rs.getString("last_sync_error_code"));
        map.put("lastSyncErrorMessage", rs.getString("last_sync_error_message"));
        return map;
    }

    private List<Map<String, Object>> skus(Long accountId, String goodsId) {
        return jdbcTemplate.query("""
                SELECT sku_key, property_text, price, quantity, sku_id
                  FROM xianyu_goods_sku
                 WHERE tenant_id=? AND xianyu_account_id=? AND xy_goods_id=? ORDER BY id
                """, (rs, rowNum) -> {
            Map<String, Object> sku = new LinkedHashMap<>();
            sku.put("skuKey", rs.getString("sku_key"));
            sku.put("skuText", rs.getString("property_text"));
            sku.put("price", rs.getBigDecimal("price"));
            sku.put("stock", nullableInteger(rs, "quantity"));
            sku.put("platformStatus", null);
            sku.put("syncDifference", null);
            sku.put("skuId", rs.getString("sku_id"));
            return sku;
        }, requireTenant(), accountId, goodsId);
    }

    private Map<String, Object> marketing(Long accountId, String goodsId) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                SELECT xianyu_auto_delivery_on, xianyu_auto_reply_on, xianyu_auto_rate_on,
                       xianyu_auto_polish_on, last_polish_time
                  FROM xianyu_goods_config
                 WHERE tenant_id=? AND xianyu_account_id=? AND xy_goods_id=?
                """, (rs, rowNum) -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("autoDeliveryEnabled", rs.getInt("xianyu_auto_delivery_on") == 1);
            value.put("autoReplyEnabled", rs.getInt("xianyu_auto_reply_on") == 1);
            value.put("autoRateEnabled", rs.getInt("xianyu_auto_rate_on") == 1);
            value.put("autoPolishEnabled", rs.getInt("xianyu_auto_polish_on") == 1);
            value.put("lastPolishTime", nullableLong(rs, "last_polish_time"));
            value.put("fanPrice", null);
            value.put("bargain", null);
            value.put("coinDeduction", null);
            value.put("marketingCoverageStatus", "UNSYNCED");
            return value;
        }, requireTenant(), accountId, goodsId);
        if (!rows.isEmpty()) return rows.getFirst();
        Map<String, Object> unknown = new LinkedHashMap<>();
        unknown.put("autoDeliveryEnabled", false);
        unknown.put("autoReplyEnabled", false);
        unknown.put("autoRateEnabled", false);
        unknown.put("autoPolishEnabled", false);
        unknown.put("lastPolishTime", null);
        unknown.put("fanPrice", null);
        unknown.put("bargain", null);
        unknown.put("coinDeduction", null);
        unknown.put("marketingCoverageStatus", "UNSYNCED");
        return unknown;
    }

    private Map<String, Object> metricWindow(Long accountId, String goodsId, int days) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                SELECT COUNT(*) sample_days,
                       SUM(exposure_count) exposure_count, SUM(visitor_count) visitor_count,
                       SUM(click_count) click_count, SUM(favorite_count) favorite_count,
                       SUM(inquiry_count) inquiry_count, SUM(paid_order_count) paid_order_count,
                       SUM(paid_amount) paid_amount,
                       CASE WHEN COUNT(*)=0 THEN 'UNSYNCED'
                            WHEN SUM(coverage_status='FULL')=COUNT(*) THEN 'FULL' ELSE 'PARTIAL' END coverage_status,
                       MAX(synced_at) synced_at
                  FROM xianyu_goods_metric_daily
                 WHERE tenant_id=? AND xianyu_account_id=? AND xy_goods_id=?
                   AND metric_date >= DATE_SUB(CURRENT_DATE(), INTERVAL ? DAY)
                """, (rs, rowNum) -> {
            int samples = rs.getInt("sample_days");
            Map<String, Object> metric = new LinkedHashMap<>();
            metric.put("windowDays", days);
            metric.put("sampleDays", samples);
            metric.put("exposureCount", samples == 0 ? null : nullableLong(rs, "exposure_count"));
            metric.put("visitorCount", samples == 0 ? null : nullableLong(rs, "visitor_count"));
            metric.put("clickCount", samples == 0 ? null : nullableLong(rs, "click_count"));
            metric.put("favoriteCount", samples == 0 ? null : nullableLong(rs, "favorite_count"));
            metric.put("inquiryCount", samples == 0 ? null : nullableLong(rs, "inquiry_count"));
            metric.put("paidOrderCount", samples == 0 ? null : nullableLong(rs, "paid_order_count"));
            metric.put("paidAmount", samples == 0 ? null : rs.getBigDecimal("paid_amount"));
            metric.put("coverageStatus", samples == 0 ? "UNSYNCED" : rs.getString("coverage_status"));
            metric.put("syncedAt", samples == 0 ? null : instant(rs, "synced_at"));
            return metric;
        }, requireTenant(), accountId, goodsId, Math.max(0, days - 1));
        return rows.getFirst();
    }

    private Map<String, Object> eventRow(ResultSet rs) throws SQLException {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", rs.getLong("id"));
        event.put("eventType", rs.getString("event_type"));
        event.put("eventOrigin", rs.getString("event_origin"));
        event.put("outcomeState", rs.getString("outcome_state"));
        event.put("dataSource", rs.getString("data_source"));
        event.put("operatorUserId", nullableLong(rs, "operator_user_id"));
        event.put("operatorUsername", rs.getString("operator_username"));
        event.put("requestId", rs.getString("request_id"));
        event.put("idempotencyKey", rs.getString("idempotency_key"));
        event.put("before", readJson(rs.getString("before_json")));
        event.put("after", readJson(rs.getString("after_json")));
        event.put("fieldDiff", readJson(rs.getString("field_diff_json")));
        event.put("rawSnapshotHash", rs.getString("raw_snapshot_hash"));
        event.put("platformResponseCode", rs.getString("platform_response_code"));
        event.put("errorMessage", rs.getString("error_message"));
        event.put("createdTime", instant(rs, "created_time"));
        return event;
    }

    private Map<String, Object> batchRow(ResultSet rs) throws SQLException {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("jobId", rs.getLong("id"));
        job.put("batchId", rs.getString("batch_id"));
        job.put("requestId", rs.getString("request_id"));
        job.put("operationType", rs.getString("operation_type"));
        job.put("selectionMode", rs.getString("selection_mode"));
        job.put("operationParams", readJson(rs.getString("operation_params_json")));
        job.put("status", rs.getString("status"));
        job.put("selectedCount", rs.getInt("selected_count"));
        job.put("conflictCount", rs.getInt("conflict_count"));
        job.put("executableCount", rs.getInt("executable_count"));
        job.put("successCount", rs.getInt("success_count"));
        job.put("failedCount", rs.getInt("failed_count"));
        job.put("unknownCount", rs.getInt("unknown_count"));
        job.put("maxOperationsPerMinute", rs.getInt("max_operations_per_minute"));
        job.put("confirmationSummary", rs.getString("confirmation_summary"));
        job.put("operatorUserId", nullableLong(rs, "operator_user_id"));
        job.put("operatorUsername", rs.getString("operator_username"));
        job.put("startedTime", instant(rs, "started_time"));
        job.put("completedTime", instant(rs, "completed_time"));
        job.put("createdTime", instant(rs, "created_time"));
        return job;
    }

    private Map<String, Object> batchItemRow(ResultSet rs) throws SQLException {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("itemId", rs.getLong("id"));
        item.put("accountId", rs.getLong("xianyu_account_id"));
        item.put("accountNote", rs.getString("account_note"));
        item.put("goodsId", rs.getString("xy_goods_id"));
        item.put("title", rs.getString("title"));
        item.put("operationType", rs.getString("operation_type"));
        item.put("status", rs.getString("status"));
        item.put("conflictCode", rs.getString("conflict_code"));
        item.put("conflictMessage", rs.getString("conflict_message"));
        item.put("attemptCount", rs.getInt("attempt_count"));
        item.put("maxAttempts", rs.getInt("max_attempts"));
        item.put("nextRetryTime", instant(rs, "next_retry_time"));
        item.put("outcomeState", rs.getString("outcome_state"));
        item.put("platformResponseCode", rs.getString("platform_response_code"));
        item.put("result", readJson(rs.getString("result_json")));
        item.put("errorMessage", rs.getString("error_message"));
        item.put("startedTime", instant(rs, "started_time"));
        item.put("completedTime", instant(rs, "completed_time"));
        return item;
    }

    private List<ProductRef> resolveSelection(BatchRequest request) {
        LinkedHashSet<ProductRef> refs = new LinkedHashSet<>();
        if ("FILTER_SNAPSHOT".equals(request.selectionMode())) {
            ProductFilter filter = normalizeFilter(request.filter());
            QueryParts query=queryParts(filter,true);
            List<Map<String,Object>> products=namedJdbc.query("SELECT goods.*,account.account_note,account.unb "+query.fromWhere()
                    +" ORDER BY goods.updated_time DESC,goods.id DESC LIMIT 501",query.params(),(rs,row)->productRow(rs));
            products.forEach(product -> refs.add(new ProductRef(
                    ((Number) product.get("accountId")).longValue(), string(product.get("goodsId")))));
            if (products.size() > 500) throw new BusinessException(400, "跨页批量单次最多500个商品，请缩小筛选范围");
        } else {
            if (request.items() != null) refs.addAll(request.items());
        }
        if (refs.isEmpty()) throw new BusinessException(400, "请选择至少一个商品");
        if (refs.size() > 500) throw new BusinessException(400, "批量任务单次最多500个商品");
        refs.forEach(ref -> {
            if (ref == null || ref.accountId() == null || ref.accountId() <= 0 || trim(ref.goodsId()) == null) {
                throw new BusinessException(400, "商品选择范围包含无效账号或商品ID");
            }
            accountAccessService.requireAccess(ref.accountId());
        });
        return List.copyOf(refs);
    }

    private BatchRequest normalizeBatchRequest(BatchRequest request, boolean forCreate) {
        if (request == null) throw new BusinessException(400, "批量任务参数不能为空");
        String operation = requireEnum(request.operationType(), BATCH_OPERATIONS, "批量操作");
        String selectionMode = request.selectionMode() == null ? "EXPLICIT_IDS"
                : requireEnum(request.selectionMode(), Set.of("EXPLICIT_IDS", "FILTER_SNAPSHOT"), "选择范围");
        if (forCreate) requireText(request.requestId(), "requestId", 80);
        int rate = request.maxOperationsPerMinute() == null ? 10
                : Math.max(1, Math.min(request.maxOperationsPerMinute(), 30));
        Map<String, Object> params = request.operationParams() == null ? Map.of() : request.operationParams();
        if ("CHANGE_PRICE".equals(operation)) {
            BigDecimal price = decimal(params.get("price"));
            if (price == null || price.signum() <= 0) throw new BusinessException(400, "批量改价需要大于0的price");
        }
        if ("CHANGE_STOCK".equals(operation)) {
            Integer stock = integer(params.get("stock"));
            if (stock == null || stock < 0) throw new BusinessException(400, "批量改库存需要不小于0的stock");
        }
        return new BatchRequest(trim(request.requestId()), operation, selectionMode, request.items(), request.filter(),
                params, rate, request.confirmationText());
    }

    private Map<String, Object> findProduct(Long accountId, String goodsId) {
        requireProductAccess(accountId, goodsId);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT title, status, product_source, sync_status, coverage_status
                  FROM xianyu_goods WHERE tenant_id=? AND xianyu_account_id=? AND xy_good_id=?
                """, requireTenant(), accountId, goodsId);
        if (rows.isEmpty()) throw new BusinessException(404, "商品不存在：" + goodsId);
        return rows.getFirst();
    }

    private String conflict(String operation, Map<String, Object> params, Map<String, Object> product) {
        Integer status = integer(product.get("status"));
        String source = string(product.get("product_source"));
        if ("LOCAL_DRAFT".equals(source) && !"SYNC".equals(operation)) return "本地草稿尚无平台商品，不能执行平台操作";
        if ("ON_SALE".equals(operation) && Integer.valueOf(0).equals(status)) return "商品已经在售";
        if ("OFF_SHELF".equals(operation) && status != null && Set.of(1, -1, -98).contains(status)) return "商品已经下架或删除";
        if ("DELETE".equals(operation) && Integer.valueOf(-1).equals(status)) return "商品已经删除";
        if ("CHANGE_PRICE".equals(operation)) {
            if (decimal(params.get("price")) == null) return "缺少目标价格";
            return "当前接入通道尚未验证平台改价能力，禁止创建只改本地缓存的任务";
        }
        if ("CHANGE_STOCK".equals(operation)) {
            if (integer(params.get("stock")) == null) return "缺少目标库存";
            return "当前接入通道尚未验证平台改库存能力，禁止创建只改本地缓存的任务";
        }
        return null;
    }

    private Map<String, Object> pageSummary(List<Map<String, Object>> records) {
        BigDecimal priceTotal = BigDecimal.ZERO;
        long stockTotal = 0;
        int knownPrices = 0;
        int knownStocks = 0;
        for (Map<String, Object> record : records) {
            if (record.get("price") instanceof BigDecimal price) {
                priceTotal = priceTotal.add(price);
                knownPrices++;
            }
            if (record.get("stock") instanceof Number stock) {
                stockTotal += stock.longValue();
                knownStocks++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("productCount", records.size());
        summary.put("knownPriceCount", knownPrices);
        summary.put("averagePrice", knownPrices == 0 ? null : priceTotal.divide(BigDecimal.valueOf(knownPrices), 2, RoundingMode.HALF_UP));
        summary.put("knownStockCount", knownStocks);
        summary.put("knownStockTotal", knownStocks == 0 ? null : stockTotal);
        summary.put("metricCoverageStatus", "UNSYNCED");
        return summary;
    }

    private void requireProductAccess(Long accountId, String goodsId) {
        if (accountId == null || accountId <= 0) throw new BusinessException(400, "账号ID无效");
        requireText(goodsId, "商品ID", 100);
        accountAccessService.requireAccess(accountId);
    }

    private String itemScopeCondition(String alias) {
        AccountScopeContext.Scope scope = AccountScopeContext.get();
        if (scope == null || scope.unrestricted()) return "";
        if (scope.accountIds().isEmpty()) return " AND 1=0";
        String ids = scope.accountIds().stream().sorted().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        return " AND " + alias + ".xianyu_account_id IN (" + ids + ")";
    }

    private void audit(Long accountId, String type, String description, String requestId,
                       String outcome, Object request, Object result) {
        XianyuOperationLog log = new XianyuOperationLog();
        log.setXianyuAccountId(accountId);
        log.setOperationType(type);
        log.setOperationModule("商品管理");
        log.setOperationDesc(description);
        log.setOperationStatus(1);
        log.setRequestId(requestId);
        log.setIdempotencyKey(requestId);
        log.setOutcomeState(outcome);
        log.setDataSource("LOCAL");
        log.setRequestParams(json(request));
        log.setResponseResult(json(result));
        operationLogService.log(log);
    }

    private String operationLabel(String operation) {
        return switch (operation) {
            case "ON_SALE" -> "上架";
            case "OFF_SHELF" -> "下架";
            case "CHANGE_PRICE" -> "改价";
            case "CHANGE_STOCK" -> "改库存";
            case "POLISH" -> "擦亮";
            case "DELETE" -> "删除";
            default -> "同步";
        };
    }

    private String statusBucket(int status, String source) {
        if ("LOCAL_DRAFT".equals(source)) return "DRAFT";
        if (status == 0) return "ON_SALE";
        if (status == 2) return "SOLD";
        if (Set.of(1, -1, -98).contains(status)) return "OFF_SHELF";
        return "OTHER";
    }

    private Long requireTenant() {
        Long tenantId = TenantContext.get();
        if (tenantId == null || tenantId <= 0) throw new BusinessException(401, "缺少经营主体上下文");
        return tenantId;
    }

    private String requireEnum(String value, Set<String> allowed, String label) {
        String normalized = upper(value);
        if (normalized == null || !allowed.contains(normalized))
            throw new BusinessException(400, label + "无效，可选值：" + String.join(",", allowed));
        return normalized;
    }

    private String normalizeOptional(String value, Set<String> allowed, String label) {
        return trim(value) == null ? null : requireEnum(value, allowed, label);
    }

    private String requireText(String value, String label, int maxLength) {
        String normalized = trim(value);
        if (normalized == null) throw new BusinessException(400, label + "不能为空");
        if (normalized.length() > maxLength) throw new BusinessException(400, label + "不能超过" + maxLength + "个字符");
        return normalized;
    }

    private String json(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(400, "JSON序列化失败", e);
        }
    }

    private Object readJson(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return objectMapper.readValue(value, Object.class);
        } catch (JsonProcessingException ignored) {
            return value;
        }
    }

    private MapSqlParameterSource copy(MapSqlParameterSource source) {
        MapSqlParameterSource copy = new MapSqlParameterSource();
        for (String name : source.getValues().keySet()) copy.addValue(name, source.getValue(name));
        return copy;
    }

    private static String upper(String value) {
        String text = trim(value);
        return text == null ? null : text.toUpperCase(Locale.ROOT);
    }

    private static String trim(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? null : Integer.valueOf(String.valueOf(value)); }
        catch (NumberFormatException e) { return null; }
    }

    private static BigDecimal decimal(Object value) {
        try { return value == null || String.valueOf(value).isBlank() ? null : new BigDecimal(String.valueOf(value)); }
        catch (NumberFormatException e) { return null; }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String csv(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    private record QueryParts(String fromWhere, MapSqlParameterSource params) {}

    public record ProductFilter(String search, List<Long> accountIds, String statusBucket,
                                String source, String publishChannel, Integer page, Integer pageSize) {}

    public record SavedFilterCommand(String name,ProductFilter filter,String requestId) {}

    public record ProductRef(Long accountId, String goodsId) {}

    public record BatchRequest(String requestId, String operationType, String selectionMode,
                               List<ProductRef> items, ProductFilter filter, Map<String, Object> operationParams,
                               Integer maxOperationsPerMinute, String confirmationText) {}

    public record BatchCandidate(Long accountId, String goodsId, String title, Integer status,
                                 boolean executable, String conflictMessage) {}

    public record BatchPreview(String operationType, String selectionMode, int selectedCount,
                               int accountCount, int conflictCount, int executableCount,
                               String confirmationSummary, List<BatchCandidate> items) {}
}
