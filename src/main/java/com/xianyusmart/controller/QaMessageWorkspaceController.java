package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.service.AccountAccessService;
import com.xianyusmart.service.AiHandoffService;
import com.xianyusmart.service.ProductBatchQaMockService;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * IM-01~06 隔离验收夹具。只建立本地会话、决策证据和转人工任务，绝不调用闲鱼或 AI 服务。
 * qa profile、显式 Mock 开关、租户、店铺和 QA 商品前缀必须同时命中。
 */
@Profile("qa")
@RestController
@RequestMapping("/api/qa/message-workspace")
public class QaMessageWorkspaceController {

    private static final Set<String> SCENARIOS = Set.of(
            "HUMAN_REQUEST", "SENSITIVE", "NO_STRATEGY", "LOW_CONFIDENCE", "AI_UNAVAILABLE", "OUTCOME_UNKNOWN");

    private final JdbcTemplate jdbcTemplate;
    private final ProductBatchQaMockService qaMockService;
    private final AccountAccessService accountAccessService;
    private final AiHandoffService handoffService;

    public QaMessageWorkspaceController(JdbcTemplate jdbcTemplate,
                                        ProductBatchQaMockService qaMockService,
                                        AccountAccessService accountAccessService,
                                        AiHandoffService handoffService) {
        this.jdbcTemplate = jdbcTemplate;
        this.qaMockService = qaMockService;
        this.accountAccessService = accountAccessService;
        this.handoffService = handoffService;
    }

    @PostMapping("/fixtures")
    @Transactional
    public ResultObject<Map<String, Object>> fixture(@RequestBody FixtureRequest request) {
        requireEnabled();
        String requestId = validateRequestId(request == null ? null : request.requestId());
        Long accountId = request == null ? null : request.accountId();
        String scenario = request == null || request.scenario() == null
                ? "NO_STRATEGY" : request.scenario().trim().toUpperCase(Locale.ROOT);
        if (!SCENARIOS.contains(scenario)) throw new BusinessException(400, "不支持的 QA 客服场景");

        Long tenantId = TenantContext.get();
        Map<String, Object> configuration = qaMockService.publicConfiguration();
        Long allowedTenantId = ((Number) configuration.get("tenantId")).longValue();
        @SuppressWarnings("unchecked")
        Set<Long> allowedAccounts = (Set<Long>) configuration.get("accountIds");
        String goodsPrefix = String.valueOf(configuration.get("goodsPrefix"));
        if (tenantId == null || !tenantId.equals(allowedTenantId)) {
            throw new BusinessException(403, "当前租户不在隔离 QA 白名单");
        }
        if (accountId == null || !allowedAccounts.contains(accountId)) {
            throw new BusinessException(403, "当前店铺不在隔离 QA 白名单");
        }
        accountAccessService.requireAccess(accountId);

        String suffix = requestId.substring(3).replaceAll("[^A-Za-z0-9]", "-");
        String goodsId = goodsPrefix + "IM-" + scenario;
        if (!qaMockService.isEligible(tenantId, accountId, goodsId)) {
            throw new BusinessException(403, "QA 商品前缀未通过隔离白名单");
        }
        String sessionId = "qa-im-session-" + suffix;
        String messageId = "qa-im-message-" + suffix;
        String buyerId = "qa-buyer-" + suffix;
        String reasonCode = reasonCode(scenario);
        String reasonDetail = reasonDetail(scenario);
        Double confidence = "LOW_CONFIDENCE".equals(scenario) ? 0.120000d : null;
        String model = Set.of("LOW_CONFIDENCE", "AI_UNAVAILABLE").contains(scenario) ? "qa-model" : null;

        jdbcTemplate.update("""
                INSERT INTO xianyu_chat_message
                    (tenant_id,xianyu_account_id,pnm_id,s_id,content_type,msg_content,sender_user_name,
                     sender_user_id,xy_goods_id,complete_msg,message_time)
                VALUES (?,?,?, ?,1,?,'QA 买家',?,?, '{}',?)
                ON DUPLICATE KEY UPDATE msg_content=VALUES(msg_content),message_time=VALUES(message_time)
                """, tenantId, accountId, messageId, sessionId, buyerMessage(scenario), buyerId, goodsId,
                System.currentTimeMillis());
        jdbcTemplate.update("""
                INSERT INTO xianyu_buyer_profile
                    (tenant_id,xianyu_account_id,buyer_user_id,buyer_user_name,last_interaction_time)
                VALUES (?,?,?,'QA 买家',NOW(3))
                ON DUPLICATE KEY UPDATE buyer_user_name=VALUES(buyer_user_name),last_interaction_time=NOW(3)
                """, tenantId, accountId, buyerId);
        jdbcTemplate.update("""
                INSERT INTO xianyu_goods_auto_reply_record
                    (tenant_id,xianyu_account_id,xy_goods_id,s_id,pnm_id,buyer_user_id,buyer_user_name,
                     buyer_message,state,decision_state,confidence_score,model_name,processing_duration_ms,
                     handoff_reason_code,scheduled_time,last_error_code,last_error_message)
                VALUES (?,?,?,?,?,?, 'QA 买家',?,-2,'HUMAN_REQUIRED',?,?,88,?,NOW(3),?,?)
                ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id),decision_state='HUMAN_REQUIRED',
                    confidence_score=VALUES(confidence_score),model_name=VALUES(model_name),
                    handoff_reason_code=VALUES(handoff_reason_code),last_error_code=VALUES(last_error_code),
                    last_error_message=VALUES(last_error_message)
                """, tenantId, accountId, goodsId, sessionId, messageId, buyerId, buyerMessage(scenario),
                confidence, model, reasonCode, reasonCode, reasonDetail);
        Long replyRecordId = jdbcTemplate.queryForObject("""
                SELECT id FROM xianyu_goods_auto_reply_record
                 WHERE tenant_id=? AND xianyu_account_id=? AND pnm_id=?
                """, Long.class, tenantId, accountId, messageId);

        if ("OUTCOME_UNKNOWN".equals(scenario)) {
            jdbcTemplate.update("""
                    INSERT INTO xianyu_message_send_attempt
                        (tenant_id,xianyu_account_id,session_id,recipient_user_id,xy_goods_id,content_type,
                         content_sha256,content_excerpt,request_id,idempotency_key,outcome_state,error_message,
                         operator_user_id,operator_username)
                    VALUES (?,?,?,?,?,'TEXT',REPEAT('0',64),'QA 发送结果未知夹具',?,?, 'UNKNOWN',?, ?,?)
                    ON DUPLICATE KEY UPDATE outcome_state='UNKNOWN',error_message=VALUES(error_message)
                    """, tenantId, accountId, sessionId, buyerId, goodsId, requestId, requestId,
                    "QA Mock：平台请求超时，未发起真实网络请求", UserContext.getUserId(), UserContext.getUsername());
        }

        Map<String, Object> task = handoffService.open(new AiHandoffService.OpenCommand(
                accountId, sessionId, goodsId, buyerId, replyRecordId, reasonCode, reasonDetail,
                confidence, model, 88L, "QA_IM:" + tenantId + ":" + accountId + ":" + requestId,
                requestId));
        return ResultObject.success(Map.of(
                "safeFixture", true,
                "platformNetworkCalls", false,
                "aiNetworkCalls", false,
                "scenario", scenario,
                "accountId", accountId,
                "sessionId", sessionId,
                "goodsId", goodsId,
                "replyRecordId", replyRecordId,
                "handoffTask", task));
    }

