package com.xianyusmart.service.delivery;

import com.xianyusmart.entity.XianyuGoodsOrder;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.mapper.XianyuGoodsOrderMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;

@Service
public class OrderRecipientVerifier {
    private final OrderDetailFetcher fetcher;
    private final XianyuAccountMapper accounts;
    @Autowired
    private XianyuGoodsOrderMapper orders;

    private static final Set<String> MESSAGE_STATUSES = Set.of(
            "PENDING", "PROCESSING", "RETRY_WAIT", "AWAITING_MESSAGE", "COMPLETED");
    public OrderRecipientVerifier(OrderDetailFetcher fetcher, XianyuAccountMapper accounts) {
        this.fetcher = fetcher; this.accounts = accounts;
    }
    public String verify(XianyuGoodsOrder order) {
        if (order == null) throw new IllegalArgumentException("订单不存在");
        return verify(order, fetcher.fetch(order.getXianyuAccountId(), order.getXyGoodsId(), order.getOrderId()));
    }
    public String verify(XianyuGoodsOrder order, OrderDetailFetcher.OrderDetailInfo detail) {
        String buyer = verifyIdentity(order, detail);
        // First delivery still requires an unfulfilled, paid platform order.
        if (!"PAID".equals(detail.tradeStatus) || detail.consignTime != null && !detail.consignTime.isBlank())
            throw new IllegalStateException("订单非待发货状态或已有发货时间，已停止发货");
        return buyer;
    }

    /** Card allocation cannot rely on missing platform identity or a default purchase quantity. */
    public String verifyCardOrder(XianyuGoodsOrder requested, OrderDetailFetcher.OrderDetailInfo detail) {
        XianyuGoodsOrder order = verifyCardOrderIdentity(requested, detail);
        if (!Objects.equals(detail.buyNum, order.getBuyNum())
                || !Objects.equals(detail.buyNum, order.getExpectedQuantity())
                || !Objects.equals(requested.getBuyNum(), order.getBuyNum())
                || !Objects.equals(requested.getExpectedQuantity(), order.getExpectedQuantity())) {
            throw new IllegalStateException("平台卡密数量与本地履约数量不一致，已停止分配");
        }
        return detail.buyerUserId;
    }

    /** Preflight only: authorization for quantity preparation does not authorize card allocation. */
    public String verifyCardOrderBeforeQuantityPreparation(XianyuGoodsOrder requested, OrderDetailFetcher.OrderDetailInfo detail) {
        return verifyCardOrderIdentity(requested, detail).getBuyerUserId();
    }

    private XianyuGoodsOrder verifyCardOrderIdentity(XianyuGoodsOrder requested, OrderDetailFetcher.OrderDetailInfo detail) {
        if (requested == null || requested.getId() == null) throw new IllegalArgumentException("订单不存在");
        XianyuGoodsOrder order = orders.selectById(requested.getId());
        if (order == null || !Objects.equals(requested.getId(), order.getId())
                || requested.getTenantId() == null || !Objects.equals(requested.getTenantId(), order.getTenantId())
                || requested.getXianyuAccountId() == null
                || !Objects.equals(requested.getXianyuAccountId(), order.getXianyuAccountId())
                || !sameIdentity(requested.getOrderId(), order.getOrderId())
                || !sameIdentity(requested.getXyGoodsId(), order.getXyGoodsId())
                || !sameIdentity(requested.getBuyerUserId(), order.getBuyerUserId())) {
            throw new IllegalStateException("卡密订单引用与持久化订单不一致");
        }
        if (!"PROCESSING".equals(order.getDeliveryStatus()) || Integer.valueOf(1).equals(order.getState())
                || Integer.valueOf(1).equals(order.getConfirmState())
                || order.getDeliveryMessageState() != null && order.getDeliveryMessageState() != 0
                || order.getRefundStatus() != null && !"NONE".equals(order.getRefundStatus())) {
            throw new IllegalStateException("卡密订单履约或退款状态需要核对，已停止分配");
        }
        var account = accounts.selectById(order.getXianyuAccountId());
        if (account == null || !Integer.valueOf(1).equals(account.getStatus())
                || !Objects.equals(order.getXianyuAccountId(), account.getId())
                || !Objects.equals(order.getTenantId(), account.getTenantId())) {
            throw new IllegalStateException("卡密账号已停用或租户不一致");
        }
        if (detail == null || !sameIdentity(order.getOrderId(), detail.orderId)
                || !sameIdentity(order.getXyGoodsId(), detail.goodsId)
                || !sameIdentity(order.getBuyerUserId(), detail.buyerUserId)) {
            throw new IllegalStateException("平台卡密订单或买家身份未明确匹配");
        }
        if (!"PAID".equals(detail.tradeStatus) || detail.consignTime != null && !detail.consignTime.isBlank()) {
            throw new IllegalStateException("卡密订单非待发货状态或已有发货时间，已停止分配");
        }
        if (detail.buyNum == null || detail.buyNum <= 0) {
            throw new IllegalStateException("平台卡密购买数量未明确为正整数，已停止分配");
        }
        return order;
    }

