package com.xianyusmart.service.impl;

import com.xianyusmart.entity.XianyuGoodsAutoDeliveryConfig;
import com.xianyusmart.entity.XianyuGoodsOrder;
import com.xianyusmart.entity.XianyuGoodsAutoReplyRecord;
import com.xianyusmart.entity.XianyuGoodsConfig;
import com.xianyusmart.enums.DeliveryStatus;
import com.xianyusmart.event.DeliveryMessageSentEvent;
import com.xianyusmart.mapper.XianyuGoodsAutoDeliveryConfigMapper;
import com.xianyusmart.mapper.XianyuGoodsConfigMapper;
import com.xianyusmart.mapper.XianyuGoodsOrderMapper;
import com.xianyusmart.mapper.XianyuGoodsAutoReplyRecordMapper;
import com.xianyusmart.service.AutoDeliveryService;
import com.xianyusmart.service.BuyerMessageService;
import com.xianyusmart.service.DeliveryTaskService;
import com.xianyusmart.service.EmailNotifyService;
import com.xianyusmart.service.GoodsSkuService;
import com.xianyusmart.service.NotificationCenterService;
import com.xianyusmart.service.KamiConfigService;
import com.xianyusmart.service.OrderService;
import com.xianyusmart.service.RiskControlService;
import com.xianyusmart.service.WebSocketService;
import com.xianyusmart.service.delivery.DeliveryContext;
import com.xianyusmart.service.delivery.DeliveryStrategyResolver;
import com.xianyusmart.service.delivery.OrderDetailFetcher;
import com.xianyusmart.utils.HumanLikeDelayUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 自动发货服务实现类（编排层）
 *
 * <p>负责发货流程的编排，具体逻辑委托给 delivery 包下的组件：</p>
 * <ul>
 *   <li>{@link OrderDetailFetcher} - 订单详情获取与解析</li>
 *   <li>{@link DeliveryStrategyResolver} - 发货内容策略解析（文本/卡密/自定义）</li>
 * </ul>
 */
@Slf4j
@Service
public class AutoDeliveryServiceImpl implements AutoDeliveryService {
    @Autowired
    private com.xianyusmart.service.delivery.OrderRecipientVerifier recipientVerifier;
    @Autowired
    private com.xianyusmart.service.delivery.DeliveryLeaseService leaseService;
    @Autowired
    private com.xianyusmart.mapper.DeliveryExecutionMapper executionMapper;
    @Autowired
    private com.xianyusmart.service.OrderConfirmationService confirmationService;
    
    @Autowired
    private XianyuGoodsConfigMapper goodsConfigMapper;
    
    @Autowired
    private XianyuGoodsAutoDeliveryConfigMapper autoDeliveryConfigMapper;
    
    @Autowired
    private XianyuGoodsOrderMapper orderMapper;
    
    @Autowired
    private XianyuGoodsAutoReplyRecordMapper autoReplyRecordMapper;
    
    @Lazy
    @Autowired
    private WebSocketService webSocketService;
    
    @Autowired
    private com.xianyusmart.service.SentMessageSaveService sentMessageSaveService;

    @Autowired
    private EmailNotifyService emailNotifyService;

    @Autowired
    private NotificationCenterService notificationCenterService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private DeliveryTaskService deliveryTaskService;

    @Autowired
    private RiskControlService riskControlService;

    @Autowired
    private OrderDetailFetcher orderDetailFetcher;

    @Autowired
    private DeliveryStrategyResolver deliveryStrategyResolver;

    @Autowired
    private KamiConfigService kamiConfigService;

    @Autowired
    private BuyerMessageService buyerMessageService;

    @Autowired
    private GoodsSkuService goodsSkuService;

    @Autowired
    @Qualifier("taskExecutor")
    private Executor taskExecutor;
    
    @Override
    public XianyuGoodsConfig getGoodsConfig(Long accountId, String xyGoodsId) {
        return goodsConfigMapper.selectByAccountAndGoodsId(accountId, xyGoodsId);
    }
    
    @Override
    public XianyuGoodsAutoDeliveryConfig getAutoDeliveryConfig(Long accountId, String xyGoodsId) {
        return autoDeliveryConfigMapper.findByAccountIdAndGoodsIdNoSku(accountId, xyGoodsId);
    }
    
    @Override
    public void saveOrUpdateGoodsConfig(XianyuGoodsConfig config) {
        XianyuGoodsConfig existing = goodsConfigMapper.selectByAccountAndGoodsId(
                config.getXianyuAccountId(), config.getXyGoodsId());
        
        if (existing == null) {
            goodsConfigMapper.insert(config);
        } else {
            config.setId(existing.getId());
            goodsConfigMapper.update(config);
        }
    }
    
