package com.xianyusmart.service;

import com.xianyusmart.context.TenantContext;
import com.xianyusmart.mapper.OrderConfirmationMapper;
import com.xianyusmart.mapper.XianyuGoodsOrderMapper;
import com.xianyusmart.mapper.XianyuAccountMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.UUID;
import java.util.Map;

@Service
public class OrderConfirmationService {
    private final OrderConfirmationMapper mapper;
    private final XianyuGoodsOrderMapper orders;
    private final XianyuAccountMapper accounts;
    private final OrderService service;
    private final NotificationCenterService notifications;
    public OrderConfirmationService(OrderConfirmationMapper mapper, XianyuGoodsOrderMapper orders,
            XianyuAccountMapper accounts, OrderService service, NotificationCenterService notifications) {
        this.mapper=mapper; this.orders=orders; this.accounts=accounts; this.service=service; this.notifications=notifications;
    }
    public void enqueue(Long accountId, String orderId) {
        var order=orders.selectByAccountIdAndOrderId(accountId,orderId);
        if(order==null) throw new IllegalArgumentException("订单不存在或无权访问");
        if(Integer.valueOf(1).equals(order.getConfirmState())) return;
        mapper.enqueue(order.getTenantId(),accountId,orderId);
    }
    public void retry(Long accountId,String orderId) { enqueue(accountId,orderId); mapper.retry(accountId,orderId); }

    @Scheduled(fixedDelay=5000,initialDelay=20000)
    public void poll() {
        for(var task:mapper.due()) {
            String token=UUID.randomUUID().toString();
            try {
                TenantContext.set(task.getTenantId());
                if(mapper.claim(task.getId(),token)!=1) continue;
                var account=accounts.selectById(task.getXianyuAccountId());
                if(account==null || !Integer.valueOf(1).equals(account.getStatus())) {
                    mapper.finish(task.getId(),token,"RETRY_WAIT","账号不可用，等待恢复"); continue;
                }
                var order=orders.selectByAccountIdAndOrderId(task.getXianyuAccountId(),task.getOrderId());
                if(order==null) { mapper.finish(task.getId(),token,"FAILED","订单不存在"); continue; }
                if(java.util.Set.of("REFUNDED","REFUNDING","CLOSED").contains(String.valueOf(order.getPlatformTradeStatus()))) {
                    mapper.finish(task.getId(),token,"FAILED","订单退款或关闭，停止自动确认");continue;
                }
                if(Integer.valueOf(1).equals(order.getConfirmState())) { mapper.finish(task.getId(),token,"COMPLETED",null); continue; }
                String result=service.confirmShipment(task.getXianyuAccountId(),task.getOrderId());
                if(result!=null && !OrderService.CONSIGN_DEFERRED.equals(result)
                        && !OrderService.CONSIGN_PLATFORM_BUSY.equals(result)
                        && !OrderService.CONSIGN_UNCERTAIN.equals(result)) {
                    orders.updateConfirmState(task.getXianyuAccountId(),task.getOrderId());
                    mapper.finish(task.getId(),token,"COMPLETED",null);
                } else {
                    boolean exhausted=task.getAttemptCount()>=4;
                    mapper.finish(task.getId(),token,exhausted?"FAILED":"RETRY_WAIT","内容已交付，平台确认待处理");
                    if(exhausted) notifications.dispatch("DELIVERY_EXCEPTION",task.getXianyuAccountId(),"平台确认发货待处理",
                            "内容已交付，请核对平台状态并重试确认，无需重新发货。",Map.of("orderId",task.getOrderId()));
                }
            } catch(Exception e) {
                mapper.finish(task.getId(),token,task.getAttemptCount()>=4?"FAILED":"RETRY_WAIT","平台确认异常，等待重试");
            } finally { TenantContext.clear(); }
        }
    }
}
