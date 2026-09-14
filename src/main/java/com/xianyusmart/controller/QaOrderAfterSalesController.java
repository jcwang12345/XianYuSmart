package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.service.AccountAccessService;
import com.xianyusmart.service.OrderMatrixService;
import com.xianyusmart.service.ProductBatchQaMockService;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** ORD-04/05 隔离验收夹具；只在 qa profile 注册，且复用商品批任务的显式租户/店铺白名单。 */
@Profile("qa")
@RestController
@RequestMapping("/api/qa/order-after-sales")
public class QaOrderAfterSalesController {

    private final JdbcTemplate jdbcTemplate;
    private final ProductBatchQaMockService qaMockService;
    private final OrderMatrixService orderMatrixService;
    private final AccountAccessService accountAccessService;

    public QaOrderAfterSalesController(JdbcTemplate jdbcTemplate, ProductBatchQaMockService qaMockService,
                                       OrderMatrixService orderMatrixService,
                                       AccountAccessService accountAccessService) {
        this.jdbcTemplate = jdbcTemplate;
        this.qaMockService = qaMockService;
        this.orderMatrixService = orderMatrixService;
        this.accountAccessService = accountAccessService;
    }

    @PostMapping("/fixtures/{orderRecordId}")
    @Transactional
    public ResultObject<Map<String, Object>> fixture(@PathVariable Long orderRecordId,
                                                     @RequestBody FixtureRequest request) {
        if (!qaMockService.enabled()) throw new BusinessException(404, "隔离 QA Mock 未启用");
        if (request == null || request.requestId() == null || request.requestId().isBlank()
                || request.requestId().length() > 70) {
            throw new BusinessException(400, "requestId 不能为空且不能超过70个字符");
        }
        Long tenantId = TenantContext.get();
        Long allowedTenantId = ((Number) qaMockService.publicConfiguration().get("tenantId")).longValue();
        @SuppressWarnings("unchecked")
        Set<Long> allowedAccounts = (Set<Long>) qaMockService.publicConfiguration().get("accountIds");
        if (tenantId == null || !tenantId.equals(allowedTenantId)) {
            throw new BusinessException(403, "当前租户不在隔离 QA 白名单");
        }
        List<Map<String, Object>> orders = jdbcTemplate.queryForList("""
                SELECT id,xianyu_account_id,order_id FROM xianyu_goods_order
                 WHERE tenant_id=? AND id=?
                """, tenantId, orderRecordId);
        if (orders.isEmpty()) throw new BusinessException(404, "QA 订单不存在");
        Map<String, Object> order = orders.getFirst();
        Long accountId = ((Number) order.get("xianyu_account_id")).longValue();
        String orderId = String.valueOf(order.get("order_id"));
        accountAccessService.requireAccess(accountId);
        if (!allowedAccounts.contains(accountId) || !orderId.startsWith("QA-ORDER-")) {
            throw new BusinessException(403, "只允许白名单店铺的 QA-ORDER- 隔离订单");
        }
        String refundId = "QA-REFUND-" + orderRecordId;
        jdbcTemplate.update("""
                INSERT INTO xianyu_refund_case
                (tenant_id,xianyu_account_id,order_record_id,order_id,platform_refund_id,refund_type,
                 after_sales_type,refund_reason,requested_amount,approved_amount,currency_code,evidence_json,
                 applied_time,decision_deadline,seller_decision_deadline,buyer_return_deadline,platform_status,
                 dispute_status,return_status,platform_action_required,latest_status_message,source,sync_status,
                 coverage_status,synced_at,last_request_id)
                VALUES (?,?,?,?,?,'RETURN_REFUND','RETURN_REFUND','QA 退货退款验收夹具',29.90,NULL,'CNY',
                        '{"fixture":true}',NOW(3),DATE_ADD(NOW(3),INTERVAL 2 DAY),
                        DATE_ADD(NOW(3),INTERVAL 2 DAY),DATE_ADD(NOW(3),INTERVAL 7 DAY),'PROCESSING',NULL,
                        'IN_TRANSIT','WAIT_BUYER_RETURN','买家已寄回，等待商家签收','QA_FIXTURE','SUCCEEDED',
                        'FULL',NOW(3),?)
                ON DUPLICATE KEY UPDATE after_sales_type=VALUES(after_sales_type),
                    seller_decision_deadline=VALUES(seller_decision_deadline),
                    buyer_return_deadline=VALUES(buyer_return_deadline),return_status=VALUES(return_status),
                    platform_action_required=VALUES(platform_action_required),latest_status_message=VALUES(latest_status_message),
                    source='QA_FIXTURE',sync_status='SUCCEEDED',coverage_status='FULL',synced_at=NOW(3),
                    last_request_id=VALUES(last_request_id)
                """, tenantId, accountId, orderRecordId, orderId, refundId, request.requestId());
        Long refundCaseId = jdbcTemplate.queryForObject("""
                SELECT id FROM xianyu_refund_case
                 WHERE tenant_id=? AND xianyu_account_id=? AND platform_refund_id=?
                """, Long.class, tenantId, accountId, refundId);
        jdbcTemplate.update("""
                INSERT INTO xianyu_return_shipment
                (tenant_id,refund_case_id,xianyu_account_id,order_record_id,direction,logistics_company_code,
                 logistics_company_name,tracking_number,shipment_status,latest_event,shipped_time,source,
                 coverage_status,synced_at,request_id)
                VALUES (?,?,?,?,'BUYER_TO_SELLER','QA-SF','顺丰速运',?,'IN_TRANSIT',
                        'QA 夹具：运输中，不触达闲鱼平台',NOW(3),'QA_FIXTURE','FULL',NOW(3),?)
                ON DUPLICATE KEY UPDATE shipment_status='IN_TRANSIT',latest_event=VALUES(latest_event),
                    source='QA_FIXTURE',coverage_status='FULL',synced_at=NOW(3)
                """, tenantId, refundCaseId, accountId, orderRecordId, "QA-RETURN-" + orderRecordId,
                "qa-return-fixture-" + orderRecordId);
        jdbcTemplate.update("""
                UPDATE xianyu_goods_order
                   SET refund_status='PROCESSING',refund_amount=NULL,last_sync_request_id=?
                 WHERE tenant_id=? AND id=?
                """, request.requestId(), tenantId, orderRecordId);
        return ResultObject.success(Map.of(
                "safeFixture", true,
                "platformNetworkCalls", false,
                "orderRecordId", orderRecordId,
                "refundCaseId", refundCaseId,
                "detail", orderMatrixService.detail(orderRecordId)));
    }

    public record FixtureRequest(String requestId) {}
}