    @Override
    public void saveOrUpdateAutoDeliveryConfig(XianyuGoodsAutoDeliveryConfig config) {
        String skuId = config.getSkuId();
        if (skuId != null && skuId.isEmpty()) {
            skuId = null;
            config.setSkuId(null);
        }
        XianyuGoodsAutoDeliveryConfig existingConfig;
        if (skuId != null) {
            existingConfig = autoDeliveryConfigMapper.findByAccountIdAndGoodsIdAndSkuId(
                    config.getXianyuAccountId(), config.getXyGoodsId(), skuId);
        } else {
            existingConfig = autoDeliveryConfigMapper.findByAccountIdAndGoodsIdNoSku(
                    config.getXianyuAccountId(), config.getXyGoodsId());
        }
        
        if (existingConfig == null) {
            autoDeliveryConfigMapper.insert(config);
        } else {
            config.setId(existingConfig.getId());
            autoDeliveryConfigMapper.updateById(config);
        }
    }
    
    @Override
    public void recordAutoDelivery(Long accountId, String xyGoodsId, String buyerUserId, String buyerUserName, String content, Integer state) {
        recordAutoDelivery(accountId, xyGoodsId, buyerUserId, buyerUserName, content, state, null, null);
    }
    
    public void recordAutoDelivery(Long accountId, String xyGoodsId, String buyerUserId, String buyerUserName, 
                                   String content, Integer state, String pnmId, String orderId) {
        XianyuGoodsOrder record = new XianyuGoodsOrder();
        record.setXianyuAccountId(accountId);
        record.setXyGoodsId(xyGoodsId);
        record.setBuyerUserId(buyerUserId);
        record.setBuyerUserName(buyerUserName);
        record.setContent(content);
        record.setState(state);
        record.setPnmId(pnmId != null ? pnmId : "");
        record.setOrderId(orderId != null ? orderId : "");
        record.setConfirmState(0);
        
        orderMapper.insert(record);
    }
    
    @Override
    public void handleAutoDelivery(Long accountId, String xyGoodsId, String sId, String buyerUserId, String buyerUserName) {
        handleAutoDelivery(accountId, xyGoodsId, sId, buyerUserId, buyerUserName, null);
    }
    
    public void handleAutoDelivery(Long accountId, String xyGoodsId, String sId, String buyerUserId, String buyerUserName, String orderId) {
        if (orderId == null || orderId.isBlank()) throw new IllegalArgumentException("缺少平台订单号，不能核验买家或执行发货");
        XianyuGoodsOrder order = orderMapper.selectByAccountIdAndOrderId(accountId, orderId);
        if (order == null) throw new IllegalArgumentException("请先同步平台订单");
        executeDelivery(order.getId(), accountId, order.getXyGoodsId(), order.getSid(), orderId, order.getBuyerUserName(), true);
    }
    
    @Override
    public void handleAutoReply(Long accountId, String xyGoodsId, String sId, String buyerMessage) {
        log.info("【账号{}】自动回复功能已移除: xyGoodsId={}", accountId, xyGoodsId);
    }
    
    private void recordAutoReply(Long accountId, String xyGoodsId, String buyerMessage, 
                                  String replyContent, String matchedKeyword, Integer state) {
        try {
            XianyuGoodsAutoReplyRecord record = new XianyuGoodsAutoReplyRecord();
            record.setXianyuAccountId(accountId);
            record.setXyGoodsId(xyGoodsId);
            record.setBuyerMessage(buyerMessage);
            record.setReplyContent(replyContent);
            record.setMatchedKeyword(matchedKeyword);
            record.setState(state);
            
            autoReplyRecordMapper.insert(record);
        } catch (Exception e) {
            log.error("【账号{}】记录自动回复失败", accountId, e);
        }
    }
    