    /** Reuse only the persisted delivery message after a confirmed voucher or chat delivery. */
    public String verifyDeliveryMessage(XianyuGoodsOrder requested, String message) {
        if (requested == null || requested.getId() == null) throw new IllegalArgumentException("订单不存在");
        XianyuGoodsOrder order = orders.selectById(requested.getId());
        if (order == null || !Objects.equals(requested.getXianyuAccountId(), order.getXianyuAccountId())
                || !Objects.equals(requested.getOrderId(), order.getOrderId())
                || !Objects.equals(requested.getXyGoodsId(), order.getXyGoodsId())
                || requested.getTenantId() != null && !Objects.equals(requested.getTenantId(), order.getTenantId())) {
            throw new IllegalStateException("私聊订单引用与持久化订单不一致");
        }
        if (order.getDeliveryStatus() == null || !MESSAGE_STATUSES.contains(order.getDeliveryStatus())) {
            throw new IllegalStateException("订单履约状态需要人工核对，已停止私聊");
        }
        // Legacy NULL follows RefundObservationService's NONE contract; unknown/blank states do not.
        if (order.getRefundStatus() != null && !"NONE".equals(order.getRefundStatus())) {
            throw new IllegalStateException("订单本地退款状态未解除，已停止私聊");
        }
        String original = order.getDeliveryMessageContent();
        if ((original == null || original.isBlank()) && "COMPLETED".equals(order.getDeliveryStatus())) {
            original = order.getContent();
        }
        if (message == null || message.isBlank() || !message.equals(original)) {
            throw new IllegalStateException("私聊内容与原准备内容不一致，已停止私聊");
        }
        if (order.getBuyerUserId() == null || order.getBuyerUserId().isBlank()) {
            throw new IllegalStateException("持久化订单缺少已核验买家，已停止私聊");
        }
        requireMatch(requested.getBuyerUserId(), order.getBuyerUserId());
        OrderDetailFetcher.OrderDetailInfo detail = fetcher.fetch(
                order.getXianyuAccountId(), order.getXyGoodsId(), order.getOrderId());
        String buyer = verifyIdentity(order, detail);
        if (!Objects.equals(order.getOrderId(), detail.orderId)
                || !Objects.equals(order.getXyGoodsId(), detail.goodsId)) {
            throw new IllegalStateException("订单详情缺少匹配的订单或商品身份，已停止私聊");
        }
        if ("PAID".equals(detail.tradeStatus) && (detail.consignTime == null || detail.consignTime.isBlank())) {
            return buyer;
        }
        boolean confirmedDelivery = Integer.valueOf(1).equals(order.getConfirmState())
                || "COMPLETED".equals(order.getDeliveryStatus()) && Integer.valueOf(1).equals(order.getState())
                && Integer.valueOf(1).equals(order.getDeliveryMessageState());
        if (("SHIPPED".equals(detail.tradeStatus) || "COMPLETED".equals(detail.tradeStatus)) && confirmedDelivery) {
            return buyer;
        }
        throw new IllegalStateException("订单状态或既有交付证据未确认，已停止私聊");
    }

