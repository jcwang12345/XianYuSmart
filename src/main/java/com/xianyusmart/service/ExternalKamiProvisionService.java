package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.entity.XianyuKamiConfig;
import com.xianyusmart.entity.XianyuKamiExternalRequest;
import com.xianyusmart.entity.XianyuKamiItem;
import com.xianyusmart.enums.KamiStatus;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.mapper.XianyuKamiConfigMapper;
import com.xianyusmart.mapper.XianyuKamiExternalRequestMapper;
import com.xianyusmart.mapper.XianyuKamiItemMapper;
import com.xianyusmart.mapper.SharedAccountLinkMapper;
import com.xianyusmart.service.kami.ExternalKamiGateway;
import com.xianyusmart.service.kami.ExternalKamiResponseParser;
import com.xianyusmart.service.kami.ExternalSupplyPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

/**
 * 外部卡密供货协调服务
 */
@Service
public class ExternalKamiProvisionService {

    private final XianyuKamiExternalRequestMapper requestMapper;
    private final XianyuKamiItemMapper itemMapper;
    private final XianyuKamiConfigMapper configMapper;
    private final SharedAccountLinkMapper sharedAccountLinkMapper;
    private final ExternalKamiGateway gateway;
    private final ExternalKamiResponseParser responseParser;
    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;