    @Override
    public com.xianyusmart.controller.dto.AutoDeliveryRecordRespDTO getAutoDeliveryRecords(
            com.xianyusmart.controller.dto.AutoDeliveryRecordReqDTO reqDTO) {
        
        Long accountId = reqDTO.getXianyuAccountId();
        String xyGoodsId = reqDTO.getXyGoodsId();
        String keyword = reqDTO.getKeyword();
        int pageNum = Math.max(reqDTO.getPageNum() != null ? reqDTO.getPageNum() : 1, 1);
        int pageSize = Math.min(Math.max(reqDTO.getPageSize() != null ? reqDTO.getPageSize() : 20, 1), 100);
        List<String> deliveryStatuses = reqDTO.getDeliveryStatuses() == null ? List.of() :
                reqDTO.getDeliveryStatuses().stream().map(status -> {
                    try {
                        return DeliveryStatus.valueOf(status).name();
                    } catch (Exception e) {
                        throw new IllegalArgumentException("无效的履约状态: " + status);
                    }
                }).distinct().toList();
        
        // 使用长整型计算偏移，避免异常页码触发整数溢出。
        long offset = (long) (pageNum - 1) * pageSize;
        
        List<XianyuGoodsOrder> records = orderMapper.selectByAccountIdWithPage(
                accountId, xyGoodsId, keyword, deliveryStatuses, pageSize, offset);
        
        long total = orderMapper.countByAccountId(accountId, xyGoodsId, keyword, deliveryStatuses);
        
        List<com.xianyusmart.controller.dto.AutoDeliveryRecordDTO> recordDTOs = new ArrayList<>();
        for (XianyuGoodsOrder record : records) {
            com.xianyusmart.controller.dto.AutoDeliveryRecordDTO dto = 
                    new com.xianyusmart.controller.dto.AutoDeliveryRecordDTO();
            dto.setId(record.getId());
            dto.setXianyuAccountId(record.getXianyuAccountId());
            dto.setXyGoodsId(record.getXyGoodsId());
            dto.setGoodsTitle(record.getGoodsTitle());
            dto.setBuyerUserName(record.getBuyerUserName());
            dto.setContent(record.getContent());
            dto.setState(record.getState());
            dto.setDeliveryStatus(record.getDeliveryStatus());
            dto.setDeliveryMessageState(record.getDeliveryMessageContent() == null
                    ? null : record.getDeliveryMessageState());
            dto.setFailReason(record.getFailReason() != null && !record.getFailReason().isBlank()
                    ? record.getFailReason() : record.getLastErrorMessage());
            dto.setConfirmState(record.getConfirmState());
            dto.setRateStatus(record.getRateStatus());
            dto.setRateTime(record.getRateTime());
            dto.setRateContent(record.getRateContent());
            dto.setRateSource(record.getRateSource());
            dto.setOrderId(record.getOrderId());
            dto.setSkuName(record.getSkuName());
            dto.setOrderCreateTime(record.getOrderCreateTime());
            dto.setPaySuccessTime(record.getPaySuccessTime());
            dto.setConsignTime(record.getConsignTime());
            dto.setTotalPrice(record.getTotalPrice());
            dto.setBuyNum(record.getBuyNum());
            dto.setCreateTime(record.getCreateTime());
            recordDTOs.add(dto);
        }
        
        com.xianyusmart.controller.dto.AutoDeliveryRecordRespDTO respDTO = 
                new com.xianyusmart.controller.dto.AutoDeliveryRecordRespDTO();
        respDTO.setRecords(recordDTOs);
        respDTO.setTotal(total);
        respDTO.setPageNum(pageNum);
        respDTO.setPageSize(pageSize);
        
        return respDTO;
    }

    @Override
    public com.xianyusmart.common.ResultObject<String> triggerAutoDelivery(
            com.xianyusmart.controller.dto.TriggerAutoDeliveryReqDTO reqDTO) {
        try {
            Long accountId = reqDTO.getXianyuAccountId();
            String xyGoodsId = reqDTO.getXyGoodsId();
            String orderId = reqDTO.getOrderId();
            Boolean needHumanLikeDelay = reqDTO.getNeedHumanLikeDelay() != null ? reqDTO.getNeedHumanLikeDelay() : false;

            log.info("【账号{}】触发自动发货: xyGoodsId={}, orderId={}, needHumanLikeDelay={}", 
                    accountId, xyGoodsId, orderId, needHumanLikeDelay);

            XianyuGoodsOrder record = orderMapper.selectByOrderId(accountId, xyGoodsId, orderId);
            if (record == null) {
                log.warn("【账号{}】发货记录不存在: orderId={}", accountId, orderId);
                return com.xianyusmart.common.ResultObject.failed("发货记录不存在");
            }

            String pnmId = record.getPnmId();
            if (pnmId == null || pnmId.isEmpty()) {
                log.warn("【账号{}】发货记录没有pnmId: orderId={}", accountId, orderId);
                return com.xianyusmart.common.ResultObject.failed("发货记录没有pnmId");
            }

            Long recordId = record.getId();
            String sId = record.getSid() != null ? record.getSid() : record.getBuyerUserId() + "@goofish";
            String buyerUserName = record.getBuyerUserName();

            XianyuGoodsConfig goodsConfig = goodsConfigMapper.selectByAccountAndGoodsId(accountId, xyGoodsId);
            if (goodsConfig == null || goodsConfig.getXianyuAutoDeliveryOn() != 1) {
                log.info("【账号{}】商品未开启自动发货: xyGoodsId={}", accountId, xyGoodsId);
                return com.xianyusmart.common.ResultObject.failed("商品未开启自动发货");
            }

            executeDelivery(recordId, accountId, xyGoodsId, sId, orderId, buyerUserName, needHumanLikeDelay);

            XianyuGoodsOrder updatedRecord = orderMapper.selectByOrderId(accountId, xyGoodsId, orderId);
            if (updatedRecord != null && updatedRecord.getState() == 1) {
                return com.xianyusmart.common.ResultObject.success("触发自动发货成功");
            } else {
                String failReason = updatedRecord != null ? updatedRecord.getFailReason() : "未知错误";
                return com.xianyusmart.common.ResultObject.failed(failReason != null ? failReason : "发货失败");
            }

        } catch (Exception e) {
            log.error("【账号{}】触发自动发货失败: xyGoodsId={}, orderId={}", 
                    reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId(), reqDTO.getOrderId(), e);
            return com.xianyusmart.common.ResultObject.failed("触发自动发货失败: " + e.getMessage());
        }
    }