    /** Receipt follow-ups require fresh local delivery evidence and platform-confirmed receipt. */
    public String verifyReceiptFollowUp(XianyuGoodsOrder requested) {
        if (requested == null || requested.getId() == null) throw new IllegalArgumentException("订单不存在");
        XianyuGoodsOrder order = orders.selectById(requested.getId());
        if (order == null || !Objects.equals(requested.getId(), order.getId())
                || requested.getTenantId() == null || !Objects.equals(requested.getTenantId(), order.getTenantId())
                || requested.getXianyuAccountId() == null
                || !Objects.equals(requested.getXianyuAccountId(), order.getXianyuAccountId())
                || !sameIdentity(requested.getOrderId(), order.getOrderId())
                || !sameIdentity(requested.getXyGoodsId(), order.getXyGoodsId())
                || !sameIdentity(requested.getBuyerUserId(), order.getBuyerUserId())) {
            throw new IllegalStateException("收货后话术订单引用与持久化订单不一致");
        }
        // state=1 alone may be legacy/manual bookkeeping; require completed delivery and a confirmed result.
        if (!Integer.valueOf(1).equals(order.getState()) || !"COMPLETED".equals(order.getDeliveryStatus())
                || !Integer.valueOf(1).equals(order.getConfirmState())
                && !Integer.valueOf(1).equals(order.getDeliveryMessageState())) {
            throw new IllegalStateException("订单尚无已完成交付证据，已停止收货后话术");
        }
        // Match the existing refund NONE contract, including legacy NULL; unknown states remain blocked.
        if (order.getRefundStatus() != null && !"NONE".equals(order.getRefundStatus())) {
            throw new IllegalStateException("订单本地退款状态未解除，已停止收货后话术");
        }
        var account = accounts.selectById(order.getXianyuAccountId());
        if (account == null || !Integer.valueOf(1).equals(account.getStatus())
                || !Objects.equals(order.getXianyuAccountId(), account.getId())
                || !Objects.equals(order.getTenantId(), account.getTenantId())) {
            throw new IllegalStateException("收货后话术账号已停用或租户不一致");
        }
        OrderDetailFetcher.OrderDetailInfo detail = fetcher.fetch(
                order.getXianyuAccountId(), order.getXyGoodsId(), order.getOrderId());
        if (detail == null || !"COMPLETED".equals(detail.tradeStatus)
                || !sameIdentity(order.getOrderId(), detail.orderId)
                || !sameIdentity(order.getXyGoodsId(), detail.goodsId)
                || !sameIdentity(order.getBuyerUserId(), detail.buyerUserId)) {
            throw new IllegalStateException("平台收货状态或订单买家身份未确认，已停止收货后话术");
        }
        return detail.buyerUserId;
    }

    private boolean sameIdentity(String requested, String persisted) {
        return requested != null && !requested.isBlank() && requested.equals(persisted);
    }

    private String verifyIdentity(XianyuGoodsOrder order, OrderDetailFetcher.OrderDetailInfo detail) {
        if (order == null || detail == null) throw new IllegalStateException("订单详情不可用，暂不能核验买家");
        var account = accounts.selectById(order.getXianyuAccountId());
        if (account == null || !Integer.valueOf(1).equals(account.getStatus())) throw new IllegalStateException("账号已停用");
        String buyer = requireMatch(order.getBuyerUserId(), detail.buyerUserId);
        if (detail.goodsId != null && !detail.goodsId.equals(order.getXyGoodsId())) throw new IllegalStateException("订单商品与发货商品不一致");
        if (detail.orderId != null && !detail.orderId.equals(order.getOrderId())) throw new IllegalStateException("订单号不一致");
        return buyer;
    }
    public static String requireMatch(String recorded, String verified) {
        if (verified == null || verified.isBlank()) throw new IllegalStateException("订单详情缺少买家身份，等待核验");
        if (recorded != null && !recorded.isBlank() && !recorded.equals(verified)) throw new IllegalStateException("订单买家与消息买家不一致，需人工核对");
        return verified;
    }
}
