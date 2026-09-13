package com.xianyusmart.service.notification;

/** Operational advice must distinguish a new order from an actual delivery failure. */
public final class NotificationGuide {
    private NotificationGuide() {}
    public static String advice(String event) {
        return switch (event) {
            case "ORDER_CREATED" -> "【信息】系统已发现订单，正在按配置处理；此通知不代表发货失败，请勿仅凭此通知重复手工发货。";
            case "DELIVERY_SUCCESS" -> "【信息】交付任务已完成；平台确认发货、买家收货进度请查看订单时间线。";
            case "DELIVERY_EXCEPTION" -> "【需核对】请查看失败阶段和订单进度；结果不确定时先核对聊天记录、卡密占用和平台订单，再决定补发，避免重复交付。";
            case "ACCOUNT_OFFLINE" -> "【连接异常】消息接收、AI 回复和发货可能受影响。系统会退避重连；普通网络掉线无需立即重新扫码。";
            case "CREDENTIAL_EXPIRED" -> "【需扫码】自动凭证恢复未成功，AI 回复和自动发货可能受阻。请使用对应闲鱼账号扫码确认，仅更新此账号；不要转发登录二维码。";
            case "ACCOUNT_RECOVERED" -> "【恢复】已验证账号消息连接恢复。安全待处理任务将按队列继续；结果不确定或已失败需核对的历史任务不会盲目重发。";
            case "ACCOUNT_VERIFICATION_REQUIRED" -> "【需官方验证】这是平台安全验证，不等同于普通 Cookie 过期。请在官方页面或 App 完成验证，不要反复扫码或重试发货。";
            case "KAMI_STOCK_LOW" -> "【库存预警】请补充对应卡密库存；库存不足订单需要核对处理，切勿重复导入已使用卡密。";
            default -> "";
        };
    }
}