    @Override
    public void executeDelivery(Long recordId, Long accountId, String xyGoodsId, String sId, String orderId, String buyerUserName, boolean needHumanLikeDelay) {
        if (com.xianyusmart.service.delivery.DeliveryExecution.token() == null) {
            XianyuGoodsOrder task = orderMapper.selectById(recordId);
            if (task == null || !accountId.equals(task.getXianyuAccountId())) throw new IllegalArgumentException("订单不存在");
            task.setLeaseOwner(null);
            leaseService.run(task, () -> executeDelivery(recordId, accountId, xyGoodsId, sId, orderId, buyerUserName, needHumanLikeDelay));
            return;
        }
        executeClaimedDelivery(recordId, accountId, xyGoodsId, sId, orderId, buyerUserName, needHumanLikeDelay);
    }

    private void executeClaimedDelivery(Long recordId, Long accountId, String xyGoodsId, String sId, String orderId, String buyerUserName, boolean needHumanLikeDelay) {
        boolean cardDelivery = false;
        boolean cardDeliveryAttempted = false;
        boolean cardReservationCommitted = false;
        boolean deliveryMessageHeld = false;
        boolean anySuccess = false;
        XianyuGoodsOrder deliveryOrder = null;
        StringBuilder allContent = new StringBuilder();
        try {
            log.info("【账号{}】开始执行自动发货: recordId={}, xyGoodsId={}, orderId={}", accountId, recordId, xyGoodsId, orderId);

            XianyuGoodsConfig goodsConfig = goodsConfigMapper.selectByAccountAndGoodsId(accountId, xyGoodsId);
            if (goodsConfig == null || goodsConfig.getXianyuAutoDeliveryOn() == null || goodsConfig.getXianyuAutoDeliveryOn() != 1) {
                log.warn("【账号{}】商品未开启自动发货: xyGoodsId={}", accountId, xyGoodsId);
                updateRecordState(recordId, -1, null, "商品未开启自动发货");
                return;
            }

            OrderDetailFetcher.OrderDetailInfo orderDetail = orderDetailFetcher.fetch(accountId, xyGoodsId, orderId);
            if (orderDetail == null && orderId != null && !orderId.isEmpty()) {
                log.warn("【账号{}】获取订单详情失败(可能Cookie过期或API异常)，中断发货: orderId={}", accountId, orderId);
                String failReason = "获取订单详情失败(可能Cookie过期)，请检查Cookie状态";
                updateRecordState(recordId, -1, null, failReason);
                emailNotifyService.sendAutoDeliveryFailEmail(null, xyGoodsId, orderId, failReason);
                return;
            }
            XianyuGoodsOrder verifiedOrder = orderMapper.selectById(recordId);
            String verifiedBuyer = recipientVerifier.verify(verifiedOrder, orderDetail);
            sId = verifiedBuyer + "@goofish";
            verifiedOrder.setBuyerUserId(verifiedBuyer);
            verifiedOrder.setSid(sId);
            orderMapper.updateVerifiedBuyer(recordId, verifiedBuyer, sId);
            com.xianyusmart.service.delivery.DeliveryExecution.check();
            String orderSkuId = orderDetail.skuId != null ? orderDetail.skuId : verifiedOrder.getSkuId();
            int buyNum = (orderDetail != null && orderDetail.buyNum != null && orderDetail.buyNum > 0) ? orderDetail.buyNum : 1;
            log.info("【账号{}】订单SKU: orderId={}, skuId={}, buyNum={}", accountId, orderId, orderSkuId, buyNum);

            if (orderDetail != null) {
                orderMapper.updateOrderDetail(recordId, orderDetail.buyerUserName, orderDetail.orderCreateTime, orderDetail.paySuccessTime, orderDetail.consignTime, orderDetail.skuName, orderDetail.skuId, orderDetail.goodsTitle, orderDetail.totalPrice, orderDetail.buyNum);
            }

            XianyuGoodsAutoDeliveryConfig deliveryConfig;
            try {
                deliveryConfig = resolveDeliveryConfig(accountId, xyGoodsId, orderSkuId);
            } catch (IllegalStateException e) {
                log.warn("【账号{}】商品规格发货配置校验失败: xyGoodsId={}, skuId={}, reason={}",
                        accountId, xyGoodsId, orderSkuId, e.getMessage());
                updateRecordState(recordId, -1, null, e.getMessage());
                return;
            }

            if (deliveryConfig == null) {
                log.warn("【账号{}】商品无匹配的发货配置: xyGoodsId={}, skuId={}", accountId, xyGoodsId, orderSkuId);
                updateRecordState(recordId, -1, null, "无匹配的发货配置");
                return;
            }

            int deliveryMode = deliveryConfig.getDeliveryMode() != null ? deliveryConfig.getDeliveryMode() : 1;
            cardDelivery = deliveryMode == 2;
            boolean voucherDeliveryEnabled = !Integer.valueOf(0).equals(deliveryConfig.getVoucherDeliveryEnabled());
            boolean chatDeliveryEnabled = !Integer.valueOf(0).equals(deliveryConfig.getChatDeliveryEnabled());
            String cid = sId.replace("@goofish", "");
            String resolvedBuyerName = orderDetail != null && orderDetail.buyerUserName != null
                    && !orderDetail.buyerUserName.isBlank() ? orderDetail.buyerUserName : buyerUserName;

            DeliveryContext ctx = DeliveryContext.builder()
                    .recordId(recordId)
                    .accountId(accountId)
                    .xyGoodsId(xyGoodsId)
                    .sId(sId)
                    .orderId(orderId)
                    .buyerUserName(resolvedBuyerName)
                    .quantity(buyNum)
                    .deliveryConfig(deliveryConfig)
                    .build();

            String content = deliveryStrategyResolver.resolve(deliveryMode, ctx);
            if (content == null) {
                String failMsg = deliveryMode == 1 ? "未配置固定发货模板" : "卡密库存不足，无可用卡密";
                log.warn("【账号{}】发货内容解析失败: {}", accountId, failMsg);
                updateRecordState(recordId, -1, null, failMsg);
                emailNotifyService.sendAutoDeliveryFailEmail(null, xyGoodsId, orderId, failMsg);
                return;
            }

            String messageTemplate = deliveryMode == 1
                    ? ctx.getFixedTemplate().getMessageTemplate()
                    : deliveryConfig.getDeliveryMessageTemplate();
            String finalDeliveryContent = buyerMessageService.renderVariables(
                    buyerMessageService.normalizeDeliveryMessageTemplate(messageTemplate),
                    resolvedBuyerName, orderId, content);
            allContent.append(finalDeliveryContent);

            if (voucherDeliveryEnabled && finalDeliveryContent.length() > 200) {
                if (cardDelivery) {
                    kamiConfigService.releaseReservation(orderId);
                }
                String failMsg = "渲染后的发货内容超过凭证接口200字符限制，请缩短模板或关闭凭证发货";
                updateRecordState(recordId, -1, null, failMsg);
                emailNotifyService.sendAutoDeliveryFailEmail(null, xyGoodsId, orderId, failMsg);
                return;
            }

            List<String> imageUrls = new ArrayList<>();
            String imageUrlStr = deliveryConfig.getAutoDeliveryImageUrl();
            if (imageUrlStr != null && !imageUrlStr.trim().isEmpty()) {
                for (String url : imageUrlStr.split(",")) {
                    String trimmed = url.trim();
                    if (!trimmed.isEmpty()) imageUrls.add(trimmed);
                }
            }

            if (chatDeliveryEnabled) {
                deliveryOrder = orderMapper.selectById(recordId);
                if (deliveryOrder == null) {
                    throw new IllegalStateException("发货订单记录不存在");
                }
                if (deliveryOrder.getSid() == null || deliveryOrder.getSid().isBlank()) {
                    deliveryOrder.setSid(sId);
                }
                if ((deliveryOrder.getBuyerUserName() == null || deliveryOrder.getBuyerUserName().isBlank())
                        && orderDetail != null) {
                    deliveryOrder.setBuyerUserName(orderDetail.buyerUserName);
                }
                if (cardDelivery || voucherDeliveryEnabled) {
                    boolean held = cardDelivery
                            ? buyerMessageService.holdDeliveryMessage(deliveryOrder, finalDeliveryContent)
                            : buyerMessageService.holdFixedDeliveryMessage(deliveryOrder, finalDeliveryContent);
                    if (!held) {
                        throw new IllegalStateException("发货私聊暂存失败");
                    }
                    deliveryMessageHeld = true;
                }
            }

            // 两个渠道共用同一次渲染结果，开启双渠道时严格先写凭证再发私聊。
            if (voucherDeliveryEnabled) {
                com.xianyusmart.service.delivery.DeliveryExecution.beforeExternal();
                cardDeliveryAttempted = cardDelivery;
                String deliveryResult = orderService.consignDummyDelivery(
                        accountId, orderId, finalDeliveryContent, imageUrls);
                if (OrderService.CONSIGN_DEFERRED.equals(deliveryResult)
                        || OrderService.CONSIGN_PLATFORM_BUSY.equals(deliveryResult)) {
                    if (cardDelivery) {
                        kamiConfigService.releaseReservation(orderId);
                    }
                    if (deliveryMessageHeld) {
                        buyerMessageService.cancelHeldDeliveryMessage(deliveryOrder);
                        deliveryMessageHeld = false;
                    }
                    // 平台请求未发出时保留订单任务，熔断结束后重新解析并履约。
                    deliveryTaskService.deferForRisk(recordId,
                            consignRetryTime(accountId, deliveryResult), deliveryResult);
                    log.info("【账号{}】自动发货等待平台恢复: recordId={}, orderId={}",
                            accountId, recordId, orderId);
                    return;
                }
                if (OrderService.CONSIGN_UNCERTAIN.equals(deliveryResult)) {
                    String failReason = "发货结果待确认，请核对平台凭证后处理";
                    if (cardDelivery) {
                        // 外部接口结果不确定时锁定原卡密，避免重试后向同一订单分配不同内容。
                        kamiConfigService.markReservationReviewRequired(orderId);
                    }
                    if (deliveryMessageHeld) {
                        buyerMessageService.cancelHeldDeliveryMessage(deliveryOrder);
                        deliveryMessageHeld = false;
                    }
                    updateRecordState(recordId, -1, finalDeliveryContent, failReason);
                    orderMapper.markTaskReviewRequired(recordId, failReason);
                    log.warn("【账号{}】发货结果待确认: recordId={}", accountId, recordId);
                    return;
                }
                if (OrderService.CONSIGN_ALREADY_DELIVERED.equals(deliveryResult)) {
                    String failReason = "订单已存在发货凭证，请核对凭证与私聊内容";
                    if (cardDelivery) {
                        // 已存在凭证时无法确认首次请求是否使用当前卡密，必须锁定等待核对。
                        kamiConfigService.markReservationReviewRequired(orderId);
                    }
                    if (deliveryMessageHeld) {
                        buyerMessageService.cancelHeldDeliveryMessage(deliveryOrder);
                        deliveryMessageHeld = false;
                    }
                    updateRecordState(recordId, -1, finalDeliveryContent, failReason);
                    orderMapper.markTaskReviewRequired(recordId, failReason);
                    log.warn("【账号{}】订单已存在发货凭证: recordId={}", accountId, recordId);
                    return;
                }
                if (!OrderService.CONSIGN_SUCCESS.equals(deliveryResult)) {
                    if (cardDelivery) {
                        kamiConfigService.releaseReservation(orderId);
                    }
                    if (deliveryMessageHeld) {
                        buyerMessageService.cancelHeldDeliveryMessage(deliveryOrder);
                        deliveryMessageHeld = false;
                    }
                    log.error("【账号{}】❌ 发货凭证提交失败: recordId={}", accountId, recordId);
                    updateRecordState(recordId, -1, finalDeliveryContent, "发货凭证提交失败");
                    emailNotifyService.sendAutoDeliveryFailEmail(null, xyGoodsId, orderId, "发货凭证提交失败");
                    return;
                }
                anySuccess = true;
                if (deliveryMessageHeld && !cardDelivery) {
                    if (orderMapper.confirmFixedHeldDeliveryMessage(accountId, orderId) != 1) {
                        throw new IllegalStateException("固定内容凭证状态确认失败");
                    }
                    deliveryOrder.setConfirmState(1);
                    deliveryOrder.setDeliveryMessageState(5);
                } else {
                    orderMapper.updateConfirmState(accountId, orderId);
                }
            }

            if (cardDelivery) {
                com.xianyusmart.service.delivery.DeliveryExecution.check();
                try {
                    // 私聊内容已处于不可调度状态，卡密提交成功后再激活发送。
                    kamiConfigService.commitReservation(orderId, accountId, xyGoodsId, cid, resolvedBuyerName);
                    cardReservationCommitted = true;
                } catch (RuntimeException e) {
                    if (deliveryMessageHeld) {
                        buyerMessageService.cancelHeldDeliveryMessage(deliveryOrder);
                        deliveryMessageHeld = false;
                    }
                    throw e;
                }
            }

            if (chatDeliveryEnabled && needHumanLikeDelay) {
                HumanLikeDelayUtils.mediumDelay();
                HumanLikeDelayUtils.typingDelay(finalDeliveryContent.length());
            }

            if (chatDeliveryEnabled) {
                boolean messageSent = deliveryMessageHeld
                        ? buyerMessageService.activateAndSendDeliveryMessage(deliveryOrder)
                        : buyerMessageService.queueDeliveryMessage(deliveryOrder, finalDeliveryContent);
                anySuccess = true;
                if (messageSent) {
                    sendDeliveryImages(accountId, xyGoodsId, cid, cid, deliveryConfig, needHumanLikeDelay);
                } else {
                    executionMapper.finish(recordId, com.xianyusmart.service.delivery.DeliveryExecution.token(),
                            0, "AWAITING_MESSAGE", finalDeliveryContent, "内容已准备，等待私聊送达");
                    return;
                }
            }
            log.info("【账号{}】✅ 自动发货渠道处理完成: recordId={}, deliveryMode={}, voucher={}, chat={}",
                    accountId, recordId, deliveryMode, voucherDeliveryEnabled, chatDeliveryEnabled);

            if (anySuccess) {
                updateRecordState(recordId, 1, allContent.toString(), null);
                notificationCenterService.dispatch("DELIVERY_SUCCESS", accountId, "自动发货完成",
                        "订单已按配置完成交付。",
                        Map.of("orderId", orderId == null ? "" : orderId,
                                "xyGoodsId", xyGoodsId == null ? "" : xyGoodsId,
                                "deliveryMode", deliveryMode));
            }

        } catch (Exception e) {
            if (e instanceof com.xianyusmart.service.delivery.DeliveryExecution.LeaseLostException) {
                log.warn("旧发货执行者停止: taskId={}", recordId);
                return;
            }
            if (e instanceof com.xianyusmart.exception.DeliveryUncertainException
                    || com.xianyusmart.service.delivery.DeliveryExecution.started()) {
                if (cardDelivery && !cardReservationCommitted) kamiConfigService.markReservationReviewRequired(orderId);
                executionMapper.finish(recordId, com.xianyusmart.service.delivery.DeliveryExecution.token(), -1,
                        "REVIEW_REQUIRED", allContent.toString(), "外发结果待核对: " + e.getMessage());
                return;
            }
            if (deliveryMessageHeld && !anySuccess && !cardReservationCommitted) {
                buyerMessageService.cancelHeldDeliveryMessage(deliveryOrder);
            }
            if (cardDelivery && !cardReservationCommitted) {
                if (cardDeliveryAttempted) {
                    kamiConfigService.markReservationReviewRequired(orderId);
                } else {
                    kamiConfigService.releaseReservation(orderId);
                }
            }
            log.error("【账号{}】执行自动发货异常: recordId={}, xyGoodsId={}", accountId, recordId, xyGoodsId, e);
            if (anySuccess) {
                // 外部发货凭证已提交，后续异常不能把已履约订单回写为失败。
                updateRecordState(recordId, 1, allContent.toString(), null);
                return;
            }
            String failReason = "发货异常: " + e.getMessage();
            updateRecordState(recordId, -1, null, failReason);
            emailNotifyService.sendAutoDeliveryFailEmail(null, xyGoodsId, orderId, failReason);
        }
    }