    private void requireEnabled() {
        if (!qaMockService.enabled()) throw new BusinessException(404, "隔离 QA Mock 未启用");
    }

    private String validateRequestId(String value) {
        if (value == null || !value.matches("qa-[A-Za-z0-9._:-]{1,67}")) {
            throw new BusinessException(400, "requestId 必须以 qa- 开头且不能超过70个字符");
        }
        return value;
    }

    private String reasonCode(String scenario) {
        return switch (scenario) {
            case "HUMAN_REQUEST" -> "BUYER_REQUESTED_HUMAN";
            case "SENSITIVE" -> "SENSITIVE_OR_HIGH_RISK";
            case "NO_STRATEGY" -> "NO_REPLY_STRATEGY";
            case "LOW_CONFIDENCE" -> "LOW_CONFIDENCE";
            case "AI_UNAVAILABLE" -> "AI_UNAVAILABLE";
            case "OUTCOME_UNKNOWN" -> "MESSAGE_OUTCOME_UNKNOWN";
            default -> throw new BusinessException(400, "不支持的 QA 客服场景");
        };
    }

    private String reasonDetail(String scenario) {
        return switch (scenario) {
            case "HUMAN_REQUEST" -> "QA 夹具：买家明确要求人工客服";
            case "SENSITIVE" -> "QA 夹具：退款争议或账号安全高风险问题";
            case "NO_STRATEGY" -> "QA 夹具：关键词规则和 AI 策略均未生成回复";
            case "LOW_CONFIDENCE" -> "QA 夹具：知识命中最高分低于安全阈值";
            case "AI_UNAVAILABLE" -> "QA 夹具：AI 服务不可用或超时";
            case "OUTCOME_UNKNOWN" -> "QA 夹具：外部尝试状态未知，核对前禁止重发";
            default -> "QA 夹具";
        };
    }

    private String buyerMessage(String scenario) {
        return switch (scenario) {
            case "HUMAN_REQUEST" -> "QA-请帮我转人工客服";
            case "SENSITIVE" -> "QA-我需要人工处理退款争议";
            case "NO_STRATEGY" -> "QA-这是一个没有命中任何规则的问题";
            case "LOW_CONFIDENCE" -> "QA-知识库置信度不足的问题";
            case "AI_UNAVAILABLE" -> "QA-AI 服务不可用时的问题";
            case "OUTCOME_UNKNOWN" -> "QA-请核对上一条消息是否已经发出";
            default -> "QA-客服验收消息";
        };
    }

    public record FixtureRequest(Long accountId, String scenario, String requestId) {}
}