    public ExternalKamiProvisionService(XianyuKamiExternalRequestMapper requestMapper,
                                        XianyuKamiItemMapper itemMapper,
                                        XianyuKamiConfigMapper configMapper,
                                        SharedAccountLinkMapper sharedAccountLinkMapper,
                                        ExternalKamiGateway gateway,
                                        ObjectMapper objectMapper,
                                        JdbcTemplate jdbcTemplate,
                                        PlatformTransactionManager transactionManager) {
        this.requestMapper = requestMapper;
        this.itemMapper = itemMapper;
        this.configMapper = configMapper;
        this.sharedAccountLinkMapper = sharedAccountLinkMapper;
        this.gateway = gateway;
        this.responseParser = new ExternalKamiResponseParser(objectMapper);
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public List<XianyuKamiItem> reserve(XianyuKamiConfig config, String orderId,
                                        Long accountId, int quantity) {
        // 短事务锁定配置并建立幂等请求，配置修改或删除无法与供货请求交叉执行。
        Preparation preparation = transactionTemplate.execute(status ->
                prepare(config.getId(), orderId, accountId, quantity));
        if (preparation == null) {
            throw new BusinessException(409, "外部卡密请求准备失败");
        }
        if (preparation.existingItems() != null) {
            return preparation.existingItems();
        }
        XianyuKamiConfig currentConfig = preparation.config();
        XianyuKamiExternalRequest request = preparation.request();

        try {
            String responseBody = gateway.request(
                    currentConfig, orderId, quantity, request.getRequestToken());
            List<String> contents = responseParser.parse(
                    responseBody, currentConfig.getExternalApiResultPath(), quantity);
            XianyuKamiExternalRequest finalRequest = request;
            List<XianyuKamiItem> items = transactionTemplate.execute(status ->
                    saveReservedItems(currentConfig, orderId, accountId, contents, finalRequest));
            if (items == null) {
                throw new BusinessException(409, "外部卡密保存失败");
            }
            return items;
        } catch (ExternalKamiGateway.ExternalKamiException e) {
            String requestStatus = e.isUncertain() ? "REVIEW_REQUIRED" : "FAILED";
            markExternalFailure(currentConfig.getId(), request.getId(), request.getXianyuAccountId(),
                    request.getOrderId(), requestStatus, e.isUncertain(), limit(e.getMessage(), 500));
            throw new BusinessException(409, e.getMessage());
        } catch (IllegalArgumentException e) {
            markExternalFailure(currentConfig.getId(), request.getId(), request.getXianyuAccountId(),
                    request.getOrderId(), "REVIEW_REQUIRED", true, limit(e.getMessage(), 500));
            throw new BusinessException(409, e.getMessage());
        } catch (RuntimeException e) {
            markExternalFailure(currentConfig.getId(), request.getId(), request.getXianyuAccountId(),
                    request.getOrderId(), "REVIEW_REQUIRED", true,
                    limit(e.getMessage() == null ? "外部卡密保存结果需要人工核对" : e.getMessage(), 500));
            throw new BusinessException(409, "外部卡密保存结果需要人工核对");
        }
    }

    private Preparation prepare(Long configId, String orderId, Long accountId, int quantity) {
        XianyuKamiConfig config = configMapper.lockById(configId);
        if (config == null) {
            throw new BusinessException(404, "卡密配置不存在");
        }
        if (!"API".equalsIgnoreCase(config.getSourceType())) {
            throw new BusinessException(409, "卡密供货来源已变更，请重新提交订单");
        }
        if (accountId == null || !sharedAccountLinkMapper.selectKamiConfigAccounts(configId).contains(accountId)) {
            throw new BusinessException(403, "外部卡密仓库不适用于当前闲鱼账号");
        }
        XianyuKamiExternalRequest request = createRequest(config, orderId, accountId, quantity);
        boolean ownsRequest = requestMapper.insertIfAbsent(request) == 1;
        if (!ownsRequest) {
            request = requestMapper.findByOrder(config.getId(), orderId);
            if (request == null) {
                throw new BusinessException(409, "外部卡密请求状态异常");
            }
            if (!Integer.valueOf(quantity).equals(request.getQuantity())) {
                throw new BusinessException(409, "订单卡密数量与已有外部供货请求不一致");
            }
            if (!java.util.Objects.equals(request.getPayloadFingerprint(),
                    fingerprint(config, orderId, accountId, quantity))) {
                throw new BusinessException(409, "订单外部供货请求与已有幂等载荷不一致");
            }
            if ("SUCCESS".equals(request.getRequestStatus())) {
                List<XianyuKamiItem> existing = itemMapper.findByOrderAndStatus(
                        accountId, orderId, KamiStatus.RESERVED.getCode());
                if (existing.size() == quantity) {
                    return new Preparation(config, request, existing);
                }
                throw new BusinessException(409, "外部卡密记录与预占数量不一致，需要人工核对");
            }
            if (!"FAILED".equals(request.getRequestStatus())
                    && !"MANUAL_NOT_SUPPLIED".equals(request.getRequestStatus())
                    && !isStaleProcessing(request)) {
                throw new BusinessException(409, "外部卡密正在获取或等待人工核对");
            }
            ExternalSupplyPolicy.Admission admission = ExternalSupplyPolicy.admit(config, quantity, LocalDateTime.now());
            configMapper.updateById(config);
            if (requestMapper.claimRetry(request.getId()) != 1) {
                throw new BusinessException(409, "外部卡密请求无法重试，需要人工核对");
            }
            requestMapper.updateAdmission(request.getId(), admission.circuitState(), admission.quotaUsedAfter());
            request = requestMapper.findByOrder(config.getId(), orderId);
        } else {
            ExternalSupplyPolicy.Admission admission = ExternalSupplyPolicy.admit(config, quantity, LocalDateTime.now());
            configMapper.updateById(config);
            request = requestMapper.findByOrder(config.getId(), orderId);
            if (request == null || requestMapper.updateAdmission(request.getId(), admission.circuitState(),
                    admission.quotaUsedAfter()) != 1) {
                throw new BusinessException(409, "外部卡密请求准入状态异常");
            }
            request = requestMapper.findByOrder(config.getId(), orderId);
        }
        if (request == null) {
            throw new BusinessException(409, "外部卡密请求状态异常");
        }
        return new Preparation(config, request, null);
    }

    private List<XianyuKamiItem> saveReservedItems(XianyuKamiConfig config, String orderId,
                                                   Long accountId,
                                                   List<String> contents,
                                                   XianyuKamiExternalRequest request) {
        for (int index = 0; index < contents.size(); index++) {
            XianyuKamiItem item = new XianyuKamiItem();
            item.setKamiConfigId(config.getId());
            item.setKamiContent(contents.get(index));
            item.setStatus(KamiStatus.RESERVED.getCode());
            item.setOrderId(orderId);
            item.setReservedAccountId(accountId);
            item.setReservationToken(request.getRequestToken());
            item.setReservationExpireTime(LocalDateTime.now().plusMinutes(30));
            item.setSourceConfigVersion(config.getConfigVersion());
            item.setRowVersion(1L);
            item.setReservedTime(LocalDateTime.now());
            item.setSortOrder(index);
            itemMapper.insert(item);
            inventoryEvent(config.getId(), item.getId(), accountId, orderId, "RESERVED_EXTERNAL",
                    request.getRequestToken(), config.getConfigVersion(), request.getRequestToken(),
                    "EXTERNAL_RESPONSE", "RESERVED", "EXTERNAL_API");
        }
        if (requestMapper.markSuccess(request.getId(), "received " + contents.size() + " item(s)") != 1) {
            throw new IllegalStateException("外部卡密请求状态已变化");
        }
        XianyuKamiConfig locked = configMapper.lockById(config.getId());
        ExternalSupplyPolicy.markSuccess(locked);
        configMapper.updateById(locked);
        return itemMapper.findByOrderAndStatus(accountId, orderId, KamiStatus.RESERVED.getCode());
    }

    private XianyuKamiExternalRequest createRequest(XianyuKamiConfig config, String orderId,
                                                    Long accountId, int quantity) {
        XianyuKamiExternalRequest request = new XianyuKamiExternalRequest();
        if (TenantContext.get() == null) throw new BusinessException(403, "缺少租户上下文");
        request.setTenantId(TenantContext.get());
        request.setKamiConfigId(config.getId());
        request.setXianyuAccountId(accountId);
        request.setOrderId(orderId);
        request.setRequestToken(UUID.randomUUID().toString().replace("-", ""));
        request.setPayloadFingerprint(fingerprint(config, orderId, accountId, quantity));
        request.setQuantity(quantity);
        return request;
    }

    private void markExternalFailure(Long configId, Long requestId, Long accountId, String orderId,
                                     String requestStatus,
                                     boolean uncertain, String errorMessage) {
        transactionTemplate.executeWithoutResult(status -> {
            XianyuKamiConfig config = configMapper.lockById(configId);
            ExternalSupplyPolicy.Failure failure = ExternalSupplyPolicy.markFailure(
                    config, uncertain, LocalDateTime.now());
            configMapper.updateById(config);
            requestMapper.markFailure(requestId, requestStatus, uncertain ? 1 : 0,
                    failure.nextRetryTime(), errorMessage);
            inventoryEvent(configId, null, accountId, orderId,
                    uncertain ? "SUPPLY_UNKNOWN" : "SUPPLY_FAILED", null, config.getConfigVersion(),
                    "external-request:" + requestId,
                    "PROCESSING", uncertain ? "REVIEW_REQUIRED" : "FAILED", "EXTERNAL_API");
        });
    }

    private void inventoryEvent(Long configId, Long itemId, Long accountId, String orderId,
                                String eventType, String reservationToken, Long configVersion,
                                String requestId, String beforeStatus, String afterStatus, String source) {
        Long tenantId = TenantContext.get();
        if (tenantId == null) throw new BusinessException(403, "缺少租户上下文");
        String eventKey = eventType + ":" + configId + ":" + (itemId == null ? "none" : itemId)
                + ":" + requestId;
        jdbcTemplate.update("""
                INSERT IGNORE INTO xianyu_kami_inventory_event
                (tenant_id,kami_config_id,kami_item_id,xianyu_account_id,order_id,event_type,event_key,
                 outcome_state,request_id,reservation_token,config_version,quantity,before_json,after_json,
                 source,operator_user_id,operator_username)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, tenantId, configId, itemId, accountId, orderId, eventType, eventKey,
                "SUPPLY_UNKNOWN".equals(eventType) ? "UNKNOWN" :
                        ("SUPPLY_FAILED".equals(eventType) ? "FAILED" : "LOCAL_SUCCESS"),
                requestId, reservationToken, configVersion, 1,
                "{\"status\":\"" + beforeStatus + "\"}",
                "{\"status\":\"" + afterStatus + "\"}", source,
                UserContext.getUserId(), UserContext.getUsername());
    }

    private String fingerprint(XianyuKamiConfig config, String orderId, Long accountId, int quantity) {
        String canonical = config.getId() + "|" + accountId + "|" + orderId + "|" + quantity
                + "|" + (config.getConfigVersion() == null ? 1L : config.getConfigVersion());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("无法计算外部供货请求指纹", e);
        }
    }

    private boolean isStaleProcessing(XianyuKamiExternalRequest request) {
        return "PROCESSING".equals(request.getRequestStatus())
                && request.getUpdateTime() != null
                && request.getUpdateTime().isBefore(LocalDateTime.now().minusMinutes(2));
    }

    private String limit(String value, int maxLength) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), maxLength));
    }

    private record Preparation(XianyuKamiConfig config,
                               XianyuKamiExternalRequest request,
                               List<XianyuKamiItem> existingItems) {
    }
}