    XianyuGoodsAutoDeliveryConfig resolveDeliveryConfig(Long accountId, String xyGoodsId, String orderSkuId) {
        if (goodsSkuService.countByXyGoodsId(xyGoodsId, accountId) == 0) {
            return autoDeliveryConfigMapper.findByAccountIdAndGoodsIdNoSku(accountId, xyGoodsId);
        }
        if (orderSkuId == null || orderSkuId.isBlank()) {
            throw new IllegalStateException("订单缺少商品规格，已停止自动发货");
        }
        String normalizedSkuId = orderSkuId.trim();
        if (goodsSkuService.findByXyGoodsIdAndSkuId(xyGoodsId, accountId, normalizedSkuId) == null) {
            throw new IllegalStateException("订单商品规格无效，已停止自动发货");
        }
        XianyuGoodsAutoDeliveryConfig config = autoDeliveryConfigMapper
                .findByAccountIdAndGoodsIdAndSkuId(accountId, xyGoodsId, normalizedSkuId);
        if (config == null) {
            // 多规格商品必须精确命中当前规格，避免错发其他规格的卡密。
            throw new IllegalStateException("当前商品规格未配置自动发货");
        }
        return config;
    }

    private void sendDeliveryImages(Long accountId, String xyGoodsId, String cid, String toId,
                                    XianyuGoodsAutoDeliveryConfig deliveryConfig, boolean needHumanLikeDelay) {
        String imageUrlStr = deliveryConfig.getAutoDeliveryImageUrl();
        if (imageUrlStr == null || imageUrlStr.trim().isEmpty()) {
            return;
        }
        String[] imageUrls = imageUrlStr.split(",");
        for (int i = 0; i < imageUrls.length; i++) {
            try {
                String url = imageUrls[i].trim();
                if (url.isEmpty()) continue;
                if (i > 0) {
                    if (needHumanLikeDelay) {
                        HumanLikeDelayUtils.thinkingDelay();
                    } else {
                        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }
                }
                com.xianyusmart.service.delivery.DeliveryExecution.beforeExternal();
                boolean imgSuccess = webSocketService.sendImageMessageWithResult(accountId, cid, toId, url, 800, 800);
                if (imgSuccess) {
                    log.info("【账号{}】自动发货图片[{}/{}]发送成功: xyGoodsId={}", accountId, i + 1, imageUrls.length, xyGoodsId);
                    sentMessageSaveService.saveManualImageReply(accountId, cid, toId, url, xyGoodsId);
                } else {
                    throw new com.xianyusmart.exception.DeliveryUncertainException("发货图片未全部送达，请核对后补发图片");
                }
            } catch (Exception e) {
                throw new com.xianyusmart.exception.DeliveryUncertainException("发货图片结果待核对: " + e.getMessage());
            }
        }
    }

