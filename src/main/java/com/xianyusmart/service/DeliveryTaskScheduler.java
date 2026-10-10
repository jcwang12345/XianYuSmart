package com.xianyusmart.service;

import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.context.AccountScopeContext;
import com.xianyusmart.entity.XianyuGoodsOrder;
import com.xianyusmart.enums.DeliveryStatus;
import com.xianyusmart.enums.KamiStatus;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.mapper.XianyuGoodsOrderMapper;
import com.xianyusmart.mapper.XianyuKamiItemMapper;
import com.xianyusmart.mapper.XianyuGoodsConfigMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.concurrent.Executor;

@Slf4j
@Component
public class DeliveryTaskScheduler {
    @org.springframework.beans.factory.annotation.Autowired
    private AccountAutomationPolicy accountAutomationPolicy;


    private final DeliveryTaskService deliveryTaskService;
    private final AutoDeliveryService autoDeliveryService;
    private final XianyuGoodsOrderMapper orderMapper;
    private final XianyuKamiItemMapper kamiItemMapper;
    private final XianyuAccountMapper accountMapper;
    private final OrderService orderService;
    private final PendingOrderPollService pendingOrderPollService;
    private final WebSocketService webSocketService;
    private final BuyerProfileService buyerProfileService;
    private final Executor taskExecutor;
    private final String workerId = buildWorkerId();

    @org.springframework.beans.factory.annotation.Autowired
    private BackgroundPlatformActivityPolicy backgroundPlatformActivityPolicy = new BackgroundPlatformActivityPolicy();

    @org.springframework.beans.factory.annotation.Autowired
    private com.xianyusmart.service.delivery.DeliveryLeaseService leaseService;

    @org.springframework.beans.factory.annotation.Autowired
    private XianyuGoodsConfigMapper goodsConfigMapper;

    @Value("${app.delivery.claim-batch-size:20}")
    private int claimBatchSize;

    public DeliveryTaskScheduler(DeliveryTaskService deliveryTaskService,
                                 AutoDeliveryService autoDeliveryService,
                                 XianyuGoodsOrderMapper orderMapper,
                                 XianyuKamiItemMapper kamiItemMapper,
                                 XianyuAccountMapper accountMapper,
                                 OrderService orderService,
                                 PendingOrderPollService pendingOrderPollService,
                                 WebSocketService webSocketService,
                                 BuyerProfileService buyerProfileService,
                                 @Qualifier("taskExecutor") Executor taskExecutor) {
        this.deliveryTaskService = deliveryTaskService;
        this.autoDeliveryService = autoDeliveryService;
        this.orderMapper = orderMapper;
        this.kamiItemMapper = kamiItemMapper;
        this.accountMapper = accountMapper;
        this.orderService = orderService;
        this.pendingOrderPollService = pendingOrderPollService;
        this.webSocketService = webSocketService;
        this.buyerProfileService = buyerProfileService;
        this.taskExecutor = taskExecutor;
    }

    @org.springframework.beans.factory.annotation.Autowired
    private R01QaPlatformService r01Qa;

    @Scheduled(fixedDelayString = "${app.delivery.dispatch-delay-ms:1000}", initialDelay = 5000)
    public void dispatchDueTasks() {
        if (!backgroundPlatformActivityPolicy.enabled()) return;
        DispatchContext caller = DispatchContext.capture();
        try {
            List<Long> tenantIds = accountMapper.selectDeliveryTaskTenantIds();
            if (tenantIds == null) return;
            for (Long tenantId : new LinkedHashSet<>(tenantIds)) {
                if (tenantId == null || tenantId <= 0) continue;
                // A scoped caller cannot turn an enumeration result into cross-tenant authority.
                if (caller.tenantId != null && !caller.tenantId.equals(tenantId)) continue;
                if (caller.userId != null && caller.tenantId == null) continue;
                try {
                    caller.restore();
                    TenantContext.set(tenantId);
                    List<XianyuGoodsOrder> tasks = deliveryTaskService.claimDueTasks(workerId,
                            Math.max(1, Math.min(claimBatchSize, 100)));
                    if (tasks == null) continue;
                    for (XianyuGoodsOrder task : tasks) {
                        if (task == null || !Objects.equals(tenantId, task.getTenantId())) continue;
                        if (caller.scope != null && !caller.scope.unrestricted()
                                && !caller.scope.accountIds().contains(task.getXianyuAccountId())) continue;
                        try {
                            taskExecutor.execute(() -> {
                                DispatchContext worker = DispatchContext.capture();
                                try {
                                    caller.restore();
                                    executeTask(task);
                                } finally {
                                    worker.restore();
                                }
                            });
                        } catch (RuntimeException e) {
                            log.warn("订单任务提交失败，保留租约恢复: taskId={}", task.getId());
                        }
                    }
                } catch (RuntimeException e) {
                    log.warn("租户发货任务领取失败: tenantId={}", tenantId);
                } finally {
                    caller.restore();
                }
            }
        } finally {
            caller.restore();
        }
    }

