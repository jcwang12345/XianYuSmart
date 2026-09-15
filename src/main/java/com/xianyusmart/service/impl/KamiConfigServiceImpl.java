package com.xianyusmart.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.common.ResultObject;
import com.xianyusmart.controller.dto.*;
import com.xianyusmart.entity.XianyuKamiConfig;
import com.xianyusmart.entity.XianyuKamiItem;
import com.xianyusmart.entity.XianyuKamiUsageRecord;
import com.xianyusmart.entity.XianyuOperationLog;
import com.xianyusmart.enums.KamiStatus;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.mapper.XianyuKamiConfigMapper;
import com.xianyusmart.mapper.XianyuKamiExternalRequestMapper;
import com.xianyusmart.mapper.XianyuKamiItemMapper;
import com.xianyusmart.mapper.XianyuKamiUsageRecordMapper;
import com.xianyusmart.mapper.SharedAccountLinkMapper;
import com.xianyusmart.service.EmailNotifyService;
import com.xianyusmart.service.ExternalKamiProvisionService;
import com.xianyusmart.service.KamiConfigService;
import com.xianyusmart.service.NotificationCenterService;
import com.xianyusmart.service.OperationLogService;
import com.xianyusmart.service.notification.WebhookSecurity;
import com.xianyusmart.service.kami.ExternalSupplyPolicy;
import com.xianyusmart.service.kami.KamiSecretMasker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class KamiConfigServiceImpl implements KamiConfigService {

    @Autowired
    private XianyuKamiConfigMapper kamiConfigMapper;

    @Autowired
    private XianyuKamiExternalRequestMapper kamiExternalRequestMapper;

    @Autowired
    private XianyuAccountMapper xianyuAccountMapper;

    @Autowired
    private XianyuKamiItemMapper kamiItemMapper;

    @Autowired
    private XianyuKamiUsageRecordMapper kamiUsageRecordMapper;

    @Autowired
    private SharedAccountLinkMapper sharedAccountLinkMapper;

    @Autowired
    private EmailNotifyService emailNotifyService;

    @Autowired
    private ExternalKamiProvisionService externalKamiProvisionService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private NotificationCenterService notificationCenterService;

    @Autowired
    private OperationLogService operationLogService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ConcurrentHashMap<Long, Long> stockOutEmailSentTime = new ConcurrentHashMap<>();

    private static final long STOCK_OUT_EMAIL_INTERVAL_MS = 10 * 60 * 1000L;

    @Override
    @Transactional
    public ResultObject<KamiConfigRespDTO> createOrUpdateConfig(KamiConfigReqDTO reqDTO) {
        if (reqDTO == null || reqDTO.getRequestId() == null || reqDTO.getRequestId().isBlank()
                || reqDTO.getRequestId().trim().length() > 80) {
            return ResultObject.failed(400, "保存卡密配置需要有效的 requestId");
        }
        try {
            validateSourceConfig(reqDTO);
            List<Long> accountIds = normalizeAccountIds(reqDTO.getXianyuAccountIds(), reqDTO.getXianyuAccountId());
            if (accountIds.isEmpty()) {
                return ResultObject.failed("至少选择一个适用账号");
            }
            for (Long accountId : accountIds) {
                if (xianyuAccountMapper.selectById(accountId) == null) {
                    return ResultObject.failed("闲鱼账号不存在或无权访问");
                }
            }
            String sharingMode = "SHARED".equalsIgnoreCase(reqDTO.getSharingMode()) ? "SHARED" : "PRIVATE";
            if ("PRIVATE".equals(sharingMode)) {
                accountIds = List.of(accountIds.get(0));
            }
            XianyuKamiConfig config;
            Map<String, Object> before = new LinkedHashMap<>();
            if (reqDTO.getId() != null) {
                config = kamiConfigMapper.lockById(reqDTO.getId());
                if (config == null) {
                    return ResultObject.failed("卡密配置不存在");
                }
                if (supplyConfigurationChanged(config, reqDTO) && hasUnsettledSupply(config.getId())) {
                    return ResultObject.failed("存在预占库存或待核对供货请求，暂不能修改供货来源");
                }
                before = configAuditView(config, sharedAccountLinkMapper.selectKamiConfigAccounts(config.getId()));
            } else {
                config = new XianyuKamiConfig();
                config.setXianyuAccountId(accountIds.get(0));
                config.setTenantId(xianyuAccountMapper.selectById(accountIds.get(0)).getTenantId());
                config.setTotalCount(0);
                config.setUsedCount(0);
                config.setConfigVersion(1L);
            }
            config.setSharingMode(sharingMode);
            config.setXianyuAccountId(accountIds.get(0));
            if (reqDTO.getAliasName() != null) {
                config.setAliasName(reqDTO.getAliasName());
            }
            config.setSourceType(reqDTO.getSourceType() == null ? "LOCAL" : reqDTO.getSourceType().trim().toUpperCase());
            config.setExternalApiUrl(reqDTO.getExternalApiUrl());
            if (reqDTO.getId() == null || (reqDTO.getExternalApiHeaders() != null
                    && !reqDTO.getExternalApiHeaders().isBlank())) {
                config.setExternalApiHeaders(reqDTO.getExternalApiHeaders());
            }
            if (reqDTO.getId() == null || (reqDTO.getExternalApiBody() != null
                    && !reqDTO.getExternalApiBody().isBlank())) {
                config.setExternalApiBody(reqDTO.getExternalApiBody());
            }
            config.setExternalApiResultPath(reqDTO.getExternalApiResultPath());
            config.setExternalApiTimeoutSeconds(reqDTO.getExternalApiTimeoutSeconds() == null
                    ? 10 : reqDTO.getExternalApiTimeoutSeconds());
            validateSupplyPolicy(reqDTO);
            if (reqDTO.getId() == null || reqDTO.getExternalDailyQuota() != null) {
                config.setExternalDailyQuota(reqDTO.getExternalDailyQuota());
            }
            if (reqDTO.getId() == null || reqDTO.getExternalFailureThreshold() != null) {
                config.setExternalFailureThreshold(reqDTO.getExternalFailureThreshold() == null
                        ? 3 : reqDTO.getExternalFailureThreshold());
            }
            if (reqDTO.getId() == null || reqDTO.getExternalCooldownSeconds() != null) {
                config.setExternalCooldownSeconds(reqDTO.getExternalCooldownSeconds() == null
                        ? 300 : reqDTO.getExternalCooldownSeconds());
            }
            if (config.getExternalCircuitState() == null) config.setExternalCircuitState("CLOSED");
            if (config.getExternalConsecutiveFailures() == null) config.setExternalConsecutiveFailures(0);
            if (config.getExternalQuotaUsed() == null) config.setExternalQuotaUsed(0);
            if (reqDTO.getAlertEnabled() != null) {
                config.setAlertEnabled(reqDTO.getAlertEnabled());
            }
            if (reqDTO.getAlertThresholdType() != null) {
                config.setAlertThresholdType(reqDTO.getAlertThresholdType());
            }
            if (reqDTO.getAlertThresholdValue() != null) {
                config.setAlertThresholdValue(reqDTO.getAlertThresholdValue());
            }
            if (reqDTO.getAlertEmail() != null) {
                config.setAlertEmail(reqDTO.getAlertEmail());
            }
            if (reqDTO.getId() != null) {
                config.setConfigVersion((config.getConfigVersion() == null ? 1L : config.getConfigVersion()) + 1L);
                kamiConfigMapper.updateById(config);
            } else {
                kamiConfigMapper.insert(config);
            }
            replaceAccounts(config.getId(), config.getTenantId(), accountIds);
            Map<String, Object> after = configAuditView(config, accountIds);
            XianyuOperationLog audit = new XianyuOperationLog();
            audit.setXianyuAccountId(config.getXianyuAccountId());
            audit.setOperationType(reqDTO.getId() == null ? "KAMI_CONFIG_CREATE" : "KAMI_CONFIG_UPDATE");
            audit.setOperationModule("卡密库存");
            audit.setOperationDesc(reqDTO.getId() == null ? "创建卡密仓库" : "更新卡密仓库配置");
            audit.setOperationStatus(1);
            audit.setTargetType("KAMI_CONFIG");
            audit.setTargetId(String.valueOf(config.getId()));
            audit.setRequestId(reqDTO.getRequestId().trim());
            audit.setIdempotencyKey(reqDTO.getRequestId().trim());
            audit.setOutcomeState("LOCAL_SUCCESS");
            audit.setDataSource("LOCAL");
            audit.setRequestParams(objectMapper.writeValueAsString(Map.of(
                    "configId", config.getId(), "accountIds", accountIds)));
            audit.setResponseResult(objectMapper.writeValueAsString(Map.of(
                    "configId", config.getId(), "configVersion", config.getConfigVersion())));
            audit.setFieldDiffJson(objectMapper.writeValueAsString(Map.of("before", before, "after", after)));
            operationLogService.logRequired(audit);
            return ResultObject.success(toConfigRespDTO(config));
        } catch (Exception e) {
            try {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            } catch (Exception ignored) {
                // Unit invocation outside a Spring transaction has nothing to roll back.
            }
            log.error("创建/更新卡密配置失败", e);
            return ResultObject.failed("创建/更新卡密配置失败: " + e.getMessage());
        }
    }

    @Override
    public ResultObject<List<KamiConfigRespDTO>> getConfigsByAccountId(Long xianyuAccountId) {
        try {
            List<XianyuKamiConfig> configs = kamiConfigMapper.findByAccountId(xianyuAccountId);
            List<KamiConfigRespDTO> result = configs.stream()
                    .map(this::toConfigRespDTO)
                    .collect(Collectors.toList());
            return ResultObject.success(result);
        } catch (Exception e) {
            log.error("查询卡密配置列表失败", e);
            return ResultObject.failed("查询卡密配置列表失败: " + e.getMessage());
        }
    }

    @Override
    public ResultObject<KamiConfigRespDTO> getConfigById(Long id) {
        try {
            XianyuKamiConfig config = kamiConfigMapper.selectById(id);
            if (config == null) {
                return ResultObject.failed("卡密配置不存在");
            }
            return ResultObject.success(toConfigRespDTO(config));
        } catch (Exception e) {
            log.error("查询卡密配置失败", e);
            return ResultObject.failed("查询卡密配置失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public ResultObject<Void> deleteConfig(Long id, String requestId) {
        try {
            String normalizedRequestId = requireMutationRequestId(requestId);
            XianyuKamiConfig config = kamiConfigMapper.lockById(id);
            if (config == null) {
                return ResultObject.failed("卡密配置不存在");
            }
            List<XianyuKamiItem> items = kamiItemMapper.findByConfigId(id);
            // 预占库存和待复核外部请求必须先完成处理，避免删除后丢失供应审计链路。
            if (hasUnsettledSupply(id)) {
                return ResultObject.failed("存在预占库存或待核对供货请求，请处理后再删除");
            }
            for (XianyuKamiItem item : items) {
                kamiItemMapper.deleteById(item.getId());
            }
            kamiConfigMapper.deleteById(id);
            auditInventoryMutation(config, "KAMI_CONFIG_DELETE", "删除卡密仓库",
                    normalizedRequestId, "KAMI_CONFIG", String.valueOf(id), items.size(),
                    "EXISTS", "DELETED");
            return ResultObject.success(null);
        } catch (Exception e) {
            rollbackCurrentTransaction();
            log.error("删除卡密配置失败", e);
            return ResultObject.failed("删除卡密配置失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public ResultObject<KamiItemRespDTO> addKamiItem(KamiItemReqDTO reqDTO) {
        try {
            String normalizedRequestId = requireMutationRequestId(reqDTO.getRequestId());
            XianyuKamiConfig config = kamiConfigMapper.selectById(reqDTO.getKamiConfigId());
            if (config == null) {
                return ResultObject.failed("卡密配置不存在");
            }
            if ("API".equalsIgnoreCase(config.getSourceType())) {
                return ResultObject.failed("外部接口卡密仓库不支持手动添加库存");
            }
            XianyuKamiItem item = new XianyuKamiItem();
            item.setKamiConfigId(reqDTO.getKamiConfigId());
            String content = reqDTO.getKamiContent().trim();
            item.setKamiContent(content);
            item.setStatus(0);
            item.setSortOrder(kamiItemMapper.countByConfigId(reqDTO.getKamiConfigId()));

            boolean duplicated = kamiItemMapper.countByConfigIdAndContent(reqDTO.getKamiConfigId(), content) > 0;
            if (duplicated) return ResultObject.failed("卡密内容已存在，未重复导入");
            kamiItemMapper.insert(item);
            inventoryEvent(config.getId(), item.getId(), config.getXianyuAccountId(), null,
                    "IMPORTED", null, config.getConfigVersion(), normalizedRequestId,
                    "NONE", "AVAILABLE");
            refreshConfigCounts(reqDTO.getKamiConfigId());
            auditInventoryMutation(config, "KAMI_ITEM_ADD", "添加单条卡密",
                    normalizedRequestId, "KAMI_ITEM", String.valueOf(item.getId()), 1,
                    "NONE", "AVAILABLE");
            return ResultObject.success(toItemRespDTO(item, false));
        } catch (Exception e) {
            rollbackCurrentTransaction();
            log.error("添加卡密失败", e);
            return ResultObject.failed("添加卡密失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public ResultObject<Integer> batchImportKamiItems(KamiBatchImportReqDTO reqDTO) {
        try {
            String normalizedRequestId = requireMutationRequestId(reqDTO.getRequestId());
            XianyuKamiConfig config = kamiConfigMapper.selectById(reqDTO.getKamiConfigId());
            if (config == null) {
                return ResultObject.failed("卡密配置不存在");
            }
            if ("API".equalsIgnoreCase(config.getSourceType())) {
                return ResultObject.failed("外部接口卡密仓库不支持批量导入库存");
            }
            String[] lines = reqDTO.getKamiContents().split("\\r?\\n");
            int baseOrder = kamiItemMapper.countByConfigId(reqDTO.getKamiConfigId());
            int added = 0;
            int duplicated = 0;
            java.util.LinkedHashSet<String> uniqueLines = new java.util.LinkedHashSet<>();
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                if (!uniqueLines.add(trimmed)) duplicated++;
            }
            for (String trimmed : uniqueLines) {
                boolean dup = kamiItemMapper.countByConfigIdAndContent(reqDTO.getKamiConfigId(), trimmed) > 0;
                if (dup) {
                    duplicated++;
                    continue;
                }

                XianyuKamiItem item = new XianyuKamiItem();
                item.setKamiConfigId(reqDTO.getKamiConfigId());
                item.setKamiContent(trimmed);
                item.setStatus(0);
                item.setSortOrder(baseOrder + added);
                kamiItemMapper.insert(item);
                inventoryEvent(config.getId(), item.getId(), config.getXianyuAccountId(), null,
                        "IMPORTED", null, config.getConfigVersion(),
                        normalizedRequestId + ":" + (added + 1), "NONE", "AVAILABLE");
                added++;
            }
            refreshConfigCounts(reqDTO.getKamiConfigId());
            auditInventoryMutation(config, "KAMI_BATCH_IMPORT", "批量导入卡密",
                    normalizedRequestId, "KAMI_CONFIG", String.valueOf(config.getId()), added,
                    "UNCHANGED", "INVENTORY_INCREASED");
            String msg = duplicated > 0
                    ? String.format("成功导入%d条，其中重复%d条", added, duplicated)
                    : String.format("成功导入%d条", added);
            return ResultObject.success(added, msg);
        } catch (Exception e) {
            rollbackCurrentTransaction();
            log.error("批量导入卡密失败", e);
            return ResultObject.failed("批量导入卡密失败: " + e.getMessage());
        }
    }

    @Override
    public ResultObject<List<KamiItemRespDTO>> getKamiItemsByConfigId(Long kamiConfigId) {
        try {
            List<XianyuKamiItem> items = kamiItemMapper.findByConfigId(kamiConfigId);
            List<KamiItemRespDTO> result = items.stream()
                    .map(item -> toItemRespDTO(item, false))
                    .collect(Collectors.toList());
            return ResultObject.success(result);
        } catch (Exception e) {
            log.error("查询卡密列表失败", e);
            return ResultObject.failed("查询卡密列表失败: " + e.getMessage());
        }
    }

    @Override
    public ResultObject<List<KamiItemRespDTO>> getKamiItemsByConfigIdWithFilter(KamiItemQueryReqDTO reqDTO) {
        try {
            List<XianyuKamiItem> items = kamiItemMapper.findByConfigIdWithFilter(
                    reqDTO.getKamiConfigId(), 
                    reqDTO.getStatus(), 
                    reqDTO.getKeyword());
            List<KamiItemRespDTO> result = items.stream()
                    .map(item -> toItemRespDTO(item, false))
                    .collect(Collectors.toList());
            return ResultObject.success(result);
        } catch (Exception e) {
            log.error("查询卡密列表失败", e);
            return ResultObject.failed("查询卡密列表失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public ResultObject<Void> deleteKamiItem(Long id, String requestId) {
        try {
            String normalizedRequestId = requireMutationRequestId(requestId);
            XianyuKamiItem item = kamiItemMapper.selectById(id);
            if (item == null) {
                return ResultObject.failed("卡密不存在");
            }
            if (item.getStatus() != null && (item.getStatus() == KamiStatus.RESERVED.getCode()
                    || item.getStatus() == KamiStatus.REVIEW_REQUIRED.getCode())) {
                return ResultObject.failed("预占或待核对卡密不可删除，请先完成释放或人工核对");
            }
            XianyuKamiConfig config = kamiConfigMapper.selectById(item.getKamiConfigId());
            if (config == null) return ResultObject.failed("卡密配置不存在或无权访问");
            String beforeStatus = itemStatusName(item.getStatus());
            inventoryEvent(config.getId(), item.getId(), config.getXianyuAccountId(), item.getOrderId(),
                    "DELETED", null, config.getConfigVersion(), normalizedRequestId,
                    beforeStatus, "DELETED");
            kamiItemMapper.deleteById(id);
            refreshConfigCounts(item.getKamiConfigId());
            auditInventoryMutation(config, "KAMI_ITEM_DELETE", "删除卡密库存项",
                    normalizedRequestId, "KAMI_ITEM", String.valueOf(id), 1,
                    beforeStatus, "DELETED");
            return ResultObject.success(null);
        } catch (Exception e) {
            rollbackCurrentTransaction();
            log.error("删除卡密失败", e);
            return ResultObject.failed("删除卡密失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public ResultObject<Void> resetKamiItem(Long id, String requestId) {
        try {
            String normalizedRequestId = requireMutationRequestId(requestId);
            XianyuKamiItem item = kamiItemMapper.selectById(id);
            if (item == null) return ResultObject.failed("卡密不存在");
            XianyuKamiConfig config = kamiConfigMapper.selectById(item.getKamiConfigId());
            if (config == null) return ResultObject.failed("卡密配置不存在或无权访问");
            int rows = kamiItemMapper.markUnused(id);
            if (rows == 0) {
                return ResultObject.failed("卡密状态重置失败，可能已是未使用状态");
            }
            inventoryEvent(config.getId(), item.getId(), config.getXianyuAccountId(), item.getOrderId(),
                    "RESET_AVAILABLE", null, config.getConfigVersion(), normalizedRequestId,
                    itemStatusName(item.getStatus()), "AVAILABLE");
            refreshConfigCounts(config.getId());
            auditInventoryMutation(config, "KAMI_ITEM_RESET", "重置卡密为可用",
                    normalizedRequestId, "KAMI_ITEM", String.valueOf(id), 1,
                    itemStatusName(item.getStatus()), "AVAILABLE");
            return ResultObject.success(null);
        } catch (Exception e) {
            rollbackCurrentTransaction();
            log.error("重置卡密状态失败", e);
            return ResultObject.failed("重置卡密状态失败: " + e.getMessage());
        }
    }

    @Override
    public XianyuKamiItem acquireKami(Long kamiConfigId, String orderId) {
        try {
            XianyuKamiConfig config = kamiConfigMapper.selectById(kamiConfigId);
            List<XianyuKamiItem> items = reserveKami(kamiConfigId, orderId,
                    config == null ? null : config.getXianyuAccountId(), 1);
            return items.isEmpty() ? null : items.getFirst();
        } catch (BusinessException e) {
            return null;
        }
    }

    @Override
    public List<XianyuKamiItem> reserveKami(Long kamiConfigId, String orderId, Long accountId, int quantity) {
        if (kamiConfigId == null || orderId == null || orderId.isBlank() || accountId == null || quantity < 1) {
            throw new BusinessException(400, "卡密预占参数无效");
        }

        XianyuKamiConfig sourceConfig = kamiConfigMapper.selectById(kamiConfigId);
        if (sourceConfig == null) {
            throw new BusinessException(404, "卡密配置不存在");
        }
        if ("API".equalsIgnoreCase(sourceConfig.getSourceType())) {
            return externalKamiProvisionService.reserve(sourceConfig, orderId, accountId, quantity);
        }
        List<XianyuKamiItem> reserved = new TransactionTemplate(transactionManager).execute(status ->
                reserveLocalKami(kamiConfigId, orderId, accountId, quantity));
        if (reserved == null) {
            throw new BusinessException(409, "卡密预占失败");
        }
        return reserved;
    }

    private List<XianyuKamiItem> reserveLocalKami(Long kamiConfigId, String orderId,
                                                   Long accountId, int quantity) {
        // 账号、仓库、订单三者共同限定预占，避免多账号出现相同订单号时串单。
        List<XianyuKamiItem> existing = kamiItemMapper.lockReservedByOrder(kamiConfigId, accountId, orderId);
        if (!existing.isEmpty()) {
            boolean allReserved = existing.stream()
                    .allMatch(item -> item.getStatus() == KamiStatus.RESERVED.getCode());
            if (!allReserved) {
                throw new BusinessException(409, "订单卡密已交付或正在待核对");
            }
            if (existing.size() == quantity) {
                return existing;
            }
            throw new BusinessException(409, "订单卡密数量与已有预占不一致");
        }

        // 同一卡密库短事务串行预占，避免同订单在租约交叠时重复取卡。
        XianyuKamiConfig config = kamiConfigMapper.lockById(kamiConfigId);
        if (config == null) {
            throw new BusinessException(404, "卡密配置不存在");
        }

        List<XianyuKamiItem> items = kamiItemMapper.lockAvailable(kamiConfigId, quantity);
        if (items.size() != quantity) {
            sendStockOutEmailIfNeeded(config, kamiConfigId, orderId);
            throw new BusinessException(409, "卡密库存不足");
        }

        List<Long> itemIds = items.stream().map(XianyuKamiItem::getId).toList();
        String reservationToken = java.util.UUID.randomUUID().toString().replace("-", "");
        Long configVersion = config.getConfigVersion() == null ? 1L : config.getConfigVersion();
        if (kamiItemMapper.reserve(itemIds, accountId, orderId, reservationToken, configVersion) != quantity) {
            throw new BusinessException(409, "卡密预占冲突");
        }
        items.forEach(item -> {
            item.setStatus(KamiStatus.RESERVED.getCode());
            item.setOrderId(orderId);
            item.setReservedAccountId(accountId);
            item.setReservationToken(reservationToken);
            item.setReservationExpireTime(LocalDateTime.now().plusMinutes(30));
            item.setSourceConfigVersion(configVersion);
            inventoryEvent(config.getId(), item.getId(), accountId, orderId, "RESERVED",
                    reservationToken, configVersion, reservationToken, "AVAILABLE", "RESERVED");
        });
        return items;
    }

    @Override
    @Transactional
    public void commitReservation(String orderId, Long accountId, String xyGoodsId,
                                  String buyerUserId, String buyerUserName) {
        List<XianyuKamiItem> reservedItems = kamiItemMapper.findByOrderAndStatus(
                accountId, orderId, KamiStatus.RESERVED.getCode());
        if (reservedItems.isEmpty()) {
            return;
        }

        if (kamiItemMapper.commitReservation(accountId, orderId) != reservedItems.size()) {
            throw new BusinessException(409, "卡密交付提交冲突");
        }

        for (int index = 0; index < reservedItems.size(); index++) {
            XianyuKamiItem item = reservedItems.get(index);
            XianyuKamiUsageRecord usageRecord = new XianyuKamiUsageRecord();
            usageRecord.setKamiConfigId(item.getKamiConfigId());
            usageRecord.setKamiItemId(item.getId());
            usageRecord.setXianyuAccountId(accountId);
            usageRecord.setXyGoodsId(xyGoodsId);
            usageRecord.setOrderId(orderId);
            usageRecord.setReservationToken(item.getReservationToken());
            usageRecord.setConfigVersion(item.getSourceConfigVersion());
            usageRecord.setRequestId("delivery:" + accountId + ":" + orderId);
            usageRecord.setDeliveryIndex(index + 1);
            usageRecord.setDeliveryStatus(KamiStatus.DELIVERED.name());
            usageRecord.setBuyerUserId(buyerUserId);
            usageRecord.setBuyerUserName(buyerUserName);
            usageRecord.setKamiContent(item.getKamiContent());
            kamiUsageRecordMapper.insert(usageRecord);
            inventoryEvent(item.getKamiConfigId(), item.getId(), accountId, orderId, "CONSUMED",
                    item.getReservationToken(), item.getSourceConfigVersion(), usageRecord.getRequestId(),
                    "RESERVED", "DELIVERED");
        }

        reservedItems.stream().map(XianyuKamiItem::getKamiConfigId).distinct().forEach(configId -> {
            refreshConfigCounts(configId);
            XianyuKamiConfig config = kamiConfigMapper.selectById(configId);
            if (config != null) {
                checkAndSendAlert(config, configId);
            }
        });
    }

    @Override
    @Transactional
    public void releaseReservation(String orderId, Long accountId) {
        if (orderId != null && !orderId.isBlank() && accountId != null) {
            List<XianyuKamiItem> reserved = kamiItemMapper.findByOrderAndStatus(
                    accountId, orderId, KamiStatus.RESERVED.getCode());
            kamiItemMapper.releaseReservation(accountId, orderId);
            reserved.forEach(item -> inventoryEvent(item.getKamiConfigId(), item.getId(), accountId,
                    orderId, "RELEASED", item.getReservationToken(), item.getSourceConfigVersion(),
                    "release:" + accountId + ":" + orderId, "RESERVED", "AVAILABLE"));
        }
    }

    @Override
    @Transactional
    public void markReservationReviewRequired(String orderId, Long accountId) {
        if (orderId != null && !orderId.isBlank() && accountId != null) {
            List<XianyuKamiItem> reserved = kamiItemMapper.findByOrderAndStatus(
                    accountId, orderId, KamiStatus.RESERVED.getCode());
            kamiItemMapper.markReservationReviewRequired(accountId, orderId);
            reserved.forEach(item -> inventoryEvent(item.getKamiConfigId(), item.getId(), accountId,
                    orderId, "REVIEW_REQUIRED", item.getReservationToken(), item.getSourceConfigVersion(),
                    "review:" + accountId + ":" + orderId, "RESERVED", "REVIEW_REQUIRED"));
        }
    }

    private void sendStockOutEmailIfNeeded(XianyuKamiConfig config, Long kamiConfigId, String orderId) {
        Long lastSentTime = stockOutEmailSentTime.get(kamiConfigId);
        long now = System.currentTimeMillis();
        if (lastSentTime != null && (now - lastSentTime) < STOCK_OUT_EMAIL_INTERVAL_MS) {
            log.debug("卡密库存不足邮件10分钟内已发送过，跳过: configId={}", kamiConfigId);
            return;
        }
        stockOutEmailSentTime.put(kamiConfigId, now);
        String configName = config.getAliasName() != null ? config.getAliasName() : "卡密配置" + kamiConfigId;
        notificationCenterService.dispatch("KAMI_STOCK_LOW", config.getXianyuAccountId(),
                "卡密库存不足", configName + " 已无可用卡密",
                Map.of("configId", kamiConfigId, "orderId", orderId == null ? "" : orderId));
        emailNotifyService.sendKamiStockOutEmail(config.getAlertEmail(), configName, orderId);
    }

    @Override
    public XianyuKamiConfig getConfig(Long kamiConfigId) {
        return kamiConfigMapper.selectById(kamiConfigId);
    }

    @Override
    @Transactional
    public ResultObject<List<KamiItemRespDTO>> exportKamiItems(KamiExportReqDTO reqDTO) {
        try {
            if (reqDTO == null || reqDTO.getKamiConfigId() == null || reqDTO.getRequestId() == null
                    || reqDTO.getRequestId().isBlank() || reqDTO.getRequestId().length() > 80) {
                throw new BusinessException(400, "卡密导出需要有效的 requestId");
            }
            XianyuKamiConfig config = kamiConfigMapper.selectById(reqDTO.getKamiConfigId());
            if (config == null) throw new BusinessException(404, "卡密配置不存在");
            List<XianyuKamiItem> items = new ArrayList<>();
            boolean includeUnused = reqDTO.getIncludeUnused() != null && reqDTO.getIncludeUnused();
            boolean includeUsed = reqDTO.getIncludeUsed() != null && reqDTO.getIncludeUsed();

            if (includeUnused && includeUsed) {
                items = kamiItemMapper.findByConfigId(reqDTO.getKamiConfigId());
            } else if (includeUnused) {
                items = kamiItemMapper.findByConfigIdAndStatus(reqDTO.getKamiConfigId(), 0);
            } else if (includeUsed) {
                items = kamiItemMapper.findByConfigIdAndStatus(reqDTO.getKamiConfigId(), 1);
            }

            List<KamiItemRespDTO> result = items.stream()
                    .map(item -> toItemRespDTO(item, true))
                    .collect(Collectors.toList());
            XianyuOperationLog audit = new XianyuOperationLog();
            audit.setXianyuAccountId(config.getXianyuAccountId());
            audit.setOperationType("KAMI_EXPORT");
            audit.setOperationModule("卡密库存");
            audit.setOperationDesc("导出卡密库存（敏感内容）");
            audit.setOperationStatus(1);
            audit.setTargetType("KAMI_CONFIG");
            audit.setTargetId(String.valueOf(config.getId()));
            audit.setRequestId(reqDTO.getRequestId().trim());
            audit.setIdempotencyKey(reqDTO.getRequestId().trim());
            audit.setOutcomeState("LOCAL_SUCCESS");
            audit.setDataSource("LOCAL");
            audit.setRequestParams("{\"includeUnused\":" + includeUnused
                    + ",\"includeUsed\":" + includeUsed + "}");
            audit.setResponseResult("{\"exportedCount\":" + result.size() + "}");
            operationLogService.logRequired(audit);
            return ResultObject.success(result);
        } catch (Exception e) {
            log.error("导出卡密失败", e);
            return ResultObject.failed("导出卡密失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public ResultObject<KamiConfigRespDTO> resetExternalCircuit(Long kamiConfigId, String requestId) {
        if (kamiConfigId == null || requestId == null || requestId.isBlank() || requestId.length() > 80) {
            throw new BusinessException(400, "重置熔断需要有效的 requestId");
        }
        XianyuKamiConfig config = kamiConfigMapper.lockById(kamiConfigId);
        if (config == null) throw new BusinessException(404, "卡密配置不存在");
        String normalizedRequestId = requestId.trim();
        String resetEventKey = "CIRCUIT_RESET:" + kamiConfigId + ":none:" + normalizedRequestId;
        Integer existing = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM xianyu_kami_inventory_event
                 WHERE tenant_id=? AND event_key=?
                """, Integer.class, com.xianyusmart.context.TenantContext.get(), resetEventKey);
        if (existing != null && existing > 0) {
            return ResultObject.success(toConfigRespDTO(config), "幂等重放：熔断已重置");
        }
        String beforeState = config.getExternalCircuitState() == null ? "CLOSED" : config.getExternalCircuitState();
        int beforeFailures = config.getExternalConsecutiveFailures() == null
                ? 0 : config.getExternalConsecutiveFailures();
        ExternalSupplyPolicy.reset(config);
        kamiConfigMapper.updateById(config);
        inventoryEvent(config.getId(), null, config.getXianyuAccountId(), null, "CIRCUIT_RESET",
                null, config.getConfigVersion(), normalizedRequestId, beforeState, "CLOSED");

        XianyuOperationLog audit = new XianyuOperationLog();
        audit.setXianyuAccountId(config.getXianyuAccountId());
        audit.setOperationType("EXTERNAL_SUPPLY_CIRCUIT_RESET");
        audit.setOperationModule("卡密库存");
        audit.setOperationDesc("人工重置外部供货熔断");
        audit.setOperationStatus(1);
        audit.setTargetType("KAMI_CONFIG");
        audit.setTargetId(String.valueOf(config.getId()));
        audit.setRequestId(normalizedRequestId);
        audit.setIdempotencyKey(normalizedRequestId);
        audit.setOutcomeState("LOCAL_SUCCESS");
        audit.setDataSource("LOCAL");
        audit.setFieldDiffJson("{\"externalCircuitState\":{\"before\":\"" + beforeState
                + "\",\"after\":\"CLOSED\"},\"externalConsecutiveFailures\":{\"before\":"
                + beforeFailures + ",\"after\":0}}");
        operationLogService.logRequired(audit);
        return ResultObject.success(toConfigRespDTO(config));
    }

    @Override
    public ResultObject<Map<String, Object>> getInventoryEvents(Long kamiConfigId, Integer page, Integer pageSize) {
        if (kamiConfigId == null) throw new BusinessException(400, "卡密配置ID不能为空");
        XianyuKamiConfig config = kamiConfigMapper.selectById(kamiConfigId);
        if (config == null) throw new BusinessException(404, "卡密配置不存在或无权访问");
        int normalizedPage = page == null || page < 1 ? 1 : page;
        int normalizedSize = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);
        int offset = (normalizedPage - 1) * normalizedSize;
        Long tenantId = com.xianyusmart.context.TenantContext.get();
        Integer total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM xianyu_kami_inventory_event
                 WHERE tenant_id=? AND kami_config_id=?
                """, Integer.class, tenantId, kamiConfigId);
        List<Map<String, Object>> events = jdbcTemplate.queryForList("""
                SELECT id,kami_item_id AS kamiItemId,xianyu_account_id AS accountId,order_id AS orderId,
                       event_type AS eventType,outcome_state AS outcomeState,request_id AS requestId,
                       config_version AS configVersion,quantity,
                       before_json AS beforeJson,after_json AS afterJson,source,
                       operator_user_id AS operatorUserId,operator_username AS operatorUsername,
                       created_time AS createdTime
                  FROM xianyu_kami_inventory_event
                 WHERE tenant_id=? AND kami_config_id=?
                 ORDER BY id DESC LIMIT ? OFFSET ?
                """, tenantId, kamiConfigId, normalizedSize, offset);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("configId", kamiConfigId);
        result.put("configVersion", config.getConfigVersion());
        result.put("page", normalizedPage);
        result.put("pageSize", normalizedSize);
        result.put("total", total == null ? 0 : total);
        result.put("events", events);
        return ResultObject.success(result);
    }

    @Override
    public ResultObject<Map<String, Object>> getExternalRequests(Long kamiConfigId, String status,
                                                                  Integer page, Integer pageSize) {
        if (kamiConfigId == null) throw new BusinessException(400, "卡密配置ID不能为空");
        XianyuKamiConfig config = kamiConfigMapper.selectById(kamiConfigId);
        if (config == null) throw new BusinessException(404, "卡密配置不存在或无权访问");
        int normalizedPage = page == null || page < 1 ? 1 : page;
        int normalizedSize = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);
        String normalizedStatus = status == null || status.isBlank() ? "ALL" : status.trim().toUpperCase();
        Set<String> allowed = Set.of("ALL", "PROCESSING", "FAILED", "REVIEW_REQUIRED", "SUCCESS",
                "MANUAL_NOT_SUPPLIED", "MANUAL_SUPPLIED_REVIEW");
        if (!allowed.contains(normalizedStatus)) throw new BusinessException(400, "外部供货状态筛选无效");
        Long tenantId = com.xianyusmart.context.TenantContext.get();
        String statusClause = "ALL".equals(normalizedStatus) ? "" : " AND request_status=?";
        Object[] countArgs = "ALL".equals(normalizedStatus)
                ? new Object[]{tenantId, kamiConfigId}
                : new Object[]{tenantId, kamiConfigId, normalizedStatus};
        Integer total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM xianyu_kami_external_request
                 WHERE tenant_id=? AND kami_config_id=?
                """ + statusClause, Integer.class, countArgs);
        int offset = (normalizedPage - 1) * normalizedSize;
        List<Object> queryArgs = new ArrayList<>(List.of(tenantId, kamiConfigId));
        if (!"ALL".equals(normalizedStatus)) queryArgs.add(normalizedStatus);
        queryArgs.add(normalizedSize);
        queryArgs.add(offset);
        List<Map<String, Object>> requests = jdbcTemplate.queryForList("""
                SELECT request.id,request.xianyu_account_id AS accountId,request.order_id AS orderId,
                       request.quantity,request.request_status AS requestStatus,
                       request.result_unknown AS resultUnknown,request.attempt_count AS attemptCount,
                       request.error_message AS errorMessage,request.next_retry_time AS nextRetryTime,
                       request.circuit_state_at_request AS circuitStateAtRequest,
                       request.quota_used_after AS quotaUsedAfter,
                       request.resolution_decision AS resolutionDecision,
                       request.resolution_note AS resolutionNote,
                       request.resolution_request_id AS resolutionRequestId,
                       request.resolved_time AS resolvedTime,
                       request.create_time AS createTime,request.update_time AS updateTime,
                       (SELECT COUNT(*) FROM xianyu_kami_item item
                         WHERE item.kami_config_id=request.kami_config_id
                           AND item.reserved_account_id=request.xianyu_account_id
                           AND item.order_id=request.order_id AND item.status=3) AS attachedReviewCount
                  FROM xianyu_kami_external_request request
                 WHERE request.tenant_id=? AND request.kami_config_id=?
                """ + statusClause + " ORDER BY request.id DESC LIMIT ? OFFSET ?", queryArgs.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configId", kamiConfigId);
        result.put("page", normalizedPage);
        result.put("pageSize", normalizedSize);
        result.put("total", total == null ? 0 : total);
        result.put("requests", requests);
        result.put("secretFieldsReturned", false);
        result.put("dataNotice", "仅返回供货状态和人工处置证据；请求密钥、幂等令牌、载荷指纹和卡密内容不回显。");
        return ResultObject.success(result);
    }

    @Override
    public ResultObject<Map<String, Object>> previewExternalResolution(Long externalRequestId, String decision) {
        Map<String, Object> request = requireExternalRequest(externalRequestId, false);
        String normalizedDecision = normalizeResolutionDecision(decision);
        requireUnknownExternalRequest(request);
        int quantity = ((Number) request.get("quantity")).intValue();
        String orderId = String.valueOf(request.get("orderId"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("externalRequestId", externalRequestId);
        result.put("configId", request.get("configId"));
        result.put("accountId", request.get("accountId"));
        result.put("orderId", orderId);
        result.put("quantity", quantity);
        result.put("decision", normalizedDecision);
        result.put("requiredCardCount", "CONFIRMED_SUPPLIED".equals(normalizedDecision) ? quantity : 0);
        result.put("platformWrite", "NOT_PERFORMED");
        result.put("confirmationText", resolutionConfirmation(normalizedDecision, orderId, quantity));
        result.put("effect", "CONFIRMED_SUPPLIED".equals(normalizedDecision)
                ? "附加卡密将进入待人工核对，不会自动发送给买家"
                : "清除结果未知并允许原订单在下一次调度中安全重试供货");
        return ResultObject.success(result);
    }

    @Override
    @Transactional
    public ResultObject<Map<String, Object>> resolveExternalRequest(Long externalRequestId, String decision,
                                                                    String confirmationText,
                                                                    List<String> cardContents, String note,
                                                                    String requestId) {
        String normalizedRequestId = requireResolutionRequestId(requestId);
        String normalizedDecision = normalizeResolutionDecision(decision);
        Map<String, Object> request = requireExternalRequest(externalRequestId, true);
        Object previousResolutionRequestId = request.get("resolutionRequestId");
        if (previousResolutionRequestId != null) {
            if (normalizedRequestId.equals(String.valueOf(previousResolutionRequestId))
                    && normalizedDecision.equals(String.valueOf(request.get("resolutionDecision")))) {
                return ResultObject.success(Map.of("idempotentReplay", true,
                        "externalRequestId", externalRequestId,
                        "requestStatus", request.get("requestStatus")));
            }
            throw new BusinessException(409, "该外部供货请求已经人工处置，不能覆盖原结论");
        }
        requireUnknownExternalRequest(request);
        Long tenantId = com.xianyusmart.context.TenantContext.get();
        Integer requestIdUsed = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM xianyu_kami_external_request
                 WHERE tenant_id=? AND resolution_request_id=? AND id<>?
                """, Integer.class, tenantId, normalizedRequestId, externalRequestId);
        if (requestIdUsed != null && requestIdUsed > 0) {
            throw new BusinessException(409, "requestId 已用于其他人工供货处置");
        }
        int quantity = ((Number) request.get("quantity")).intValue();
        String orderId = String.valueOf(request.get("orderId"));
        String expectedConfirmation = resolutionConfirmation(normalizedDecision, orderId, quantity);
        if (!expectedConfirmation.equals(confirmationText)) {
            throw new BusinessException(400, "确认范围已变化，请重新预检人工处置");
        }
        Long configId = ((Number) request.get("configId")).longValue();
        Long accountId = ((Number) request.get("accountId")).longValue();
        XianyuKamiConfig config = kamiConfigMapper.lockById(configId);
        if (config == null) throw new BusinessException(404, "卡密配置不存在或无权访问");
        String requestStatus;
        int attachedCount = 0;
        if ("CONFIRMED_SUPPLIED".equals(normalizedDecision)) {
            List<String> normalizedCards = normalizeManualCards(cardContents, quantity, configId);
            int baseOrder = kamiItemMapper.countByConfigId(configId);
            for (int index = 0; index < normalizedCards.size(); index++) {
                XianyuKamiItem item = new XianyuKamiItem();
                item.setKamiConfigId(configId);
                item.setKamiContent(normalizedCards.get(index));
                item.setStatus(KamiStatus.REVIEW_REQUIRED.getCode());
                item.setOrderId(orderId);
                item.setReservedAccountId(accountId);
                item.setSourceConfigVersion(config.getConfigVersion());
                item.setRowVersion(1L);
                item.setSortOrder(baseOrder + index);
                kamiItemMapper.insert(item);
                inventoryEvent(configId, item.getId(), accountId, orderId, "MANUAL_SUPPLY_ATTACHED",
                        null, config.getConfigVersion(), normalizedRequestId + ":" + (index + 1),
                        "SUPPLY_UNKNOWN", "REVIEW_REQUIRED");
            }
            attachedCount = normalizedCards.size();
            requestStatus = "MANUAL_SUPPLIED_REVIEW";
        } else {
            requestStatus = "MANUAL_NOT_SUPPLIED";
            inventoryEvent(configId, null, accountId, orderId, "MANUAL_SUPPLY_NOT_SUPPLIED",
                    null, config.getConfigVersion(), normalizedRequestId,
                    "SUPPLY_UNKNOWN", "SAFE_TO_RETRY");
        }
        int updated = jdbcTemplate.update("""
                UPDATE xianyu_kami_external_request
                   SET request_status=?,result_unknown=0,next_retry_time=NULL,
                       resolution_decision=?,resolution_note=?,resolution_request_id=?,
                       resolved_by=?,resolved_time=NOW(3),update_time=NOW(3)
                 WHERE tenant_id=? AND id=? AND resolution_request_id IS NULL
                   AND (result_unknown=1 OR request_status='REVIEW_REQUIRED')
                """, requestStatus, normalizedDecision, limitText(note, 500), normalizedRequestId,
                com.xianyusmart.context.UserContext.getUserId(), tenantId, externalRequestId);
        if (updated != 1) throw new BusinessException(409, "供货请求状态已经变化，请刷新后重新核对");
        refreshConfigCounts(configId);

        XianyuOperationLog audit = new XianyuOperationLog();
        audit.setXianyuAccountId(accountId);
        audit.setOperationType("EXTERNAL_SUPPLY_MANUAL_RESOLUTION");
        audit.setOperationModule("卡密库存");
        audit.setOperationDesc("人工核对外部供货结果");
        audit.setOperationStatus(1);
        audit.setTargetType("KAMI_EXTERNAL_REQUEST");
        audit.setTargetId(String.valueOf(externalRequestId));
        audit.setRequestId(normalizedRequestId);
        audit.setIdempotencyKey(normalizedRequestId);
        audit.setOutcomeState("CONFIRMED_SUPPLIED".equals(normalizedDecision)
                ? "MANUAL_REVIEW_REQUIRED" : "MANUAL_CONFIRMED_NOT_SUPPLIED");
        audit.setDataSource("MANUAL_PROVIDER_CONFIRMATION");
        audit.setRequestParams(jsonSafe(Map.of("decision", normalizedDecision,
                "externalRequestId", externalRequestId, "cardCount", attachedCount,
                "cardContentStoredInAudit", false)));
        audit.setResponseResult(jsonSafe(Map.of("requestStatus", requestStatus,
                "platformWrite", false, "attachedReviewCount", attachedCount)));
        operationLogService.logRequired(audit);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("idempotentReplay", false);
        result.put("externalRequestId", externalRequestId);
        result.put("requestStatus", requestStatus);
        result.put("attachedReviewCount", attachedCount);
        result.put("automaticBuyerSend", false);
        result.put("requestId", normalizedRequestId);
        return ResultObject.success(result);
    }

    private Map<String, Object> requireExternalRequest(Long externalRequestId, boolean forUpdate) {
        if (externalRequestId == null || externalRequestId <= 0) {
            throw new BusinessException(400, "外部供货请求ID无效");
        }
        String lock = forUpdate ? " FOR UPDATE" : "";
        List<Map<String, Object>> requests = jdbcTemplate.queryForList("""
                SELECT request.id,request.kami_config_id AS configId,
                       request.xianyu_account_id AS accountId,request.order_id AS orderId,
                       request.quantity,request.request_status AS requestStatus,
                       request.result_unknown AS resultUnknown,
                       request.resolution_decision AS resolutionDecision,
                       request.resolution_request_id AS resolutionRequestId
                  FROM xianyu_kami_external_request request
                  JOIN xianyu_kami_config config ON config.id=request.kami_config_id
                     AND config.tenant_id=request.tenant_id
                 WHERE request.tenant_id=? AND request.id=?
                """ + lock, com.xianyusmart.context.TenantContext.get(), externalRequestId);
        if (requests.isEmpty()) throw new BusinessException(404, "外部供货请求不存在或无权访问");
        XianyuKamiConfig config = kamiConfigMapper.selectById(((Number) requests.getFirst().get("configId")).longValue());
        if (config == null) throw new BusinessException(404, "卡密配置不存在或无权访问");
        return requests.getFirst();
    }

    private void requireUnknownExternalRequest(Map<String, Object> request) {
        boolean unknown = request.get("resultUnknown") instanceof Number value && value.intValue() == 1;
        if (!unknown && !"REVIEW_REQUIRED".equals(String.valueOf(request.get("requestStatus")))) {
            throw new BusinessException(409, "仅结果未知或待核对的供货请求允许人工处置");
        }
    }

    private String normalizeResolutionDecision(String decision) {
        String normalized = decision == null ? "" : decision.trim().toUpperCase();
        if (!Set.of("CONFIRMED_SUPPLIED", "CONFIRMED_NOT_SUPPLIED").contains(normalized)) {
            throw new BusinessException(400, "人工处置结论必须为已出卡或确认未出卡");
        }
        return normalized;
    }

    private String resolutionConfirmation(String decision, String orderId, int quantity) {
        return "CONFIRMED_SUPPLIED".equals(decision)
                ? "确认供应商已出卡，附加" + quantity + "条卡密并转人工核对订单" + orderId
                : "确认供应商未出卡，可安全重试订单" + orderId;
    }

    private String requireResolutionRequestId(String requestId) {
        if (requestId == null || requestId.isBlank() || requestId.trim().length() > 80) {
            throw new BusinessException(400, "人工处置需要有效的 requestId");
        }
        return requestId.trim();
    }

    private List<String> normalizeManualCards(List<String> cardContents, int quantity, Long configId) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (cardContents != null) {
            for (String card : cardContents) {
                if (card == null || card.trim().isEmpty()) continue;
                String normalized = card.trim();
                if (normalized.length() > 4000) throw new BusinessException(400, "单条卡密内容过长");
                if (!unique.add(normalized)) throw new BusinessException(409, "人工附加的卡密存在重复内容");
            }
        }
        if (unique.size() != quantity) {
            throw new BusinessException(400, "已出卡结论需要提供与请求数量一致的卡密，本次应为" + quantity + "条");
        }
        for (String card : unique) {
            if (kamiItemMapper.countByConfigIdAndContent(configId, card) > 0) {
                throw new BusinessException(409, "人工附加的卡密已存在于当前仓库");
            }
        }
        return new ArrayList<>(unique);
    }

    private String limitText(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        return normalized.substring(0, Math.min(max, normalized.length()));
    }

    private String jsonSafe(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BusinessException(500, "无法生成脱敏审计证据");
        }
    }

    private String requireMutationRequestId(String requestId) {
        if (requestId == null || requestId.isBlank() || requestId.trim().length() > 80) {
            throw new BusinessException(400, "库存变更需要有效的 requestId");
        }
        return requestId.trim();
    }

    private String itemStatusName(Integer status) {
        if (status == null) return "UNKNOWN";
        return switch (status) {
            case 0 -> "AVAILABLE";
            case 1 -> "DELIVERED";
            case 2 -> "RESERVED";
            case 3 -> "REVIEW_REQUIRED";
            default -> "UNKNOWN";
        };
    }

    private void auditInventoryMutation(XianyuKamiConfig config, String operationType,
                                        String description, String requestId,
                                        String targetType, String targetId, int quantity,
                                        String beforeStatus, String afterStatus) {
        XianyuOperationLog audit = new XianyuOperationLog();
        audit.setXianyuAccountId(config.getXianyuAccountId());
        audit.setOperationType(operationType);
        audit.setOperationModule("卡密库存");
        audit.setOperationDesc(description);
        audit.setOperationStatus(1);
        audit.setTargetType(targetType);
        audit.setTargetId(targetId);
        audit.setRequestId(requestId);
        audit.setIdempotencyKey(requestId);
        audit.setOutcomeState("LOCAL_SUCCESS");
        audit.setDataSource("LOCAL");
        audit.setRequestParams(jsonSafe(Map.of("configId", config.getId(), "quantity", quantity,
                "sensitiveContentStored", false)));
        audit.setResponseResult(jsonSafe(Map.of("beforeStatus", beforeStatus, "afterStatus", afterStatus)));
        operationLogService.logRequired(audit);
    }

    private void rollbackCurrentTransaction() {
        try {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        } catch (Exception ignored) {
            // 直接单元调用没有 Spring 事务时无需回滚。
        }
    }

    private void refreshConfigCounts(Long kamiConfigId) {
        int total = kamiItemMapper.countByConfigId(kamiConfigId);
        int used = kamiItemMapper.countUsed(kamiConfigId);
        XianyuKamiConfig config = kamiConfigMapper.selectById(kamiConfigId);
        if (config != null) {
            config.setTotalCount(total);
            config.setUsedCount(used);
            kamiConfigMapper.updateById(config);
        }
    }

    private KamiConfigRespDTO toConfigRespDTO(XianyuKamiConfig config) {
        KamiConfigRespDTO dto = new KamiConfigRespDTO();
        dto.setId(config.getId());
        dto.setXianyuAccountId(config.getXianyuAccountId());
        dto.setXianyuAccountIds(sharedAccountLinkMapper.selectKamiConfigAccounts(config.getId()));
        dto.setSharingMode(config.getSharingMode() == null ? "PRIVATE" : config.getSharingMode());
        dto.setConfigVersion(config.getConfigVersion());
        dto.setAliasName(config.getAliasName());
        dto.setSourceType(config.getSourceType());
        dto.setExternalApiUrl(config.getExternalApiUrl());
        dto.setExternalApiHeadersConfigured(config.getExternalApiHeaders() != null
                && !config.getExternalApiHeaders().isBlank());
        boolean sensitiveBody = containsSensitiveJsonKey(config.getExternalApiBody());
        dto.setExternalApiBodySensitiveConfigured(sensitiveBody);
        dto.setExternalApiBody(sensitiveBody ? null : config.getExternalApiBody());
        dto.setExternalApiResultPath(config.getExternalApiResultPath());
        dto.setExternalApiTimeoutSeconds(config.getExternalApiTimeoutSeconds());
        dto.setExternalDailyQuota(config.getExternalDailyQuota());
        dto.setExternalFailureThreshold(config.getExternalFailureThreshold());
        dto.setExternalCooldownSeconds(config.getExternalCooldownSeconds());
        dto.setExternalCircuitState(config.getExternalCircuitState());
        dto.setExternalConsecutiveFailures(config.getExternalConsecutiveFailures());
        dto.setExternalCircuitOpenedAt(config.getExternalCircuitOpenedAt());
        dto.setExternalQuotaUsed(config.getExternalQuotaUsed());
        dto.setAlertEnabled(config.getAlertEnabled());
        dto.setAlertThresholdType(config.getAlertThresholdType());
        dto.setAlertThresholdValue(config.getAlertThresholdValue());
        dto.setAlertEmail(config.getAlertEmail());
        dto.setTotalCount(config.getTotalCount());
        dto.setUsedCount(config.getUsedCount());
        int unused = kamiItemMapper.countUnused(config.getId());
        dto.setAvailableCount(unused);
        dto.setReservedCount(kamiItemMapper.findByConfigIdAndStatus(config.getId(), KamiStatus.RESERVED.getCode()).size());
        dto.setReviewRequiredCount(kamiItemMapper.findByConfigIdAndStatus(
                config.getId(), KamiStatus.REVIEW_REQUIRED.getCode()).size());
        dto.setCreateTime(config.getCreateTime());
        dto.setUpdateTime(config.getUpdateTime());
        return dto;
    }

    private void validateSupplyPolicy(KamiConfigReqDTO reqDTO) {
        if (reqDTO.getExternalDailyQuota() != null
                && (reqDTO.getExternalDailyQuota() < 1 || reqDTO.getExternalDailyQuota() > 100_000)) {
            throw new BusinessException(400, "外部供货日配额应为1至100000");
        }
        if (reqDTO.getExternalFailureThreshold() != null
                && (reqDTO.getExternalFailureThreshold() < 1 || reqDTO.getExternalFailureThreshold() > 20)) {
            throw new BusinessException(400, "外部供货熔断阈值应为1至20");
        }
        if (reqDTO.getExternalCooldownSeconds() != null
                && (reqDTO.getExternalCooldownSeconds() < 30 || reqDTO.getExternalCooldownSeconds() > 86_400)) {
            throw new BusinessException(400, "外部供货冷却时间应为30至86400秒");
        }
    }

    private List<Long> normalizeAccountIds(List<Long> accountIds, Long legacyAccountId) {
        List<Long> normalized = accountIds == null ? new ArrayList<>()
                : accountIds.stream().filter(Objects::nonNull).distinct().toList();
        if (normalized.isEmpty() && legacyAccountId != null) return List.of(legacyAccountId);
        return normalized;
    }

    private void replaceAccounts(Long configId, Long tenantId, List<Long> accountIds) {
        sharedAccountLinkMapper.deleteKamiConfigAccounts(configId);
        sharedAccountLinkMapper.insertKamiConfigAccounts(configId, tenantId, accountIds);
    }

    private Map<String, Object> configAuditView(XianyuKamiConfig config, List<Long> accountIds) {
        Map<String, Object> view = new LinkedHashMap<>();
        if (config == null) return view;
        view.put("accountIds", accountIds == null ? List.of() : accountIds);
        view.put("sharingMode", config.getSharingMode());
        view.put("aliasName", config.getAliasName());
        view.put("sourceType", config.getSourceType());
        view.put("externalApiUrl", config.getExternalApiUrl());
        view.put("externalApiHeadersConfigured", config.getExternalApiHeaders() != null
                && !config.getExternalApiHeaders().isBlank());
        view.put("externalApiTimeoutSeconds", config.getExternalApiTimeoutSeconds());
        view.put("externalDailyQuota", config.getExternalDailyQuota());
        view.put("externalFailureThreshold", config.getExternalFailureThreshold());
        view.put("externalCooldownSeconds", config.getExternalCooldownSeconds());
        view.put("alertEnabled", config.getAlertEnabled());
        view.put("alertThresholdType", config.getAlertThresholdType());
        view.put("alertThresholdValue", config.getAlertThresholdValue());
        view.put("alertEmail", config.getAlertEmail());
        view.put("configVersion", config.getConfigVersion());
        return view;
    }

    private KamiItemRespDTO toItemRespDTO(XianyuKamiItem item, boolean includeSensitive) {
        KamiItemRespDTO dto = new KamiItemRespDTO();
        dto.setId(item.getId());
        dto.setKamiConfigId(item.getKamiConfigId());
        dto.setKamiContent(includeSensitive ? item.getKamiContent() : KamiSecretMasker.mask(item.getKamiContent()));
        dto.setStatus(item.getStatus());
        dto.setOrderId(item.getOrderId());
        dto.setReservedAccountId(item.getReservedAccountId());
        dto.setReservationExpireTime(item.getReservationExpireTime());
        dto.setSourceConfigVersion(item.getSourceConfigVersion());
        dto.setRowVersion(item.getRowVersion());
        dto.setUsedTime(item.getUsedTime());
        dto.setSortOrder(item.getSortOrder());
        dto.setCreateTime(item.getCreateTime());
        return dto;
    }

    private void inventoryEvent(Long configId, Long itemId, Long accountId, String orderId,
                                String eventType, String reservationToken, Long configVersion,
                                String requestId, String beforeStatus, String afterStatus) {
        Long tenantId = com.xianyusmart.context.TenantContext.get();
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
                "LOCAL_SUCCESS", requestId, reservationToken, configVersion, 1,
                "{\"status\":\"" + beforeStatus + "\"}",
                "{\"status\":\"" + afterStatus + "\"}", "LOCAL",
                com.xianyusmart.context.UserContext.getUserId(),
                com.xianyusmart.context.UserContext.getUsername());
    }

    private void checkAndSendAlert(XianyuKamiConfig config, Long kamiConfigId) {
        if (config == null || config.getAlertEnabled() == null || config.getAlertEnabled() != 1) {
            return;
        }

        int availableCount = kamiItemMapper.countUnused(kamiConfigId);
        int totalCount = config.getTotalCount() != null ? config.getTotalCount() : 0;
        int thresholdValue = config.getAlertThresholdValue() != null ? config.getAlertThresholdValue() : 10;
        int thresholdType = config.getAlertThresholdType() != null ? config.getAlertThresholdType() : 1;

        boolean shouldAlert = false;
        if (thresholdType == 1) {
            shouldAlert = availableCount < thresholdValue;
        } else {
            if (totalCount > 0) {
                int percentage = (availableCount * 100) / totalCount;
                shouldAlert = percentage < thresholdValue;
            }
        }

        if (shouldAlert) {
            log.info("卡密库存触发预警: configId={}, available={}, total={}, thresholdType={}, thresholdValue={}",
                    kamiConfigId, availableCount, totalCount, thresholdType, thresholdValue);
            notificationCenterService.dispatch("KAMI_STOCK_LOW", config.getXianyuAccountId(),
                    "卡密库存预警", (config.getAliasName() == null ? "卡密仓库" : config.getAliasName())
                            + " 可用库存剩余 " + availableCount,
                    Map.of("configId", kamiConfigId,
                            "availableCount", availableCount,
                            "totalCount", totalCount));
            emailNotifyService.sendKamiAlertEmail(
                    config.getAlertEmail(),
                    config.getAliasName(),
                    availableCount,
                    totalCount
            );
        }
    }

    private void validateSourceConfig(KamiConfigReqDTO request) {
        String sourceType = request.getSourceType() == null
                ? "LOCAL" : request.getSourceType().trim().toUpperCase();
        if (!List.of("LOCAL", "API").contains(sourceType)) {
            throw new IllegalArgumentException("卡密来源类型无效");
        }
        if (!"API".equals(sourceType)) {
            return;
        }
        WebhookSecurity.requireSafeUrl(request.getExternalApiUrl());
        boolean bodyProvided = request.getExternalApiBody() != null && !request.getExternalApiBody().isBlank();
        if (!bodyProvided && request.getId() == null) {
            throw new IllegalArgumentException("请填写外部接口请求体模板");
        }
        if (request.getExternalApiResultPath() == null || request.getExternalApiResultPath().isBlank()) {
            throw new IllegalArgumentException("请填写外部接口卡密结果路径");
        }
        int timeout = request.getExternalApiTimeoutSeconds() == null
                ? 10 : request.getExternalApiTimeoutSeconds();
        if (timeout < 3 || timeout > 30) {
            throw new IllegalArgumentException("外部接口超时时间必须在3到30秒之间");
        }
        try {
            if (bodyProvided) {
                JsonNode body = objectMapper.readTree(request.getExternalApiBody());
                if (!body.isObject()) {
                    throw new IllegalArgumentException("外部接口请求体必须是 JSON 对象");
                }
                if (containsSensitiveJsonKey(body)) {
                    throw new IllegalArgumentException("请求体包含疑似密钥字段；请把 API Key、Token、密码或授权信息放到只写请求头中");
                }
            }
            if (request.getExternalApiHeaders() != null && !request.getExternalApiHeaders().isBlank()
                    && !objectMapper.readTree(request.getExternalApiHeaders()).isObject()) {
                throw new IllegalArgumentException("外部接口请求头必须是 JSON 对象");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("外部接口请求配置不是有效 JSON");
        }
    }

    private boolean hasUnsettledSupply(Long configId) {
        return kamiItemMapper.countUnsettledByConfigId(configId) > 0
                || kamiExternalRequestMapper.countUnsettledByConfigId(configId) > 0;
    }

    private boolean supplyConfigurationChanged(XianyuKamiConfig config, KamiConfigReqDTO request) {
        String requestedSource = request.getSourceType() == null
                ? "LOCAL" : request.getSourceType().trim().toUpperCase();
        if (!requestedSource.equalsIgnoreCase(config.getSourceType())) {
            return true;
        }
        if (!"API".equals(requestedSource)) {
            return false;
        }
        boolean headersChanged = request.getExternalApiHeaders() != null
                && !request.getExternalApiHeaders().isBlank()
                && !Objects.equals(request.getExternalApiHeaders(), config.getExternalApiHeaders());
        return headersChanged
                || !Objects.equals(trimToNull(request.getExternalApiUrl()), trimToNull(config.getExternalApiUrl()))
                || (request.getExternalApiBody() != null && !request.getExternalApiBody().isBlank()
                    && !Objects.equals(request.getExternalApiBody(), config.getExternalApiBody()))
                || !Objects.equals(trimToNull(request.getExternalApiResultPath()),
                        trimToNull(config.getExternalApiResultPath()))
                || !Objects.equals(request.getExternalApiTimeoutSeconds() == null
                        ? 10 : request.getExternalApiTimeoutSeconds(), config.getExternalApiTimeoutSeconds());
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private boolean containsSensitiveJsonKey(String json) {
        if (json == null || json.isBlank()) return false;
        try {
            return containsSensitiveJsonKey(objectMapper.readTree(json));
        } catch (Exception ignored) {
            // 非法 JSON 会在保存校验阶段拒绝；读取旧配置时按敏感内容处理，避免意外回显。
            return true;
        }
    }

    private boolean containsSensitiveJsonKey(JsonNode node) {
        if (node == null) return false;
        if (node.isObject()) {
            java.util.Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey().replaceAll("[^A-Za-z0-9]", "").toLowerCase();
                boolean runtimeRequestField = "requesttoken".equals(key) || "idempotencykey".equals(key);
                if (!runtimeRequestField && (key.contains("password") || key.contains("passwd")
                        || key.contains("secret") || key.contains("apikey") || key.contains("accesstoken")
                        || key.contains("refreshtoken") || key.contains("authorization")
                        || key.contains("credential") || key.contains("cookie"))) {
                    return true;
                }
                if (containsSensitiveJsonKey(field.getValue())) return true;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) if (containsSensitiveJsonKey(child)) return true;
        }
        return false;
    }
}