    @EventListener
    public void handleDeliveryMessageSent(DeliveryMessageSentEvent event) {
        XianyuGoodsOrder order = event == null ? null : event.order();
        if (order == null || order.getXianyuAccountId() == null || order.getXyGoodsId() == null
                || order.getOrderId() == null || Integer.valueOf(1).equals(order.getConfirmState())) {
            return;
        }
        try {
            XianyuGoodsAutoDeliveryConfig config = resolveDeliveryConfig(
                    order.getXianyuAccountId(), order.getXyGoodsId(), order.getSkuId());
            if (config == null || !Integer.valueOf(0).equals(config.getVoucherDeliveryEnabled())
                    || Integer.valueOf(0).equals(config.getChatDeliveryEnabled())
                    || !Integer.valueOf(1).equals(config.getAutoConfirmShipment())) {
                return;
            }
            // 私聊独立交付成功后再确认平台订单，避免卡密未送达时提前标记发货。
            executeAutoConfirmShipment(order.getXianyuAccountId(), order.getOrderId());
        } catch (Exception e) {
            log.error("【账号{}】私聊交付后自动确认发货异常: orderId={}",
                    order.getXianyuAccountId(), order.getOrderId(), e);
        }
    }

    private void executeAutoConfirmShipment(Long accountId, String orderId) {
        confirmationService.enqueue(accountId, orderId);
    }


