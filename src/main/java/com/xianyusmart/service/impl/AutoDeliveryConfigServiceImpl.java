package com.xianyusmart.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.common.ResultObject;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.entity.XianyuGoodsAutoDeliveryConfig;
import com.xianyusmart.entity.XianyuGoodsInfo;
import com.xianyusmart.entity.XianyuKamiConfig;
import com.xianyusmart.entity.XianyuOperationLog;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.mapper.XianyuGoodsInfoMapper;
import com.xianyusmart.mapper.XianyuGoodsConfigMapper;
import com.xianyusmart.mapper.XianyuKamiConfigMapper;
import com.xianyusmart.mapper.XianyuGoodsAutoDeliveryConfigMapper;
import com.xianyusmart.mapper.SharedAccountLinkMapper;
import com.xianyusmart.controller.dto.AutoDeliveryConfigReqDTO;
import com.xianyusmart.controller.dto.AutoDeliveryConfigRespDTO;
import com.xianyusmart.controller.dto.AutoDeliveryConfigQueryReqDTO;
import com.xianyusmart.controller.dto.ReceiptFollowUpUpdateReqDTO;
import com.xianyusmart.controller.dto.UniformCardDeliveryReqDTO;
import com.xianyusmart.service.AccountAccessService;
import com.xianyusmart.service.AutomationActivationService;
import com.xianyusmart.service.AutoDeliveryConfigService;
import com.xianyusmart.service.BuyerMessageService;
import com.xianyusmart.service.FixedDeliveryTemplateService;
import com.xianyusmart.service.GoodsSkuReadinessService;
import com.xianyusmart.service.OperationLogService;
import com.xianyusmart.service.UniformCardDeliveryPolicyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AutoDeliveryConfigServiceImpl implements AutoDeliveryConfigService {
    
    @Autowired
    private XianyuGoodsAutoDeliveryConfigMapper autoDeliveryConfigMapper;

    @Autowired
    private BuyerMessageService buyerMessageService;

    @Autowired
    private FixedDeliveryTemplateService fixedDeliveryTemplateService;

    @Autowired
    private XianyuAccountMapper accountMapper;

    @Autowired
    private XianyuGoodsInfoMapper goodsInfoMapper;

    @Autowired
    private XianyuKamiConfigMapper kamiConfigMapper;

    @Autowired
    private SharedAccountLinkMapper sharedAccountLinkMapper;

    @Autowired
    private GoodsSkuReadinessService goodsSkuReadinessService;

    @Autowired
    private XianyuGoodsConfigMapper goodsConfigMapper;

    @Autowired
    private AccountAccessService accountAccessService;

    @Autowired
    private AutomationActivationService automationActivationService;

    @Autowired
    private OperationLogService operationLogService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UniformCardDeliveryPolicyService uniformCardDeliveryPolicyService;

    @Override
    @Transactional
    public ResultObject<AutoDeliveryConfigRespDTO> configureUniformCard(UniformCardDeliveryReqDTO req) {
        if (req == null || req.getXianyuAccountId() == null || req.getXianyuAccountId() <= 0
                || req.getConfigId() == null || req.getConfigId() <= 0 || req.getEnabled() == null
                || req.getXyGoodsId() == null || !req.getXyGoodsId().matches("[A-Za-z0-9_-]{1,40}")) {
            throw new BusinessException(400, "通用卡密配置参数无效");
        }
        if (req.getEnabled() && !Boolean.TRUE.equals(req.getConfirmAllVariantsSameEntitlement())) {
            throw new BusinessException(400, "请明确确认该商品所有规格交付同一权益，不能用于不同套餐或不同版本");
        }
        Long tenant = TenantContext.get();
        if (tenant == null) throw new BusinessException(401, "缺少当前经营主体");
        Long accountId = req.getXianyuAccountId();
        accountAccessService.requireAccess(accountId);
        var account = accountMapper.selectOne(new LambdaQueryWrapper<XianyuAccount>()
                .eq(XianyuAccount::getId, accountId).eq(XianyuAccount::getTenantId, tenant).last("FOR UPDATE"));
        if (account == null || !Objects.equals(tenant, account.getTenantId())
                || !Objects.equals(accountId, account.getId())) {
            throw new BusinessException(404, "账号不存在或无权访问");
        }
        var config = autoDeliveryConfigMapper.selectOne(new LambdaQueryWrapper<XianyuGoodsAutoDeliveryConfig>()
                .apply("tenant_id = {0}", tenant).eq(XianyuGoodsAutoDeliveryConfig::getId, req.getConfigId())
                .eq(XianyuGoodsAutoDeliveryConfig::getXianyuAccountId, accountId)
                .eq(XianyuGoodsAutoDeliveryConfig::getXyGoodsId, req.getXyGoodsId()).last("FOR UPDATE"));
        if (config == null || !Objects.equals(config.getId(), req.getConfigId())
                || !Objects.equals(config.getXianyuAccountId(), accountId)
                || !Objects.equals(config.getXyGoodsId(), req.getXyGoodsId())) {
            throw new BusinessException(404, "发货配置不存在或不属于当前账号商品");
        }
        if (req.getEnabled()) {
            if (!Integer.valueOf(2).equals(config.getDeliveryMode())) {
                throw new BusinessException(400, "商品统一卡密仅适用于卡密发货配置");
            }
            // Reuse channel and content validation; this endpoint changes only the declaration.
            AutoDeliveryConfigReqDTO contentCheck = new AutoDeliveryConfigReqDTO();
            BeanUtils.copyProperties(config, contentCheck);
            normalizeDeliveryChannels(contentCheck);
            validateDeliveryContent(contentCheck);
        }
        boolean before = uniformCardDeliveryPolicyService.permits(accountId, req.getXyGoodsId(), config);
        uniformCardDeliveryPolicyService.configure(tenant, config, req.getEnabled());
        XianyuOperationLog audit = new XianyuOperationLog();
        audit.setXianyuAccountId(accountId);
        audit.setOperationType("UNIFORM_CARD_DELIVERY_POLICY");
        audit.setOperationModule("AUTO_DELIVERY");
        audit.setOperationDesc("设置商品统一卡密交付范围");
        audit.setOperationStatus(1);
        audit.setTargetType("DELIVERY_CONFIG");
        audit.setTargetId(String.valueOf(config.getId()));
        audit.setRequestId("uniform-card-" + UUID.randomUUID());
        audit.setOutcomeState("LOCAL_SUCCESS");
        audit.setDataSource("LOCAL");
        audit.setFieldDiffJson(receiptAuditJson(Map.of("before", before, "after", req.getEnabled(),
                "scope", "SAME_ENTITLEMENT_ALL_VARIANTS")));
        operationLogService.logRequired(audit);
        return ResultObject.success(toResponse(config));
    }

    /** Update only existing receipt-follow-up fields, independently of delivery/SKU readiness. */
    @Override
    @Transactional
    public ResultObject<Void> updateReceiptFollowUp(ReceiptFollowUpUpdateReqDTO reqDTO) {
        if (reqDTO == null || reqDTO.getXianyuAccountId() == null || reqDTO.getXianyuAccountId() <= 0
                || reqDTO.getXyGoodsId() == null || !reqDTO.getXyGoodsId().matches("[A-Za-z0-9_-]{1,40}")) {
            throw new BusinessException(400, "账号或商品参数无效");
        }
        Long tenant = TenantContext.get();
        if (tenant == null) throw new BusinessException(401, "缺少当前经营主体");
        Long accountId = reqDTO.getXianyuAccountId();
        String goodsId = reqDTO.getXyGoodsId();
        String skuId = normalizeReceiptSku(reqDTO.getSkuId());
        if (skuId != null && skuId.length() > 32
                || reqDTO.getReceiptFollowUpMessages() != null && reqDTO.getReceiptFollowUpMessages().length() > 6000) {
            throw new BusinessException(400, "规格或收货后话术参数过长");
        }
        accountAccessService.requireAccess(accountId);
        // Keep the account-first lock order used by activation and engagement admission.
        XianyuAccount account = accountMapper.selectOne(new LambdaQueryWrapper<XianyuAccount>()
                .eq(XianyuAccount::getId, accountId).eq(XianyuAccount::getTenantId, tenant).last("FOR UPDATE"));
        if (account == null || !Objects.equals(accountId, account.getId())
                || !Objects.equals(tenant, account.getTenantId())) {
            throw new BusinessException(404, "闲鱼账号不存在或无权访问");
        }
        XianyuGoodsInfo goods = goodsInfoMapper.selectOne(new LambdaQueryWrapper<XianyuGoodsInfo>()
                .apply("tenant_id = {0}", tenant).eq(XianyuGoodsInfo::getXianyuAccountId, accountId)
                .eq(XianyuGoodsInfo::getXyGoodId, goodsId).last("FOR UPDATE"));
        if (goods == null || goods.getId() == null || !Objects.equals(accountId, goods.getXianyuAccountId())
                || !Objects.equals(goodsId, goods.getXyGoodId())) {
            throw new BusinessException(404, "商品不存在或不属于当前账号");
        }
        XianyuGoodsAutoDeliveryConfig config = autoDeliveryConfigMapper
                .findOwnedReceiptFollowUpConfig(tenant, accountId, goodsId, skuId);
        if (config == null || config.getId() == null || !Objects.equals(accountId, config.getXianyuAccountId())
                || !Objects.equals(goodsId, config.getXyGoodsId())
                || !Objects.equals(skuId, normalizeReceiptSku(config.getSkuId()))
                || config.getXianyuGoodsId() != null && !Objects.equals(goods.getId(), config.getXianyuGoodsId())) {
            throw new BusinessException(404, "现有发货配置不存在或不属于当前账号商品");
        }
        String normalized = buyerMessageService.normalizeReceiptFollowUpMessages(reqDTO.getReceiptFollowUpMessages());
        int interval = buyerMessageService.normalizeReceiptFollowUpInterval(reqDTO.getReceiptFollowUpIntervalSeconds());
        Map<String, Object> before = receiptAuditSnapshot(
                config.getReceiptFollowUpMessages(), config.getReceiptFollowUpIntervalSeconds());
        Map<String, Object> after = receiptAuditSnapshot(normalized, interval);
        if (!buyerMessageService.parseReceiptFollowUpMessages(normalized).isEmpty()) {
            automationActivationService.ensureActivated("REVIEW_INVITE", tenant, accountId, goodsId);
        }
        if (autoDeliveryConfigMapper.updateReceiptFollowUp(
                tenant, config.getId(), accountId, goodsId, skuId, normalized, interval) != 1) {
            throw new BusinessException(404, "现有发货配置已变更，请重新读取后保存");
        }
        XianyuOperationLog audit = new XianyuOperationLog();
        audit.setXianyuAccountId(accountId);
        audit.setOperationType("RECEIPT_FOLLOW_UP_UPDATE");
        audit.setOperationModule("AUTO_DELIVERY");
        audit.setOperationDesc("更新收货后话术配置");
        audit.setOperationStatus(1);
        audit.setTargetType("DELIVERY_CONFIG");
        audit.setTargetId(String.valueOf(config.getId()));
        audit.setRequestId("receipt-follow-up-" + UUID.randomUUID());
        audit.setOutcomeState("LOCAL_SUCCESS");
        audit.setDataSource("LOCAL");
        audit.setFieldDiffJson(receiptAuditJson(Map.of("before", before, "after", after)));
        operationLogService.logRequired(audit);
        return ResultObject.success(null);
    }

    private String normalizeReceiptSku(String skuId) {
        return skuId == null || skuId.isBlank() ? null : skuId.trim();
    }

    private Map<String, Object> receiptAuditSnapshot(String messages, Integer interval) {
        int count;
        try { count = buyerMessageService.parseReceiptFollowUpMessages(messages).size(); }
        catch (IllegalArgumentException invalidLegacy) { count = -1; }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("messageCount", count);
        try {
            snapshot.put("messagesHash", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((messages == null ? "" : messages).getBytes(StandardCharsets.UTF_8))));
        } catch (Exception e) { throw new IllegalStateException("收货后话术审计摘要生成失败", e); }
        snapshot.put("intervalSeconds", interval);
        return snapshot;
    }

    private String receiptAuditJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("收货后话术审计序列化失败", e); }
    }
    
    @Override
    public ResultObject<AutoDeliveryConfigRespDTO> saveOrUpdateConfig(AutoDeliveryConfigReqDTO reqDTO) {
        try {
            if (reqDTO.getDeliveryMode() == null) {
                reqDTO.setDeliveryMode(1);
            }
            // 两种发货模式只保存当前模式所需字段，避免历史关联形成隐性组合配置。
            normalizeExclusiveDeliveryFields(reqDTO);
            validateResourceOwnership(reqDTO);
            normalizeDeliveryChannels(reqDTO);
            validateDeliveryContent(reqDTO);
            String deliveryMessageTemplate = reqDTO.getDeliveryMode() == 2
                    ? buyerMessageService.normalizeDeliveryMessageTemplate(reqDTO.getDeliveryMessageTemplate())
                    : null;
            String receiptFollowUpMessages = buyerMessageService.normalizeReceiptFollowUpMessages(
                    reqDTO.getReceiptFollowUpMessages());
            int receiptFollowUpIntervalSeconds = buyerMessageService.normalizeReceiptFollowUpInterval(
                    reqDTO.getReceiptFollowUpIntervalSeconds());
            String skuId = reqDTO.getSkuId();
            XianyuGoodsAutoDeliveryConfig existingConfig = null;
            if (skuId != null && !skuId.isEmpty()) {
                existingConfig = autoDeliveryConfigMapper
                        .findByAccountIdAndGoodsIdAndSkuId(reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId(), skuId);
            }
            if (existingConfig == null) {
                existingConfig = autoDeliveryConfigMapper
                        .findByAccountIdAndGoodsIdNoSku(reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId());
                if (existingConfig != null && skuId != null && !skuId.isEmpty()) {
                    existingConfig = null;
                }
            }
            
            XianyuGoodsAutoDeliveryConfig config;
            if (existingConfig != null) {
                config = existingConfig;
                config.setDeliveryMode(reqDTO.getDeliveryMode());
                config.setFixedTemplateId(reqDTO.getFixedTemplateId());
                config.setSkuId(reqDTO.getSkuId());
                config.setSkuName(reqDTO.getSkuName());
                config.setAutoDeliveryContent(reqDTO.getAutoDeliveryContent());
                config.setKamiConfigIds(reqDTO.getKamiConfigIds());
                config.setKamiDeliveryTemplate(null);
                config.setDeliveryMessageTemplate(deliveryMessageTemplate);
                config.setVoucherDeliveryEnabled(reqDTO.getVoucherDeliveryEnabled());
                config.setChatDeliveryEnabled(reqDTO.getChatDeliveryEnabled());
                config.setReceiptFollowUpMessages(receiptFollowUpMessages);
                config.setReceiptFollowUpIntervalSeconds(receiptFollowUpIntervalSeconds);
                config.setAutoDeliveryImageUrl(reqDTO.getAutoDeliveryImageUrl());
                config.setXianyuGoodsId(reqDTO.getXianyuGoodsId());
                config.setAutoConfirmShipment(1);
                
                autoDeliveryConfigMapper.updateById(config);
                log.info("更新自动发货配置成功，ID: {}", config.getId());
            } else {
                config = new XianyuGoodsAutoDeliveryConfig();
                BeanUtils.copyProperties(reqDTO, config);
                if (config.getSkuId() == null) {
                    config.setSkuId(null);
                }
                config.setKamiDeliveryTemplate(null);
                config.setDeliveryMessageTemplate(deliveryMessageTemplate);
                config.setVoucherDeliveryEnabled(reqDTO.getVoucherDeliveryEnabled());
                config.setChatDeliveryEnabled(reqDTO.getChatDeliveryEnabled());
                config.setReceiptFollowUpMessages(receiptFollowUpMessages);
                config.setReceiptFollowUpIntervalSeconds(receiptFollowUpIntervalSeconds);
                config.setAutoConfirmShipment(1);
                
                autoDeliveryConfigMapper.insert(config);
                log.info("创建自动发货配置成功，ID: {}", config.getId());
            }
            
            AutoDeliveryConfigRespDTO respDTO = toResponse(config);
            
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("保存自动发货配置失败", e);
            return ResultObject.failed("保存自动发货配置失败: " + e.getMessage());
        }
    }
    
    @Override
    public ResultObject<AutoDeliveryConfigRespDTO> getConfig(AutoDeliveryConfigQueryReqDTO reqDTO) {
        try {
            log.info("开始查询自动发货配置: xianyuAccountId={}, xyGoodsId={}, skuId={}", 
                    reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId(), reqDTO.getSkuId());
            
            XianyuGoodsAutoDeliveryConfig config = null;
            
            if (reqDTO.getXyGoodsId() != null && !reqDTO.getXyGoodsId().trim().isEmpty()) {
                String skuId = reqDTO.getSkuId();
                if (skuId != null && !skuId.isEmpty()) {
                    config = autoDeliveryConfigMapper.findByAccountIdAndGoodsIdAndSkuId(
                            reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId(), skuId);
                } else {
                    config = autoDeliveryConfigMapper.findByAccountIdAndGoodsIdNoSku(
                            reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId());
                }
            } else {
                List<XianyuGoodsAutoDeliveryConfig> configs = autoDeliveryConfigMapper
                        .findByAccountId(reqDTO.getXianyuAccountId());
                config = configs.isEmpty() ? null : configs.get(0);
            }
            
            if (config == null) {
                return ResultObject.success(null);
            }
            
            AutoDeliveryConfigRespDTO respDTO = toResponse(config);
            
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("查询自动发货配置失败", e);
            return ResultObject.failed("查询自动发货配置失败: " + e.getMessage());
        }
    }
    
    @Override
    public ResultObject<List<AutoDeliveryConfigRespDTO>> getConfigsByGoodsId(Long xianyuAccountId, String xyGoodsId) {
        try {
            List<XianyuGoodsAutoDeliveryConfig> configs = autoDeliveryConfigMapper
                    .findByAccountIdAndGoodsId(xianyuAccountId, xyGoodsId);
            
            List<AutoDeliveryConfigRespDTO> respDTOs = configs.stream()
                    .map(config -> {
                        return toResponse(config);
                    })
                    .collect(Collectors.toList());
            
            return ResultObject.success(respDTOs);
        } catch (Exception e) {
            log.error("查询商品自动发货配置列表失败", e);
            return ResultObject.failed("查询商品自动发货配置列表失败: " + e.getMessage());
        }
    }
    
    @Override
    public ResultObject<List<AutoDeliveryConfigRespDTO>> getConfigsByAccountId(Long xianyuAccountId) {
        try {
            List<XianyuGoodsAutoDeliveryConfig> configs = autoDeliveryConfigMapper
                    .findByAccountId(xianyuAccountId);
            
            List<AutoDeliveryConfigRespDTO> respDTOs = configs.stream()
                    .map(config -> {
                        return toResponse(config);
                    })
                    .collect(Collectors.toList());
            
            return ResultObject.success(respDTOs);
        } catch (Exception e) {
            log.error("查询账号自动发货配置列表失败", e);
            return ResultObject.failed("查询账号自动发货配置列表失败: " + e.getMessage());
        }
    }
    
    @Override
    public ResultObject<Void> deleteConfig(Long xianyuAccountId, String xyGoodsId) {
        try {
            LambdaQueryWrapper<XianyuGoodsAutoDeliveryConfig> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(XianyuGoodsAutoDeliveryConfig::getXianyuAccountId, xianyuAccountId)
                   .eq(XianyuGoodsAutoDeliveryConfig::getXyGoodsId, xyGoodsId);
            
            int deletedCount = autoDeliveryConfigMapper.delete(wrapper);
            
            if (deletedCount > 0) {
                log.info("删除自动发货配置成功，账号ID: {}, 商品ID: {}", xianyuAccountId, xyGoodsId);
                return ResultObject.success(null);
            } else {
                return ResultObject.failed("未找到对应的自动发货配置");
            }
        } catch (Exception e) {
            log.error("删除自动发货配置失败", e);
            return ResultObject.failed("删除自动发货配置失败: " + e.getMessage());
        }
    }

    private void validateDeliveryContent(AutoDeliveryConfigReqDTO reqDTO) {
        int deliveryMode = reqDTO.getDeliveryMode() == null ? 1 : reqDTO.getDeliveryMode();
        if (deliveryMode != 1 && deliveryMode != 2) {
            throw new IllegalArgumentException("发货类型只能选择固定内容或卡密");
        }
        boolean voucherEnabled = Integer.valueOf(1).equals(reqDTO.getVoucherDeliveryEnabled());
        boolean chatEnabled = Integer.valueOf(1).equals(reqDTO.getChatDeliveryEnabled());
        if (!voucherEnabled && !chatEnabled) {
            throw new IllegalArgumentException("发货凭证和买家私聊至少开启一个");
        }
        if (deliveryMode == 1) {
            if (fixedDeliveryTemplateService.findOwnedTemplate(
                    reqDTO.getXianyuAccountId(), reqDTO.getFixedTemplateId()) == null) {
                throw new IllegalArgumentException("固定内容发货必须选择有效模板");
            }
        }
        if (deliveryMode == 2 && (reqDTO.getKamiConfigIds() == null || reqDTO.getKamiConfigIds().isBlank())) {
            throw new IllegalArgumentException("卡密发货必须绑定卡密配置");
        }
    }

    private void normalizeDeliveryChannels(AutoDeliveryConfigReqDTO reqDTO) {
        int voucherEnabled = reqDTO.getVoucherDeliveryEnabled() == null
                ? 1 : reqDTO.getVoucherDeliveryEnabled();
        int chatEnabled = reqDTO.getChatDeliveryEnabled() == null
                ? 1 : reqDTO.getChatDeliveryEnabled();
        if ((voucherEnabled != 0 && voucherEnabled != 1) || (chatEnabled != 0 && chatEnabled != 1)) {
            throw new IllegalArgumentException("发送渠道开关参数无效");
        }
        reqDTO.setVoucherDeliveryEnabled(voucherEnabled);
        reqDTO.setChatDeliveryEnabled(chatEnabled);
    }

    private void normalizeExclusiveDeliveryFields(AutoDeliveryConfigReqDTO reqDTO) {
        if (reqDTO.getDeliveryMode() == 1) {
            reqDTO.setKamiConfigIds(null);
            reqDTO.setKamiDeliveryTemplate(null);
            reqDTO.setDeliveryMessageTemplate(null);
        } else if (reqDTO.getDeliveryMode() == 2) {
            reqDTO.setFixedTemplateId(null);
            reqDTO.setKamiDeliveryTemplate(null);
        }
        reqDTO.setAutoDeliveryContent(null);
    }

    private void validateResourceOwnership(AutoDeliveryConfigReqDTO reqDTO) {
        Long accountId = reqDTO.getXianyuAccountId();
        if (accountId == null || accountMapper.selectById(accountId) == null) {
            throw new IllegalArgumentException("闲鱼账号不存在或无权访问");
        }
        LambdaQueryWrapper<XianyuGoodsInfo> goodsQuery = new LambdaQueryWrapper<>();
        goodsQuery.eq(XianyuGoodsInfo::getXianyuAccountId, accountId)
                .eq(XianyuGoodsInfo::getXyGoodId, reqDTO.getXyGoodsId());
        XianyuGoodsInfo goods = goodsInfoMapper.selectOne(goodsQuery);
        if (goods == null) {
            throw new IllegalArgumentException("商品不存在或不属于当前账号");
        }
        if (reqDTO.getXianyuGoodsId() != null && !reqDTO.getXianyuGoodsId().equals(goods.getId())) {
            throw new IllegalArgumentException("商品本地ID与当前账号商品不一致");
        }
        reqDTO.setXianyuGoodsId(goods.getId());
        var readiness = goodsSkuReadinessService.inspect(accountId, reqDTO.getXyGoodsId());
        var goodsConfig = goodsConfigMapper.selectByAccountAndGoodsId(accountId, reqDTO.getXyGoodsId());
        boolean enabled = goodsConfig != null && Integer.valueOf(1).equals(goodsConfig.getXianyuAutoDeliveryOn());
        if (enabled && !readiness.complete()) {
            throw new IllegalStateException("商品规格信息未就绪，不能覆盖正在运行的配置；请先关闭自动发货再保存。" + readiness.message());
        }
        String skuId = reqDTO.getSkuId();
        var verifiedSku = enabled
                ? goodsSkuReadinessService.requireValidSelection(accountId, reqDTO.getXyGoodsId(), skuId)
                : goodsSkuReadinessService.requireConfigSelection(accountId, reqDTO.getXyGoodsId(), skuId);
        if (verifiedSku == null) {
            reqDTO.setSkuId(null);
            reqDTO.setSkuName(null);
        } else {
            skuId = skuId.trim();
            // 规格名称以商品同步数据为准，避免前端参数伪造或过期。
            reqDTO.setSkuId(skuId);
            reqDTO.setSkuName(verifiedSku.getValueText());
        }
        if (reqDTO.getDeliveryMode() == null || reqDTO.getDeliveryMode() != 2
                || reqDTO.getKamiConfigIds() == null || reqDTO.getKamiConfigIds().isBlank()) {
            return;
        }
        for (String configIdText : reqDTO.getKamiConfigIds().split(",")) {
            try {
                XianyuKamiConfig kamiConfig =
                        kamiConfigMapper.selectById(Long.parseLong(configIdText.trim()));
                if (kamiConfig == null
                        || !sharedAccountLinkMapper.selectKamiConfigAccounts(kamiConfig.getId()).contains(accountId)) {
                    throw new IllegalArgumentException("卡密仓库不存在或不属于当前账号");
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("卡密仓库参数格式错误");
            }
        }
    }

    private AutoDeliveryConfigRespDTO toResponse(XianyuGoodsAutoDeliveryConfig config) {
        AutoDeliveryConfigRespDTO response = new AutoDeliveryConfigRespDTO();
        BeanUtils.copyProperties(config, response);
        var readiness = goodsSkuReadinessService.inspect(config.getXianyuAccountId(), config.getXyGoodsId());
        response.setSkuReadinessStatus(readiness.status().name());
        response.setSkuDeclaredCount(readiness.declaredCount());
        response.setSkuVerifiedCount(readiness.verifiedCount());
        boolean limitedFixed = !readiness.complete() && goodsSkuReadinessService.permitsLimitedFixedTemplate(
                config.getXianyuAccountId(), config.getXyGoodsId(), readiness, config);
        boolean uniformCard = goodsSkuReadinessService.permitsUniformCard(
                config.getXianyuAccountId(), config.getXyGoodsId(), config);
        response.setUniformCardDeliveryEnabled(uniformCard);
        boolean ready = readiness.complete();
        if (ready) {
            try {
                goodsSkuReadinessService.requireValidSelection(config.getXianyuAccountId(), config.getXyGoodsId(), config.getSkuId());
                if (readiness.hasSkuChildren()) {
                    var mappings = autoDeliveryConfigMapper.findByAccountIdAndGoodsId(config.getXianyuAccountId(), config.getXyGoodsId());
                    ready = mappings != null && mappings.stream().map(XianyuGoodsAutoDeliveryConfig::getSkuId)
                            .filter(id -> id != null && !id.isBlank()).distinct().count() == readiness.verifiedCount();
                    if (ready) for (var mapping : mappings) {
                        if (mapping.getSkuId() != null && !mapping.getSkuId().isBlank())
                            goodsSkuReadinessService.requireValidSelection(config.getXianyuAccountId(), config.getXyGoodsId(), mapping.getSkuId());
                    }
                }
            } catch (IllegalArgumentException | IllegalStateException invalidMapping) {
                ready = false;
            }
        }
        ready = ready || limitedFixed || uniformCard;
        response.setConfigurationReady(ready);
        response.setReadinessMessage(uniformCard ? "商品统一卡密已确认：所有规格交付同一权益；商品规格同步状态不变，执行仍核验官方付款订单、数量和库存"
                : limitedFixed ? "通用固定模板已可手工开启；商品规格仍未完整，仅订单无规格证据时可执行，卡密不适用" : ready ? "配置已就绪，可手工开启自动发货；开启和实际执行仍会校验订单与库存"
                : readiness.complete() ? "尚未完成全部有效规格的配置，请逐项保存后再开启自动发货"
                : readiness.message() + "；本配置仅待就绪保存，不会开启或执行自动发货");
        return response;
    }
}
