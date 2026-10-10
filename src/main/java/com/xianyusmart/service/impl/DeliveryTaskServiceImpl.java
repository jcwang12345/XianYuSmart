package com.xianyusmart.service.impl;

import com.xianyusmart.entity.XianyuGoodsOrder;
import com.xianyusmart.enums.DeliveryChannel;
import com.xianyusmart.enums.DeliveryStatus;
import com.xianyusmart.mapper.XianyuGoodsOrderMapper;
import com.xianyusmart.service.DeliveryTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class DeliveryTaskServiceImpl implements DeliveryTaskService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.xianyusmart.service.AccountAutomationPolicy accountAutomationPolicy;


    private final XianyuGoodsOrderMapper orderMapper;

    @org.springframework.beans.factory.annotation.Autowired
    private com.xianyusmart.mapper.DeliveryExecutionMapper executionMapper;

    @org.springframework.beans.factory.annotation.Autowired
    private com.xianyusmart.mapper.XianyuAccountMapper accountMapper;
    @org.springframework.beans.factory.annotation.Autowired
    private com.xianyusmart.mapper.XianyuGoodsInfoMapper goodsInfoMapper;
    @org.springframework.beans.factory.annotation.Autowired
    private com.xianyusmart.mapper.XianyuGoodsConfigMapper goodsConfigMapper;

    @Value("${app.delivery.lease-seconds:120}")
    private int leaseSeconds;

    @Value("${app.delivery.max-attempts:3}")
    private int maxAttempts;

    public DeliveryTaskServiceImpl(XianyuGoodsOrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    @Override
    @Transactional
    public XianyuGoodsOrder discover(XianyuGoodsOrder order, DeliveryChannel channel) {
        return accountAutomationPolicy.guarded(order.getXianyuAccountId(),com.xianyusmart.service.AccountAutomationPolicy.Action.DISCOVERY,
                () -> discoverAllowed(order,channel));
    }

    @Override
    @Transactional
    public XianyuGoodsOrder recordPaymentProjection(XianyuGoodsOrder order, DeliveryChannel channel) {
        Long tenant = com.xianyusmart.context.TenantContext.get();
        if (tenant == null || order == null || order.getXianyuAccountId() == null
                || order.getXianyuGoodsId() == null || channel == null
                || order.getOrderId() == null || !order.getOrderId().matches("[A-Za-z0-9_-]{1,100}")
                || order.getXyGoodsId() == null || order.getXyGoodsId().isBlank()
                || order.getTenantId() != null && !tenant.equals(order.getTenantId())) {
            throw new IllegalArgumentException("付款投影缺少可信租户或订单引用");
        }
        var scope = com.xianyusmart.context.AccountScopeContext.get();
        if (scope != null && !scope.unrestricted() && !scope.accountIds().contains(order.getXianyuAccountId())) {
            throw new IllegalArgumentException("付款投影账号不在当前范围");
        }
        // Reuse the existing transaction-held lifecycle read guards without authorizing execution.
        accountAutomationPolicy.protectTenantExecutionClaims();
        var account = accountMapper.selectById(order.getXianyuAccountId());
        var goods = goodsInfoMapper.selectById(order.getXianyuGoodsId());
        if (account == null || !tenant.equals(account.getTenantId()) || goods == null
                || !order.getXianyuAccountId().equals(goods.getXianyuAccountId())
                || !order.getXyGoodsId().equals(goods.getXyGoodId())) {
            throw new IllegalArgumentException("付款投影账号或商品归属不符");
        }
        order.setTenantId(tenant);
        var existing = orderMapper.selectByAccountIdAndOrderId(order.getXianyuAccountId(), order.getOrderId());
        if (existing != null) return validatePaymentProjection(existing, order);

        var config = goodsConfigMapper.selectByAccountAndGoodsId(order.getXianyuAccountId(), order.getXyGoodsId());
        String reason = order.getFailReason();
        if (reason == null && (config == null || !Integer.valueOf(1).equals(config.getXianyuAutoDeliveryOn()))) {
            reason = "已记录付款消息，自动发货未开启，待人工核对";
        }
        if (reason == null && (!accountAutomationPolicy.allows(order.getXianyuAccountId(), com.xianyusmart.service.AccountAutomationPolicy.Action.DISCOVERY)
                || !accountAutomationPolicy.allows(order.getXianyuAccountId(), com.xianyusmart.service.AccountAutomationPolicy.Action.DELIVERY))) {
            reason = "已记录付款消息，账号本地状态或策略保护，待人工核对";
        }
        order.setId(null);
        if (reason == null) {
            return accountAutomationPolicy.guarded(order.getXianyuAccountId(), com.xianyusmart.service.AccountAutomationPolicy.Action.DISCOVERY,
                    () -> validatePaymentProjection(discoverAllowed(order, channel), order));
        }
        order.setState(-1);
        order.setDeliveryStatus(DeliveryStatus.REVIEW_REQUIRED.name());
        order.setFailReason(reason);
        order.setContent(null);
        order.setConfirmState(0);
        // Existing NOT NULL columns use zero only for a non-executable, unverified quantity.
        order.setBuyNum(order.getBuyNum() != null && order.getBuyNum() > 0 ? order.getBuyNum() : 0);
        order.setExpectedQuantity(order.getBuyNum());
        order.setDeliveryChannel(channel.name());
        orderMapper.insert(order);
        var persisted = orderMapper.selectByAccountIdAndOrderId(order.getXianyuAccountId(), order.getOrderId());
        if (persisted == null) throw new IllegalStateException("付款投影持久化失败");
        return validatePaymentProjection(persisted, order);
    }

    private XianyuGoodsOrder validatePaymentProjection(XianyuGoodsOrder persisted, XianyuGoodsOrder requested) {
        if (!java.util.Objects.equals(persisted.getTenantId(), requested.getTenantId())
                || !java.util.Objects.equals(persisted.getXianyuAccountId(), requested.getXianyuAccountId())
                || !java.util.Objects.equals(persisted.getXianyuGoodsId(), requested.getXianyuGoodsId())
                || !java.util.Objects.equals(persisted.getXyGoodsId(), requested.getXyGoodsId())
                || !java.util.Objects.equals(persisted.getOrderId(), requested.getOrderId())) {
            throw new IllegalStateException("付款投影幂等引用冲突");
        }
        return persisted;
    }

    private XianyuGoodsOrder discoverAllowed(XianyuGoodsOrder order, DeliveryChannel channel) {
        int quantity = order.getBuyNum() != null && order.getBuyNum() > 0 ? order.getBuyNum() : 1;
        if (!DeliveryStatus.REVIEW_REQUIRED.name().equals(order.getDeliveryStatus())) {
            order.setDeliveryStatus(DeliveryStatus.PENDING.name());
        }
        order.setExpectedQuantity(quantity);
        order.setDeliveryChannel(channel.name());
        orderMapper.insert(order);

        XianyuGoodsOrder persisted = order.getId() != null ? orderMapper.selectById(order.getId()) : null;
        if (persisted == null && order.getOrderId() != null) {
            persisted = orderMapper.selectByAccountIdAndOrderId(order.getXianyuAccountId(), order.getOrderId());
        }
        if (persisted == null && order.getPnmId() != null) {
            persisted = orderMapper.selectByPnmId(order.getXianyuAccountId(), order.getPnmId());
        }
        if (persisted == null) {
            throw new IllegalStateException("订单任务持久化失败");
        }
        return persisted;
    }

    @Override
    public boolean claimOrderCreatedNotification(Long taskId) {
        return taskId != null && orderMapper.claimOrderCreatedNotification(taskId) == 1;
    }

    @Override
    @Transactional
    public List<XianyuGoodsOrder> claimDueTasks(String workerId, int limit) {
        accountAutomationPolicy.protectTenantExecutionClaims();
        executionMapper.recoverUncertain();
        int batchSize = Math.max(1, Math.min(limit, 100));
        List<XianyuGoodsOrder> tasks = orderMapper.lockDueTasks(batchSize).stream()
                .filter(task -> accountAutomationPolicy.allows(task.getXianyuAccountId(),com.xianyusmart.service.AccountAutomationPolicy.Action.DELIVERY))
                .toList();
        if (tasks.isEmpty()) {
            return tasks;
        }
        List<Long> taskIds = tasks.stream().map(XianyuGoodsOrder::getId).toList();
        String claimToken = java.util.UUID.randomUUID().toString();
        if (orderMapper.claimTasks(taskIds, claimToken, Math.max(120, leaseSeconds)) != taskIds.size()) {
            throw new IllegalStateException("订单任务租约领取冲突");
        }
        tasks.forEach(task -> {
            task.setLeaseOwner(claimToken);
            task.setDeliveryStatus(DeliveryStatus.PROCESSING.name());
            task.setAttemptCount((task.getAttemptCount() != null ? task.getAttemptCount() : 0) + 1);
        });
        return tasks;
    }

    @Override
    public void complete(Long taskId) {
        orderMapper.completeTask(taskId);
    }

    @Override
    public void retryOrFail(Long taskId, String errorMessage) {
        XianyuGoodsOrder task = orderMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        int attempts = task.getAttemptCount() != null ? task.getAttemptCount() : 0;
        boolean exhausted = attempts >= maxAttempts;
        String status = exhausted ? DeliveryStatus.FAILED.name() : DeliveryStatus.RETRY_WAIT.name();
        LocalDateTime nextRetryTime = exhausted ? null : LocalDateTime.now().plusSeconds(Math.min(300L, 5L << attempts));
        String safeMessage = errorMessage == null ? "自动发货失败" : errorMessage.substring(0, Math.min(errorMessage.length(), 500));
        orderMapper.retryOrFailTask(taskId, status, nextRetryTime, safeMessage);
    }

    @Override
    public void deferForRisk(Long taskId, LocalDateTime retryAt, String errorMessage) {
        String safeMessage = errorMessage == null ? "账号风控冷却中" :
                errorMessage.substring(0, Math.min(errorMessage.length(), 500));
        orderMapper.deferForRisk(taskId, retryAt, safeMessage);
    }

    @Override
    public void markReviewRequired(Long taskId, String errorMessage) {
        String safeMessage = errorMessage == null ? "发送结果不确定，请人工核对" :
                errorMessage.substring(0, Math.min(errorMessage.length(), 500));
        orderMapper.markTaskReviewRequired(taskId, safeMessage);
    }

    @Override
    public boolean requeueFailed(Long taskId, Long accountId) {
        // 账号和异常状态同时进入更新条件，保证并发重试只成功一次。
        return orderMapper.requeueFailedTask(taskId, accountId) == 1;
    }

    @Override
    public boolean markDelivered(Long taskId, Long accountId) {
        // 仅更新本地履约状态并清理待发私聊，不调用平台接口，避免人工发货后重复发送。
        return orderMapper.markDeliveredTask(taskId, accountId) == 1;
    }
}