    private void updateRecordState(Long recordId, Integer state, String content, String failReason) {
        if (com.xianyusmart.service.delivery.DeliveryExecution.token() != null) {
            if (executionMapper.finish(recordId, com.xianyusmart.service.delivery.DeliveryExecution.token(), state,
                    state == 1 ? "COMPLETED" : "FAILED", content, failReason) != 1)
                throw new com.xianyusmart.service.delivery.DeliveryExecution.LeaseLostException();
            return;
        }
        try {
            orderMapper.updateStateContentAndFailReason(recordId, state, content, failReason);
        } catch (Exception e) {
            log.error("更新订单状态失败: recordId={}, state={}", recordId, state, e);
        }
    }

    private LocalDateTime riskRetryTime(Long accountId) {
        long retryAt = riskControlService.getStatus(accountId).retryAt();
        if (retryAt <= System.currentTimeMillis()) {
            retryAt = System.currentTimeMillis() + 60_000L;
        }
        return Instant.ofEpochMilli(retryAt).atZone(ZoneId.of("Asia/Shanghai")).toLocalDateTime();
    }

    private LocalDateTime consignRetryTime(Long accountId, String result) {
        if (OrderService.CONSIGN_PLATFORM_BUSY.equals(result)) {
            return LocalDateTime.now().plusHours(2);
        }
        return riskRetryTime(accountId);
    }

