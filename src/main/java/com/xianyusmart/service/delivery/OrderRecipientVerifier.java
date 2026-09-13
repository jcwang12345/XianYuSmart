package com.xianyusmart.service.delivery;

import com.xianyusmart.entity.XianyuGoodsOrder;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.service.AccountService;
import org.springframework.stereotype.Service;

@Service
public class OrderRecipientVerifier {
    private final OrderDetailFetcher fetcher;
    private final AccountService accountService;
    private final XianyuAccountMapper accounts;
    public OrderRecipientVerifier(OrderDetailFetcher fetcher, AccountService accountService, XianyuAccountMapper accounts) {
        this.fetcher = fetcher; this.accountService = accountService; this.accounts = accounts;
    }
    public String verify(XianyuGoodsOrder order) {
        if (order == null) throw new IllegalArgumentException("订单不存在");
        return verify(order, fetcher.fetch(order.getXianyuAccountId(), order.getXyGoodsId(), order.getOrderId()));
    }
    public String verify(XianyuGoodsOrder order, OrderDetailFetcher.OrderDetailInfo detail) {
        if (order == null || detail == null) throw new IllegalStateException("订单详情不可用，暂不能核验买家");
        var account = accounts.selectById(order.getXianyuAccountId());
        if (account == null || !Integer.valueOf(1).equals(account.getStatus())) throw new IllegalStateException("账号已停用");
        String buyer = requireMatch(order.getBuyerUserId(), detail.buyerUserId);
        if (detail.goodsId != null && !detail.goodsId.equals(order.getXyGoodsId())) throw new IllegalStateException("订单商品与发货商品不一致");
        if (detail.orderId != null && !detail.orderId.equals(order.getOrderId())) throw new IllegalStateException("订单号不一致");
        if (buyer.equals(accountService.getXianyuUserId(order.getXianyuAccountId())) || accounts.countOwnBuyer(buyer) > 0)
            throw new IllegalStateException("收件人是本系统卖家账号，已停止发货");
        if ("REFUNDED".equals(detail.tradeStatus) || "CLOSED".equals(detail.tradeStatus) || "REFUNDING".equals(detail.tradeStatus))
            throw new IllegalStateException("订单正在退款或已经关闭，已停止发货");
        return buyer;
    }
    public static String requireMatch(String recorded, String verified) {
        if (verified == null || verified.isBlank()) throw new IllegalStateException("订单详情缺少买家身份，等待核验");
        if (recorded != null && !recorded.isBlank() && !recorded.equals(verified)) throw new IllegalStateException("订单买家与消息买家不一致，需人工核对");
        return verified;
    }
}
