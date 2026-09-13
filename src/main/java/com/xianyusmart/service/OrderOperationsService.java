package com.xianyusmart.service;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.mapper.*;
import com.xianyusmart.service.delivery.OrderDetailFetcher;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.*;
@Service
public class OrderOperationsService {
    private final OrderOperationsMapper operations;
    private final XianyuGoodsOrderMapper orders;
    private final XianyuAccountMapper accounts;
    private final OrderConfirmationMapper confirmations;
    private final OrderDetailFetcher details;
    private final WebSocketTokenService tokens;
    private final Map<Long,Long> nextCheck=new java.util.concurrent.ConcurrentHashMap<>();
    public OrderOperationsService(OrderOperationsMapper operations,XianyuGoodsOrderMapper orders,XianyuAccountMapper accounts,
            OrderConfirmationMapper confirmations,OrderDetailFetcher details,WebSocketTokenService tokens) {
        this.operations=operations;this.orders=orders;this.accounts=accounts;this.confirmations=confirmations;this.details=details;this.tokens=tokens;
    }
    @Scheduled(fixedDelay=30000,initialDelay=90000)
    public void reconcile() {
        for(var account:accounts.selectReconnectableAccounts()) {
            if(nextCheck.getOrDefault(account.getId(),0L)>System.currentTimeMillis() || tokens.getPendingCaptchaUrl(account.getId())!=null) continue;
            nextCheck.put(account.getId(),System.currentTimeMillis()+300000+java.util.concurrent.ThreadLocalRandom.current().nextLong(60000));
            try {
                TenantContext.set(account.getTenantId());
                for(var order:operations.due(account.getId())) {
                    var detail=details.fetch(account.getId(),order.getXyGoodsId(),order.getOrderId());
                    operations.reconcile(order.getId(),detail==null?null:detail.tradeStatus,detail==null?null:detail.skuId,detail==null?null:detail.buyerUserId);
                    if(detail!=null && Set.of("REFUNDING","REFUNDED","CLOSED").contains(detail.tradeStatus==null?"":detail.tradeStatus))
                        orders.skipPendingTaskForRefund(account.getId(),order.getOrderId(),"平台订单已退款或关闭");
                    if(detail==null) break;
                }
            } catch (RuntimeException e) {
                // One unavailable account must not starve other tenants' reconciliation.
                nextCheck.put(account.getId(),System.currentTimeMillis()+900000);
            } finally { TenantContext.clear(); }
        }
    }
    public Map<String,Object> timeline(Long account,String orderId) {
        var order=orders.selectByAccountIdAndOrderId(account,orderId);
        if(order==null) throw new IllegalArgumentException("订单不存在或无权访问");
        List<Map<String,Object>> steps=new ArrayList<>();
        steps.add(step("下单",order.getOrderCreateTime(),"已记录"));
        steps.add(step("付款",order.getPaySuccessTime(),"付款时间以平台记录为准"));
        steps.add(step("内容交付",null,order.getDeliveryStatus()));
        steps.add(step("私聊送达",null,messageState(order.getDeliveryMessageState())));
        var confirm=confirmations.find(account,orderId);
        steps.add(step("平台确认",confirm==null?null:confirm.getUpdateTime(),Integer.valueOf(1).equals(order.getConfirmState())?"已确认":confirm==null?"未确认":confirm.getStatus()));
        steps.add(step("平台交易",order.getPlatformStatusCheckedAt(),order.getPlatformTradeStatus()));
        steps.add(step("评价",order.getRateTime(),Integer.valueOf(1).equals(order.getRateStatus())?"已评价":"等待满足评价条件"));
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("orderId",orderId);result.put("steps",steps);result.put("confirmation",confirm);
        result.put("lastError",order.getLastErrorMessage());
        return result;
    }
    private static Map<String,Object> step(String name,Object time,String status) {
        Map<String,Object> m=new LinkedHashMap<>();m.put("name",name);m.put("time",time);m.put("status",status==null?"尚未核实":status);return m;
    }
    private static String messageState(Integer state) {
        if(state==null)return "未发送";
        return switch(state){case 1->"收到平台回执";case 2->"发送中";case 6->"结果未知，需核对";case 3,4,5->"等待交付确认";default->"未发送或等待重试";};
    }
}