    @Override
    public void updateAutoConfirmShipment(Long accountId, String xyGoodsId, Integer autoConfirmShipment) {
        XianyuGoodsAutoDeliveryConfig config = autoDeliveryConfigMapper.findByAccountIdAndGoodsIdNoSku(accountId, xyGoodsId);
        if (config == null) {
            config = new XianyuGoodsAutoDeliveryConfig();
            config.setXianyuAccountId(accountId);
            config.setXyGoodsId(xyGoodsId);
            config.setAutoConfirmShipment(autoConfirmShipment);
            autoDeliveryConfigMapper.insert(config);
        } else {
            config.setAutoConfirmShipment(autoConfirmShipment);
            autoDeliveryConfigMapper.updateById(config);
        }
    }

    @Override
    public com.xianyusmart.common.ResultObject<String> manualDelivery(Long xianyuAccountId, String orderId, String content) {
        try {
            if (orderId == null || orderId.isEmpty()) {
                return com.xianyusmart.common.ResultObject.failed("订单ID不能为空");
            }
            if (content == null || content.trim().isEmpty()) {
                return com.xianyusmart.common.ResultObject.failed("发货内容不能为空");
            }

            XianyuGoodsOrder record = orderMapper.selectByAccountIdAndOrderId(xianyuAccountId, orderId);
            if (record == null) {
                return com.xianyusmart.common.ResultObject.failed("订单记录不存在");
            }

            record.setLeaseOwner(null);
            java.util.concurrent.atomic.AtomicBoolean sent = new java.util.concurrent.atomic.AtomicBoolean();
            leaseService.run(record, () -> {
                try {
                    String buyer = recipientVerifier.verify(record);
                    if (!webSocketService.ensureConnected(xianyuAccountId)) throw new IllegalStateException("账号连接未就绪");
                    com.xianyusmart.service.delivery.DeliveryExecution.beforeExternal();
                    if (!webSocketService.sendMessageWithResult(xianyuAccountId, buyer, buyer, content))
                        throw new com.xianyusmart.exception.DeliveryUncertainException("未确认私聊送达，请核对后处理");
                    updateRecordState(record.getId(), 1, content, null);
                    sent.set(true);
                    sentMessageSaveService.saveAiAssistantReply(xianyuAccountId,buyer,buyer,content,record.getXyGoodsId());
                } catch (Exception e) {
                    executionMapper.finish(record.getId(), com.xianyusmart.service.delivery.DeliveryExecution.token(), -1,
                            com.xianyusmart.service.delivery.DeliveryExecution.started()?"REVIEW_REQUIRED":"FAILED",content,
                            "自定义发货未完成，请核对订单和聊天后处理");
                    throw e;
                }
            });
            return sent.get() ? com.xianyusmart.common.ResultObject.success("自定义发货成功")
                    : com.xianyusmart.common.ResultObject.failed("消息尚未确认送达");
        } catch (Exception e) {
            log.error("【账号{}】自定义发货异常: orderId={}", xianyuAccountId, orderId, e);
            return com.xianyusmart.common.ResultObject.failed("自定义发货异常: " + e.getMessage());
        }
    }
}