    private record DispatchContext(Long tenantId, Long userId, String username, Long userTenantId,
                                   AccountScopeContext.Scope scope) {
        static DispatchContext capture() {
            return new DispatchContext(TenantContext.get(), UserContext.getUserId(), UserContext.getUsername(),
                    UserContext.getTenantId(), AccountScopeContext.get());
        }

        void restore() {
            UserContext.clear();
            if (userId != null || username != null || userTenantId != null) UserContext.set(userId, username, userTenantId);
            TenantContext.clear();
            if (tenantId != null && !Objects.equals(tenantId, userTenantId)) TenantContext.set(tenantId);
            AccountScopeContext.clear();
            if (scope != null) AccountScopeContext.set(scope.unrestricted(), scope.accountIds());
        }
    }

    @Scheduled(fixedDelayString = "${app.delivery.order-discovery-delay-ms:30000}", initialDelay = 30000)
    public void discoverOrdersFromApi() {
        if (!backgroundPlatformActivityPolicy.enabled()) return;
        // 仅轮询Cookie有效的正常账号；过期账号会在更新Cookie后由恢复任务重新接入。
        List<XianyuAccount> accounts = r01Qa != null && r01Qa.enabled()
                ? r01Qa.discoveryAccounts() : accountMapper.selectReconnectableAccounts();
        if (accounts == null) {
            return;
        }
        discoverAccounts(accounts);
    }

    /** Explicit QA action uses only guarded synthetic responses; background total gate stays closed. */
    public void discoverQaOrders() {
        if(r01Qa==null || !r01Qa.enabled()) throw new IllegalStateException("R01 QA 未启用");
        discoverAccounts(r01Qa.discoveryAccounts());
    }

    private void discoverAccounts(List<XianyuAccount> accounts) {
        for (XianyuAccount account : accounts) {
            try {
                TenantContext.set(account.getTenantId());
                if(!accountAutomationPolicy.allows(account.getId(),AccountAutomationPolicy.Action.DISCOVERY))continue;
                accountAutomationPolicy.guarded(account.getId(),AccountAutomationPolicy.Action.DISCOVERY,
                        () -> { discoverAccountAllowed(account);return null; });
            } catch (Exception e) {
                log.warn("【账号{}】待发货订单发现失败: {}", account.getId(), e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }

    private void discoverAccountAllowed(XianyuAccount account) {
                // A live socket is not proof that business events are still parseable.
                // Keep a low-frequency, idempotent API lane for accounts that actually
                // have auto-delivery enabled.
                if (goodsConfigMapper.countAutoDeliveryEnabledByAccountId(account.getId()) == 0) {
                    return;
                }
                List<Map<String, Object>> pendingOrders = orderService.queryPendingOrders(account.getId());
                if (pendingOrders != null && !pendingOrders.isEmpty()) {
                    pendingOrderPollService.syncOrdersToDb(account.getId(), pendingOrders);
                }
    }

    private void executeTask(XianyuGoodsOrder task) {
        try {
            TenantContext.set(task.getTenantId());
            // 任务领取后再次检查买家状态，避免排队期间新增拦截仍继续自动发货。
            String blockReason = buyerProfileService.automationBlockReason(
                    task.getXianyuAccountId(), task.getBuyerUserId());
            if (blockReason != null) {
                deliveryTaskService.markReviewRequired(task.getId(), blockReason);
                return;
            }
            String sId = task.getSid();
            if (sId == null || sId.isBlank()) {
                String receiverId = task.getBuyerUserId() != null ? task.getBuyerUserId() : task.getOrderId();
                sId = receiverId + "@goofish";
            }
            final String sessionId = sId;
            leaseService.run(task, () -> autoDeliveryService.executeDelivery(
                    task.getId(), task.getXianyuAccountId(), task.getXyGoodsId(), sessionId,
                    task.getOrderId(), task.getBuyerUserName(), false));

            XianyuGoodsOrder result = orderMapper.selectById(task.getId());
            if (result != null && Integer.valueOf(1).equals(result.getState())) {
                // The executing claim settles its own result with a token-checked update.
            } else if (result != null && DeliveryStatus.RETRY_WAIT.name().equals(result.getDeliveryStatus())
                    && "RISK_GUARD_WAIT".equals(result.getLastErrorCode())) {
                // 风控等待已经设置准确恢复时间，不能再按普通失败增加次数。
                log.info("订单任务等待平台恢复: taskId={}, orderId={}", task.getId(), task.getOrderId());
            } else if (result != null && DeliveryStatus.REVIEW_REQUIRED.name().equals(result.getDeliveryStatus())) {
                log.warn("订单发货结果待人工核对: taskId={}, orderId={}", task.getId(), task.getOrderId());
            } else if (kamiItemMapper.countByOrderAndStatus(task.getOrderId(), KamiStatus.REVIEW_REQUIRED.getCode()) > 0) {
                deliveryTaskService.markReviewRequired(task.getId(), result != null ? result.getFailReason() : null);
            } else {
                log.debug("发货结果由执行租约持有者结算: taskId={}", task.getId());
            }
        } catch (Exception e) {
            log.error("订单任务执行异常: taskId={}, orderId={}", task.getId(), task.getOrderId(), e);
            log.warn("发货异常保留持久化任务供租约恢复处理: taskId={}", task.getId());
        } finally {
            TenantContext.clear();
        }
    }

    private String buildWorkerId() {
        String host = Optional.ofNullable(System.getenv("HOSTNAME"))
                .orElse(Optional.ofNullable(System.getenv("COMPUTERNAME")).orElse("local"));
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
